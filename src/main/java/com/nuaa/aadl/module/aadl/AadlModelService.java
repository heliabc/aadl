package com.nuaa.aadl.module.aadl;

import com.nuaa.aadl.chat.ChatSendRequest;
import com.nuaa.aadl.chat.ChatSendResponse;
import com.nuaa.aadl.chat.MessageRepository;
import com.nuaa.aadl.shared.doc.ModuleDocRepository;
import com.nuaa.aadl.shared.file.FileService;
import com.nuaa.aadl.chat.LlmClientService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class AadlModelService {

    private static final Pattern CODE_BLOCK = Pattern.compile("```(?:aadl)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    private final FileService fileService;
    private final ModuleDocRepository moduleDocRepository;
    private final ObjectMapper objectMapper;
    private final LlmClientService llmClientService;
    private final MessageRepository messageRepository;
    private final AadlPromptRuleService aadlPromptRuleService;

    public AadlModelService(FileService fileService,
                            ModuleDocRepository moduleDocRepository,
                            ObjectMapper objectMapper,
                            LlmClientService llmClientService,
                            MessageRepository messageRepository,
                            AadlPromptRuleService aadlPromptRuleService) {
        this.fileService = fileService;
        this.moduleDocRepository = moduleDocRepository;
        this.objectMapper = objectMapper;
        this.llmClientService = llmClientService;
        this.messageRepository = messageRepository;
        this.aadlPromptRuleService = aadlPromptRuleService;
    }

    // ==================== 公开方法 ====================

    /**
     * 普通聊天式 AADL 生成入口：同步调用大模型，提取 AADL 后保存到模块文档。
     */
    public String generateAadl(ChatSendRequest request) {
        try {
            List<Map<String, String>> messages = buildMessages(request);
            messageRepository.appendMessage(request.conversationId(), "user", request.module(), request.message());
            String raw = llmClientService.chat(messages);
            String aadl = extractAndPersistAadl(request, raw);
            messageRepository.appendMessage(request.conversationId(), "assistant", request.module(), buildAssistantReply(aadl, false, null));
            return aadl;
        } catch (Exception e) {
            messageRepository.appendMessage(request.conversationId(), "assistant", request.module(), "AADL generation failed: " + e.getMessage());
            return generateFallbackAadl(request, "大模型调用失败，已返回规则生成结果: " + e.getMessage());
        }
    }

    /**
     * 普通聊天式 AADL 流式生成入口：边接收大模型输出边推送给前端，结束后保存最终模型。
     */
    public void generateAadlStream(ChatSendRequest request, SseEmitter emitter) {
        try {
            System.out.println("[AADL Stream] Started for conversation: " + request.conversationId());
            emitter.send(SseEmitter.event().name("status").data(Map.of("stage", "started")));
            System.out.println("[AADL Stream] Sent status: started");
            List<Map<String, String>> messages = buildMessages(request);
            messageRepository.appendMessage(request.conversationId(), "user", request.module(), request.message());
            StringBuilder fullReply = new StringBuilder();

            llmClientService.chatStream(
                    messages,
                    delta -> {
                        fullReply.append(delta);
                        try {
                            System.out.println("[AADL Stream] Sending delta: " + delta);
                            emitter.send(SseEmitter.event().name("delta").data(Map.of("text", delta)));
                        } catch (IOException e) {
                            System.out.println("[AADL Stream] Send error: " + e.getMessage());
                            throw new RuntimeException(e);
                        }
                    },
                    think -> {
                        try {
                            emitter.send(SseEmitter.event().name("think").data(Map.of("text", think)));
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    },
                    () -> {
                        try {
                            String aadl = extractAndPersistAadl(request, fullReply.toString());
                            String streamedReply = fullReply.toString().trim();
                            String finalReply = streamedReply.isBlank()
                                    ? buildAssistantReply(aadl, false, null)
                                    : streamedReply;
                            messageRepository.appendMessage(request.conversationId(), "assistant", request.module(), finalReply);
                            emitter.send(SseEmitter.event().name("done").data(new ChatSendResponse(finalReply, buildAadlModuleData(request, aadl))));
                            emitter.complete();
                        } catch (Exception e) {
                            emitter.completeWithError(e);
                        }
                    },
                    error -> {
                        try {
                            String aadl = generateFallbackAadl(request, "大模型调用失败，已返回规则生成结果: " + error.getMessage());
                            String reply = buildAssistantReply(aadl, true, "大模型调用失败，已返回规则生成结果。");
                            emitter.send(SseEmitter.event().name("delta").data(Map.of("text", reply)));
                            emitter.send(SseEmitter.event().name("done").data(new ChatSendResponse(reply, buildAadlModuleData(request, aadl))));
                            emitter.complete();
                        } catch (IOException e) {
                            emitter.completeWithError(e);
                        }
                    }
            );
        } catch (Exception e) {
            try {
                String aadl = generateFallbackAadl(request, "流式调用失败: " + e.getMessage());
                String reply = buildAssistantReply(aadl, true, "流式调用失败，已返回规则生成结果。");
                emitter.send(SseEmitter.event().name("delta").data(Map.of("text", reply)));
                emitter.send(SseEmitter.event().name("done").data(new ChatSendResponse(reply, buildAadlModuleData(request, aadl))));
                emitter.complete();
            } catch (IOException ex) {
                emitter.completeWithError(ex);
            }
        }
    }

    /**
     * 将 AADL 模型包装成助手回复；失败兜底时展示失败说明，成功时展示模型摘要。
     */
    public String buildAssistantReply(String aadl, boolean fallback, String note) {
        String prefix = fallback
                ? (note == null || note.isBlank() ? "已使用本地规则生成AADL模型。" : note)
                : summarizeAadl(aadl);
        return prefix + "\n\n```aadl\n" + aadl + "\n```";
    }

    /**
     * 读取当前会话保存的 AADL；没有保存内容时返回一个可展示的默认模型。
     */
    public String getAadl(String conversationId) {
        return moduleDocRepository.readModuleDoc(conversationId, "aadl", "model")
                .map(record -> readContent(record.payloadJson()))
                .orElse(defaultModel(conversationId));
    }

    /**
     * 读取当前会话保存的 AADL；没有保存内容时返回空串，供其他模块拼接上下文。
     */
    public String getAadlIfExists(String conversationId) {
        return moduleDocRepository.readModuleDoc(conversationId, "aadl", "model")
                .map(record -> readContent(record.payloadJson()))
                .orElse("");
    }

    /**
     * 一键生成入口：根据原始需求走“两步生成”，并持久化最终 AADL。
     */
    public String generateAndPersistFromRequirement(String conversationId, String requirementText) {
        String aadl = generateFullAadlFromRequirement(conversationId, requirementText);
        persistDocument(conversationId, aadl, requirementText, "", "generate-from-requirement");
        return aadl;
    }

    /**
     * 一键生成的非流式实现：先生成架构需求，再基于架构需求生成最终 AADL。
     * 支持多轮对话：会读取历史对话作为上下文。
     */
    public String generateFullAadlFromRequirement(String conversationId, String requirementText) {
        try {
            String template = generateRequirementTemplate(conversationId, requirementText);
            return generateAadlFromTemplate(conversationId, template, requirementText);
        } catch (Exception e) {
            return buildHeuristicAadl(requirementText, "");
        }
    }

    /**
     * 一键生成的流式入口：第一步生成架构需求，第二步生成 AADL，前端只看到最终 AADL 流。
     * 支持多轮对话：会读取历史对话作为上下文。
     */
    public void generateFromRequirementStream(String conversationId, String requirementText, SseEmitter emitter) {
        CompletableFuture.runAsync(() -> {
            StringBuilder templateBuilder = new StringBuilder();
            StringBuilder templateThinkBuilder = new StringBuilder();
            StringBuilder aadlBuilder = new StringBuilder();
            StringBuilder aadlThinkBuilder = new StringBuilder();
            StringBuilder stepTwoPending = new StringBuilder();
            StringBuilder stepTwoThinkPending = new StringBuilder();
            AtomicLong lastDeltaFlushAt = new AtomicLong(System.currentTimeMillis());
            AtomicLong lastThinkFlushAt = new AtomicLong(System.currentTimeMillis());
            AtomicBoolean streamClosed = new AtomicBoolean(false);
            AtomicBoolean aadlCodeBlockOpened = new AtomicBoolean(false);
            AtomicBoolean stepTwoFinished = new AtomicBoolean(false);

            if (!safeSend(emitter, streamClosed, SseEmitter.event().name("status").data(Map.of("stage", "step1_template_started")))) {
                return;
            }

            // 构建包含历史对话的消息列表
            List<Map<String, String>> stepOneMessages = buildStepOneMessages(conversationId, requirementText);
            logLlmPrompt("AADL step1 template stream prompt", stepOneMessages);

            llmClientService.chatStream(
                    stepOneMessages,
                    delta -> {
                        if (streamClosed.get()) return;
                        templateBuilder.append(delta);
                        // 一键生成的第1步是“模板”，不推到正文 delta，避免页面把模板当作最终答案
                    },
                    think -> {
                        if (streamClosed.get()) return;
                        templateThinkBuilder.append(think);
                    },
                    () -> {
                        if (streamClosed.get()) return;
                        try {
                            if (!safeSend(emitter, streamClosed, SseEmitter.event().name("status").data(Map.of("stage", "step2_aadl_started")))) {
                                return;
                            }
                        } catch (IllegalStateException e) {
                            streamClosed.set(true);
                            return;
                        }
                        String aadlPrompt = buildAadlPromptFromArchitectureRequirement(templateBuilder.toString());
                        // 构建包含历史对话的第二步消息列表
                        List<Map<String, String>> stepTwoMessages = buildStepTwoMessages(conversationId, aadlPrompt, requirementText);
                        logLlmPrompt("AADL step2 code stream prompt", stepTwoMessages);

                        scheduleStepTwoTimeout(
                                conversationId,
                                requirementText,
                                templateBuilder,
                                aadlBuilder,
                                aadlCodeBlockOpened,
                                stepTwoFinished,
                                streamClosed,
                                emitter
                        );

                        llmClientService.chatStream(
                                stepTwoMessages,
                                delta2 -> {
                                    if (streamClosed.get() || stepTwoFinished.get()) return;
                                    aadlBuilder.append(delta2);
                                    stepTwoPending.append(delta2);
                                    long now = System.currentTimeMillis();
                                    if (shouldFlushPendingDelta(stepTwoPending, now, lastDeltaFlushAt.get())) {
                                        if (aadlCodeBlockOpened.compareAndSet(false, true)) {
                                            if (!safeSend(emitter, streamClosed, SseEmitter.event().name("delta").data(Map.of("text", "\n```aadl\n")))) {
                                                return;
                                            }
                                        }
                                        if (!safeSend(emitter, streamClosed, SseEmitter.event().name("delta").data(Map.of("text", stepTwoPending.toString())))) {
                                            return;
                                        }
                                        stepTwoPending.setLength(0);
                                        lastDeltaFlushAt.set(now);
                                    }
                                },
                                think2 -> {
                                    if (streamClosed.get() || stepTwoFinished.get()) return;
                                    aadlThinkBuilder.append(think2);
                                    stepTwoThinkPending.append(think2);
                                    long now = System.currentTimeMillis();
                                    if (shouldFlushPendingDelta(stepTwoThinkPending, now, lastThinkFlushAt.get())) {
                                        if (!safeSend(emitter, streamClosed, SseEmitter.event().name("think").data(Map.of("text", stepTwoThinkPending.toString())))) {
                                            return;
                                        }
                                        stepTwoThinkPending.setLength(0);
                                        lastThinkFlushAt.set(now);
                                    }
                                },
                                () -> {
                                    if (streamClosed.get()) return;
                                    if (!stepTwoFinished.compareAndSet(false, true)) return;
                                    try {
                                        if (!stepTwoPending.isEmpty()) {
                                            if (aadlCodeBlockOpened.compareAndSet(false, true)) {
                                                if (!safeSend(emitter, streamClosed, SseEmitter.event().name("delta").data(Map.of("text", "\n```aadl\n")))) {
                                                    return;
                                                }
                                            }
                                            if (!safeSend(emitter, streamClosed, SseEmitter.event().name("delta").data(Map.of("text", stepTwoPending.toString())))) {
                                                return;
                                            }
                                            stepTwoPending.setLength(0);
                                        }
                                        if (aadlCodeBlockOpened.get()) {
                                            if (!safeSend(emitter, streamClosed, SseEmitter.event().name("delta").data(Map.of("text", "\n```\n")))) {
                                                return;
                                            }
                                        }
                                        if (!stepTwoThinkPending.isEmpty()) {
                                            if (!safeSend(emitter, streamClosed, SseEmitter.event().name("think").data(Map.of("text", stepTwoThinkPending.toString())))) {
                                                return;
                                            }
                                            stepTwoThinkPending.setLength(0);
                                        }
                                        String rawAadl = aadlBuilder.toString();
                                        String aadl = normalizeAadlOrFallback(rawAadl, requirementText, templateBuilder.toString());
                                        String finalReply = "✅ 生成的 AADL 模型：\n```aadl\n" + aadl + "\n```";
                                        String rawReply = "step1_template:\n" + templateBuilder + "\n\nstep2_raw:\n" + rawAadl
                                                + "\n\nstep1_think:\n" + templateThinkBuilder + "\n\nstep2_think:\n" + aadlThinkBuilder;
                                        persistDocument(conversationId, aadl, requirementText, "", rawReply);
                                        if (safeSend(emitter, streamClosed, SseEmitter.event().name("done").data(Map.of("reply", finalReply, "aadlModel", aadl)))) {
                                            safeComplete(emitter, streamClosed);
                                        }
                                    } catch (RuntimeException e) {
                                        streamClosed.set(true);
                                    }
                                },
                                error2 -> {
                                    if (streamClosed.get()) return;
                                    if (!stepTwoFinished.compareAndSet(false, true)) return;
                                    try {
                                        String aadl = buildHeuristicAadl(requirementText, templateBuilder.toString());
                                        String fallbackReply = "⚠️ 第二步流式调用失败，已返回规则生成结果。\n\n```aadl\n" + aadl + "\n```";
                                        persistDocument(conversationId, aadl, requirementText, "", "step2_error: " + error2.getMessage());
                                        if (aadlCodeBlockOpened.get()) {
                                            if (!safeSend(emitter, streamClosed, SseEmitter.event().name("delta").data(Map.of("text", "\n```\n")))) {
                                                return;
                                            }
                                        }
                                        safeSend(emitter, streamClosed, SseEmitter.event().name("error").data(Map.of("message", error2.getMessage() == null ? "step2_stream_failed" : error2.getMessage())));
                                        if (safeSend(emitter, streamClosed, SseEmitter.event().name("done").data(Map.of("reply", fallbackReply, "aadlModel", aadl)))) {
                                            safeComplete(emitter, streamClosed);
                                        }
                                    } catch (RuntimeException e) {
                                        streamClosed.set(true);
                                    }
                                }
                        );
                    },
                    error -> {
                        if (streamClosed.get()) return;
                        try {
                            String aadl = buildHeuristicAadl(requirementText, "");
                            String fallbackReply = "⚠️ 第一步流式调用失败，已返回规则生成结果。\n\n```aadl\n" + aadl + "\n```";
                            persistDocument(conversationId, aadl, requirementText, "", "step1_error: " + error.getMessage());
                            safeSend(emitter, streamClosed, SseEmitter.event().name("error").data(Map.of("message", error.getMessage() == null ? "step1_stream_failed" : error.getMessage())));
                            if (safeSend(emitter, streamClosed, SseEmitter.event().name("done").data(Map.of("reply", fallbackReply, "aadlModel", aadl)))) {
                                safeComplete(emitter, streamClosed);
                            }
                        } catch (IllegalStateException e) {
                            streamClosed.set(true);
                        }
                    }
            );
        });
    }

    // ==================== 聊天 Prompt 与上下文 ====================

    /**
     * 组装普通聊天生成所需的 system、历史消息和当前用户消息。
     */
    private List<Map<String, String>> buildMessages(ChatSendRequest request) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", buildSystemPrompt()));
        for (MessageRepository.MessageRecord record : messageRepository.getRecentMessages(request.conversationId(), 20)) {
            if (!"user".equals(record.role()) && !"assistant".equals(record.role())) {
                continue;
            }
            String module = record.module() == null || record.module().isBlank() ? "" : "[" + record.module() + "] ";
            messages.add(Map.of("role", record.role(), "content", module + record.content()));
        }
        messages.add(Map.of("role", "user", "content", buildUserPrompt(request)));
        return messages;
    }

    /**
     * 普通聊天生成的系统提示词，约束模型输出完整 AADL 或按用户意图解释模型。
     */
    public String buildSystemPrompt() {
        return "你是专业AADL建模助手。请根据用户需求和附件内容生成可直接下载的完整AADL模型。"
                + "默认先给出简短说明，再给出完整AADL代码。"
                + "若用户明确要求“只要代码/不要说明”，则只输出代码。"
                + "输出代码时把完整AADL放在```aadl代码块```中。"
                + "不要输出伪代码，不要省略end，不要只给片段。"
                + "若用户让你分析或解析AADL模型,则不需要输出aadl代码，只需要给出解释aadl模型。";
    }

    /**
     * 普通聊天生成的用户提示词，合并用户输入、附件内容和已有模块文档。
     */
    public String buildUserPrompt(ChatSendRequest request) {
        String attachmentContext = fileService.buildAttachmentContext(emptyIfNull(request.attachmentIds()));
        String documentContext = buildDocumentContext(request.conversationId());
        if (!documentContext.isBlank()) {
            attachmentContext = attachmentContext + "\n\n" + documentContext;
        }
        return "用户需求:\n" + request.message() + "\n\n"
                + "附件上下文:\n" + attachmentContext + "\n\n"
                + "请生成完整AADL模型，要求可以直接作为.aadl文件保存。"
                + "如果用户提到“介绍/说明/解释”，请直接解释模型结构与关键设计而不需要给出代码。"
                + "如果当前有多个aadl模型文件则当作工程处理,需要整体介绍再分别介绍。"
                + "当用户让你修改代码时,尽量少修改代码结构,做到增量修改";
    }

    /**
     * 拼接历史 AADL/FMEA/FTA 文档，作为当前生成或解释的上下文。
     */
    private String buildDocumentContext(String conversationId) {
        StringBuilder context = new StringBuilder();
        moduleDocRepository.readModuleDoc(conversationId, "aadl", "model").ifPresent(record ->
            context.append("[Previous AADL Document]\n").append(truncate(record.payloadJson(), 6000))
        );
        moduleDocRepository.readModuleDoc(conversationId, "safety", "fmea").ifPresent(record -> {
            if (!context.isEmpty()) context.append("\n\n");
            context.append("[Previous Safety FMEA]\n").append(truncate(record.payloadJson(), 4000));
        });
        moduleDocRepository.readModuleDoc(conversationId, "safety", "fta").ifPresent(record -> {
            if (!context.isEmpty()) context.append("\n\n");
            context.append("[Previous Safety FTA]\n").append(truncate(record.payloadJson(), 4000));
        });
        return context.toString();
    }

    /**
     * 限制上下文长度，避免历史文档过长导致提示词膨胀。
     */
    private String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text == null ? "" : text;
        }
        return text.substring(0, maxChars) + "\n...[truncated]";
    }

    // ==================== AADL 提取、保存与兜底 ====================

    /**
     * 从大模型原始回复中提取 AADL；提取失败时使用本地规则生成，并保存结果。
     */
    public String extractAndPersistAadl(ChatSendRequest request, String rawReply) {
        String aadl = extractAadl(rawReply);
        if (aadl.isBlank()) {
            aadl = buildHeuristicAadl(request.message(), fileService.buildAttachmentContext(emptyIfNull(request.attachmentIds())));
        }
        persistDocument(
                request.conversationId(),
                aadl,
                request.message(),
                fileService.buildAttachmentContext(emptyIfNull(request.attachmentIds())),
                rawReply
        );
        return aadl;
    }

    /**
     * 大模型失败时的兜底路径：根据用户需求和附件上下文生成一个基础 AADL。
     */
    public String generateFallbackAadl(ChatSendRequest request, String failureReason) {
        String aadl = buildHeuristicAadl(request.message(), fileService.buildAttachmentContext(emptyIfNull(request.attachmentIds())));
        persistDocument(
                request.conversationId(),
                aadl,
                request.message(),
                fileService.buildAttachmentContext(emptyIfNull(request.attachmentIds())),
                failureReason
        );
        return aadl;
    }

    /**
     * 优先提取 ```aadl 代码块；没有代码块时接受完整 package/end 形式的纯 AADL。
     */
    private String extractAadl(String rawReply) {
        if (rawReply == null || rawReply.isBlank()) return "";
        Matcher matcher = CODE_BLOCK.matcher(rawReply);
        if (matcher.find()) return matcher.group(1).trim();
        if (rawReply.contains("package ") && rawReply.contains("end ")) return rawReply.trim();
        return "";
    }

    /**
     * 将最终 AADL 及其生成来源写入模块文档仓库，供下载和后续模块复用。
     */
    private void persistDocument(String conversationId, String content, String prompt, String attachmentContext, String rawReply) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", content);
        payload.put("prompt", prompt);
        payload.put("attachmentContext", attachmentContext);
        payload.put("rawReply", rawReply);
        try {
            moduleDocRepository.writeModuleDoc(conversationId, "aadl", "model", objectMapper.writeValueAsString(payload));
        } catch (IOException e) {
            throw new IllegalStateException("AADL 模型保存失败", e);
        }
    }

    /**
     * 从模块文档 payload 中读取 AADL 内容；解析失败时返回默认模型。
     */
    private String readContent(String payloadJson) {
        try {
            Map<?, ?> payload = objectMapper.readValue(payloadJson, Map.class);
            Object content = payload.get("content");
            return content instanceof String str && !str.isBlank() ? str : defaultModel("demo");
        } catch (IOException e) {
            return defaultModel("demo");
        }
    }

    /**
     * 构造空会话或异常情况下用于展示的最小 AADL 模型。
     */
    private String defaultModel(String conversationId) {
        String normalized = inferSystemName(conversationId, "");
        return String.join("\n",
                "package " + normalized,
                "public",
                "  system " + normalized + "_Impl",
                "  end " + normalized + "_Impl;",
                "end " + normalized + ";",
                "");
    }

    /**
     * 本地规则生成的兜底模型，保证前端至少能拿到合法的 package 结构。
     */
    private String buildHeuristicAadl(String source, String attachmentContext) {
        String modelName = inferSystemName(source, attachmentContext);
        String componentBlock = inferComponents(attachmentContext);
        return String.join("\n",
                "package " + modelName,
                "public",
                componentBlock,
                "  system " + modelName + "_Impl",
                "  end " + modelName + "_Impl;",
                "end " + modelName + ";",
                "");
    }

    /**
     * 从用户输入和附件上下文中推断英文系统名，供兜底模型命名使用。
     */
    private String inferSystemName(String source, String attachmentContext) {
        String joined = (source == null ? "" : source) + " " + (attachmentContext == null ? "" : attachmentContext);
        String lower = joined.toLowerCase();
         Matcher englishSystemMatcher = Pattern.compile("\\b([A-Za-z][A-Za-z0-9_]*(?:System|Subsystem|Control))\\b").matcher(joined);
        if (englishSystemMatcher.find()) return englishSystemMatcher.group(1);
        if (lower.contains("brake") || joined.contains("制动")) return "BrakeSystem";
        if (lower.contains("sensor") || joined.contains("传感")) return "SensorSuite";
        if (lower.contains("flight") || joined.contains("飞行")) return "FlightControl";
        return "DemoSystem";
    }

    /**
     * 根据附件关键词补一个简单的子组件结构，供兜底模型使用。
     */
    private String inferComponents(String attachmentContext) {
        List<String> lines = List.of(
                "  system implementation DemoComponents",
                "    subcomponents",
                attachmentContext != null && attachmentContext.contains("传感")
                        ? "      sensor: device Sensor_Device;"
                        : "      controller: process Core_Process;",
                attachmentContext != null && attachmentContext.contains("执行")
                        ? "      actuator: device Actuator_Device;"
                        : "      bus: bus Data_Bus;",
                "  end DemoComponents;"
        );
        return String.join("\n", lines);
    }

    // ==================== 文件差异与流式工具 ====================

    /**
     * 统一处理可能为空的附件 ID 列表，减少调用处的空判断。
     */
    private List<String> emptyIfNull(List<String> attachmentIds) {
        return attachmentIds == null ? List.of() : attachmentIds;
    }

    

    /**
     * 控制流式输出刷新频率，兼顾页面实时性和事件数量。
     */
    private boolean shouldFlushPendingDelta(StringBuilder pending, long now, long lastFlushAt) {
        if (pending.isEmpty()) {
            return false;
        }
        if (pending.length() >= 48) {
            return true;
        }
        if (now - lastFlushAt >= 120) {
            return true;
        }
        char tail = pending.charAt(pending.length() - 1);
        return tail == '\n' || tail == '。' || tail == '！' || tail == '？' || tail == '；';
    }

    /**
     * 安全发送 SSE 事件；客户端断开或发送失败时标记流已关闭。
     */
    private boolean safeSend(SseEmitter emitter, AtomicBoolean streamClosed, SseEmitter.SseEventBuilder event) {
        if (streamClosed.get()) {
            return false;
        }
        try {
            emitter.send(event);
            return true;
        } catch (IOException | IllegalStateException e) {
            streamClosed.set(true);
            return false;
        }
    }

    /**
     * 安全结束 SSE，避免超时、异常回调和正常结束重复 complete。
     */
    private void safeComplete(SseEmitter emitter, AtomicBoolean streamClosed) {
        if (streamClosed.compareAndSet(false, true)) {
            try {
                emitter.complete();
            } catch (IllegalStateException ignored) {
                // emitter may have been completed by timeout or client disconnect
            }
        }
    }

    /**
     * 一键生成第二步的超时保护：超过 300 秒时返回当前可用内容或兜底模型。
     */
    private void scheduleStepTwoTimeout(String conversationId,
                                        String requirementText,
                                        StringBuilder templateBuilder,
                                        StringBuilder aadlBuilder,
                                        AtomicBoolean aadlCodeBlockOpened,
                                        AtomicBoolean stepTwoFinished,
                                        AtomicBoolean streamClosed,
                                        SseEmitter emitter) {
        CompletableFuture.delayedExecutor(1500, TimeUnit.SECONDS).execute(() -> {
            if (!stepTwoFinished.compareAndSet(false, true)) {
                return;
            }
            if (streamClosed.get()) {
                return;
            }

            String rawAadl = aadlBuilder.toString();
            String aadl = normalizeAadlOrFallback(rawAadl, requirementText, templateBuilder.toString());
            String timeoutReply = "⚠️ 第二步生成超过1500秒，已返回当前可用的 AADL 结果。\n\n```aadl\n" + aadl + "\n```";
            String rawReply = "step2_timeout_partial:\n" + rawAadl + "\n\nstep1_template:\n" + templateBuilder;

            try {
                persistDocument(conversationId, aadl, requirementText, "", rawReply);
            } catch (RuntimeException ignored) {
                // If persistence fails after timeout, still try to finish the stream gracefully.
            }

            if (aadlCodeBlockOpened.get()) {
                safeSend(emitter, streamClosed, SseEmitter.event().name("delta").data(Map.of("text", "\n```\n")));
            }
            safeSend(emitter, streamClosed, SseEmitter.event().name("delta").data(Map.of("text", "\n\n⚠️ 第二步生成超过1500秒，已自动结束并返回当前可用结果。\n")));
            safeSend(emitter, streamClosed, SseEmitter.event().name("error").data(Map.of("message", "step2_timeout")));
            if (safeSend(emitter, streamClosed, SseEmitter.event().name("done").data(Map.of("reply", timeoutReply, "aadlModel", aadl)))) {
                safeComplete(emitter, streamClosed);
            }
        });
    }

    /**
     * 构造聊天接口返回给前端的 AADL 模块数据。
     */
    private Map<String, Object> buildAadlModuleData(ChatSendRequest request, String aadl) {
        Map<String, Object> aadlData = new LinkedHashMap<>();
        aadlData.put("content", aadl);

        Map<String, Object> moduleData = new LinkedHashMap<>();
        moduleData.put("aadl", aadlData);
        return moduleData;
    }


    // ==================== 两步生成 Prompt ====================

    /**
     * 一键生成第一步：调用大模型把原始需求整理成结构化架构需求。
     * 支持多轮对话：读取历史对话作为上下文。
     */
    private String generateRequirementTemplate(String conversationId, String requirementText) throws Exception {
        String prompt = buildArchitectureRequirementTemplatePrompt(requirementText);
        List<Map<String, String>> messages = buildStepOneMessages(conversationId, requirementText);
        logLlmPrompt("AADL step1 template prompt", messages);
        String template = llmClientService.chat(messages);

        System.out.println("===== 第一次 LLM 调用生成的需求模板 =====");
        System.out.println(template);
        System.out.println("========================================");

        return template;
    }

    /**
     * 一键生成第二步：基于结构化架构需求生成 AADL，并做代码块提取或兜底。
     * 支持多轮对话：读取历史对话作为上下文。
     */
    private String generateAadlFromTemplate(String conversationId, String template, String requirementText) throws Exception {
        String aadlPrompt = buildAadlPromptFromArchitectureRequirement(template);

        System.out.println("===== 第二步 LLM 调用生成的 AADL Prompt =====");
        System.out.println(aadlPrompt);
        System.out.println("========================================");

        List<Map<String, String>> messages = buildStepTwoMessages(conversationId, aadlPrompt, requirementText);
        logLlmPrompt("AADL step2 code prompt", messages);
        String rawAadl = llmClientService.chat(messages);
        return normalizeAadlOrFallback(rawAadl, requirementText, template);
    }

    /**
     * 构建第一步（生成架构需求）的消息列表，包含对话历史。
     */
    private List<Map<String, String>> buildStepOneMessages(String conversationId, String requirementText) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", "你是AADL模型建模专家，严格按照要求输出格式。"));
        
        // 添加历史对话
        for (MessageRepository.MessageRecord record : messageRepository.getRecentMessages(conversationId, 10)) {
            if (!"user".equals(record.role()) && !"assistant".equals(record.role())) {
                continue;
            }
            String module = record.module() == null || record.module().isBlank() ? "" : "[" + record.module() + "] ";
            messages.add(Map.of("role", record.role(), "content", module + record.content()));
        }
        
        messages.add(Map.of("role", "user", "content", buildArchitectureRequirementTemplatePrompt(requirementText)));
        return messages;
    }

    /**
     * 构建第二步（生成AADL代码）的消息列表，包含对话历史。
     */
    private List<Map<String, String>> buildStepTwoMessages(String conversationId, String aadlPrompt, String requirementText) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", "你是AADL代码生成专家，根据需求输出AADL代码。"));
        
        // 添加历史对话
        for (MessageRepository.MessageRecord record : messageRepository.getRecentMessages(conversationId, 10)) {
            if (!"user".equals(record.role()) && !"assistant".equals(record.role())) {
                continue;
            }
            String module = record.module() == null || record.module().isBlank() ? "" : "[" + record.module() + "] ";
            messages.add(Map.of("role", record.role(), "content", module + record.content()));
        }
        
        messages.add(Map.of("role", "user", "content", aadlPrompt));
        return messages;
    }

    /**
     * 标准化第二步输出：优先使用可识别的 AADL，否则回退到本地规则模型。
     */
    private String normalizeAadlOrFallback(String rawAadl, String requirementText, String template) {
        String aadl = extractAadl(rawAadl);
        if (!aadl.isBlank()) {
            return aadl;
        }
        if (rawAadl != null) {
            String cleaned = rawAadl
                    .replace("```aadl", "")
                    .replace("```", "")
                    .trim();
            String lower = cleaned.toLowerCase();
            if (!cleaned.isBlank() && (lower.contains("package ") || lower.contains("system ") || lower.contains("process "))) {
                return cleaned;
            }
        }
        return buildHeuristicAadl(requirementText, template);
    }

    /**
     * 构建两步生成的第一步 prompt：将自然语言需求整理为架构需求清单。
     */
    private void logLlmPrompt(String label, List<Map<String, String>> messages) {
        System.out.println("===== " + label + " =====");
        for (Map<String, String> message : messages) {
            System.out.println("[" + message.getOrDefault("role", "unknown") + "]");
            System.out.println(message.getOrDefault("content", ""));
            System.out.println();
        }
        System.out.println("===== end " + label + " =====");
    }

    private String buildArchitectureRequirementTemplatePrompt(String requirementText) {
        return """
           你是AADL模型建模专家。请先参考下面的架构需求模板，将用户提供的需求填充为同样结构的架构需求。

           模板：
           {系统名称}：
           架构需求：
           1. {系统}管理器
           2. {系统}模块
           (1) {模块}管理器
           (2) 子系统/功能模块：{子系统1}、{子系统2}、..
           (3) 软件分区：{分区1}、{分区2}、... 仅当需求明确提到分区/进程/运行分区时填写
           (4) {系统}网络接口
           (5) {系统}处理器：{分区1名称}运行、{分区2名称}运行、{分区3名称}运行、{分区4名称}运行、{网络接口}运行
           (6) {系统}存储器：{分区1名称}内存、{分区2名称}内存、{分区3名称}内存、{分区4名称}内存、{网络接口}内存
           3. 设备：{设备1}、{设备2}、{设备3}、{设备4}、{设备5}、{设备6}、…
           4. 总线：{总线数量}个{总线类型}总线
           5. 数据：{数据项1}、{数据项2}、{数据项3}、…
           6. 程序
           (1) 模块与设备：初始化程序、运行程序、终止程序
           (2) 分区与网络接口：冷启动程序、热启动程序、正常程序、空闲程序
           (3) 任务：{任务1程序}、{任务2程序}、{任务3程序}、…

           填充示例（以“自动飞机空速控制系统1(PCIE)”为例）：
           自动飞机空速控制系统1(PCIE)：
           架构需求：
           1.自动飞机空速控制系统管理器
           2.自动飞机空速控制模块
           (1)自动飞机空速控制模块管理器
           (2)空速分析分区：空速差计算任务
           (3)空速控制分区：空速控制任务
           (4)自动飞机空速控制网络接口
           (5)自动飞机空速控制处理器：空速分析分区运行、空速控制分区运行、自动飞机空速控制网络接口运行
           (6)自动飞机空速控制存储器：空速分析分区内存、空速控制分区内存、自动飞机空速控制网络接口内存
           3.设备：空速传感器、控制面板、油门、升降舵、扰流板、襟翼
           4.总线：6个PCIE总线
           5.数据：当前空速、目标空速、空速差、空速控制指令
           6.程序
           (1)模块与设备：初始化程序、运行程序、终止程序
           (2)分区与网络接口：冷启动程序、热启动程序、正常程序、空闲程序
           (3)任务：空速差计算程序、空速控制程序

           用户需求：
           """ + requirementText + """

           请只输出填充后的架构需求，如果原需求没有提及，就不要填入（填空），最终模板也不需要输出空的部分，不要输出解释、Markdown 代码块或额外内容。
           """;
    }
    /**
     * 构建两步生成的第二步 prompt：要求大模型输出可被 OSATE 解析的完整 AADL。
     */
    private String buildAadlPromptFromArchitectureRequirement(String architectureRequirement) {
        String selectedRules = aadlPromptRuleService.buildRulesFor(architectureRequirement);
        return """
           # 角色
           你是一个精通 SAE AS5506D AADL 标准的架构设计专家。你的任务是依据用户提供的系统需求，生成完全符合 AADL 语法的模型代码。

           # 本次必须遵守的 AADL 语法规则
           """ + selectedRules + """

           # 需求
           """ + architectureRequirement + """

           示例：
           需求：简易空速控制系统(PCIE)，包含空速分析分区、空速控制分区、空速传感器、油门、PCIE总线、当前空速、目标空速、空速差、空速差计算程序、空速控制程序。
           AADL：
           package Automatic_aircraft_airspeed_control_system
           public
             system Automatic_aircraft_airspeed_control_system
             end Automatic_aircraft_airspeed_control_system;

             system implementation Automatic_aircraft_airspeed_control_system.impl
             subcomponents
               Automatic_aircraft_airspeed_control_module : system Automatic_aircraft_airspeed_control_module.impl;
               Airspeed_sensor : device Airspeed_sensor.impl;
               Throttle : device Throttle.impl;
               PCIE_1 : bus PCIE.impl;
             end Automatic_aircraft_airspeed_control_system.impl;

             system Automatic_aircraft_airspeed_control_module
             end Automatic_aircraft_airspeed_control_module;

             system implementation Automatic_aircraft_airspeed_control_module.impl
             subcomponents
               Airspeed_analysis_partition : process Airspeed_analysis_partition.impl;
               Airspeed_control_partition : process Airspeed_control_partition.impl;
             end Automatic_aircraft_airspeed_control_module.impl;

             process Airspeed_analysis_partition
             end Airspeed_analysis_partition;

             process implementation Airspeed_analysis_partition.impl
             subcomponents
               Airspeed_difference_calculation_task : thread Airspeed_difference_calculation_task.impl;
             end Airspeed_analysis_partition.impl;

             thread Airspeed_difference_calculation_task
             end Airspeed_difference_calculation_task;

             thread implementation Airspeed_difference_calculation_task.impl
             end Airspeed_difference_calculation_task.impl;

             device Airspeed_sensor
             end Airspeed_sensor;

             device implementation Airspeed_sensor.impl
             end Airspeed_sensor.impl;

             bus PCIE
             end PCIE;

             bus implementation PCIE.impl
             end PCIE.impl;

             data Current_airspeed
             end Current_airspeed;

             subprogram Airspeed_difference_calculation_program
             end Airspeed_difference_calculation_program;
           end Automatic_aircraft_airspeed_control_system;

           # 输出格式
           只输出合法的 AADL 代码，不要添加解释。使用 AADL 输出该模型代码，命名一定不要输出中文。代码必须包含完整的包、类型声明和实现，确保可被 AADL 工具（如 OSATE）解析。
           """;
    }
    /**
     * 从生成结果中提取 package 和主要组件数量，形成给用户看的简短摘要。
     */
    private String summarizeAadl(String aadl) {
        String text = aadl == null ? "" : aadl;
        String packageName = "未识别";
        Matcher packageMatcher = Pattern.compile("(?im)^\\s*package\\s+([A-Za-z_][A-Za-z0-9_]*)").matcher(text);
        if (packageMatcher.find()) {
            packageName = packageMatcher.group(1);
        }

        int systemCount = countMatches(text, "(?im)^\\s*system\\s+(?!implementation\\b)[A-Za-z_][A-Za-z0-9_]*");
        int processCount = countMatches(text, "(?im)^\\s*process\\s+(?!implementation\\b)[A-Za-z_][A-Za-z0-9_]*");
        int threadCount = countMatches(text, "(?im)^\\s*thread\\s+(?!implementation\\b)[A-Za-z_][A-Za-z0-9_]*");
        int deviceCount = countMatches(text, "(?im)^\\s*device\\s+(?!implementation\\b)[A-Za-z_][A-Za-z0-9_]*");
        int hasConnections = countMatches(text, "(?im)^\\s*connections\\b");

        StringBuilder summary = new StringBuilder();
        summary.append("已生成 AADL 模型（package: ").append(packageName).append("）。");
        summary.append(" 结构概览：");
        summary.append("system ").append(systemCount).append(" 个");
        summary.append("，process ").append(processCount).append(" 个");
        summary.append("，thread ").append(threadCount).append(" 个");
        summary.append("，device ").append(deviceCount).append(" 个");
        if (hasConnections > 0) {
            summary.append("，包含连接定义。");
        } else {
            summary.append("，未检测到连接段。");
        }
        summary.append(" 可在右侧模块面板下载。");
        return summary.toString();
    }

    /**
     * 统计某个正则在文本中的出现次数。
     */
    private int countMatches(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text == null ? "" : text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }


}