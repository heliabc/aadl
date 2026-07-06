package com.nuaa.aadl.chat;

import com.nuaa.aadl.module.aadl.AadlModelService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final AadlModelService aadlModelService;

    public ChatController(ChatService chatService, AadlModelService aadlModelService) {
        this.chatService = chatService;
        this.aadlModelService = aadlModelService;
    }

  @PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(@Valid @RequestBody ChatSendRequest request, HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-cache, no-transform");
    response.setHeader("Connection", "keep-alive");
    response.setHeader("X-Accel-Buffering", "no");

    SseEmitter emitter = new SseEmitter(1200_000L);

    if ("aadl".equals(request.module())) {
      aadlModelService.generateAadlStream(request, emitter);
      return emitter;
    }

    if ("evaluation".equals(request.module())) {
      chatService.streamGeneral(request, emitter);
      return emitter;
    }

    try {
      chatService.streamGeneral(request, emitter);
    } catch (Exception e) {
      emitter.completeWithError(e);
    }
    return emitter;
  }
}
