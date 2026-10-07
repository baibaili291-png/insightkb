/**
 * 接口层。
 *
 * 所有后端调用都收在这里，页面逻辑不直接碰 fetch。
 * 好处是后端改了路径或响应结构，只需要改这个文件，
 * 而不是去十几个地方找 fetch 调用。
 */

const BASE = '/api';

/** 统一处理：解析 JSON -> 判断 code -> 拆出 data，失败一律抛 Error */
async function request(url, options = {}) {
    const response = await fetch(url, options);

    let payload;
    try {
        payload = await response.json();
    } catch (e) {
        throw new Error(`服务返回了非 JSON 内容（HTTP ${response.status}）`);
    }

    if (!response.ok || payload.code !== 0) {
        throw new Error(payload.message || `请求失败（HTTP ${response.status}）`);
    }
    return payload.data;
}

export const api = {
    listDocuments() {
        return request(`${BASE}/documents`);
    },

    getDocument(id) {
        return request(`${BASE}/documents/${id}`);
    },

    deleteDocument(id) {
        return request(`${BASE}/documents/${id}`, { method: 'DELETE' });
    },

    reindexDocument(id) {
        return request(`${BASE}/documents/${id}/reindex`, { method: 'POST' });
    },

    systemInfo() {
        return request(`${BASE}/system/info`);
    },

    deleteDocumentSafe(id) {
        return request(`${BASE}/documents/${id}`, { method: 'DELETE' });
    }
};

/**
 * 文件上传。
 *
 * 这里刻意不用 fetch：fetch 至今没有可靠的方式拿到上传进度，
 * 而上传大 PDF 时"到底传了多少"是用户最需要的信息。
 * XMLHttpRequest 的 upload.onprogress 是唯一标准做法。
 */
export function uploadDocument(file, onProgress) {
    return new Promise((resolve, reject) => {
        const xhr = new XMLHttpRequest();
        xhr.open('POST', `${BASE}/documents`);

        xhr.upload.onprogress = (event) => {
            if (event.lengthComputable && typeof onProgress === 'function') {
                onProgress(event.loaded / event.total);
            }
        };

        xhr.onload = () => {
            let payload;
            try {
                payload = JSON.parse(xhr.responseText);
            } catch (e) {
                reject(new Error(`上传失败：服务返回了非 JSON 内容（HTTP ${xhr.status}）`));
                return;
            }
            if (xhr.status >= 200 && xhr.status < 300 && payload.code === 0) {
                resolve(payload.data);
            } else {
                reject(new Error(payload.message || `上传失败（HTTP ${xhr.status}）`));
            }
        };

        xhr.onerror = () => reject(new Error('网络异常，上传中断'));
        xhr.ontimeout = () => reject(new Error('上传超时'));

        const form = new FormData();
        form.append('file', file);
        xhr.send(form);
    });
}
