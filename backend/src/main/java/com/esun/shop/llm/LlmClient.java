package com.esun.shop.llm;

public interface LlmClient {
    String chat(String systemPrompt, String userPrompt);

    float[] embed(String text);
}
