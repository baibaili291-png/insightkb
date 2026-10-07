# InsightKB · 企业知识库问答平台

上传 PDF / Word / Markdown 文档，用自然语言提问，返回**带引用出处**的答案。

后端 Spring Boot 3 + Java 21，前端原生 HTML/CSS/JavaScript（不依赖任何前端框架）。

---

## 当前进度

**M2 · 混合检索 —— 已完成**（M1 最小闭环也已完成）

```
上传文档 → 文本提取 → 语义切块 → 批量向量化 ─┬─→ 内存向量库（语义召回）
                                            └─→ BM25 倒排索引（关键词召回）
                                                      ↓
用户提问 ──→ 两路各召回 recallK 条 ──→ 加权 RRF 融合 ──→ Top-K
                                                      ↓
                              Prompt 组装 → 流式生成 → 前端逐字渲染 + 引用角标
```

已完成的能力：

| 能力 | 说明 |
|---|---|
| 文档接入 | 支持 PDF / DOCX / TXT / MD，上传接口只做落盘，处理走异步线程池 |
| **MySQL 持久化** | 文档元信息与切块（含向量）落库，启动时自动恢复索引，**重启不用重新上传** |
| **多轮对话记忆** | 上下文查询改写 + 历史进 Prompt + 滚动摘要压缩，"那它的功率呢"能听懂 |
| **令牌桶限流** | 429 + `Retry-After`，空闲桶定时回收 |
| **模型降级** | 模型不可用时不报错，退回展示检索到的原文片段 |
| **高并发能力（原第 3 项目，已融入）** | 异步线程池 + 虚拟线程 / Redis 全局 QPS 指标 / 防缓存击穿分布式锁，详见「高并发能力」一节 |
| 语义切块 | 段落优先 + 句子兜底 + 尾部重叠，避免把完整语义从中间劈开 |
| 向量化 | 本地哈希向量（零依赖）与远端 Embedding 接口可一键切换 |
| **BM25 关键词召回** | 自研倒排索引，中文二元组分词，零第三方依赖 |
| **RRF 融合** | 两路结果按排名融合，可配权重，天然免疫量纲差异 |
| **检索模式热切换** | `vector` / `keyword` / `hybrid` 三种模式，改配置即可 A/B 对比 |
| **检索对比接口** | `/api/retrieval/debug` 一次返回三路结果与各路耗时，用于跑评测集 |
| 引用溯源 | 答案内嵌 `[1][2]` 角标，点击展开原文片段，片段上标出两路排名 |
| 流式输出 | 后端 SSE + 前端 `fetch` ReadableStream 手动解析 |
| 可观测 | 检索耗时 / 生成耗时 / 召回片段数 / 分数 / 检索模式全部可见 |

**M3 计划见文末。**

---

## 快速开始

### 1. 环境要求

- JDK 21+
- Maven 3.8+

### 2. 直接启动（零配置）

```bash
cd insightkb
mvn spring-boot:run
```

打开 <http://localhost:8080> 即可。

**默认是 Mock 模式**：不需要任何 API Key，向量化用本地哈希算法，
对话环节直接展示检索到的原文片段。目的是让你在没有任何外部依赖的情况下，
先把整条链路跑通、把切块参数调好。

### 3. 接入真实模型

**Key 只走环境变量，绝不写进配置文件**（这个项目是要放 GitHub 的，
一旦提交，Key 会在几分钟内被扫描机器人撸走）。

三种方式任选其一：

```bash
# ① 最省事：双击 run.bat（自动从 .env.local 读 Key 并启动）
run.bat

# ② 命令行临时注入
set LLM_API_KEY=sk-xxxx
mvn spring-boot:run

# ③ 本机永久生效：Windows 环境变量里加一条 LLM_API_KEY
```

`.env.local` 已在 `.gitignore` 里，模板见 `.env.local.example`。

