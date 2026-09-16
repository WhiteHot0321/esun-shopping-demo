package com.esun.shop.llm;

import com.esun.shop.repository.DocEmbeddingRepository;
import com.esun.shop.repository.FaqRepository;
import com.esun.shop.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * On startup, embeds any product/FAQ row that is missing from {@code doc_embedding} or whose
 * source row changed since it was last embedded, then tells {@link VectorSearchService} to load
 * the refreshed table into memory. Runs after all beans are constructed so it always sees the
 * fully wired repositories and LLM client.
 */
@Component
@ConditionalOnProperty(name = "llm.indexing.enabled", havingValue = "true", matchIfMissing = true)
public class EmbeddingIndexRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingIndexRunner.class);

    private final ProductRepository productRepository;
    private final FaqRepository faqRepository;
    private final DocEmbeddingRepository docEmbeddingRepository;
    private final LlmClient llmClient;
    private final VectorSearchService vectorSearchService;

    public EmbeddingIndexRunner(ProductRepository productRepository,
                                 FaqRepository faqRepository,
                                 DocEmbeddingRepository docEmbeddingRepository,
                                 LlmClient llmClient,
                                 VectorSearchService vectorSearchService) {
        this.productRepository = productRepository;
        this.faqRepository = faqRepository;
        this.docEmbeddingRepository = docEmbeddingRepository;
        this.llmClient = llmClient;
        this.vectorSearchService = vectorSearchService;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<IndexableDoc> docs = new ArrayList<>();
        Map<String, LocalDateTime> productEmbeddedAt;
        Map<String, LocalDateTime> faqEmbeddedAt;
        try {
            docs.addAll(productRepository.findAllForIndexing());
            docs.addAll(faqRepository.findAllForIndexing());
            productEmbeddedAt = docEmbeddingRepository.findUpdatedAtBySourceType("product");
            faqEmbeddedAt = docEmbeddingRepository.findUpdatedAtBySourceType("faq");
        } catch (DataAccessException ex) {
            log.warn("略過 embedding 索引：資料庫目前無法使用", ex);
            return;
        }

        int indexed = 0;
        for (IndexableDoc doc : docs) {
            Map<String, LocalDateTime> embeddedAt = "product".equals(doc.sourceType()) ? productEmbeddedAt : faqEmbeddedAt;
            LocalDateTime existing = embeddedAt.get(doc.sourceId());
            if (existing != null && !existing.isBefore(doc.updatedAt())) {
                continue;
            }
            try {
                float[] embedding = llmClient.embed(doc.content());
                docEmbeddingRepository.upsert(doc.sourceType(), doc.sourceId(), doc.content(), embedding);
                indexed++;
            } catch (LlmException | UnsupportedOperationException ex) {
                log.warn("略過 {}/{} 的 embedding 建立：{}", doc.sourceType(), doc.sourceId(), ex.getMessage());
            }
        }

        log.info("Embedding 索引完成，本次新增/更新 {} 筆", indexed);
        try {
            vectorSearchService.reload();
        } catch (DataAccessException ex) {
            log.warn("embedding 已處理，但無法從資料庫重新載入向量快取", ex);
        }
    }
}
