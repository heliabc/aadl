package com.nuaa.aadl.module.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nuaa.aadl.shared.doc.ModuleDocRepository;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DemoAadlReplayService {

    private static final Pattern SECTION_PATTERN = Pattern.compile(
            "<!--\\s*%s\\s*-->([\\s\\S]*?)(?=<!--\\s*[A-Z0-9_]+\\s*-->|\\z)"
    );

    private final ModuleDocRepository moduleDocRepository;
    private final ObjectMapper objectMapper;

    public DemoAadlReplayService(ModuleDocRepository moduleDocRepository, ObjectMapper objectMapper) {
        this.moduleDocRepository = moduleDocRepository;
        this.objectMapper = objectMapper;
    }

    public void replay(String caseId, String conversationId, String requirementText, SseEmitter emitter) {
        AtomicBoolean streamClosed = new AtomicBoolean(false);
        emitter.onCompletion(() -> streamClosed.set(true));
        emitter.onTimeout(() -> streamClosed.set(true));
        emitter.onError(error -> streamClosed.set(true));

        CompletableFuture.runAsync(() -> {
            try {
                DemoAadlCase demoCase = loadCase(caseId);

                if (!send(emitter, streamClosed, "status", Map.of("stage", "step1_template_started"))) return;
                if (!streamText(emitter, streamClosed, "think", demoCase.step1Think())) return;

                if (!send(emitter, streamClosed, "status", Map.of("stage", "step2_aadl_started"))) return;
                if (!streamText(emitter, streamClosed, "think", "\n\n" + demoCase.step2Think())) return;
                if (!send(emitter, streamClosed, "delta", Map.of("text", "\n```aadl\n"))) return;
                if (!streamText(emitter, streamClosed, "delta", demoCase.aadlModel())) return;
                if (!send(emitter, streamClosed, "delta", Map.of("text", "\n```\n"))) return;

                String reply = "```aadl\n"
                        + demoCase.aadlModel().trim()
                        + "\n```";
                persistDocument(conversationId, demoCase.aadlModel(), requirementText, demoCase);
                if (send(emitter, streamClosed, "done", Map.of("reply", reply, "aadlModel", demoCase.aadlModel()))) {
                    complete(emitter, streamClosed);
                }
            } catch (Exception e) {
                send(emitter, streamClosed, "error", Map.of("message", e.getMessage() == null ? "demo_replay_failed" : e.getMessage()));
                completeWithError(emitter, streamClosed, e);
            }
        });
    }

    public void replayBusEdit(String caseId, String conversationId, String userMessage, SseEmitter emitter) {
        AtomicBoolean streamClosed = new AtomicBoolean(false);
        emitter.onCompletion(() -> streamClosed.set(true));
        emitter.onTimeout(() -> streamClosed.set(true));
        emitter.onError(error -> streamClosed.set(true));

        CompletableFuture.runAsync(() -> {
            try {
                boolean isParse = userMessage != null && userMessage.contains("解析");
                if (isParse) {
                    replayParseCase(caseId, conversationId, userMessage, emitter, streamClosed);
                } else {
                    DemoAadlEditCase demoCase = loadEditCase(caseId, "aadl-chat-bus.md");

                    if (!send(emitter, streamClosed, "status", Map.of("stage", "started"))) return;
                    if (!streamText(emitter, streamClosed, "think", demoCase.think())) return;
                    if (!send(emitter, streamClosed, "delta", Map.of("text", "\n```aadl\n"))) return;
                    if (!streamText(emitter, streamClosed, "delta", demoCase.aadlModel())) return;
                    if (!send(emitter, streamClosed, "delta", Map.of("text", "\n```\n"))) return;

                    String reply = "```aadl\n"
                            + demoCase.aadlModel().trim()
                            + "\n```";
                    persistDocument(conversationId, demoCase.aadlModel(), userMessage, "demo_edit_think:\n" + demoCase.think());
                    if (send(emitter, streamClosed, "done", Map.of("reply", reply, "moduleData", Map.of(), "aadlModel", demoCase.aadlModel()))) {
                        complete(emitter, streamClosed);
                    }
                }
            } catch (Exception e) {
                send(emitter, streamClosed, "error", Map.of("message", e.getMessage() == null ? "demo_aadl_edit_failed" : e.getMessage()));
                completeWithError(emitter, streamClosed, e);
            }
        });
    }

    private void replayParseCase(String caseId, String conversationId, String userMessage,
                                  SseEmitter emitter, AtomicBoolean streamClosed) throws IOException {
        String safeCaseId = caseId == null || caseId.isBlank() ? "case-001" : caseId;
        ClassPathResource resource = new ClassPathResource("demo/" + safeCaseId + "/aadl-parse.md");
        if (!resource.exists()) {
            throw new IllegalArgumentException("Demo AADL parse case not found: " + safeCaseId + "/aadl-parse.md");
        }
        String markdown = resource.getContentAsString(StandardCharsets.UTF_8);
        String think = section(markdown, "THINK");
        String reply = section(markdown, "REPLY");

        if (!send(emitter, streamClosed, "status", Map.of("stage", "started"))) return;
        if (!streamText(emitter, streamClosed, "think", think)) return;
        if (!streamText(emitter, streamClosed, "delta", reply)) return;

        persistDocument(conversationId, "", userMessage, "demo_parse_think:\n" + think + "\n\ndemo_parse_reply:\n" + reply);
        if (send(emitter, streamClosed, "done", Map.of("reply", reply, "moduleData", Map.of()))) {
            complete(emitter, streamClosed);
        }
    }

    private DemoAadlCase loadCase(String caseId) throws IOException {
        String safeCaseId = caseId == null || caseId.isBlank() ? "case-001" : caseId;
        ClassPathResource resource = new ClassPathResource("demo/" + safeCaseId + "/aadl-generate.md");
        if (!resource.exists()) {
            throw new IllegalArgumentException("Demo case not found: " + safeCaseId);
        }
        String markdown = resource.getContentAsString(StandardCharsets.UTF_8);
        return new DemoAadlCase(
                section(markdown, "STEP1_THINK"),
                section(markdown, "STEP1_TEMPLATE"),
                section(markdown, "STEP2_THINK"),
                section(markdown, "AADL_MODEL")
        );
    }

    private DemoAadlEditCase loadEditCase(String caseId, String fileName) throws IOException {
        String safeCaseId = caseId == null || caseId.isBlank() ? "case-001" : caseId;
        ClassPathResource resource = new ClassPathResource("demo/" + safeCaseId + "/" + fileName);
        if (!resource.exists()) {
            throw new IllegalArgumentException("Demo AADL edit case not found: " + safeCaseId + "/" + fileName);
        }
        String markdown = resource.getContentAsString(StandardCharsets.UTF_8);
        return new DemoAadlEditCase(
                section(markdown, "THINK"),
                section(markdown, "AADL_MODEL")
        );
    }

    private String section(String markdown, String marker) {
        Matcher matcher = Pattern.compile(String.format(SECTION_PATTERN.pattern(), marker)).matcher(markdown);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Missing demo marker: " + marker);
        }
        return matcher.group(1).strip();
    }

    private boolean streamText(SseEmitter emitter, AtomicBoolean streamClosed, String eventName, String text) {
        return DemoStreamSupport.streamText(this::send, emitter, streamClosed, eventName, text);
    }

    private boolean send(SseEmitter emitter, AtomicBoolean streamClosed, String eventName, Object data) {
        if (streamClosed.get()) return false;
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
            return true;
        } catch (IOException | IllegalStateException e) {
            streamClosed.set(true);
            return false;
        }
    }

    private void persistDocument(String conversationId, String aadlModel, String requirementText, DemoAadlCase demoCase) {
        persistDocument(conversationId, aadlModel, requirementText, "demo_step1_template:\n" + demoCase.step1Template()
                + "\n\nstep1_think:\n" + demoCase.step1Think()
                + "\n\nstep2_think:\n" + demoCase.step2Think());
    }

    private void persistDocument(String conversationId, String aadlModel, String prompt, String rawReply) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", aadlModel);
        payload.put("prompt", prompt);
        payload.put("attachmentContext", "");
        payload.put("rawReply", rawReply);
        try {
            moduleDocRepository.writeModuleDoc(conversationId, "aadl", "model", objectMapper.writeValueAsString(payload));
        } catch (IOException e) {
            throw new IllegalStateException("Demo AADL model save failed", e);
        }
    }

    private void complete(SseEmitter emitter, AtomicBoolean streamClosed) {
        if (streamClosed.compareAndSet(false, true)) {
            emitter.complete();
        }
    }

    private void completeWithError(SseEmitter emitter, AtomicBoolean streamClosed, Exception error) {
        if (streamClosed.compareAndSet(false, true)) {
            emitter.completeWithError(error);
        }
    }

    private record DemoAadlCase(String step1Think, String step1Template, String step2Think, String aadlModel) {
    }

    private record DemoAadlEditCase(String think, String aadlModel) {
    }
}
