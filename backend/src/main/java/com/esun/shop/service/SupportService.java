package com.esun.shop.service;

import com.esun.shop.dto.SourceInfo;
import com.esun.shop.dto.SupportAnswer;
import com.esun.shop.exception.BusinessException;
import com.esun.shop.llm.LlmClient;
import com.esun.shop.llm.LlmException;
import com.esun.shop.llm.SearchResult;
import com.esun.shop.llm.VectorSearchService;
import com.esun.shop.model.Faq;
import com.esun.shop.model.Product;
import com.esun.shop.repository.FaqRepository;
import com.esun.shop.repository.ProductRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class SupportService {
    private static final String SYSTEM_PROMPT_TEMPLATE = """
            你是電商平台的商品客服助理。請僅根據下方提供的參考資料回答問題；
            資料不足以回答時，請直接說不知道，不要編造價格、庫存或其他數字；
            回答中出現的任何數字都必須是參考資料中原本就有的。

            參考資料：
            %s""";

    private final VectorSearchService vectorSearchService;
    private final LlmClient llmClient;
    private final ProductRepository productRepository;
    private final FaqRepository faqRepository;
    private final int topK;

    public SupportService(VectorSearchService vectorSearchService,
                           LlmClient llmClient,
                           ProductRepository productRepository,
                           FaqRepository faqRepository,
                           @Value("${llm.rag.top-k:4}") int topK) {
        this.vectorSearchService = vectorSearchService;
        this.llmClient = llmClient;
        this.productRepository = productRepository;
        this.faqRepository = faqRepository;
        this.topK = topK;
    }

    public SupportAnswer answer(String question) {
        List<SearchResult> results;
        String answer;
        try {
            results = vectorSearchService.search(question, topK);
            String systemPrompt = SYSTEM_PROMPT_TEMPLATE.formatted(buildContext(results));
            answer = llmClient.chat(systemPrompt, question);
        } catch (LlmException ex) {
            throw new BusinessException("AI 客服服務暫時無法使用，請稍後再試", HttpStatus.SERVICE_UNAVAILABLE);
        }

        List<SourceInfo> sources = results.stream()
                .map(this::toSourceInfo)
                .collect(Collectors.toList());
        return new SupportAnswer(escapeHtml(answer), sources);
    }

    private String buildContext(List<SearchResult> results) {
        if (results.isEmpty()) {
            return "（沒有找到相關資料）";
        }
        return results.stream()
                .map(r -> "- " + r.content())
                .collect(Collectors.joining("\n"));
    }

    private SourceInfo toSourceInfo(SearchResult result) {
        String title = switch (result.sourceType()) {
            case "product" -> {
                Product product = productRepository.findById(result.sourceId());
                yield product != null ? product.getProductName() : result.sourceId();
            }
            case "faq" -> {
                Faq faq = faqRepository.findById(Long.valueOf(result.sourceId()));
                yield faq != null ? faq.getQuestion() : result.sourceId();
            }
            default -> result.sourceId();
        };
        return new SourceInfo(result.sourceType(), result.sourceId(), title, result.similarity());
    }

    private String escapeHtml(String input) {
        if (input == null) {
            return null;
        }
        return input.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
