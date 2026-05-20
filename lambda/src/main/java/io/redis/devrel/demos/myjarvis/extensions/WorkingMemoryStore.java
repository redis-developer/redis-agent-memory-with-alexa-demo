package io.redis.devrel.demos.myjarvis.extensions;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.data.message.*;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.redis.devrel.demos.myjarvis.services.MemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static io.redis.devrel.demos.myjarvis.helpers.MessageHelper.messageContent;

public class WorkingMemoryStore implements ChatMemoryStore {

    private static final Logger logger = LoggerFactory.getLogger(WorkingMemoryStore.class);

    private MemoryService memoryService;
    private boolean storeSystemMessages = false;
    private boolean storeAiMessages = false;
    private boolean storeToolMessages = false;
    private int maxContextWindow = 1000;

    // Tracks how many messages were already in the store when getMessages was last called,
    // so updateMessages can POST only the new delta rather than the full list.
    private int lastFetchedCount = 0;

    /**
     * Hashes an arbitrary session/actor ID to a 64-character hex string (SHA-256).
     * The RAM API enforces a max length of 64 on session IDs and actor IDs, but
     * Alexa session/person IDs are much longer than that.
     */
    private static String sanitizeSessionId(String sessionId) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hash = digest.digest(sessionId.getBytes(StandardCharsets.UTF_8));
            var sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString(); // exactly 64 hex chars
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    // ---------------------------------------------------------------------------
    // ChatMemoryStore implementation
    // ---------------------------------------------------------------------------

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        var sanitizedId = sanitizeSessionId(memoryId.toString());
        var chatMessages = new ArrayList<ChatMessage>();

        for (JsonNode event : memoryService.getSessionMemoryEvents(sanitizedId)) {
            var role = event.path("role").asText("");
            var contentArray = event.path("content");
            var text = contentArray.isArray() && !contentArray.isEmpty()
                    ? contentArray.path(0).path("text").asText("")
                    : "";

            if (text.isBlank()) continue;

            if (!storeSystemMessages && "SYSTEM".equalsIgnoreCase(role)) continue;
            if (!storeAiMessages && "ASSISTANT".equalsIgnoreCase(role)) continue;
            if (!storeToolMessages && "TOOL".equalsIgnoreCase(role)) continue;

            ChatMessage chatMessage = switch (role.toUpperCase()) {
                case "USER" -> UserMessage.from(text);
                case "ASSISTANT" -> AiMessage.from(text);
                case "SYSTEM" -> SystemMessage.from(text);
                default -> {
                    logger.warn("Unknown message role: {}", role);
                    yield null;
                }
            };

            if (chatMessage != null) {
                chatMessages.add(chatMessage);
            }
        }

        // Apply context window limit
        var trimmed = chatMessages.size() > maxContextWindow
                ? chatMessages.subList(chatMessages.size() - maxContextWindow, chatMessages.size())
                : chatMessages;

        lastFetchedCount = trimmed.size();
        return new ArrayList<>(trimmed);
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> list) {
        var sanitizedId = sanitizeSessionId(memoryId.toString());

        // Only send messages that are new since the last getMessages call
        var newMessages = list.size() > lastFetchedCount
                ? list.subList(lastFetchedCount, list.size())
                : List.<ChatMessage>of();

        for (var message : newMessages) {
            if (!storeSystemMessages && message instanceof SystemMessage) continue;
            if (!storeAiMessages && message instanceof AiMessage) continue;
            if (!storeToolMessages && message instanceof ToolExecutionResultMessage) continue;

            String role = switch (message) {
                case UserMessage ignored -> "USER";
                case AiMessage ignored -> "ASSISTANT";
                case SystemMessage ignored -> "SYSTEM";
                default -> null;
            };

            if (role == null) continue;

            String actorId = "USER".equals(role) ? sanitizeSessionId(memoryId.toString()) : "assistant";
            String text = messageContent(message);
            if (text == null || text.isBlank()) continue;

            memoryService.addSessionMemoryEvent(sanitizedId, actorId, role, text, System.currentTimeMillis());
        }

        lastFetchedCount = list.size();
    }

    @Override
    public void deleteMessages(Object memoryId) {
        var sanitizedId = sanitizeSessionId(memoryId.toString());
        memoryService.deleteSessionMemory(sanitizedId);
    }

    // ---------------------------------------------------------------------------
    // Getters / setters
    // ---------------------------------------------------------------------------

    public boolean isStoreSystemMessages() { return storeSystemMessages; }
    public void setStoreSystemMessages(boolean v) { this.storeSystemMessages = v; }

    public boolean isStoreAiMessages() { return storeAiMessages; }
    public void setStoreAiMessages(boolean v) { this.storeAiMessages = v; }

    public boolean isStoreToolMessages() { return storeToolMessages; }
    public void setStoreToolMessages(boolean v) { this.storeToolMessages = v; }

    public int getMaxContextWindow() { return maxContextWindow; }
    public void setMaxContextWindow(int v) { this.maxContextWindow = v; }

    // ---------------------------------------------------------------------------
    // Builder
    // ---------------------------------------------------------------------------

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private MemoryService memoryService;
        private Optional<Boolean> storeSystemMessages = Optional.empty();
        private Optional<Boolean> storeAiMessages = Optional.empty();
        private Optional<Boolean> storeToolMessages = Optional.empty();
        private Optional<Integer> maxContextWindow = Optional.empty();

        public Builder memoryService(MemoryService value) {
            this.memoryService = value;
            return this;
        }

        public Builder storeSystemMessages(boolean value) {
            this.storeSystemMessages = Optional.of(value);
            return this;
        }

        public Builder storeAiMessages(boolean value) {
            this.storeAiMessages = Optional.of(value);
            return this;
        }

        public Builder storeToolMessages(boolean value) {
            this.storeToolMessages = Optional.of(value);
            return this;
        }

        public Builder maxContextWindow(int value) {
            this.maxContextWindow = Optional.of(value);
            return this;
        }

        public WorkingMemoryStore build() {
            java.util.Objects.requireNonNull(memoryService, "memoryService is required");
            var store = new WorkingMemoryStore();
            store.memoryService = this.memoryService;
            storeSystemMessages.ifPresent(store::setStoreSystemMessages);
            storeAiMessages.ifPresent(store::setStoreAiMessages);
            storeToolMessages.ifPresent(store::setStoreToolMessages);
            maxContextWindow.ifPresent(store::setMaxContextWindow);
            return store;
        }
    }
}