`application.yml` 里 `insightkb.llm.mode` 设为 `openai` 即走真实模型，
默认 base-url 就是 DeepSeek。实测一轮问答（检索 + 生成）约 0.6 秒。

> **DeepSeek 的硬限制：它不提供 embedding 接口。**
> 所以现在 `embedding-mode` 只能是 `mock`（本地哈希向量），语义召回质量很差，
> 检索主要靠 BM25 在撑。想让"召回率"这个数字变得可信，必须换一家：
> 硅基流动 `https://api.siliconflow.cn/v1` + 模型 `BAAI/bge-m3`（有免费额度），
> 或智谱 `https://open.bigmodel.cn/api/paas/v4` + `embedding-3`。
> 换的时候同时改 `base-url`、`embedding-model`，并把 `embedding-mode` 改成 `openai`。

或直接改 `src/main/resources/application.yml`：

```yaml
insightkb:
  llm:
    mode: openai            # 对话走真实模型
    embedding-mode: openai  # 向量走真实模型
    base-url: https://api.deepseek.com/v1
    api-key: sk-xxxx
    chat-model: deepseek-chat
    embedding-model: BAAI/bge-m3   # 见下方说明
```

> **注意**：DeepSeek 只提供对话接口，**不提供 embedding 接口**。
> 所以向量一般要换另一家服务商，例如：
> - 硅基流动 `https://api.siliconflow.cn/v1` → 模型填 `BAAI/bge-m3`
> - 智谱 `https://open.bigmodel.cn/api/paas/v4` → 模型填 `embedding-3`
>
> 也可以只把对话切到 openai、向量继续用本地哈希（`embedding-mode: mock`）。

---

## 项目结构

```
src/main/java/com/insightkb/
├── common/          统一响应体、业务异常、全局异常处理
├── config/          配置绑定、Bean 装配、线程池
├── controller/      HTTP 入口（文档 / 问答 / 系统信息）
├── llm/             对话模型抽象 + OpenAI 兼容实现 + Mock 实现
├── model/           领域对象（文档、切块、检索结果、问答结果）
├── rag/             向量模型抽象 + 本地实现 + 远端实现 + 内存向量库 + BM25 倒排索引
└── service/         业务编排（文档流水线、检索、问答）

eval/
├── questions.json   评测集（问题 + 人工标注的答案所在块）
└── run_eval.py      批量跑三路对比，输出命中率与耗时

src/main/resources/static/
├── index.html       页面骨架
├── css/style.css    全部手写样式
└── js/
    ├── api.js       接口封装（含 XHR 上传进度）
    ├── sse.js       SSE 流式解析
    ├── render.js    DOM 渲染（组件化写法）
    └── app.js       状态编排与事件绑定
```

