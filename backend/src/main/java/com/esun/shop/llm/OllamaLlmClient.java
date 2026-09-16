package com.esun.shop.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "ollama")
public class OllamaLlmClient implements LlmClient {
    private final RestClient restClient;
    private final String chatModel;
    private final String embedModel;

    public OllamaLlmClient(
            @Value("${llm.ollama.base-url}") String baseUrl,
            @Value("${llm.ollama.chat-model}") String chatModel,
            @Value("${llm.ollama.embed-model}") String embedModel,
            @Value("${llm.ollama.timeout-seconds:30}") int timeoutSeconds) {
        this.chatModel = chatModel;
        this.embedModel = embedModel;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutSeconds * 1000);
        requestFactory.setReadTimeout(timeoutSeconds * 1000);

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String chat(String systemPrompt, String userPrompt) {
        Map<String, Object> body = Map.of(
                "model", chatModel,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                ),
                "stream", false
        );
        try {
            Map<String, Object> response = restClient.post()
                    .uri("/api/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });
            Map<String, Object> message = (Map<String, Object>) response.get("message");
            return (String) message.get("content");
        } catch (ResourceAccessException ex) {
            if (ex.getCause() instanceof SocketTimeoutException) {
                throw new OllamaTimeoutException("Ollama 連線逾時", ex);
            }
            throw new OllamaUnavailableException("Ollama 服務無法連線", ex);
        } catch (RestClientException ex) {
            throw new OllamaUnavailableException("Ollama 服務發生錯誤", ex);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public float[] embed(String text) {
        Map<String, Object> body = Map.of("model", embedModel, "prompt", text);
        try {
            Map<String, Object> response = restClient.post()
                    .uri("/api/embeddings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });
            List<Number> embedding = (List<Number>) response.get("embedding");
            float[] result = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                result[i] = embedding.get(i).floatValue();
            }
            return result;
        } catch (RestClientException ex) {
            throw new EmbeddingException("Embedding 產生失敗", ex);
        }
    }
}
