package com.nuaa.aadl.module.rag.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nuaa.aadl.app.config.AppProperties;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class QdrantService {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String collectionName;
    private final String qdrantUrl;

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    public QdrantService(AppProperties appProperties) {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
        this.objectMapper.configure(com.fasterxml.jackson.core.JsonGenerator.Feature.ESCAPE_NON_ASCII, false);
        this.collectionName = appProperties.qdrant().collection();
        
        String host = appProperties.qdrant().host();
        int port = appProperties.qdrant().port();
        this.qdrantUrl = "http://" + host + ":" + port;
        
this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
        
        // 确保使用 UTF-8 编码
        System.setProperty("file.encoding", "UTF-8");
        System.setProperty("sun.stdout.encoding", "UTF-8");
        System.setProperty("sun.stderr.encoding", "UTF-8");
    }
    
private String readResponseBody(Response response) throws IOException {
        // 显式使用 UTF-8 读取响应体
        ResponseBody body = response.body();
        if (body == null) return "";
        
        byte[] rawBytes = body.bytes();
        System.out.println("=== [QdrantService] Raw response bytes length: " + rawBytes.length + " ===");
        
        // 检查是否包含中文字符的字节
        String preview = new String(rawBytes, 0, Math.min(300, rawBytes.length), java.nio.charset.StandardCharsets.UTF_8);
        System.out.println("=== [QdrantService] Response preview: " + preview.replace("\n", "\\n") + " ===");
        
        return new String(rawBytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    public void createCollectionIfNotExists() throws Exception {
        createCollectionIfNotExists(4096);
    }

    public void createCollectionIfNotExists(int vectorSize) throws Exception {
        try {
            getCollection();
            System.out.println("=== [QdrantService] Collection already exists ===");
            return;
        } catch (Exception e) {
            System.out.println("=== [QdrantService] Collection does not exist, creating... ===");
        }
        
        if (vectorSize <= 0) vectorSize = 4096;
        System.out.println("=== [QdrantService] Creating collection with size " + vectorSize + " ===");
        
        Map<String, Object> request = new HashMap<>();
        
        Map<String, Object> vectorsConfig = new HashMap<>();
        vectorsConfig.put("size", vectorSize);
        vectorsConfig.put("distance", "Cosine");
        request.put("vectors", vectorsConfig);
        
        put("/collections/" + collectionName, request);
        System.out.println("=== [QdrantService] Collection created successfully ===");
        createFullTextIndex();
    }

    /**
     * Create full-text index on content field for keyword search.
     */
    private void createFullTextIndex() throws IOException {
        Map<String, Object> request = new HashMap<>();
        request.put("field_name", "content");
        request.put("field_schema", "text");
        
        System.out.println("=== [QdrantService] Creating full-text index on content field ===");
        try {
            put("/collections/" + collectionName + "/index", request);
            System.out.println("=== [QdrantService] Full-text index created ===");
        } catch (IOException e) {
            System.out.println("!!! [QdrantService] Full-text index may already exist: " + e.getMessage());
        }
    }

    public void deleteCollection() throws IOException {
        Request request = new Request.Builder()
                .url(qdrantUrl + "/collections/" + collectionName)
                .delete()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Delete collection failed: " + response.code());
            }
            System.out.println("=== [QdrantService] Collection deleted: " + collectionName + " ===");
        }
    }

    public void recreateCollection() throws Exception {
        try {
            deleteCollection();
        } catch (Exception e) {
            System.out.println("=== [QdrantService] Collection didn't exist, skipping delete ===");
        }
        createCollectionIfNotExists(0);
    }

    private Map<String, Object> getCollection() throws IOException {
        Request request = new Request.Builder()
                .url(qdrantUrl + "/collections/" + collectionName)
                .get()
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Get collection failed: " + response.code());
            }
            return objectMapper.readValue(readResponseBody(response), new TypeReference<>() {});
        }
    }

    public void upsert(String id, String content, float[] vector, Map<String, Object> metadata) throws Exception {
        Map<String, Object> payload = new HashMap<>(metadata);
        payload.put("content", content);
        payload.put("createTime", Instant.now().toString());
        
        upsertBatch(List.of(new ChunkItem(id, content, 0, 0, "", "", id, "", "")), List.of(vector));
    }

