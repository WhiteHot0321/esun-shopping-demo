package com.esun.shop.llm;

import java.time.LocalDateTime;

/**
 * A product or FAQ row as seen by {@link EmbeddingIndexRunner}, before it becomes a
 * {@link com.esun.shop.model.DocEmbedding} row.
 */
public record IndexableDoc(String sourceType, String sourceId, String content, LocalDateTime updatedAt) {
}
