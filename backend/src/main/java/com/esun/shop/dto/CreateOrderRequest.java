package com.esun.shop.dto;

import com.esun.shop.model.PayStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;

import java.util.List;

public class CreateOrderRequest {
    @NotBlank
    @Pattern(regexp = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private String requestId;

    private String memberId;

    @Valid
    @NotEmpty
    private List<OrderItemRequest> items;

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getMemberId() {
        return memberId;
    }

    public void setMemberId(String memberId) {
        this.memberId = memberId;
    }

    /** Kept only for Java fixture compatibility; this is not a JSON input. */
    @Deprecated(forRemoval = true)
    @JsonIgnore
    public void setPayStatus(PayStatus ignored) {
        // Payment state is server-owned; new orders are always PENDING.
    }

    public List<OrderItemRequest> getItems() {
        return items;
    }

    public void setItems(List<OrderItemRequest> items) {
        this.items = items;
    }
}