public void upsertBatch(List<ChunkItem> chunks, List<float[]> vectors) throws Exception {
        if (chunks.isEmpty()) return;
        
        int batchSize = 1;
        for (int i = 0; i < chunks.size(); i += batchSize) {
            int end = Math.min(i + batchSize, chunks.size());
            List<ChunkItem> batchChunks = chunks.subList(i, end);
            List<float[]> batchVectors = vectors.subList(i, end);
            upsertBatchInternal(batchChunks, batchVectors);
        }
    }

    private void upsertBatchInternal(List<ChunkItem> chunks, List<float[]> vectors) throws Exception {
        System.out.println("=== [QdrantService] upsertBatchInternal: " + chunks.size() + " chunks ===");
        System.out.println("    First chunk ID: " + chunks.get(0).id() + " (type: " + chunks.get(0).id().getClass().getSimpleName() + ")");
        for (int i = 0; i < Math.min(3, chunks.size()); i++) {
            float[] v = vectors.get(i);
            System.out.println("    Vector[" + i + "] dim=" + v.length + ", first3=" + v[0] + "," + v[1] + "," + v[2]);
        }
        
        List<Map<String, Object>> points = new ArrayList<>();
        
for (int i = 0; i < chunks.size(); i++) {
            ChunkItem chunk = chunks.get(i);
            float[] vector = vectors.get(i);
            
            Map<String, Object> point = new HashMap<>();
            // Convert to positive integer (hashCode can be negative)
            int intId = chunk.id().hashCode() & 0x7FFFFFFF;
            point.put("id", intId);
            point.put("vector", floatToDoubleList(vector));
            
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("content", chunk.content());
            payload.put("chunkIndex", chunk.chunkIndex());
            payload.put("pageNumber", chunk.pageNumber());
            payload.put("source", chunk.source());
            payload.put("fileName", chunk.fileName());
            payload.put("fileId", chunk.fileId());
            payload.put("docType", chunk.docType());
            payload.put("sectionTitle", chunk.sectionTitle() != null ? chunk.sectionTitle() : "");
            payload.put("createTime", Instant.now().toString());
            point.put("payload", payload);
            
points.add(point);
        }
        
        Map<String, Object> request = new HashMap<>();
        request.put("points", points);
        
        put("/collections/" + collectionName + "/points", request);
    }

public List<SearchResult> search(String query, int limit) throws Exception {
        System.out.println("!!! [QdrantService] search(String) is deprecated, use searchByVector() instead ===");
        return new ArrayList<>();
    }

    public List<SearchResult> search(String query, int limit, String sourceFilter) throws Exception {
        System.out.println("!!! [QdrantService] search() with filter is deprecated ===");
        return new ArrayList<>();
    }

    public List<SearchResult> searchByVector(float[] queryVector, int limit) throws Exception {
        return searchByVector(queryVector, limit, 0.0, null);
    }

    public List<SearchResult> searchByVector(float[] queryVector, int limit, double scoreThreshold) throws Exception {
        return searchByVector(queryVector, limit, scoreThreshold, null);
    }

private List<SearchResult> searchByVector(float[] queryVector, int limit, double scoreThreshold, Map<String, Object> filter) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("vector", floatToDoubleList(queryVector));
        if (scoreThreshold > 0) {
            request.put("score_threshold", scoreThreshold);
        }
        request.put("limit", limit);
        request.put("with_payload", true);  // 关键：获取payload内容
        if (filter != null) {
            request.put("filter", filter);
        }
        
        String body = post("/collections/" + collectionName + "/points/search", request);
        
        // 直接打印响应，不做过多解析，这样可以看到原始结构
        String debugBody = body;
        if (debugBody.length() > 500) {
            debugBody = debugBody.substring(0, 500);
        }
        System.out.println("=== [QdrantService] Raw search response: " + debugBody + " ===");
        
        Map<String, Object> response = objectMapper.readValue(body, new TypeReference<>() {});
        
        // 检查响应结构
        System.out.println("=== [QdrantService] Response keys: " + response.keySet() + " ===");
        
        // 直接打印result中的content
        Object resultObj = response.get("result");
        System.out.println("=== [QdrantService] Result type: " + (resultObj != null ? resultObj.getClass().getSimpleName() : "null") + " ===");
        
        if (resultObj instanceof List) {
            List<Map<String, Object>> resultPoints = (List<Map<String, Object>>) resultObj;
            if (!resultPoints.isEmpty()) {
                Map<String, Object> first = resultPoints.get(0);
                Object payload = first.get("payload");
                if (payload instanceof Map) {
                    Object content = ((Map<String, Object>) payload).get("content");
                    System.out.println("=== [QdrantService] First result content (raw): " + content + " ===");
                    System.out.println("=== [QdrantService] First result content class: " + (content != null ? content.getClass() : "null") + " ===");
                }
            }
        }
        
        List<Map<String, Object>> resultPoints = null;
        
        if (resultObj instanceof List) {
            // 旧格式：result是数组
            resultPoints = (List<Map<String, Object>>) resultObj;
        } else if (resultObj instanceof Map) {
            // 新格式：result是对象，包含points数组
            resultPoints = (List<Map<String, Object>>) ((Map<String, Object>) resultObj).get("points");
        }
        
        System.out.println("=== [QdrantService] Search results: " + (resultPoints != null ? resultPoints.size() : 0) + " ===");
        
        List<SearchResult> results = new ArrayList<>();
        if (resultPoints != null) {
            for (Map<String, Object> p : resultPoints) {
                String id = String.valueOf(p.get("id"));
                Double score = p.get("score") != null ? ((Number) p.get("score")).doubleValue() : 0.0;
                Map<String, Object> payload = (Map<String, Object>) p.get("payload");
                
                // 直接获取content，不使用String.valueOf()
                String content = "";
                if (payload != null) {
                    Object contentObj = payload.get("content");
                    if (contentObj != null) {
                        System.out.println("=== [QdrantService] Content type: " + contentObj.getClass() + ", value: " + contentObj.toString().substring(0, Math.min(50, contentObj.toString().length())) + " ===");
                        content = contentObj.toString();
                    }
                }
                
                results.add(new SearchResult(id, content, score, payload != null ? payload : new HashMap<>()));
            }
        }
        
        for (SearchResult r : results) {
            String preview = r.content() != null && r.content().length() > 50 ? r.content().substring(0, 50) + "..." : r.content();
            System.out.println("  -> [" + r.id() + "] score: " + r.score() + ", content: " + preview);
        }
        
        return results;
    }

