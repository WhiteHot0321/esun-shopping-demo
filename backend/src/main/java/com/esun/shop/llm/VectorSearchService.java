package com.esun.shop.llm;

import com.esun.shop.repository.DocEmbeddingRepository;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Keeps every {@code doc_embedding} row in memory and answers nearest-neighbour queries with
 * cosine similarity. Reloaded once by {@link EmbeddingIndexRunner} right after it finishes
 * (re)building the embeddings, so the cache always reflects what was just indexed.
 */
@Service
public class VectorSearchService {
    private final DocEmbeddingRepository docEmbeddingRepository;
    private final LlmClient llmClient;
    private final AtomicReference<List<CachedDoc>> cache = new AtomicReference<>(List.of());

    public VectorSearchService(DocEmbeddingRepository docEmbeddingRepository, LlmClient llmClient) {
        this.docEmbeddingRepository = docEmbeddingRepository;
        this.llmClient = llmClient;
    }

    public void reload() {
        List<CachedDoc> docs = docEmbeddingRepository.findAll().stream()
                .map(doc -> new CachedDoc(doc.getSourceType(), doc.getSourceId(), doc.getContent(), doc.getEmbedding()))
                .collect(Collectors.toList());
        cache.set(docs);
    }

    public List<SearchResult> search(String query, int topK) {
        float[] queryEmbedding = llmClient.embed(query);
        return cache.get().stream()
                .map(doc -> new SearchResult(doc.sourceType, doc.sourceId, doc.content,
                        cosineSimilarity(queryEmbedding, doc.embedding)))
                .sorted(Comparator.comparingDouble(SearchResult::similarity).reversed())
                .limit(topK)
                .collect(Collectors.toList());
    }

    private double cosineSimilarity(float[] a, float[] b) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        int length = Math.min(a.length, b.length);
        for (int i = 0; i < length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private record CachedDoc(String sourceType, String sourceId, String content, float[] embedding) {
    }
}
