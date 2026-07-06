package com.nuaa.aadl.chat;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class SqliteChatMemoryStore implements ChatMemoryStore {

    private final MessageRepository messageRepository;

    public SqliteChatMemoryStore(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        return messageRepository.getMessages(memoryId.toString())
            .stream()
            .map(this::toChatMessage)
            .collect(Collectors.toList());
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        messageRepository.clearMessages(memoryId.toString());
        for (ChatMessage msg : messages) {
            String role = getRole(msg);
            String content = getContent(msg);
            if (content != null && !content.isEmpty()) {
                messageRepository.appendMessage(memoryId.toString(), role, "memory", content);
            }
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        messageRepository.clearMessages(memoryId.toString());
    }

    private String getRole(ChatMessage msg) {
        if (msg instanceof UserMessage) return "user";
        if (msg instanceof AiMessage) return "assistant";
        if (msg instanceof SystemMessage) return "system";
        return "user";
    }

    private String getContent(ChatMessage msg) {
        if (msg instanceof UserMessage u) return u.singleText();
        if (msg instanceof AiMessage a) return a.text();
        if (msg instanceof SystemMessage s) return s.text();
        return "";
    }

    private ChatMessage toChatMessage(MessageRepository.MessageRecord record) {
        return switch (record.role()) {
            case "user" -> UserMessage.userMessage(record.content());
            case "assistant" -> AiMessage.aiMessage(record.content());
            case "system" -> SystemMessage.systemMessage(record.content());
            default -> UserMessage.userMessage(record.content());
        };
    }
}
