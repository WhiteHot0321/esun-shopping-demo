package com.esun.shop.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.esun.shop.dto.ApiResponse;
import com.esun.shop.dto.AskQuestionRequest;
import com.esun.shop.dto.SupportAnswer;
import com.esun.shop.service.SupportService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "客服 Support", description = "商品問答（RAG）")
@RestController
@RequestMapping("/api/support")
public class SupportController {
    private final SupportService supportService;

    public SupportController(SupportService supportService) {
        this.supportService = supportService;
    }

    @Operation(summary = "商品問題詢問（公開）")
    @PostMapping("/ask")
    public ApiResponse<SupportAnswer> ask(@Valid @RequestBody AskQuestionRequest request) {
        return ApiResponse.ok(supportService.answer(request.getQuestion().trim()));
    }
}
