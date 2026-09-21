package com.esun.shop.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public class BulkProductRequest {
    public enum Action { DELETE, RESTOCK }

    @NotEmpty
    @Size(max = 100)
    private List<String> productIds;
    private Action action;
    private Integer amount;

    public List<String> getProductIds() { return productIds; }
    public void setProductIds(List<String> productIds) { this.productIds = productIds; }
    public Action getAction() { return action; }
    public void setAction(Action action) { this.action = action; }
    public Integer getAmount() { return amount; }
    public void setAmount(Integer amount) { this.amount = amount; }
}
