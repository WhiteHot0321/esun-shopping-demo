package com.esun.shop.service;

public class ConcurrentOrderException extends RuntimeException {
    private final String requestId;

    public ConcurrentOrderException(String requestId, Throwable cause) {
        super("System busy, please try again later", cause);
        this.requestId = requestId;
    }

    public String getRequestId() {
        return requestId;
    }
}