public void deleteByFileId(String fileId) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("points", List.of(fileId));
        
        delete("/collections/" + collectionName + "/points", request);
    }
    
    public void deleteByFileName(String fileName) throws Exception {
        // Qdrant 1.17: 使用 filter 根据 fileName 删除
        Map<String, Object> filter = new HashMap<>();
        Map<String, Object> must = new HashMap<>();
        Map<String, Object> match = new HashMap<>();
        match.put("value", fileName);
        must.put("key", "fileName");
        must.put("match", match);
        filter.put("must", List.of(must));
        
        Map<String, Object> request = new HashMap<>();
        request.put("filter", filter);
        
        System.out.println("=== [QdrantService] Deleting points with fileName: " + fileName + " ===");
        delete("/collections/" + collectionName + "/points", request);
    }

public long count() throws Exception {
        try {
            // Qdrant 1.17: count endpoint
            Map<String, Object> request = new HashMap<>();
            String response = post("/collections/" + collectionName + "/points/count", request);
            Map<String, Object> result = objectMapper.readValue(response, new TypeReference<>() {});
            Map<String, Object> resultObj = (Map<String, Object>) result.get("result");
            long count = ((Number) resultObj.get("count")).longValue();  // 修复: use "count" not "points_count"
            System.out.println("=== [QdrantService] Count: " + count + " ===");
            return count;
        } catch (Exception e) {
            System.out.println("!!! [QdrantService] Count failed: " + e.getMessage() + " (RAG unavailable)");
            return 0;
        }
    }

    /**
     * Full-text keyword search using Qdrant text index.
     * Uses scroll API with match filter for BM25-like retrieval.
     */
    public List<SearchResult> searchByKeyword(String query, int limit) throws Exception {
        System.out.println("=== [QdrantService] Keyword search: " + query + " ===");
        
        Map<String, Object> filter = new HashMap<>();
        Map<String, Object> must = new HashMap<>();
        must.put("key", "content");
        Map<String, Object> match = new HashMap<>();
        match.put("text", query);
        must.put("match", match);
        filter.put("must", java.util.List.of(must));
        
        Map<String, Object> request = new HashMap<>();
        request.put("filter", filter);
        request.put("limit", limit);
        request.put("with_payload", true);
        request.put("with_vector", false);
        
        String body = post("/collections/" + collectionName + "/points/scroll", request);
        
        com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(body);
        com.fasterxml.jackson.databind.JsonNode pointsNode = root.get("result").get("points");
        
        List<SearchResult> results = new ArrayList<>();
        if (pointsNode != null) {
            for (com.fasterxml.jackson.databind.JsonNode point : pointsNode) {
                com.fasterxml.jackson.databind.JsonNode payload = point.get("payload");
                String content = payload.has("content") ? payload.get("content").asText() : "";
                Map<String, Object> metadata = new HashMap<>();
                if (payload.has("fileName")) metadata.put("fileName", payload.get("fileName").asText());
                if (payload.has("pageNumber")) metadata.put("pageNumber", payload.get("pageNumber").asInt());
                if (payload.has("sectionTitle")) metadata.put("sectionTitle", payload.get("sectionTitle").asText());
                if (payload.has("source")) metadata.put("source", payload.get("source").asText());
                // Keyword results get a default score of 0.5 since no vector similarity
                results.add(new SearchResult("kw-" + results.size(), content, 0.5, metadata));
            }
        }
        
        System.out.println("=== [QdrantService] Keyword search returned " + results.size() + " results ===");
        return results;
    }

    public List<String> listIndexedFiles() throws Exception {
        System.out.println("=== [QdrantService] listIndexedFiles called ===");
        
        // 使用scroll API获取所有数据
        Map<String, Object> request = new HashMap<>();
        request.put("limit", 10000);
        request.put("with_payload", true);
        
        String response = post("/collections/" + collectionName + "/points/scroll", request);
        
        // 解析返回结果 - result包含points数组
        com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(response);
        com.fasterxml.jackson.databind.JsonNode resultNode = root.get("result");
        
        if (resultNode == null) {
            System.out.println("!!! [QdrantService] No result in response");
            return new ArrayList<>();
        }
        
        // result.points 是数组
        com.fasterxml.jackson.databind.JsonNode pointsNode = resultNode.get("points");
        if (pointsNode == null || !pointsNode.isArray()) {
            System.out.println("!!! [QdrantService] No points array in result");
            return new ArrayList<>();
        }
        
        Set<String> fileNames = new LinkedHashSet<>();
        for (com.fasterxml.jackson.databind.JsonNode point : pointsNode) {
            com.fasterxml.jackson.databind.JsonNode payload = point.get("payload");
            if (payload != null && payload.has("fileName")) {
                fileNames.add(payload.get("fileName").asText());
            }
        }
        
        System.out.println("=== [QdrantService] Found " + fileNames.size() + " unique files ===");
        return new ArrayList<>(fileNames);
    }

