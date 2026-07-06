package com.nuaa.aadl.module.rag.langchain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nuaa.aadl.app.config.AppProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.output.Response;
import okhttp3.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

// Disabled - using custom OllamaClient instead
// @Configuration
// @ConditionalOnProperty(name = "app.langchain-enabled", havingValue = "true")
public class LangChain4jBeans {

    @Bean
    public ChatModel chatLanguageModel(AppProperties appProperties) {
        String baseUrl = appProperties.ollamaUrl().replace("/api/chat", "");
        System.out.println("=== [LangChain4jBeans] Creating ChatLanguageModel: baseUrl=" + baseUrl + ", model=" + appProperties.ollamaModel());
        
        return OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(appProperties.ollamaModel())
                .temperature(0.7)
                .timeout(Duration.ofSeconds(120))
                .logRequests(true)
                .logResponses(true)
                .build();
    }

    @Bean
    public EmbeddingModel embeddingModel(AppProperties appProperties) {
        String baseUrl = appProperties.ollamaUrl().replace("/api/chat", "");
        System.out.println("=== [LangChain4jBeans] Creating EmbeddingModel: baseUrl=" + baseUrl + ", model=" + appProperties.embedModel());
        
        return new DirectOllamaEmbeddingModel(
            baseUrl + "/api/embeddings",
            appProperties.embedModel(),
            new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .build()
        );
    }
    
    static class DirectOllamaEmbeddingModel implements EmbeddingModel {
        private final String url;
        private final String model;
        private final OkHttpClient httpClient;
        private final ObjectMapper mapper = new ObjectMapper();
        
        DirectOllamaEmbeddingModel(String url, String model, OkHttpClient httpClient) {
            this.url = url;
            this.model = model;
            this.httpClient = httpClient;
        }
        
        @Override
        public Response<Embedding> embed(String text) {
            try {
                Map<String, Object> payload = Map.of("model", model, "prompt", text);
                RequestBody body = RequestBody.create(
                    mapper.writeValueAsString(payload),
                    MediaType.get("application/json")
                );
                
                Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .build();
                
                okhttp3.Response httpResponse = httpClient.newCall(request).execute();
                if (!httpResponse.isSuccessful()) {
                    throw new IOException("Failed: " + httpResponse);
                }
                
                Map<String, Object> result = mapper.readValue(
                    httpResponse.body().string(),
                    new TypeReference<>() {}
                );
                
                List<Double> emb = (List<Double>) result.get("embedding");
                float[] vector = new float[emb.size()];
                for (int i = 0; i < emb.size(); i++) {
                    vector[i] = emb.get(i).floatValue();
                }
                
                return Response.from(Embedding.from(vector));
            } catch (Exception e) {
                throw new RuntimeException("Embedding failed", e);
            }
        }
        
        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            List<Embedding> embeddings = new ArrayList<>();
            for (TextSegment segment : segments) {
                embeddings.add(embed(segment.text()).content());
            }
            return Response.from(embeddings);
        }
    }
}
