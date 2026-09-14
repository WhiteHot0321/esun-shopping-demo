package com.esun.shop.llm;

public abstract class LlmException extends RuntimeException {
    protected LlmException(String message) {
        super(message);
    }

    protected LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
