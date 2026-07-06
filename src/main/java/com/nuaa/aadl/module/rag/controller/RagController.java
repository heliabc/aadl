package com.nuaa.aadl.module.rag.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nuaa.aadl.module.rag.dto.RagDtos.*;
import com.nuaa.aadl.module.rag.langchain.LangChain4jRagService;
import com.nuaa.aadl.module.rag.service.KnowledgeBaseService;
import com.nuaa.aadl.module.rag.service.QdrantService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final QdrantService qdrantService;
    private final LangChain4jRagService langChain4jRagService;
    private final ObjectMapper objectMapper;

    public RagController(KnowledgeBaseService knowledgeBaseService, QdrantService qdrantService, LangChain4jRagService langChain4jRagService) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.qdrantService = qdrantService;
        this.langChain4jRagService = langChain4jRagService;
        
        // 配置ObjectMapper使用UTF-8
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(com.fasterxml.jackson.core.JsonGenerator.Feature.ESCAPE_NON_ASCII, false);
    }

    @PostMapping("/init")
    public ResponseEntity<InitResponse> initKnowledgeBase() {
        try {
            System.out.println("=== [RagController] initKnowledgeBase called ===");
            long countBefore = knowledgeBaseService.count();
            System.out.println("    Count before: " + countBefore);
            knowledgeBaseService.initBuiltinKnowledgeBase();
            long count = knowledgeBaseService.count();
            System.out.println("    Count after: " + count);
            return ResponseEntity.ok(new InitResponse(true, "Knowledge base initialized", count));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(new InitResponse(false, "Failed: " + e.getMessage(), 0));
        }
    }

    @PostMapping("/reset")
    public ResponseEntity<InitResponse> resetKnowledgeBase() {
        try {
            System.out.println("=== [RagController] resetKnowledgeBase called ===");
            knowledgeBaseService.resetKnowledgeBase();
            long count = knowledgeBaseService.count();
            return ResponseEntity.ok(new InitResponse(true, "Knowledge base reset and re-initialized", count));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(new InitResponse(false, "Failed: " + e.getMessage(), 0));
        }
    }

    @PostMapping("/ingest")
    public ResponseEntity<IngestResponse> ingestFile(@RequestParam("file") MultipartFile file) {
        try {
            knowledgeBaseService.ingestUserFile(file);
            return ResponseEntity.ok(new IngestResponse(true, "File ingested successfully", "uploaded"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(new IngestResponse(false, "Failed: " + e.getMessage(), null));
        }
    }

    @GetMapping(value = "/search", produces = "application/json; charset=UTF-8")
    public ResponseEntity<String> search(
            @RequestParam(value = "q", defaultValue = "") String q,
            @RequestParam(value = "query", defaultValue = "") String query,
            @RequestParam(value = "limit", defaultValue = "5") Integer limit,
            @RequestParam(value = "scoreThreshold", defaultValue = "0.0") Double scoreThreshold) {
        try {
            String searchText = (!q.isBlank()) ? q : query;
            
            if (searchText == null || searchText.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                    .body("{\"results\":[],\"total\":0}");
            }
            
            searchText = searchText.trim();
            
            List<LangChain4jRagService.SearchResultItem> searchResults = 
                langChain4jRagService.search(searchText, limit, scoreThreshold);
            
            System.out.println("=== [RAG API] GET /search: query=" + searchText + ", results=" + searchResults.size() + " ===");
            
            // 手动构建JSON响应
            StringBuilder json = new StringBuilder();
            json.append("{\"results\":[");
            for (int i = 0; i < searchResults.size(); i++) {
                var r = searchResults.get(i);
                if (i > 0) json.append(",");
                json.append("{\"id\":\"").append(escapeJson(r.fileName() + "-" + r.pageNumber())).append("\",");
                json.append("\"content\":\"").append(escapeJson(r.content())).append("\",");
                json.append("\"score\":").append(r.score()).append(",");
                json.append("\"metadata\":{}}");
            }
            json.append("],\"total\":").append(searchResults.size()).append("}");
            
            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(json.toString());
        } catch (Exception e) {
            System.err.println("!!! [RagController] GET /search error: " + e.getMessage());
            return ResponseEntity.internalServerError()
                .body("{\"results\":[],\"total\":0}");
        }
    }
    
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
               .replace("\"", "\\\"")
               .replace("\n", "\\n")
               .replace("\r", "\\r")
               .replace("\t", "\\t");
    }

        @GetMapping(value = "/search/hybrid", produces = "application/json; charset=UTF-8")
    public ResponseEntity<String> searchHybrid(
            @RequestParam(value = "q", defaultValue = "") String q,
            @RequestParam(value = "query", defaultValue = "") String query,
            @RequestParam(value = "limit", defaultValue = "5") Integer limit,
            @RequestParam(value = "scoreThreshold", defaultValue = "0.0") Double scoreThreshold) {
        try {
            String searchText = (!q.isBlank()) ? q : query;
            
            if (searchText == null || searchText.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                    .body("{\"results\":[],\"total\":0}");
            }
            
            searchText = searchText.trim();
            
            List<LangChain4jRagService.SearchResultItem> searchResults = 
                langChain4jRagService.searchHybrid(searchText, limit, scoreThreshold);
            
            System.out.println("=== [RAG API] GET /search/hybrid: query=" + searchText + ", results=" + searchResults.size() + " ===");
            
            StringBuilder json = new StringBuilder();
            json.append("{\"results\":[");
            for (int i = 0; i < searchResults.size(); i++) {
                var r = searchResults.get(i);
                if (i > 0) json.append(",");
                json.append("{\"id\":\"").append(escapeJson(r.fileName() + "-" + r.pageNumber())).append("\",");
                json.append("\"content\":\"").append(escapeJson(r.content())).append("\",");
                json.append("\"score\":").append(r.score()).append(",");
                json.append("\"metadata\":{}}");
            }
            json.append("],\"total\":").append(searchResults.size()).append("}");
            
            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(json.toString());
        } catch (Exception e) {
            System.err.println("!!! [RagController] GET /search/hybrid error: " + e.getMessage());
            return ResponseEntity.internalServerError()
                .body("{\"results\":[],\"total\":0}");
        }
    }

    @PostMapping("/search")
    public ResponseEntity<SearchResponse> searchPost(@RequestBody SearchRequest request) {
        try {
            String searchText = request.query();
            if (searchText == null || searchText.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(new SearchResponse(List.of(), 0));
            }
            
            int limit = request.limit() != null ? request.limit() : 5;
            
            List<LangChain4jRagService.SearchResultItem> searchResults = 
                langChain4jRagService.search(searchText, limit);
            
            System.out.println("=== [RAG API] POST /search: query=" + searchText + ", results=" + searchResults.size() + " ===");
            
            List<SearchResponse.SearchResultItem> results = searchResults.stream()
                .map(r -> new SearchResponse.SearchResultItem(
                    r.fileName() + "-" + r.pageNumber(),
                    r.content(),
                    r.score(),
                    r.metadata()
                ))
                .toList();
            
            return ResponseEntity.ok(new SearchResponse(results, results.size()));
        } catch (Exception e) {
            System.err.println("!!! [RagController] POST /search error: " + e.getMessage());
            return ResponseEntity.internalServerError()
                .body(new SearchResponse(List.of(), 0));
        }
    }

    @GetMapping("/list")
    public ResponseEntity<ListFilesResponse> listFiles() {
        try {
            List<String> files = knowledgeBaseService.listIndexedFiles();
            long count = knowledgeBaseService.count();
            return ResponseEntity.ok(new ListFilesResponse(files, count));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(new ListFilesResponse(List.of(), 0));
        }
    }

    @DeleteMapping("/file/{fileId}")
    public ResponseEntity<IngestResponse> deleteFile(@PathVariable String fileId) {
        try {
            knowledgeBaseService.deleteUserFile(fileId);
            return ResponseEntity.ok(new IngestResponse(true, "File deleted from knowledge base", fileId));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(new IngestResponse(false, "Failed: " + e.getMessage(), fileId));
        }
    }

    @GetMapping("/count")
    public ResponseEntity<Long> count() {
        try {
            return ResponseEntity.ok(knowledgeBaseService.count());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(0L);
        }
    }
}