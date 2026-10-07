/**
 * 应用入口与状态编排。
 *
 * 状态集中放在 store 里，界面由 render 层根据数据生成 ——
 * 这是"没有框架时怎么维持可维护性"的核心：只有一处数据源，
 * 不存在"DOM 上的值"和"内存里的值"不一致的问题。
 */

import {api, uploadDocument} from './api.js';
import {streamChat} from './sse.js';
import {
    createAssistantMessage,
    createTrace,
    createUserMessage,
    renderDocList
} from './render.js';

const $ = (id) => document.getElementById(id);

const dom = {
    uploadZone: $('uploadZone'),
    fileInput: $('fileInput'),
    uploadProgress: $('uploadProgress'),
    uploadProgressFill: $('uploadProgressFill'),
    uploadProgressText: $('uploadProgressText'),
    docList: $('docList'),
    docCounter: $('docCounter'),
    statChunks: $('statChunks'),
    statDim: $('statDim'),
    statEmbedding: $('statEmbedding'),
    statChat: $('statChat'),
    messages: $('messages'),
    emptyState: $('emptyState'),
    emptySamples: $('emptySamples'),
    questionInput: $('questionInput'),
    sendBtn: $('sendBtn'),
    newChatBtn: $('newChatBtn'),
    modeBadge: $('modeBadge'),
    topbarHint: $('topbarHint')
};

const store = {
    documents: [],
    streaming: false,
    system: null,
    // 多轮对话：页面级会话 ID。后端拿它取历史消息做指代消解与查询改写，
    // 点"新会话"就换新 ID，等于重新开始一段记忆
    conversationId: null
};

/** 有文档正在处理时才开的轮询定时器 */
let pollTimer = null;

const SAMPLE_QUESTIONS = [
    '这份文档主要讲了什么？',
    '有哪些关键结论或要求？',
    '文中提到的具体数据是多少？'
];

/* ==================== 启动 ==================== */

init();

async function init() {
    bindUpload();
    bindComposer();
    bindNewChat();
    bindCitationClick();
    renderSamples();
    autoGrow();

    await Promise.all([refreshDocuments(), refreshSystemInfo()]);
}

/* ==================== 文档 ==================== */

async function refreshDocuments() {
    try {
        const docs = await api.listDocuments();
        store.documents = docs;
        renderDocList(dom.docList, docs, {onDelete: handleDelete});
        dom.docCounter.textContent = String(docs.length);
        syncPolling();
    } catch (e) {
        console.error('加载文档列表失败', e);
    }
}

/**
 * 只在"存在处理中文档"时才轮询。
 * 无条件定时刷新既浪费请求，也会让日志里塞满无意义的重复调用。
 */
function syncPolling() {
    const processing = store.documents.some((d) => d.status === 'PROCESSING');
    if (processing && !pollTimer) {
        pollTimer = setInterval(refreshDocuments, 1500);
    } else if (!processing && pollTimer) {
        clearInterval(pollTimer);
        pollTimer = null;
        // 文档刚处理完，向量库规模变了，同步刷新一次统计
        refreshSystemInfo();
    }
}

async function handleDelete(doc) {
    if (!confirm(`确定删除「${doc.name}」吗？该文档的所有切块会一并从向量库移除。`)) {
        return;
    }
    try {
        await api.deleteDocument(doc.id);
        await refreshDocuments();
        await refreshSystemInfo();
    } catch (e) {
        alert(`删除失败：${e.message}`);
    }
}

/* ==================== 上传 ==================== */

function bindUpload() {
    dom.uploadZone.addEventListener('click', () => dom.fileInput.click());

    dom.fileInput.addEventListener('change', (e) => {
        handleFiles(e.target.files);
        // 清空 value，否则连续选同一个文件不会触发 change
        e.target.value = '';
    });

    ['dragenter', 'dragover'].forEach((type) => {
        dom.uploadZone.addEventListener(type, (e) => {
            e.preventDefault();
            dom.uploadZone.classList.add('dragover');
        });
    });

    ['dragleave', 'drop'].forEach((type) => {
        dom.uploadZone.addEventListener(type, (e) => {
            e.preventDefault();
            dom.uploadZone.classList.remove('dragover');
        });
    });

    dom.uploadZone.addEventListener('drop', (e) => {
        if (e.dataTransfer?.files?.length) {
            handleFiles(e.dataTransfer.files);
        }
    });
}

async function handleFiles(fileList) {
    const files = Array.from(fileList || []);
    if (files.length === 0) {
        return;
    }
    for (const file of files) {
        await uploadOne(file);
    }
    await refreshDocuments();
    await refreshSystemInfo();
}

async function uploadOne(file) {
    showUploadProgress(0, `正在上传 ${file.name}`);

    try {
        await uploadDocument(file, (ratio) => {
            showUploadProgress(ratio, `正在上传 ${file.name}  ${Math.round(ratio * 100)}%`);
        });
        showUploadProgress(1, `${file.name} 已提交，正在后台解析与向量化`);
        setTimeout(hideUploadProgress, 2000);
    } catch (e) {
        showUploadProgress(0, `上传失败：${e.message}`);
        setTimeout(hideUploadProgress, 5000);
    }
}

