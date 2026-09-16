package com.esun.shop.controller;

import com.esun.shop.dto.ApiResponse;
import com.esun.shop.dto.AskQuestionRequest;
import com.esun.shop.dto.SupportAnswer;
import com.esun.shop.service.SupportService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/support")
public class SupportController {
    private final SupportService supportService;

    public SupportController(SupportService supportService) {
        this.supportService = supportService;
    }

    @PostMapping("/ask")
    public ApiResponse<SupportAnswer> ask(@Valid @RequestBody AskQuestionRequest request) {
        return ApiResponse.ok(supportService.answer(request.getQuestion().trim()));
    }
}
