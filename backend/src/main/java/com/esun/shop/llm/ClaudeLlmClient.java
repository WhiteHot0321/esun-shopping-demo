package com.esun.shop.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Framework-only stub for this phase. To wire up for real: call
 * https://api.anthropic.com/v1/messages with the {@code apiKey} below as the
 * {@code x-api-key} header, model {@code claude-sonnet-5} (or
 * {@code claude-haiku-4-5-20251001} for a cheaper/faster option). See phase-2-1 docs.
 */
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "claude")
public class ClaudeLlmClient implements LlmClient {
    private final String apiKey;

    public ClaudeLlmClient(@Value("${llm.claude.api-key:}") String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        throw new UnsupportedOperationException(
                "Claude provider not yet implemented. See phase-2-1 docs.");
    }

    @Override
    public float[] embed(String text) {
        throw new UnsupportedOperationException("Claude embeddings not yet implemented");
    }
}
