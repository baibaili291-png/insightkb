/**
 * SSE 流式接收。
 *
 * 为什么不用浏览器原生的 EventSource：
 * EventSource 只支持 GET，问题必须拼进 URL。长问题会超长，
 * 而且 URL 经常被网关和日志系统完整记录，把用户提问暴露在日志里。
 *
 * 所以这里用 fetch + ReadableStream 手动解析 SSE 帧。
 * 协议本身没变（仍然是 text/event-stream），只是换了个读取方式，
 * 顺便获得了两个能力：可以发 POST，以及可以主动 abort。
 *
 * SSE 帧格式：
 *   data: {"type":"delta","content":"你"}
 *   <空行>
 *   data: {"type":"delta","content":"好"}
 *   <空行>
 * 空行是帧分隔符 —— 这一点是手写解析最容易踩的坑：
 * 用 read() 一次拿到的 chunk 不保证正好是一个完整的帧，
 * 可能一次拿到三帧、也可能半帧，所以必须先攒进 buffer 再按空行切。
 */
export async function streamChat(question, handlers = {}, conversationId = null) {
    const controller = new AbortController();

    const response = await fetch('/api/chat/stream', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({question, conversationId}),
        signal: controller.signal
    });

    if (!response.ok) {
        const text = await response.text();
        throw new Error(extractMessage(text) || `请求失败（HTTP ${response.status}）`);
    }
    if (!response.body) {
        throw new Error('当前浏览器不支持流式响应');
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder('utf-8');

    let buffer = '';
    let fullText = '';

    while (true) {
        const {done, value} = await reader.read();
        if (done) {
            break;
        }

        buffer += decoder.decode(value, {stream: true});
        // 统一换行符，后端的 SSE 帧可能用 \r\n 结尾
        buffer = buffer.replace(/\r\n/g, '\n');

        let separator;
        while ((separator = buffer.indexOf('\n\n')) >= 0) {
            const frame = buffer.slice(0, separator);
            buffer = buffer.slice(separator + 2);

            const payload = parseFrame(frame);
            if (!payload) {
                continue;
            }

            if (payload.type === 'delta') {
                fullText += payload.content;
                handlers.onDelta?.(payload.content, fullText);
            } else if (payload.type === 'done') {
                handlers.onDone?.(payload, fullText);
            } else if (payload.type === 'error') {
                handlers.onError?.(new Error(payload.message || '服务端处理失败'));
            }
        }
    }

    return {text: fullText, abort: () => controller.abort()};
}

/** 把一个 SSE 帧里的多行 data: 拼起来再解析成 JSON */
function parseFrame(frame) {
    const dataLines = [];
    for (const line of frame.split('\n')) {
        if (line.startsWith('data:')) {
            dataLines.push(line.slice(5).replace(/^ /, ''));
        }
    }
    if (dataLines.length === 0) {
        return null;
    }
    try {
        return JSON.parse(dataLines.join('\n'));
    } catch (e) {
        console.warn('无法解析的 SSE 帧:', frame);
        return null;
    }
}

/** 后端报错时返回的仍是 ApiResponse 结构，这里尽量把它里面的 message 取出来 */
function extractMessage(text) {
    try {
        return JSON.parse(text).message;
    } catch (e) {
        return text ? text.slice(0, 200) : '';
    }
}
