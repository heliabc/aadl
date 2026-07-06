package com.nuaa.aadl.module.rag.langchain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nuaa.aadl.app.config.AppProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class OllamaClient {

    private final String ollamaUrl;
    private final String embedModel;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OllamaClient(AppProperties appProperties) {
        this.ollamaUrl = appProperties.ollamaUrl();
        this.embedModel = appProperties.embedModel();
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        this.objectMapper = new ObjectMapper();
    }

    public float[] embed(String text) throws Exception {
        String url = ollamaUrl.replace("/api/chat", "/api/embeddings");
        
        Map<String, Object> payload = Map.of(
            "model", embedModel,
            "prompt", text
        );
        
        String requestBody = objectMapper.writeValueAsString(payload);
        
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .timeout(Duration.ofSeconds(180))
            .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            System.out.println("!!! [OllamaClient] Embedding failed: HTTP " + response.statusCode());
            System.out.println("    Response: " + response.body().substring(0, Math.min(200, response.body().length())));
            throw new Exception("Embedding failed: " + response.statusCode());
        }
        
        @SuppressWarnings("unchecked")
        Map<String, Object> result = objectMapper.readValue(
            response.body(),
            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}
        );
        
        @SuppressWarnings("unchecked")
        List<Double> embedding = (List<Double>) result.get("embedding");
        
        float[] vector = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) {
            vector[i] = embedding.get(i).floatValue();
        }
        
        return vector;
    }
}