---

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/documents` | 上传文档（multipart，字段名 `file`） |
| GET | `/api/documents` | 文档列表 |
| GET | `/api/documents/{id}` | 单个文档状态（前端轮询用） |
| DELETE | `/api/documents/{id}` | 删除文档及其全部向量 |
| POST | `/api/documents/{id}/reindex` | 重建索引（换 Embedding 模型后必须调用） |
| GET | `/api/documents/{id}/chunks` | 查看切块明细（人工评估切块粒度用） |
| POST | `/api/chat/stream` | 流式问答，返回 `text/event-stream` |
| GET | `/api/retrieval/debug` | 检索三路对比（向量 / 关键词 / 融合），参数 `q`、`topK` |
| GET | `/api/system/info` | 当前模型、向量维度、切块参数、检索模式、两个索引的规模 |

---

## 几个关键设计取舍

**1. 为什么切块不是定长硬切**
定长会把完整句子从中间劈开，检索到的片段失去上下文，模型拿到半句话很容易答偏。
这里用「段落优先 → 超长段落按句切 → 相邻块保留重叠」的策略，尽量让每块都是完整语义单元。

**2. 为什么上传接口不阻塞**
一个 30 页 PDF 的解析 + 向量化要几秒到几十秒。同步处理会让用户干等，
更严重的是长时间占用 Tomcat 线程，几个并发上传就能把线程池打满。
所以上传只做「落盘 + 登记任务」，处理丢给独立线程池，前端轮询状态。

**3. 为什么用 fetch 而不是 EventSource**
`EventSource` 只支持 GET，问题必须塞进 URL。长问题会超长，
而且 URL 经常被网关和日志完整记录，等于把用户提问暴露在日志里。
所以后端返回标准 SSE，前端用 `fetch` + `ReadableStream` 手动解析帧。

**4. 为什么 SSE 生成用虚拟线程**
流式生成绝大部分时间在等网络 IO，平台线程会被白白占住。
Java 21 的虚拟线程在阻塞时自动让出载体线程，可以按请求创建，不用纠结线程池开多大。

**5. 为什么中文分词用二元组，不引分词器**

知识库里最值钱的内容恰恰是词典里没有的：产品名、型号、缩写、内部黑话。
词典分词（IK / jieba）遇到未登录词只能切成单字，等于把这些关键信息全部稀释掉。
二元组滑动切分不需要词典，"X200 额定功率" 照样能切出稳定的词项，
代价只是索引体积约为单字的 2 倍 —— 在万级切块的规模下完全不痛。

**6. 为什么融合用 RRF，不用"分数归一化后加权"**

余弦相似度落在 `[-1,1]`（实际多集中在 0.3~0.8），BM25 没有上界，几十到几百都常见。
把两个量纲完全不同的分数加权相加，权重就变成了玄学参数 —— 换一批文档，最优权重立刻失效。
RRF 只吃"排名"：`score = Σ w_i / (k + rank_i)`，天然免疫量纲差异，
换语料也不用重新调参。这就是它在 Elasticsearch、向量库厂商里被普遍采用的真正原因。

**7. 为什么第一版不上 Elasticsearch**
这一步要验证的是「检索链路是否成立」——切块粒度、Top-K、相似度区分度。
这些结论和向量存在哪无关，但引入 ES 会额外带来索引映射、分词器、服务运维三类问题，
把排错面放大好几倍。先用内存实现把链路和参数定下来，再换存储引擎。

---

## 已知限制

- 检索索引仍在内存中（MySQL 只用于**恢复**）。开了持久化后重启会自动从库里读回，
  但如果库也没了（删库 / 换机器）数据照样丢，真正的检索存储要等 ES / pgvector
- 恢复时会跳过向量维度与当前模型不一致的切块，并在日志里提示需要 `reindex`
- 向量路仍是全量线性扫描，O(n)，万级切块以下可接受（BM25 走倒排，无此问题）
- 默认用的是本地哈希向量，语义召回质量远低于真实 Embedding 模型 ——
  想拿到能看的准确率数字，必须先把 `embedding-mode` 切到远端模型（DeepSeek 不提供
  embedding 接口，需换硅基流动 / 智谱；见下方「接入真实模型」）
- 没有 Rerank 精排
- 限流当前是单实例生效（Redis Lua 已就位，多副本共享计数已可用；若仍用进程内实现则各限各的）

---

## 开启 MySQL 持久化（默认已开）

内存方案每重启一次就要重新上传全部文档、重跑一遍 Embedding —— 演示或压测时
几十份文档的重建成本会吃掉整个下午。开了持久化之后，启动只是一次读表 + 反序列化。

```bash
# 1. 建库建表（只需一次，脚本幂等）
mysql -u root -p < db/schema.sql

# 2. 把数据库密码放进 .env.local（第二行），别写进配置文件
#    DB_PASSWORD=你的密码

