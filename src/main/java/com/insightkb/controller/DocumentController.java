package com.insightkb.controller;

import com.insightkb.common.ApiResponse;
import com.insightkb.model.Chunk;
import com.insightkb.model.DocumentMeta;
import com.insightkb.service.DocumentService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    /** 上传文档。接口只做落盘与登记，处理在后台线程池中进行，所以响应很快。 */
    @PostMapping
    public ApiResponse<DocumentMeta> upload(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(documentService.upload(file));
    }

    @GetMapping
    public ApiResponse<List<DocumentMeta>> list() {
        return ApiResponse.ok(documentService.list());
    }

    /** 前端用它轮询处理进度（状态从 PROCESSING 变为 READY / FAILED） */
    @GetMapping("/{id}")
    public ApiResponse<DocumentMeta> get(@PathVariable String id) {
        return ApiResponse.ok(documentService.get(id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        documentService.delete(id);
        return ApiResponse.ok();
    }

    /** 换掉 Embedding 模型后重建向量 */
    @PostMapping("/{id}/reindex")
    public ApiResponse<DocumentMeta> reindex(@PathVariable String id) {
        return ApiResponse.ok(documentService.reindex(id));
    }

    /** 查看某个文档的切块明细，用于人工评估"切块粒度是否合理" */
    @GetMapping("/{id}/chunks")
    public ApiResponse<List<Chunk>> chunks(@PathVariable String id) {
        return ApiResponse.ok(documentService.chunksOf(id));
    }
}
