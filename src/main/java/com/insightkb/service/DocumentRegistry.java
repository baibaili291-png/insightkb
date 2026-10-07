package com.insightkb.service;

import com.insightkb.model.DocumentMeta;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文档元信息与落盘文件的登记处（M1 内存版）。
 *
 * 拆成独立组件的原因：文档状态会被上传线程（写 PROCESSING）和处理线程
 * （写 READY / FAILED）同时读写，需要有一个共享且线程安全的地方。
 * 同时这也让 @Async 的异步方法不必回调 Service，避免了自调用导致 @Async 失效的坑。
 *
 * M2 会把这里替换成 MySQL 表，接口保持不变。
 */
@Component
public class DocumentRegistry {

    private final Map<String, DocumentMeta> documents = new ConcurrentHashMap<>();
    private final Map<String, Path> files = new ConcurrentHashMap<>();

    public void put(DocumentMeta meta) {
        documents.put(meta.id(), meta);
    }

    public DocumentMeta get(String id) {
        return documents.get(id);
    }

    public List<DocumentMeta> list() {
        return documents.values().stream()
                .sorted(Comparator.comparingLong(DocumentMeta::createdAt).reversed())
                .toList();
    }

    public void remove(String id) {
        documents.remove(id);
        files.remove(id);
    }

    public void bindFile(String id, Path path) {
        files.put(id, path);
    }

    public Path fileOf(String id) {
        return files.get(id);
    }

    public int count() {
        return documents.size();
    }
}