function showUploadProgress(ratio, text) {
    dom.uploadProgress.hidden = false;
    dom.uploadProgressFill.style.width = `${Math.round(ratio * 100)}%`;
    dom.uploadProgressText.textContent = text;
}

function hideUploadProgress() {
    dom.uploadProgress.hidden = true;
    dom.uploadProgressFill.style.width = '0%';
}

/* ==================== 对话 ==================== */

function bindComposer() {
    dom.sendBtn.addEventListener('click', send);

    dom.questionInput.addEventListener('input', autoGrow);

    dom.questionInput.addEventListener('keydown', (e) => {
        // Enter 发送、Shift+Enter 换行，是聊天类界面的通用约定
        if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
            e.preventDefault();
            send();
        }
    });
}

/**
 * 开一段新会话。
 * 换会话 ID 就等于换一段记忆：后端不再带入上一轮的历史，
 * 也就不会出现"上一份文档的问题串到这一份"的情况。
 */
function bindNewChat() {
    dom.newChatBtn?.addEventListener('click', () => {
        store.conversationId = null;
        dom.messages.innerHTML = '';
        dom.messages.appendChild(dom.emptyState);
        dom.emptyState.hidden = store.documents.length === 0 ? false : true;
        dom.questionInput.value = '';
        autoGrow();
        dom.questionInput.focus();
    });
}

function autoGrow() {
    const node = dom.questionInput;
    node.style.height = 'auto';
    node.style.height = `${Math.min(node.scrollHeight, 150)}px`;
}

async function send() {
    const question = dom.questionInput.value.trim();
    if (!question || store.streaming) {
        return;
    }

    dom.questionInput.value = '';
    autoGrow();
    dom.emptyState.hidden = true;

    createUserMessage(dom.messages, question);
    scrollToBottom();

    const shell = createAssistantMessage(dom.messages);
    setStreaming(true);
    scrollToBottom();

    try {
        await streamChat(question, {
            onDelta: (delta, fullText) => {
                shell.update(fullText);
                scrollToBottom();
            },
            onDone: (payload) => {
                shell.finish();
                // 首次提问时后端会分配会话 ID，存下来供后续追问复用
                if (payload.conversationId) {
                    store.conversationId = payload.conversationId;
                }
                shell.attachSources(payload.contexts);
                shell.body.appendChild(createTrace(payload));
                scrollToBottom();
            },
            onError: (error) => {
                shell.showError(error.message);
            }
        }, store.conversationId);
    } catch (e) {
        shell.showError(e.message);
    } finally {
        shell.finish();
        setStreaming(false);
        refreshSystemInfo();
    }
}

function setStreaming(streaming) {
    store.streaming = streaming;
    dom.sendBtn.disabled = streaming || dom.questionInput.value.trim() === '';
    dom.sendBtn.querySelector('span').textContent = streaming ? '生成中' : '发送';
}

/**
 * 点击答案里的 [1] 角标：展开该条消息的片段面板，并高亮对应片段。
 * 用事件委托绑在容器上，而不是给每个角标单独绑事件 ——
 * 流式渲染会不断重建角标节点，逐个绑定会立刻失效。
 */
function bindCitationClick() {
    dom.messages.addEventListener('click', (e) => {
        const cite = e.target.closest('.cite');
        if (!cite) {
            return;
        }
        const message = cite.closest('.msg');
        const body = message?.querySelector('.sources-body');
        if (!body) {
            return;
        }

        body.hidden = false;
        const head = message.querySelector('.sources-head');
        if (head) {
            head.firstElementChild.textContent = '▾';
        }

        const index = cite.dataset.cite;
        message.querySelectorAll('.source-item').forEach((item) => {
            item.classList.toggle('highlight', item.dataset.index === index);
        });
        body.querySelector(`.source-item[data-index="${index}"]`)
            ?.scrollIntoView({block: 'nearest', behavior: 'smooth'});
    });
}

function renderSamples() {
    SAMPLE_QUESTIONS.forEach((text) => {
        const chip = document.createElement('button');
        chip.className = 'sample-chip';
        chip.type = 'button';
        chip.textContent = text;
        chip.addEventListener('click', () => {
            dom.questionInput.value = text;
            dom.questionInput.focus();
            autoGrow();
            setStreaming(false);
        });
        dom.emptySamples.appendChild(chip);
    });
}

/* ==================== 系统信息 ==================== */

async function refreshSystemInfo() {
    try {
        const info = await api.systemInfo();
        store.system = info;

        dom.statChunks.textContent = String(info.chunkCount);
        dom.statDim.textContent = info.embeddingDimension > 0 ? String(info.embeddingDimension) : '-';
        setEllipsisText(dom.statEmbedding, info.embeddingModel);
        setEllipsisText(dom.statChat, info.chatModel);

        dom.modeBadge.hidden = !info.mockChat;
        dom.topbarHint.textContent =
            `切块 ${info.chunkSize} 字 / 重叠 ${info.chunkOverlap} 字 · 召回 ${info.recallK} · 送入 ${info.topK} 段`;
    } catch (e) {
        console.error('加载系统信息失败', e);
    }
}

function setEllipsisText(node, text) {
    node.textContent = text || '-';
    node.title = text || '';
}

function scrollToBottom() {
    dom.messages.scrollTop = dom.messages.scrollHeight;
}