# 3. 启动
run.bat
```

启动日志里看到这两行就说明生效了：

```
持久化：开启（MySQL，重启后自动恢复索引）
索引恢复完成 | 文档 2 篇 | 切块 5 块（向量可用 5 块）| 耗时 172 ms
```

**不想用数据库就关掉**：把 `insightkb.storage.persist` 设为 `false`（或环境变量
`PERSIST_ENABLED=false`），全部持久化调用退化成空操作，零配置启动的体验不受影响。
一个必须依赖外部数据库才能跑起来的项目，对面试官和 reviewer 都不友好。

几个踩过的坑，写在这里省得重复踩：

- JDBC 的 `characterEncoding` **只能写 `UTF-8`**，写 `utf8mb4` 会直接抛
  `Unsupported character encoding` 导致连不上（`utf8mb4` 是 MySQL 的字符集名，不是 Java 编码名）。
- 向量以 JSON 存在 `kb_chunk.embedding`，**只为省掉重新调 Embedding 接口的钱和时间**，
  相似度计算仍在内存做。MySQL 没有向量索引，全表算余弦在万级切块下会慢到不可用。
- 换 Embedding 模型后旧向量维度对不上，启动会跳过并提示 `reindex`，不会静默算错。

---

## 部署：Docker + Nginx（M3）

仓库根目录已提供 `Dockerfile` / `docker-compose.yml` / `nginx.conf`，一键拉起「应用 + MySQL + Redis + Nginx」：

```bash
# 1) 准备密钥（任选其一）
cp .env.local.example .env.local   # 填入 LLM_API_KEY / DB_PASSWORD（.env.local 已被 gitignore）
# 或：export LLM_API_KEY=sk-xxx DB_PASSWORD=xxx

# 2) 构建并启动（MySQL 首次启动会自动执行 db/schema.sql 建表）
docker compose up --build

# 3) 浏览器打开 http://localhost
```

要点：

- **应用**默认监听 8080，经 `nginx.conf` 在 80 端口反向代理；Nginx 已关闭 `proxy_buffering`
  以正确转发 SSE 流式回答，并透传 `X-Forwarded-For` 让限流按真实客户端生效。
- **MySQL** 用 `MYSQL_DATABASE=insightkb` 初始化库，并通过挂载 `db/schema.sql` 自动建表；
  数据落在具名卷 `mysql-data`，重启不丢。
- **Redis** 开 `allkeys-lru` + 256MB 上限，供限流（Lua 令牌桶）与会话热缓存使用。
- **密钥全走环境变量**，不写进镜像层（Dockerfile 的 ENTRYPOINT 只 `java -jar`）。
- 想只跑本地开发，直接用 `run.bat` 即可，不依赖 Docker。

> 注：本机的 Docker 未安装，上述编排配置已按标准写法写好，但未在此环境实测构建；
> 在你本机或服务器上 `docker compose up --build` 即可验证。

---

## 多轮对话记忆（M3）

单轮 RAG 最明显的短板：用户问"它的功率是多少"，送去检索的 query 就只有这七个字，
既没型号也没对象，向量和 BM25 都不可能召回对。这里分三步解决：

1. **上下文查询改写**（收益最大）：结合历史把带指代的问题改写成独立可检索的 query。
2. **历史消息进 Prompt**：只取最近 N 轮，避免 prompt 无限膨胀。
3. **滚动摘要压缩**：每 N 轮把较早内容压成摘要存 `kb_conversation.summary`，
   长对话的 token 成本是常数级而不是线性上涨。

**一个容易搞错的点**：送进 Prompt 的是**用户原话**，送去检索的是**改写后的 query**。
模型看到原话才能结合历史答得连贯；检索必须拿改写后的完整问题，否则召回的全是噪声。
混用是很多 RAG 项目多轮效果差的直接原因。

实测（`python eval/chat_multi.py`）：

```
问：Model X200 是什么设备？
答：Model X200 是面向中型工厂场景设计的工业物联网网关 [1]...

问：那它的额定功率是多少？
   [查询改写] Model X200 工业物联网网关的额定功率是多少？
