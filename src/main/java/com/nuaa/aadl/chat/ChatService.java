package com.nuaa.aadl.chat;

import com.nuaa.aadl.module.aadl.AadlModelService;
import com.nuaa.aadl.module.rag.langchain.LangChain4jRagService;
import com.nuaa.aadl.shared.file.FileService;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class ChatService {

  private static final int CHAT_HISTORY_LIMIT = 20;
  private static final int CONTEXT_HISTORY_LIMIT = 10;

  private final MessageRepository messageRepository;
  private final AadlModelService aadlModelService;
  private final FileService fileService;
  private final LlmClientService llmClientService;
  private final ChatMemoryService chatMemoryService;
  private final LangChain4jRagService ragService;

  public ChatService(
    MessageRepository messageRepository,
    AadlModelService aadlModelService,
    FileService fileService,
    LlmClientService llmClientService,
    ChatMemoryService chatMemoryService,
    LangChain4jRagService ragService
  ) {
    this.messageRepository = messageRepository;
    this.aadlModelService = aadlModelService;
    this.fileService = fileService;
    this.llmClientService = llmClientService;
    this.chatMemoryService = chatMemoryService;
    this.ragService = ragService;
  }

  public void streamGeneral(ChatSendRequest request, SseEmitter emitter) {
    String attachmentContext = fileService.buildAttachmentContext(request.attachmentIds());
    String ragContext = searchRag(request.message());
    if (!ragContext.isEmpty()) {
      attachmentContext = attachmentContext + "\n\n[Relevant Knowledge Base]\n" + ragContext;
    }

    List<Map<String, String>> messageList = buildConversationMessages(
      request.conversationId(),
      "",
      request.message(),
      CHAT_HISTORY_LIMIT
    );

    messageRepository.appendMessage(request.conversationId(), "user", request.module(), request.message());

    StringBuilder collected = new StringBuilder();
    StringBuilder thinkCollected = new StringBuilder();
    AtomicBoolean streamClosed = new AtomicBoolean(false);

    try {
      emitter.send(SseEmitter.event().name("status").data(Map.of("stage", "started")));
    } catch (IOException e) {
      emitter.completeWithError(e);
      return;
    }

    llmClientService.chatStream(
      messageList,
      delta -> {
        if (streamClosed.get()) {
          return;
        }
        collected.append(delta);
        try {
          emitter.send(SseEmitter.event().name("delta").data(Map.of("text", delta)));
        } catch (IOException e) {
          streamClosed.set(true);
          emitter.completeWithError(e);
        }
      },
      think -> {
        if (streamClosed.get()) {
          return;
        }
        thinkCollected.append(think);
        try {
          emitter.send(SseEmitter.event().name("think").data(Map.of("text", think)));
        } catch (IOException e) {
          streamClosed.set(true);
          emitter.completeWithError(e);
        }
      },
      () -> {
        if (streamClosed.get()) {
          return;
        }
        String finalReply = collected.toString().isBlank() ? "已完成。" : collected.toString().trim();
        messageRepository.appendMessage(request.conversationId(), "assistant", request.module(), finalReply);
        try {
          emitter.send(SseEmitter.event().name("done").data(Map.of("reply", finalReply, "moduleData", Map.of())));
          emitter.complete();
        } catch (IOException e) {
          emitter.completeWithError(e);
        }
      },
      error -> {
        if (streamClosed.get()) {
          return;
        }
        String fallback = "本地模型调用失败: " + error.getMessage();
        messageRepository.appendMessage(request.conversationId(), "assistant", request.module(), fallback);
        try {
          emitter.send(SseEmitter.event().name("error").data(Map.of("message", error.getMessage() == null ? "stream_failed" : error.getMessage())));
          emitter.send(SseEmitter.event().name("done").data(Map.of("reply", fallback, "moduleData", Map.of())));
          emitter.complete();
        } catch (IOException e) {
          emitter.completeWithError(e);
        }
      }
    );
  }

  private String searchRag(String message) {
    try {
      return ragService.searchAsString(message, 5);
    } catch (Exception e) {
      System.out.println("!!! [ChatService] RAG search failed: " + e.getMessage());
      return "";
    }
  }

  private List<Map<String, String>> buildConversationMessages(
    String conversationId,
    String systemPrompt,
    String currentUserPrompt,
    int historyLimit
  ) {
    List<Map<String, String>> messages = new ArrayList<>();
    messages.add(Map.of("role", "system", "content", systemPrompt));
    for (MessageRepository.MessageRecord record : messageRepository.getRecentMessages(conversationId, historyLimit)) {
      if (!"user".equals(record.role()) && !"assistant".equals(record.role())) {
        continue;
      }
      String module = record.module() == null || record.module().isBlank() ? "" : "[" + record.module() + "] ";
      messages.add(Map.of("role", record.role(), "content", module + record.content()));
    }
    messages.add(Map.of("role", "user", "content", currentUserPrompt));
    return messages;
  }

  private String buildHistoryText(String conversationId, int limit) {
    StringBuilder context = new StringBuilder();
    for (MessageRepository.MessageRecord record : messageRepository.getRecentMessages(conversationId, limit)) {
      if (!"user".equals(record.role()) && !"assistant".equals(record.role())) {
        continue;
      }
      if (context.isEmpty()) {
        context.append("[Recent Conversation History]\n");
      }
      String module = record.module() == null || record.module().isBlank() ? "general" : record.module();
      context.append(record.role()).append("[").append(module).append("]: ")
        .append(truncate(record.content(), 1200))
        .append("\n");
    }
    return context.toString().trim();
  }

  private String truncate(String text, int maxChars) {
    if (text == null) {
      return "";
    }
    if (text.length() <= maxChars) {
      return text;
    }
    return text.substring(0, maxChars) + "\n...[truncated]";
  }
}