private String post(String path, Map<String, Object> body) throws Exception {
        String json = objectMapper.writeValueAsString(body);
        System.out.println("=== [QdrantService] POST " + path + " ===");
        
        URL url = new URL(qdrantUrl + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setDoOutput(true);
        
        try (OutputStream os = conn.getOutputStream()) {
            os.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        
        int code = conn.getResponseCode();
        System.out.println("=== [QdrantService] Response code: " + code + " ===");
        
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(
                    code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(),
                    java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
        }
        
        String responseBody = sb.toString();
        if (!String.valueOf(code).startsWith("2")) {
            System.out.println("!!! [QdrantService] POST failed: " + code);
            throw new IOException("Request failed: " + code);
        }
        return responseBody;
    }

private String get(String path) throws IOException {
        System.out.println("=== [QdrantService] GET " + path + " ===");
        Request request = new Request.Builder()
                .url(qdrantUrl + path)
                .get()
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = readResponseBody(response);
            if (!response.isSuccessful()) {
                System.out.println("!!! [QdrantService] GET failed: " + response.code() + ", body: " + responseBody);
                throw new IOException("Request failed: " + response.code() + ", body: " + responseBody);
            }
return responseBody;
        }
    }

    private String put(String path, Map<String, Object> body) throws IOException {
        String json = objectMapper.writeValueAsString(body);
        System.out.println("=== [QdrantService] PUT " + path + " ===");
        System.out.println("    Full URL: " + qdrantUrl + path);
        System.out.println("    Body: " + json);
        
        Request request = new Request.Builder()
                .url(qdrantUrl + path)
                .put(RequestBody.create(json, JSON))
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = readResponseBody(response);
            if (!response.isSuccessful()) {
                System.out.println("!!! [QdrantService] PUT failed: " + response.code() + ", body: " + responseBody);
                throw new IOException("Request failed: " + response.code() + ", body: " + responseBody);
            }
            return responseBody;
        }
    }

    private String delete(String path, Map<String, Object> body) throws IOException {
        String json = objectMapper.writeValueAsString(body);
        
        Request request = new Request.Builder()
                .url(qdrantUrl + path)
                .delete(RequestBody.create(json, JSON))
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Request failed: " + response.code());
            }
            return readResponseBody(response);
        }
    }

    private List<Double> floatToDoubleList(float[] floats) {
        List<Double> doubles = new ArrayList<>();
        for (float f : floats) {
            doubles.add((double) f);
        }
        return doubles;
    }

    public record ChunkItem(
        String id,
        String content,
        int chunkIndex,
        int pageNumber,
        String source,
        String fileName,
        String fileId,
        String docType,
        String sectionTitle
    ) {}

    public record SearchResult(String id, String content, double score, Map<String, Object> metadata) {}
}