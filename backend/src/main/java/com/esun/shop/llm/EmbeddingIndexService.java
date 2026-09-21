package com.esun.shop.llm;

import com.esun.shop.repository.DocEmbeddingRepository;
import com.esun.shop.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Embeds a single doc and upserts it into {@code doc_embedding}. {@link EmbeddingIndexRunner} uses
 * {@link #indexDoc(IndexableDoc)} for its startup bulk pass; product-mutating services call
 * {@link #indexProduct(String)} right after a write so a new/changed product is searchable by the
 * AI customer service immediately, without waiting for the next application restart.
 */
@Service
public class EmbeddingIndexService {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingIndexService.class);

    private final ProductRepository productRepository;
    private final DocEmbeddingRepository docEmbeddingRepository;
    private final LlmClient llmClient;
    private final VectorSearchService vectorSearchService;

    public EmbeddingIndexService(ProductRepository productRepository,
                                  DocEmbeddingRepository docEmbeddingRepository,
                                  LlmClient llmClient,
                                  VectorSearchService vectorSearchService) {
        this.productRepository = productRepository;
        this.docEmbeddingRepository = docEmbeddingRepository;
        this.llmClient = llmClient;
        this.vectorSearchService = vectorSearchService;
    }

    /**
     * Embeds one doc and upserts it. Returns true on success; on an LLM failure it logs a warning
     * and returns false instead of throwing, so a single bad doc never aborts a bulk indexing pass
     * or a product write.
     */
    public boolean indexDoc(IndexableDoc doc) {
        try {
            float[] embedding = llmClient.embed(doc.content());
            docEmbeddingRepository.upsert(doc.sourceType(), doc.sourceId(), doc.content(), embedding);
            return true;
        } catch (LlmException | UnsupportedOperationException ex) {
            log.warn("略過 {}/{} 的 embedding 建立：{}", doc.sourceType(), doc.sourceId(), ex.getMessage(), ex);
            return false;
        }
    }

    /**
     * Re-indexes a single product by id and refreshes the in-memory vector cache, so it is
     * immediately answerable by the AI customer service. Safe to call after create/update; any
     * failure (LLM unavailable, DB unavailable) is logged and swallowed rather than propagated,
     * since indexing is a best-effort side effect and must not block the product write.
     */
    public void indexProduct(String productId) {
        IndexableDoc doc;
        try {
            doc = productRepository.findByIdForIndexing(productId);
        } catch (DataAccessException ex) {
            log.warn("略過商品 {} 的即時索引：資料庫目前無法使用", productId, ex);
            return;
        }
        if (doc == null) {
            log.warn("略過商品 {} 的即時索引：找不到該商品", productId);
            return;
        }
        if (!indexDoc(doc)) {
            return;
        }
        try {
            vectorSearchService.reload();
        } catch (DataAccessException ex) {
            log.warn("商品 {} 已完成 embedding，但無法重新載入向量快取", productId, ex);
        }
    }

    public void removeProduct(String productId) {
        try {
            docEmbeddingRepository.delete("product", productId);
            vectorSearchService.reload();
        } catch (DataAccessException ex) {
            log.warn("商品 {} 已下架，但無法移除其即時索引", productId, ex);
        }
    }
}
