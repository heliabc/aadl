package com.nuaa.aadl.module.demo;

import com.nuaa.aadl.chat.ChatSendRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
@RequestMapping("/api/demo/cases/{caseId}/aadl")
public class DemoAadlController {

    private final DemoAadlReplayService demoAadlReplayService;

    public DemoAadlController(DemoAadlReplayService demoAadlReplayService) {
        this.demoAadlReplayService = demoAadlReplayService;
    }

    @PostMapping(path = "/generate/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generateStream(@PathVariable String caseId,
                                     @RequestBody Map<String, String> payload,
                                     HttpServletResponse response) {
        String conversationId = payload.get("conversationId");
        String requirementText = payload.getOrDefault("requirementText", "");
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId cannot be empty");
        }

        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no");

        SseEmitter emitter = new SseEmitter(600_000L);
        demoAadlReplayService.replay(caseId, conversationId, requirementText, emitter);
        return emitter;
    }

    @PostMapping(path = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@PathVariable String caseId,
                                 @RequestBody ChatSendRequest request,
                                 HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no");

        SseEmitter emitter = new SseEmitter(600_000L);
        demoAadlReplayService.replayBusEdit(caseId, request.conversationId(), request.message(), emitter);
        return emitter;
    }
}
