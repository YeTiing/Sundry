package com.sundry.feed;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商品向量重建。同步执行，782 个商品大约几十秒。
 */
@RestController
@RequestMapping("/api/admin/feed/embeddings")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
public class AdminFeedEmbeddingController {

    private final ProductEmbeddingService embeddingService;
    private final MilvusProperties milvusProperties;

    public AdminFeedEmbeddingController(ProductEmbeddingService embeddingService,
                                        MilvusProperties milvusProperties) {
        this.embeddingService = embeddingService;
        this.milvusProperties = milvusProperties;
    }

    @PostMapping("/rebuild")
    public ProductEmbeddingService.RebuildResult rebuild() {
        return embeddingService.rebuild();
    }

    @GetMapping("/status")
    public StatusView status() {
        return new StatusView(
                embeddingService.count(),
                milvusProperties.isEnabled(),
                milvusProperties.getCollection(),
                milvusProperties.getDim());
    }

    public record StatusView(long mysqlCount, boolean milvusEnabled,
                             String collection, int dim) {
    }
}
