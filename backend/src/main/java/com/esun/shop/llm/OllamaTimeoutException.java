package com.esun.shop.llm;

public class OllamaTimeoutException extends LlmException {
    public OllamaTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
