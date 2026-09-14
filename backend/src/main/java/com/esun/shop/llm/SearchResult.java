package com.esun.shop.llm;

public record SearchResult(String sourceType, String sourceId, String content, double similarity) {
}
