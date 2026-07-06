package com.nuaa.aadl.chat;

import com.nuaa.aadl.app.config.AppProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 纯 LLM 通信客户端，使用 RestTemplate 非流式 + OkHttp 流式。
 * 不依赖 WebFlux。
 */
@Service
public class LlmClientService {

    private final RestTemplate restTemplate;          // 非流式
    private final OkHttpClient okHttpClient;          // 流式
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;
// 作用是 封装与 LLM 提供商（Ollama 或 OpenAI 兼容）的通信细节，提供统一的接口供上层调用。
    public LlmClientService(RestTemplateBuilder restTemplateBuilder,
                            ObjectMapper objectMapper,
                            AppProperties appProperties) {
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(30))
                .setReadTimeout(Duration.ofSeconds(600))
                .build(); // RestTemplate 默认不支持流式，需要 OkHttp 来处理流式响应
        this.okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(600, TimeUnit.SECONDS)
                .build(); // OkHttpClient 用于处理流式响应 当 appProperties.llmProvider() 是 "openai" 时，调用 OpenAI 兼容接口；
                            // 否则调用 Ollama 接口
        this.objectMapper = objectMapper;
        this.appProperties = appProperties;
    }

    /**
     * 非流式调用：发送消息列表，返回完整响应文本
     */
    public String chat(List<Map<String, String>> messages) throws IOException {
        return chat(messages, appProperties.ollamaModel());
    }

    /** 非流式调用，指定模型 */
    public String chat(List<Map<String, String>> messages, String model) throws IOException {
        if ("openai".equalsIgnoreCase(appProperties.llmProvider())) {
            return callOpenAiCompatible(messages, false);
        } else {
            return callOllama(messages, false, model);
        }
    }

/**
     * 流式调用：使用 OkHttp 异步流式读取，通过回调实时返回 delta 片段
     */
    public void chatStream(List<Map<String, String>> messages,
                           Consumer<String> onDelta,
                           Consumer<String> onThink,
                           Runnable onComplete,
                           Consumer<Throwable> onError) {
        chatStream(messages, onDelta, onThink, onComplete, onError, appProperties.ollamaModel());
    }

    /** 流式调用，指定模型 */
    public void chatStream(List<Map<String, String>> messages,
                           Consumer<String> onDelta,
                           Consumer<String> onThink,
                           Runnable onComplete,
                           Consumer<Throwable> onError,
                           String model) {
        if ("openai".equalsIgnoreCase(appProperties.llmProvider())) {
            streamOpenAiCompatible(messages, onDelta, onThink, onComplete, onError);
        } else {
            streamOllama(messages, onDelta, onThink, onComplete, onError, model);
        }
    }

    // ==================== 非流式实现（RestTemplate） ====================

    private String callOllama(List<Map<String, String>> messages, boolean stream) throws IOException {
        return callOllama(messages, stream, appProperties.ollamaModel());
    }

    private String callOllama(List<Map<String, String>> messages, boolean stream, String model) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("stream", stream);
        payload.put("think",false);
        payload.put("messages", messages);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(appProperties.ollamaUrl(), entity, String.class);
        Map<String, Object> body = objectMapper.readValue(response.getBody(), new TypeReference<>() {});
        Object msg = body.get("message");
        if (msg instanceof Map<?, ?> map) {
            Object content = map.get("content");
            if (content instanceof String str) {
                return str;
            }
        }
        return "";
    }

    private String callOpenAiCompatible(List<Map<String, String>> messages, boolean stream) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", appProperties.openaiModel());
        payload.put("stream", stream);
        payload.put("messages", messages);

        HttpHeaders headers = createOpenAiHeaders();
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(appProperties.openaiUrl(), entity, String.class);
        Map<String, Object> body = objectMapper.readValue(response.getBody(), new TypeReference<>() {});
        return extractOpenAiContent(body);
    }

    // ==================== 流式实现（OkHttp） ====================
// 通过 OkHttp 的异步回调机制，实时读取响应流中的数据块，解析出增量文本并通过 onDelta 回调返回给调用者
// 对于 Ollama，监听 "message" 字段中的 "content" 和 "thinking" 字段，分别通过 onDelta 和 onThink 回调返回；
// 对于 OpenAI 兼容接口，监听 "data:" 行，解析出 delta 内容通过 on
    private void streamOllama(List<Map<String, String>> messages,
                              Consumer<String> onDelta,
                              Consumer<String> onThink,
                              Runnable onComplete,
                              Consumer<Throwable> onError) {
        streamOllama(messages, onDelta, onThink, onComplete, onError, appProperties.ollamaModel());
    }

    private void streamOllama(List<Map<String, String>> messages,
                              Consumer<String> onDelta,
                              Consumer<String> onThink,
                              Runnable onComplete,
                              Consumer<Throwable> onError,
                              String model) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("stream", true);
        payload.put("think", true);
        payload.put("messages", messages);

        String jsonPayload; // 将请求参数序列化为 JSON 字符串，作为 OkHttp 请求体发送给 Ollama 流式接口
        try {
            jsonPayload = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            onError.accept(e);
            return;
        }

        // 构建 OkHttp 请求，发送 POST 请求到 Ollama 流式接口
        Request request = new Request.Builder()
                .url(appProperties.ollamaUrl())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(jsonPayload, okhttp3.MediaType.parse("application/json")))
                .build();

