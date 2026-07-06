package com.nuaa.aadl.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
    String dataDir,
    String uploadDir,
    String knowledgeBaseDir,
    String userKnowledgeDir,
    String llmProvider,
    String ollamaUrl,
    String ollamaModel,
    String embedModel,
    String openaiUrl,
    String openaiModel,
    String openaiApiKey,
    boolean langchainEnabled,
    QdrantProperties qdrant
) {
    public record QdrantProperties(
        String host,
        int port,
        String apiKey,
        String collection
    ) {}
}
