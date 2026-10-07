-- =============================================================================
-- InsightKB · M3 阶段使用的建表脚本（MySQL 8.0）
--
-- 现在 M1 用的是内存存储，这份脚本提前写好，等接数据库时直接执行即可。
-- 在 Navicat 里新建查询窗口，粘贴执行；或者命令行：
--   mysql -u root -p < schema.sql
-- =============================================================================

CREATE DATABASE IF NOT EXISTS insightkb
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE insightkb;

-- -----------------------------------------------------------------------------
-- 文档表
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS kb_chunk;
DROP TABLE IF EXISTS kb_document;

CREATE TABLE kb_document
(
    id           VARCHAR(32)  NOT NULL COMMENT '文档 ID（UUID 前 16 位）',
    name         VARCHAR(255) NOT NULL COMMENT '原始文件名',
    ext          VARCHAR(16)  NOT NULL COMMENT '扩展名',
    size_bytes   BIGINT       NOT NULL DEFAULT 0 COMMENT '文件字节数',
    storage_path VARCHAR(512)          DEFAULT NULL COMMENT '落盘路径',
    chunk_count  INT          NOT NULL DEFAULT 0 COMMENT '切块数量',
    status       VARCHAR(16)  NOT NULL DEFAULT 'PROCESSING'
        COMMENT 'PROCESSING / READY / FAILED',
    error_msg    VARCHAR(1024)         DEFAULT NULL COMMENT '失败原因',
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_status (status),
    KEY idx_created_at (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='知识库文档元信息';

-- -----------------------------------------------------------------------------
-- 切块表
--
-- embedding 字段的存储方式有两种选择，按实际使用的检索引擎决定：
--   A. 接 Elasticsearch / pgvector：向量由检索引擎持有，这张表不存向量，
--      只存 text 用于展示原文。此时把 embedding 列删掉。
--   B. 自己在 MySQL 里算相似度（不推荐，只适合验证阶段）：
--      用 JSON 存 float 数组，或 BLOB 存 float32 二进制。
--      注意 JSON 存法会让向量膨胀 5~10 倍，且 MySQL 无法建向量索引，
--      查询必须全表扫描 —— 这正是后面要迁到 ES 的原因。
-- -----------------------------------------------------------------------------
CREATE TABLE kb_chunk
(
    id         VARCHAR(48)  NOT NULL COMMENT '切块 ID（docId#index）',
    doc_id     VARCHAR(32)  NOT NULL COMMENT '所属文档',
    doc_name   VARCHAR(255) NOT NULL COMMENT '冗余文档名，避免展示时回表',
    chunk_index INT         NOT NULL COMMENT '在文档内的序号，从 0 开始',
    content    MEDIUMTEXT   NOT NULL COMMENT '切块正文',
    char_count INT          NOT NULL DEFAULT 0 COMMENT '正文字符数',
    embedding  JSON                  DEFAULT NULL COMMENT '向量（JSON 数组）',
    dimension  SMALLINT              DEFAULT NULL COMMENT '向量维度',
    model_name VARCHAR(64)           DEFAULT NULL COMMENT '生成该向量的模型名',
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_doc_id (doc_id, chunk_index),
    -- 换 Embedding 模型后需要按模型批量重建，这个索引是给重建任务用的
    KEY idx_model (model_name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='文档切块与向量';

-- -----------------------------------------------------------------------------
-- 会话与消息（M3 实现多轮对话记忆时使用）
--
-- 设计说明：原始对话轮次存 kb_message，超窗口的历史压缩成摘要存 kb_conversation.summary。
-- 这样长对话不必把全部历史塞进 Prompt，控制 token 成本。
-- -----------------------------------------------------------------------------
CREATE TABLE kb_conversation
(
    id          VARCHAR(32)  NOT NULL COMMENT '会话 ID',
    title       VARCHAR(255)          DEFAULT NULL COMMENT '会话标题（取首个问题）',
    summary     TEXT                  DEFAULT NULL COMMENT '历史对话的压缩摘要',
    turn_count  INT          NOT NULL DEFAULT 0 COMMENT '累计轮次',
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_updated_at (updated_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='问答会话';

CREATE TABLE kb_message
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    conversation_id VARCHAR(32)  NOT NULL COMMENT '所属会话',
    role            VARCHAR(16)  NOT NULL COMMENT 'user / assistant',
    content         MEDIUMTEXT   NOT NULL COMMENT '消息正文',
    cited_chunks    JSON                  DEFAULT NULL COMMENT '本次回答引用的切块 ID 列表',
    retrieval_ms    INT          NOT NULL DEFAULT 0 COMMENT '检索耗时',
    generation_ms   INT          NOT NULL DEFAULT 0 COMMENT '生成耗时',
    token_usage     INT          NOT NULL DEFAULT 0 COMMENT '消耗 token 数',
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_conversation (conversation_id, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='对话消息（含耗时与 token 记录）';

-- -----------------------------------------------------------------------------
-- 问答评测集（对应 README 里说的"30~50 条标注问答"）
-- 有了这张表，每次优化检索参数都可以跑一遍回归，得到真实的准确率对比。
-- 这是简历上量化数字的真正来源。
-- -----------------------------------------------------------------------------
CREATE TABLE kb_eval_case
(
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    question          VARCHAR(512) NOT NULL COMMENT '测试问题',
    expected_answer   TEXT                  DEFAULT NULL COMMENT '标准答案',
    expected_doc_id   VARCHAR(32)           DEFAULT NULL COMMENT '答案应在哪篇文档中',
    expected_keywords VARCHAR(512)          DEFAULT NULL COMMENT '答案应包含的关键词，用于自动判定',
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='问答评测集';

CREATE TABLE kb_eval_run
(
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    run_name      VARCHAR(128) NOT NULL COMMENT '本次评测的说明，如"基线-纯向量检索"',
    embedding_model VARCHAR(64)         DEFAULT NULL,
    chunk_size    INT                  DEFAULT NULL,
    top_k         INT                  DEFAULT NULL,
    total_cases   INT         NOT NULL DEFAULT 0,
    hit_cases     INT         NOT NULL DEFAULT 0,
    accuracy      DECIMAL(5, 2)         DEFAULT NULL COMMENT '命中率百分比',
    avg_retrieval_ms  INT               DEFAULT NULL,
    avg_generation_ms INT               DEFAULT NULL,
    created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_created_at (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='评测运行记录（用于优化前后对比）';
