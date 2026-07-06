package com.nuaa.aadl.chat;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ChatMemoryService {

    private final Map<String, ChatMemory> memoryStore = new ConcurrentHashMap<>();
    private final SqliteChatMemoryStore chatMemoryStore;
    private final int maxMessages;

    public ChatMemoryService(
            SqliteChatMemoryStore chatMemoryStore,
            @Value("${chat.memory.max-messages:20}") int maxMessages) {
        this.chatMemoryStore = chatMemoryStore;
        this.maxMessages = maxMessages;
    }

    public ChatMemory getMemory(String conversationId) {
        return memoryStore.computeIfAbsent(conversationId, id ->
            MessageWindowChatMemory.builder()
                .id(id)
                .maxMessages(maxMessages)
                .chatMemoryStore(chatMemoryStore)
                .build()
        );
    }

    public void clearMemory(String conversationId) {
        memoryStore.remove(conversationId);
        chatMemoryStore.deleteMessages(conversationId);
    }
}