答：Model X200 的额定功率为 18 W [1]。
```

配置在 `insightkb.chat.*`：`history-turns`（进 Prompt 的轮数）、
`query-rewrite`（是否改写，关掉可省一次模型调用）、`summary-every-turns`（压缩频率）。

## 限流与降级（M3）

- **令牌桶 + Redis Lua**（`common/RateLimiter.java` 接口，两个实现）：
  - `RedisTokenBucketLimiter`：用一段 Lua 脚本在 Redis 里**原子**地完成"读令牌→算→写"，
    避免并发下超发；多副本部署时共享同一计数。
  - `TokenBucketLimiter`：进程内实现，零网络开销，单机够用。
  - 由 `config/RateLimiterConfig.java` 按 `insightkb.limit.backend`（`redis` / `memory` / `auto`）
    自动装配。`auto` 模式下探测到 Redis 可用就走 Redis，否则静默退回进程内。
    当前后端可在 `GET /api/system/info` 的 `rateLimitBackend` 字段确认。
  - `qps=1.0`、`burst=5`，超限返回 **429 + `Retry-After`**。实测 8 个并发打进去，6 个 200、2 个 429。
- **为什么用 Lua 而不是 GET→算→SET 三步**：三步之间有时间窗，两个并发请求可能同时读到
  `tokens=1` 各自减成 0 再写回，结果放行两次——限流在并发下悄悄失效，且只在压测时暴露。
- **按 X-Forwarded-For 取来源**：挂了 Nginx 之后如果只认 `remoteAddr`，
  所有请求会被当成同一个来源，限流直接失效。XFF 可伪造，所以只用于限流、不用于鉴权。
- **降级**：模型超时 / 余额用完 / 网络抖动时，不再返回 500，而是把已检索到的
  原文片段整理成一段说明顶上去，问答链路仍然可用。
- **Redis 挂了的兜底**：Redis 限流脚本执行异常时降级为**放行**并告警，避免限流器本身成为单点故障。

## 会话热缓存：Redis + MySQL 两级存储（M3）

多轮对话的历史消息存储分层，兼顾"快"和"不丢"：

- **MySQL（冷存储 / 真相源）**：每次读写都落库，`kb_message` 存原始消息、`kb_conversation` 存摘要，
  重启不丢、可审计。
- **Redis（热缓存）**：把"最近活跃的会话"按消息列表缓存，设 TTL（默认 30 分钟）。
  取历史时先打 Redis，命中即返回；未命中回源 MySQL 并预热。Redis 挂了 / 没配就直接走 MySQL，
  对话功能不受影响。

设计取舍：存原始消息而非只存摘要，是因为摘要是有损的——用户回头追问被压掉的段落时，
原始消息能兜底，摘要只用于压缩送进 Prompt 的长度。

## 高并发能力（原规划「高并发练兵场」，已融入本仓库）

规划书里第 3 个项目本是一个独立的高并发 Demo，后来决定不单独建仓，
把「高并发」作为 InsightKB 的核心工程能力来打磨——简历上仍是 InsightKB + FlowCraft 两个项目，
但 InsightKB 内已经集齐了校招后端岗最常考的三件套：**限流 / 缓存 / 异步**，外加可观测指标与防击穿锁。

- **异步（线程池 + 虚拟线程）**：`config/AsyncConfig.java`
  - 文档处理丢进 `ThreadPoolTaskExecutor`（`core=2 / max=4 / 队列 200`，拒绝策略 `CallerRunsPolicy`，
    优雅停机等任务跑完再退）。队列满时让调用线程自己跑，任务不丢——对"慢但要保结果"的文档处理，
    丢任务比变慢严重得多。
  - SSE 流式生成用 **Java 21 虚拟线程**（`Executors.newVirtualThreadPerTaskExecutor()`）：
    流式生成大部分时间在等 IO，虚拟线程阻塞时自动让出载体线程，几百个并发问答也不会把线程池打满。
- **限流**：见上「限流与降级（M3）」，Redis Lua 令牌桶，多副本共享计数。
- **缓存**：见上「会话热缓存」，Redis + MySQL 两级。
- **请求并发指标**（`service/MetricsService.java`，可在 `GET /api/system/info` 的 `concurrency` 字段看到）：
  - `qps` / `qps2s` / `total` / `window60s`（最近 60 秒请求数）。
  - 计数也走 **Redis INCR**（秒桶 + 分钟桶），多实例部署时 QPS 是全局准确的；
    没配 Redis 时自动退化成进程内 `AtomicLong`，功能不丢只是不再全局。
  - 计数放在限流**之后**：被 429 挡掉的请求不计入，指标反映的是"真实放行的流量"。
- **防缓存击穿的分布式锁**（`common/RedisLock.java`，用在 `ConversationStore` 的冷读预热路径）：
  - 同一会话高并发冷读时，按 `conversationId` 加一把锁，保证只有一个线程去查 MySQL 预热，
    其余线程要么命中别人刚写好的缓存、要么回退 MySQL 直查，避免"缓存击穿"把 DB 打爆。
  - 释放用 **Lua 脚本比对 token** 再 `DEL`，杜绝"锁过期后误删别人刚拿到的锁"。

> 面试怎么讲：限流（Redis Lua 原子）、缓存（两级 + 防击穿锁）、异步（线程池拒绝策略 + 虚拟线程）、
> 可观测（Redis 全局 QPS 计数）——这四点是高并发后端的标准考点，本项目每个都有真实实现可指。

---

## 检索质量怎么调

`src/main/resources/application.yml` 里的 `insightkb.rag.*` 是全部旋钮：

| 配置项 | 默认 | 什么时候调 |
|---|---|---|
| `retrieval-mode` | `hybrid` | 想跑基线对比时切成 `vector` 或 `keyword` |
| `recall-k` | 20 | 融合前的候选池。太小会让好片段根本进不了候选 |
| `top-k` | 5 | 送入大模型的片段数。太多会稀释注意力、烧 token |
| `rrf-k` | 60 | 调大 = 更看重"两路都靠前"；调小 = 更看重单路第一名 |
| `vector-weight` / `keyword-weight` | 1.0 / 1.0 | 专有名词类查询召回差时，把 `keyword-weight` 提到 1.5 试试 |
| `bm25-k1` | 1.5 | 调大 = 词频高的词权重更高（文档里反复出现的词更重要） |
| `bm25-b` | 0.75 | 调小 = 减弱对长文档的惩罚 |

---

## 后续计划

**M2 剩余 · 检索质量**
- 接入 Rerank 模型做精排（对融合后的 Top-20 重排，取 Top-5）
- 查询改写：把口语化问题扩写成多条子查询，再分别召回
- 建立 30~50 条标注问答的评测集，量化对比优化前后的准确率
  （用 `/api/retrieval/debug` 批量跑，脚本见下）

**M3 · 工程化（已完成）**
- ~~文档元信息与切块落 MySQL~~ **已完成**（`repository/KnowledgeStore.java` + `IndexBootstrap.java`）
- ~~多轮对话记忆（改写 + 历史 + 滚动摘要）~~ **已完成**（`ConversationService` + `ConversationStore`，Redis 热缓存 + MySQL 冷存储两级）
- ~~令牌桶限流 + 429 + 模型降级~~ **已完成**（`RateLimiter` 接口，Redis Lua / 进程内双实现，auto 自动探测）
- ~~高并发能力（原规划第 3 个项目）~~ **已完成并融入本仓库**：异步线程池 + 虚拟线程、请求并发指标（Redis 全局 QPS）、防缓存击穿分布式锁（`MetricsService` + `RedisLock` + `AsyncConfig`），见「高并发能力」一节
- ~~Docker Compose 编排 + Nginx 反向代理~~ **已完成**（`Dockerfile` / `docker-compose.yml` / `nginx.conf`，含 SSE 流式代理配置）
- 向量迁移到 Elasticsearch（dense_vector）或 pgvector —— 评测稳定后再换，接口与现在一致
- RabbitMQ 替换当前的线程池异步，支持任务持久化与重试 —— 文档量大、需要削峰时再做
- Rerank 精排：对融合后的 Top-20 重排取 Top-5，进一步抬升首条命中率

---

## 怎么用 debug 接口跑评测集

简历上那句"混合检索使召回率从 X% 提升到 Y%"，必须自己跑出来。最小流程：

1. 准备 30~50 条问题，人工标注每条的正确答案来自哪一块（记下块 id）；
2. 批量调接口取三路结果：

```bash
curl -G "http://localhost:8080/api/retrieval/debug" \
     --data-urlencode "q=Model X200 的额定功率是多少" \
     --data-urlencode "topK=5"
