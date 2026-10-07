/**
 * 渲染层。
 *
 * 约定：这里的函数「接收数据 + 返回 DOM 节点」，不直接操作页面上的其他元素。
 * 好处是每块 UI 都能独立测试和复用，也不会出现"改一个地方崩三个地方"。
 * 这就是没有框架时替代"组件"的做法 —— 组件本质上也只是"输入数据、输出界面"。
 */

export function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) {
        node.className = className;
    }
    if (text != null) {
        node.textContent = text;
    }
    return node;
}

export function escapeHtml(str) {
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

/* ==================== 文档列表 ==================== */

const STATUS_TEXT = {
    PROCESSING: '处理中',
    READY: '就绪',
    FAILED: '失败'
};

export function renderDocList(container, docs, handlers) {
    container.innerHTML = '';

    if (!docs.length) {
        container.appendChild(el('div', 'list-empty', '还没有文档'));
        return;
    }

    docs.forEach((doc) => container.appendChild(createDocItem(doc, handlers)));
}

function createDocItem(doc, handlers) {
    const item = el('div', 'doc-item');
    item.dataset.id = doc.id;

    item.appendChild(el('div', 'doc-item-name', doc.name));

    const meta = el('div', 'doc-item-meta');
    meta.appendChild(el('span', `doc-status ${doc.status.toLowerCase()}`,
        STATUS_TEXT[doc.status] || doc.status));
    if (doc.status === 'READY') {
        meta.appendChild(el('span', null, `${doc.chunkCount} 块`));
    }
    meta.appendChild(el('span', null, formatSize(doc.size)));
    item.appendChild(meta);

    if (doc.status === 'FAILED' && doc.message) {
        item.appendChild(el('div', 'doc-error', doc.message));
    }

    const del = el('button', 'doc-delete', '×');
    del.title = '删除文档';
    del.addEventListener('click', (event) => {
        // 阻止冒泡，避免以后加了"点击文档查看切块"时被误触发
        event.stopPropagation();
        handlers.onDelete(doc);
    });
    item.appendChild(del);

    return item;
}

function formatSize(bytes) {
    if (!bytes) {
        return '0 B';
    }
    if (bytes < 1024) {
        return `${bytes} B`;
    }
    if (bytes < 1024 * 1024) {
        return `${(bytes / 1024).toFixed(1)} KB`;
    }
    return `${(bytes / 1024 / 1024).toFixed(2)} MB`;
}

/* ==================== 消息 ==================== */

/**
 * 创建一条助手消息骨架，返回可被流式更新的引用。
 * 流式渲染需要反复改写同一块文本，所以这里把关键节点引用暴露出去，
 * 而不是每来一个分片就重建整个 DOM —— 后者会让滚动位置不停跳动。
 */
export function createAssistantMessage(scrollContainer) {
    const root = el('div', 'msg assistant');
    root.appendChild(el('div', 'msg-avatar', 'AI'));

    const body = el('div', 'msg-body');
    const textNode = el('div', 'msg-text');
    textNode.innerHTML = '<span class="typing"><i></i><i></i><i></i></span>';
    body.appendChild(textNode);
    root.appendChild(body);

    scrollContainer.appendChild(root);

    let firstDelta = true;

    return {
        root,
        body,
        textNode,

        /** 收到增量文本时调用 */
        update(fullText) {
            if (firstDelta) {
                textNode.innerHTML = '';
                firstDelta = false;
            }
            renderAnswerText(textNode, fullText);
            appendCursor(textNode);
        },

        /** 生成结束，去掉闪烁光标 */
        finish() {
            removeCursor(textNode);
        },

        /** 把检索片段面板挂到这条消息下面 */
        attachSources(contexts) {
            if (!contexts || contexts.length === 0) {
                return;
            }
            const sources = createSources(contexts);
            body.appendChild(sources);
        },

        showError(message) {
            removeCursor(textNode);
            if (!textNode.textContent.trim()) {
                textNode.innerHTML = '';
            }
            const err = el('div', 'msg-error');
            err.textContent = `出错了：${message}`;
            body.appendChild(err);
        }
    };
}

export function createUserMessage(scrollContainer, text) {
    const root = el('div', 'msg user');
    root.appendChild(el('div', 'msg-avatar', '我'));
    const body = el('div', 'msg-body');
    body.appendChild(el('div', 'msg-text', text));
    root.appendChild(body);
    scrollContainer.appendChild(root);
    return root;
}

/**
 * 渲染答案正文，并把 [1] [2] 这类引用标记变成可点击的角标。
 * 先转义再替换：如果顺序反了，文档内容里的 HTML 会被当成标签执行。
 */
export function renderAnswerText(node, text) {
    const safe = escapeHtml(text);
    node.innerHTML = safe.replace(/\[(\d+)\]/g,
        (match, n) => `<span class="cite" data-cite="${n}">${n}</span>`);
}

function appendCursor(textNode) {
    removeCursor(textNode);
    textNode.insertAdjacentHTML('beforeend', '<span class="cursor"></span>');
}

function removeCursor(textNode) {
    textNode.querySelector('.cursor')?.remove();
}

/* ==================== 检索片段 ==================== */

function createSources(contexts) {
    const box = el('div', 'sources');

    const head = el('div', 'sources-head');
    head.innerHTML = `<span>▸</span><span>检索到 ${contexts.length} 个相关片段</span>`;
    box.appendChild(head);

    const body = el('div', 'sources-body');
    body.hidden = true;

    contexts.forEach((ctx, i) => {
        const item = el('div', 'source-item');
        item.dataset.index = String(i + 1);

        const head2 = el('div', 'source-head');
        head2.appendChild(el('span', 'source-index', String(i + 1)));
        head2.appendChild(el('span', null, `${ctx.docName} · 第 ${ctx.index + 1} 块`));

        // 混合检索下 score 是 RRF 融合分，再叫"相似度"会误导；
        // 单路召回时才叫相似度。两路排名直接标出来，方便看是不是某一路独力捞回来的
        const bothRoutes = ctx.vectorRank != null && ctx.keywordRank != null;
        head2.appendChild(el('span', 'source-score',
            `${bothRoutes ? '融合分' : '相似度'} ${ctx.score.toFixed(4)}`));

        const ranks = [];
        if (ctx.vectorRank != null) {
            ranks.push(`向量 #${ctx.vectorRank}`);
        }
        if (ctx.keywordRank != null) {
            ranks.push(`关键词 #${ctx.keywordRank}`);
        }
        if (ranks.length) {
            head2.appendChild(el('span', 'source-rank', ranks.join(' · ')));
        }
        item.appendChild(head2);

        item.appendChild(el('div', 'source-text', ctx.text));
        body.appendChild(item);
    });

    box.appendChild(body);

    head.addEventListener('click', () => {
        body.hidden = !body.hidden;
        head.firstElementChild.textContent = body.hidden ? '▸' : '▾';
    });

    return box;
}

/* ==================== Trace ==================== */

const RETRIEVAL_MODE_TEXT = {
    vector: '纯向量',
    keyword: '纯关键词',
    hybrid: '混合 RRF'
};

export function createTrace(payload) {
    const trace = el('div', 'trace');
    const mode = RETRIEVAL_MODE_TEXT[payload.retrievalMode] || '混合 RRF';
    trace.innerHTML =
        `<span>检索 <b>${payload.retrievalMs} ms</b></span>` +
        `<span>生成 <b>${payload.generationMs} ms</b></span>` +
        `<span>召回 <b>${payload.contexts ? payload.contexts.length : 0}</b> 段</span>` +
        `<span>模式 <b>${mode}</b></span>` +
        `<span><b>${escapeHtml(payload.chatModel || '-')}</b></span>`;

    // 多轮追问被改写过时才显示：这是"为什么能听懂'那它的功率呢'"的解释入口
    if (payload.rewritten && payload.effectiveQuery) {
        const tip = el('div', 'trace-rewrite', `检索用改写问法：${payload.effectiveQuery}`);
        trace.appendChild(tip);
    }
    return trace;
}
