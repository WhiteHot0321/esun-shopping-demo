package com.esun.shop.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class ReviewRequest {
    @NotNull(message = "請選擇星等")
    @Min(value = 1, message = "星等必須介於 1 到 5")
    @Max(value = 5, message = "星等必須介於 1 到 5")
    private Integer rating;

    @NotBlank(message = "請輸入評論內容")
    @Size(max = 1000, message = "評論內容最多 1000 字")
    private String content;

    public Integer getRating() { return rating; }
    public void setRating(Integer rating) { this.rating = rating; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}