```

返回里 `vector` / `keyword` / `fused` 三段的 `id` 就是各自命中的切块，
`keywordOnlyCount` 表示"只有关键词路捞回来、向量路漏掉"的条数 —— 这个数字越大，
说明混合检索的补位价值越实在，写简历时比"准确率提升"更有说服力。

3. 按 `retrieval-mode` 切成 `vector` 重启跑一遍、再切回 `hybrid` 跑一遍，
   同一批问题的命中率差值就是真实提升幅度。

脚本已经写好了，直接跑：

```bash
python eval/run_eval.py --base http://localhost:52113 --topk 1
python eval/run_eval.py --base http://localhost:52113 --topk 3
```

### 当前基线（2026-10-07 复测，DeepSeek 对话 + 本地哈希向量 + 5 个切块 / 12 条问题）

> 复测结论与上次一致：在如此小的文档集上三路都是满分（Top-3 命中 12/12），
> 但 `keywordOnlyCount=3`、`vectorOnlyCount=8` 说明两路召回**明显互补**——
> 纯向量会漏掉的 3 个片段靠 BM25 捞回，纯关键词会漏掉的 8 个片段靠向量捞回，
> 这正是混合检索存在的价值。样本太小（12 问 / 5 块）仍不足以写进简历，见下方说明。

| Top-K | 纯向量 | 纯关键词 | 融合 RRF |
|---|---|---|---|
| 1 | 66.7% | 75.0% | **75.0%** |
| 3 | 91.7% | 100% | **100%** |
| 5 | 100% | 100% | 100% |

**这组数字现在还不能写进简历**，两个原因：

1. Top-5 命中率 100% 是假象——库里一共只有 5 个切块，Top-5 等于把全库都返回了，
   任何算法都是满分。**评测必须让 K 远小于库规模**，所以要看就看 Top-1 / Top-3。
2. 12 条问题、2 份文档样本太小，波动一条就是 8 个百分点。
   要写到简历上的数字，至少 **30~50 条问题 + 上百个切块** 才站得住。

下一步把文档堆到 20 份以上、问题补到 40 条，这套脚本跑出来的差值才有意义。

---

## 量化指标怎么测

这个项目要写进简历，数字必须是自己测出来的。测法：

| 指标 | 怎么测 |
|---|---|
| 首字延迟 TTFB | 浏览器 DevTools → Network → 看 SSE 第一个分片到达时间 |
| 回答准确率 | 准备 30~50 条问题 + 标准答案，人工判定「答案是否被召回片段支撑」 |
| 召回率提升 | 同一批问题，`retrieval-mode` 分别设 `vector` / `hybrid` 各跑一遍，比命中率 |
| 关键词补位条数 | debug 接口的 `keywordOnlyCount`：纯向量会漏、靠 BM25 捞回的片段数 |
| 上传接口耗时 | DevTools 看 `POST /api/documents` 的响应时间 |
| 并发能力 | JMeter / wrk 压测 `/api/chat/stream`，记录 QPS、P95、错误率 |
| token 消耗 | 从模型响应的 usage 字段累计，对比开启摘要压缩前后 |

**不要编数字。** 面试官一定会问「你怎么测的」，
真实数据哪怕只有 62% → 75%，也远胜漂亮的假数据。
