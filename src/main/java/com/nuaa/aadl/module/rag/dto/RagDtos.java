package com.nuaa.aadl.module.rag.dto;

import java.util.List;
import java.util.Map;

public class RagDtos {

    public record IngestReportRequest(
        String conversationId,
        String fmeaContent,
        String ftaContent
    ) {}

    public record SearchRequest(
        String query,
        Integer limit,
        String source
    ) {}

    public record SearchResponse(
        List<SearchResultItem> results,
        long total
    ) {
        public record SearchResultItem(
            String id,
            String content,
            double score,
            Map<String, Object> metadata
        ) {}
    }

    public record InitResponse(
        boolean success,
        String message,
        long chunksIndexed
    ) {}

    public record IngestResponse(
        boolean success,
        String message,
        String fileId
    ) {}

    public record ListFilesResponse(
        List<String> files,
        long totalChunks
    ) {}
}