// newcall 发起异步请求，监听响应流 , 作用是 实时处理 Ollama 返回的流式数据，解析出增量文本并通过回调返回给调用者，同时处理错误和完成事件。

        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                onError.accept(e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody body = response.body()) {
                    if (!response.isSuccessful()) {
                        onError.accept(new IOException("Ollama stream failed: HTTP " + response.code()));
                        return;
                    }
                    if (body == null) {
                        onError.accept(new IOException("Empty response body"));
                        return;
                    }
                    boolean doneReceived = false;
                    try (okio.BufferedSource source = body.source()) {
                        String line;
                        while ((line = source.readUtf8Line()) != null) {
                            if (line.isBlank()) continue;
                            try {
                                System.out.println("[Ollama Raw] " + line);
                                Map<String, Object> chunk = objectMapper.readValue(line, new TypeReference<>() {});
                                Object msg = chunk.get("message");
                                if (msg instanceof Map<?, ?> map) {
                                    Object content = map.get("content");
                                    Object thinking = map.get("thinking");

                                    // 有独立 thinking 字段：分别路由
                                    boolean hasThinking = thinking instanceof String t && !t.isEmpty();
                                    if (hasThinking) {
                                        System.out.println("[Ollama Think] " + thinking);
                                        onThink.accept((String) thinking);
                                    }
                                    if (content instanceof String c && !c.isEmpty()) {
                                        System.out.println("[Ollama Delta] " + c);
                                        onDelta.accept(c);
                                    }
                                }
                                if (Boolean.TRUE.equals(chunk.get("done"))) {
                                    doneReceived = true;
                                    System.out.println("[Ollama Done] true");
                                    break;
                                }
                            } catch (Exception e) {
                                System.out.println("[Ollama Parse Error] " + e.getMessage() + " | Line: " + line);
                            }
                        }
                    }
                    if (!doneReceived) {
                        onError.accept(new IOException("Ollama stream ended without done=true"));
                        return;
                    }
                    onComplete.run();
                } catch (Exception e) {
                    onError.accept(e);
                }
            }
        });
    }

private void streamOpenAiCompatible(List<Map<String, String>> messages,
                                        Consumer<String> onDelta,
                                        Consumer<String> onThink,
                                        Runnable onComplete,
                                        Consumer<Throwable> onError) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", appProperties.openaiModel());
        payload.put("stream", true);
        payload.put("messages", messages);

        String jsonPayload;
        try {
            jsonPayload = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            onError.accept(e);
            return;
        }

        Request request = new Request.Builder()
                .url(appProperties.openaiUrl())
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer " + appProperties.openaiApiKey())
                .post(RequestBody.create(jsonPayload, okhttp3.MediaType.parse("application/json")))
                .build();

        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                onError.accept(e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody body = response.body()) {
                    if (!response.isSuccessful()) {
                        onError.accept(new IOException("OpenAI-compatible stream failed: HTTP " + response.code()));
                        return;
                    }
                    if (body == null) {
                        onError.accept(new IOException("Empty response body"));
                        return;
                    }
                    boolean doneReceived = false;
                    try (okio.BufferedSource source = body.source()) {
                        String line;
                        while ((line = source.readUtf8Line()) != null) {
                            if (!line.startsWith("data:")) {
                                continue;
                            }
                            String data = line.substring(5).trim();
                            if ("[DONE]".equals(data)) {
                                doneReceived = true;
                                break;
                            }
                            if (data.isEmpty()) continue;
                            try {
                                Map<String, Object> chunk = objectMapper.readValue(data, new TypeReference<>() {});
                                String delta = extractOpenAiDelta(chunk);
                                if (delta != null && !delta.isEmpty()) {
                                    onDelta.accept(delta);
                                }
                            } catch (Exception e) {
                                // 忽略单行解析错误
                            }
                        }
                    }
                    if (!doneReceived) {
                        onError.accept(new IOException("OpenAI-compatible stream ended without [DONE]"));
                        return;
                    }
                    onComplete.run();
                } catch (Exception e) {
                    onError.accept(e);
                }
            }
        });
    }

    // ==================== 辅助方法 ====================

    private HttpHeaders createOpenAiHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (appProperties.openaiApiKey() != null && !appProperties.openaiApiKey().isBlank()) {
            headers.setBearerAuth(appProperties.openaiApiKey());
        }
        return headers;
    }

    private String extractOpenAiContent(Map<String, Object> body) {
        Object choices = body.get("choices");
        if (choices instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof Map<?, ?> choice) {
                Object message = choice.get("message");
                if (message instanceof Map<?, ?> map) {
                    Object content = map.get("content");
                    if (content instanceof String str) {
                        return str;
                    }
                }
            }
        }
        return "";
    }

    private String extractOpenAiDelta(Map<String, Object> body) {
        Object choices = body.get("choices");
        if (choices instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof Map<?, ?> choice) {
                Object delta = choice.get("delta");
                if (delta instanceof Map<?, ?> map) {
                    Object content = map.get("content");
                    if (content instanceof String str) {
                        return str;
                    }
                }
            }
        }
        return "";
    }
}
