package com.esun.shop.llm;

import com.esun.shop.repository.DocEmbeddingRepository;
import com.esun.shop.repository.FaqRepository;
import com.esun.shop.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
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
        docs.addAll(productRepository.findAllForIndexing());
        docs.addAll(faqRepository.findAllForIndexing());

        Map<String, LocalDateTime> productEmbeddedAt = docEmbeddingRepository.findUpdatedAtBySourceType("product");
        Map<String, LocalDateTime> faqEmbeddedAt = docEmbeddingRepository.findUpdatedAtBySourceType("faq");

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
            } catch (LlmException ex) {
                log.warn("略過 {}/{} 的 embedding 建立：{}", doc.sourceType(), doc.sourceId(), ex.getMessage());
            }
        }

        log.info("Embedding 索引完成，本次新增/更新 {} 筆", indexed);
        vectorSearchService.reload();
    }
}
