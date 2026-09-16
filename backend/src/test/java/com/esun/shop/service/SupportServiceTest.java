package com.esun.shop.service;

import com.esun.shop.dto.SupportAnswer;
import com.esun.shop.exception.BusinessException;
import com.esun.shop.llm.LlmClient;
import com.esun.shop.llm.OllamaUnavailableException;
import com.esun.shop.llm.SearchResult;
import com.esun.shop.llm.VectorSearchService;
import com.esun.shop.model.Product;
import com.esun.shop.repository.FaqRepository;
import com.esun.shop.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SupportService}. VectorSearchService/LlmClient/repositories are mocked
 * so these exercise only the service's own logic (context assembly, source lookup, and mapping
 * a failed Ollama call to a 503 BusinessException), not the real RAG pipeline.
 */
@ExtendWith(MockitoExtension.class)
class SupportServiceTest {

    @Mock
    private VectorSearchService vectorSearchService;

    @Mock
    private LlmClient llmClient;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private FaqRepository faqRepository;

    private SupportService supportService;

    @BeforeEach
    void setUp() {
        supportService = new SupportService(vectorSearchService, llmClient, productRepository, faqRepository, 4);
    }

    @Test
    void answer_returnsLlmAnswerWithMatchedSources() {
        SearchResult productMatch = new SearchResult("product", "P001", "防水登山鞋", 0.91);
        when(vectorSearchService.search("有防水的商品嗎？", 4)).thenReturn(List.of(productMatch));
        Product product = new Product();
        product.setProductName("防水登山鞋");
        when(productRepository.findById("P001")).thenReturn(product);
        when(llmClient.chat(anyString(), anyString())).thenReturn("有的，防水登山鞋現在有現貨。");

        SupportAnswer result = supportService.answer("有防水的商品嗎？");

        assertThat(result.getAnswer()).isEqualTo("有的，防水登山鞋現在有現貨。");
        assertThat(result.getSources()).hasSize(1);
        assertThat(result.getSources().get(0).getTitle()).isEqualTo("防水登山鞋");
        assertThat(result.getSources().get(0).getSimilarity()).isEqualTo(0.91);
    }

    @Test
    void answer_doesNotHtmlEscapeTheLlmAnswer() {
        when(vectorSearchService.search(anyString(), anyInt())).thenReturn(List.of());
        when(llmClient.chat(anyString(), anyString())).thenReturn("H&M <script> 品牌 \"優惠\"");

        SupportAnswer result = supportService.answer("有 H&M 嗎？");

        // SupportChat.vue renders this through {{ }} text interpolation, which already
        // escapes on output; escaping here too would double-encode & and " for the user.
        assertThat(result.getAnswer()).isEqualTo("H&M <script> 品牌 \"優惠\"");
    }

    @Test
    void answer_whenOllamaUnavailable_throws503BusinessException() {
        when(vectorSearchService.search(anyString(), anyInt())).thenReturn(List.of());
        when(llmClient.chat(anyString(), anyString()))
                .thenThrow(new OllamaUnavailableException("Ollama 服務無法連線", new RuntimeException("connection refused")));

        assertThatThrownBy(() -> supportService.answer("問題"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
