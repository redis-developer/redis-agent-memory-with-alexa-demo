package io.redis.devrel.demos.myjarvis.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static io.redis.devrel.demos.myjarvis.helpers.Constants.*;

public class MemoryService {

    private static final Logger logger = LoggerFactory.getLogger(MemoryService.class);
    private static final ObjectMapper objectMapper = createObjectMapper();
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .build();

    private static final String USERS_NAMESPACE = "users";
    private static final String LONG_TERM_MEMORY_NAMESPACE = "long-term-memory";
    private static final String KNOWLEDGE_NAMESPACE = "knowledge-base";
    private static final String MEMORY_TYPE_SEMANTIC = "semantic";

    private final String apiUrl;
    private final String apiKey;
    private final String storeId;

    private MemoryService(String apiUrl, String apiKey, String storeId) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.storeId = storeId;
    }

    private static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
        return mapper;
    }

    // ---------------------------------------------------------------------------
    // URL helpers
    // ---------------------------------------------------------------------------

    private String storeUrl(String path) {
        return apiUrl + "/v1/stores/" + storeId + path;
    }

    /**
     * Hashes an arbitrary user ID to a 64-character hex string (SHA-256).
     * The RAM API enforces a max length of 64 on the ownerId field, but Alexa
     * user/person IDs are much longer than that.
     */
    private static String sanitizeOwnerId(String userId) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hash = digest.digest(userId.getBytes(StandardCharsets.UTF_8));
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
    // Health check
    // ---------------------------------------------------------------------------

    public boolean checkHealth() {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl + "/health"))
                .GET()
                .build();

        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == HttpStatus.SC_OK) {
                var root = objectMapper.readTree(response.body());
                return "healthy".equals(root.path("status").asText());
            }
        } catch (Exception ex) {
            logger.error("Error checking agent memory server health", ex);
        }

        return false;
    }

    // ---------------------------------------------------------------------------
    // User operations
    // ---------------------------------------------------------------------------

    public Optional<String> getUserName(String userId) {
        if (userId == null || userId.isBlank()) {
            logger.warn("Invalid userId provided");
            return Optional.empty();
        }

        var searchRequest = Map.of(
                "text", userId,
                "limit", 1,
                "minScore", 0.0,
                "filter", Map.of(
                        "ownerId", Map.of("eq", sanitizeOwnerId(userId)),
                        "namespace", Map.of("eq", USERS_NAMESPACE)
                )
        );

        try {
            var request = buildJsonRequest(
                    URI.create(storeUrl("/long-term-memory/search")),
                    searchRequest,
                    "POST"
            );
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            logger.info("getUserName search — status: {}, body: {}", response.statusCode(), response.body());

            if (response.statusCode() == HttpStatus.SC_OK) {
                var responseJson = objectMapper.readTree(response.body());
                return Optional.ofNullable(responseJson)
                        .map(node -> node.path("items"))
                        .filter(memories -> !memories.isEmpty() && !memories.isMissingNode())
                        .map(memories -> memories.path(0))
                        .map(firstMemory -> firstMemory.path("text"))
                        .filter(JsonNode::isTextual)
                        .map(JsonNode::asText)
                        .filter(text -> !text.isBlank());
            }
        } catch (Exception ex) {
            logger.error("Error searching for user: {}", userId, ex);
        }

        return Optional.empty();
    }

    public boolean createUser(String userId, String userName) {
        if (!validateUserInput(userId, userName)) {
            logger.warn("Invalid user input: userId={}, userName={}", userId, userName);
            return false;
        }

        var sanitizedUserName = userName.replaceAll("[\\p{Cntrl}]", "");
        var memoryData = Map.of(
                "memories", List.of(Map.of(
                        "id", UUID.randomUUID().toString(),
                        "ownerId", sanitizeOwnerId(userId),
                        "text", sanitizedUserName,
                        "namespace", USERS_NAMESPACE,
                        "memoryType", MEMORY_TYPE_SEMANTIC
                ))
        );

        try {
            var request = buildJsonRequest(
                    URI.create(storeUrl("/long-term-memory")),
                    memoryData,
                    "POST"
            );
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == HttpStatus.SC_OK || response.statusCode() == HttpStatus.SC_CREATED) {
                var created = objectMapper.readTree(response.body()).path("created");
                return created.isArray() && !created.isEmpty();
            }

            logger.warn("Failed to create user, status code: {}", response.statusCode());
        } catch (Exception ex) {
            logger.error("Error saving new user", ex);
        }

        return false;
    }

    private boolean validateUserInput(String userId, String userName) {
        return userId != null && !userId.isBlank()
                && userName != null && !userName.isBlank()
                && userId.length() <= 255
                && userName.length() <= 255;
    }

    // ---------------------------------------------------------------------------
    // Long-term memory operations
    // ---------------------------------------------------------------------------

    public List<String> searchUserMemories(String userId, String memory) {
        var searchRequest = Map.of(
                "text", memory,
                "limit", Integer.parseInt(USER_MEMORIES_SEARCH_LIMIT),
                "filter", Map.of(
                        "ownerId", Map.of("eq", sanitizeOwnerId(userId)),
                        "namespace", Map.of("eq", LONG_TERM_MEMORY_NAMESPACE)
                )
        );

        return extractTexts(executeSearch(searchRequest));
    }

    public boolean createUserMemory(String sessionId, String userId,
                                    String timezone, String memory) {
        var memoryData = Map.of(
                "memories", List.of(Map.of(
                        "id", UUID.randomUUID().toString(),
                        "sessionId", sanitizeOwnerId(sessionId),
                        "ownerId", sanitizeOwnerId(userId),
                        "namespace", LONG_TERM_MEMORY_NAMESPACE,
                        "text", memory,
                        "memoryType", MEMORY_TYPE_SEMANTIC
                ))
        );

        try {
            var request = buildJsonRequest(
                    URI.create(storeUrl("/long-term-memory")),
                    memoryData,
                    "POST"
            );

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == HttpStatus.SC_OK || response.statusCode() == HttpStatus.SC_CREATED) {
                var created = objectMapper.readTree(response.body()).path("created");
                return created.isArray() && !created.isEmpty();
            }

            logger.error("Failed to create user memory — status: {}, body: {}",
                    response.statusCode(), response.body());
        } catch (Exception ex) {
            logger.error("Error saving long-term memory", ex);
        }

        return false;
    }

    public void createKnowledgeBaseEntry(String memory) {
        var sanitizedMemory = Optional.ofNullable(memory)
                .map(m -> m.replaceAll("[\\r\\n]+", " "))
                .map(m -> m.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", ""))
                .orElse("");

        var formattedMemory = "Fact from %s, %s".formatted(Instant.now(), sanitizedMemory);

        var memoryData = Map.of(
                "memories", List.of(Map.of(
                        "id", UUID.randomUUID().toString(),
                        "namespace", KNOWLEDGE_NAMESPACE,
                        "text", formattedMemory,
                        "memoryType", MEMORY_TYPE_SEMANTIC
                ))
        );

        try {
            var request = buildJsonRequest(
                    URI.create(storeUrl("/long-term-memory")),
                    memoryData,
                    "POST"
            );

            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception ex) {
            logger.error("Exception occurred while creating long-term memory", ex);
        }
    }

    public List<String> searchKnowledgeBase(String memory) {
        var searchRequest = Map.of(
                "text", memory,
                "limit", Integer.parseInt(KNOWLEDGE_BASE_SEARCH_LIMIT),
                "filter", Map.of(
                        "namespace", Map.of("eq", KNOWLEDGE_NAMESPACE)
                )
        );

        return extractTexts(executeSearch(searchRequest));
    }

    // ---------------------------------------------------------------------------
    // Session memory operations
    // ---------------------------------------------------------------------------

    /**
     * Fetches all events for the given (already-sanitized) session ID.
     * Returns an empty list when the session does not exist (404) or on error.
     */
    public List<JsonNode> getSessionMemoryEvents(String sanitizedSessionId) {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(storeUrl("/session-memory/" + sanitizedSessionId)))
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .build();

        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == HttpStatus.SC_OK) {
                var events = objectMapper.readTree(response.body()).path("events");
                if (!events.isEmpty() && !events.isMissingNode()) {
                    var result = new ArrayList<JsonNode>();
                    events.forEach(result::add);
                    return result;
                }
            } else if (response.statusCode() != HttpStatus.SC_NOT_FOUND) {
                logger.warn("Unexpected status fetching session memory: {} — body: {}",
                        response.statusCode(), response.body());
            }
        } catch (Exception ex) {
            logger.error("Error fetching session memory for: {}", sanitizedSessionId, ex);
        }

        return List.of();
    }

    /**
     * Appends a single event to the given (already-sanitized) session.
     */
    public void addSessionMemoryEvent(String sanitizedSessionId,
                                      String actorId,
                                      String role,
                                      String text,
                                      long createdAt) {
        var eventBody = Map.of(
                "sessionId", sanitizedSessionId,
                "actorId", actorId,
                "role", role,
                "content", List.of(Map.of("text", text)),
                "createdAt", Instant.ofEpochMilli(createdAt).toString()
        );

        try {
            var requestBody = objectMapper.writeValueAsString(eventBody);
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(storeUrl("/session-memory/events")))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            var status = response.statusCode();

            if (status == HttpStatus.SC_OK || status == HttpStatus.SC_CREATED || status == HttpStatus.SC_NO_CONTENT) {
                logger.debug("Session event added for: {} (status {})", sanitizedSessionId, status);
            } else {
                logger.error("Failed to add session event for: {} — status: {}, body: {}",
                        sanitizedSessionId, status, response.body());
            }
        } catch (Exception ex) {
            logger.error("Error adding session event for: {}", sanitizedSessionId, ex);
        }
    }

    /**
     * Deletes all session memory for the given (already-sanitized) session ID.
     */
    public void deleteSessionMemory(String sanitizedSessionId) {
        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(storeUrl("/session-memory/" + sanitizedSessionId)))
                    .header("Authorization", "Bearer " + apiKey)
                    .DELETE()
                    .timeout(Duration.ofSeconds(3))
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == HttpStatus.SC_NO_CONTENT) {
                logger.info("Successfully deleted session memory for: {}", sanitizedSessionId);
            } else if (response.statusCode() == HttpStatus.SC_NOT_FOUND) {
                logger.warn("Session memory not found for: {}", sanitizedSessionId);
            } else {
                logger.error("Failed to delete session memory. Status: {}", response.statusCode());
            }
        } catch (Exception ex) {
            logger.error("Error deleting session memory for: {}", sanitizedSessionId, ex);
        }
    }

    // ---------------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------------

    private HttpRequest buildJsonRequest(URI uri, Object body, String method) {
        try {
            var requestBuilder = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey);

            var bodyPublisher = body != null
                    ? HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body))
                    : HttpRequest.BodyPublishers.noBody();

            return switch (method) {
                case "POST" -> requestBuilder.POST(bodyPublisher).build();
                case "DELETE" -> requestBuilder.DELETE().build();
                default -> throw new IllegalArgumentException("Unsupported method: " + method);
            };
        } catch (Exception e) {
            throw new RuntimeException("Failed to build request", e);
        }
    }

    private List<JsonNode> executeSearch(Map<String, Object> searchRequest) {
        try {
            var request = buildJsonRequest(
                    URI.create(storeUrl("/long-term-memory/search")),
                    searchRequest,
                    "POST"
            );

            logger.debug("Executing search request: {}", request);
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            logger.debug("Search response status: {}", response.statusCode());

            if (response.statusCode() == HttpStatus.SC_OK) {
                var memories = objectMapper.readTree(response.body()).path("items");
                if (!memories.isEmpty()) {
                    var result = new ArrayList<JsonNode>();
                    memories.forEach(result::add);
                    logger.debug("Number of memories returned: {}", memories.size());
                    return result;
                }
            }
        } catch (Exception ex) {
            logger.error("Error during memory search", ex);
        }

        return List.of();
    }

    private List<String> extractTexts(List<JsonNode> nodes) {
        return nodes.stream()
                .map(node -> node.path("text").asText())
                .filter(text -> !text.isEmpty())
                .toList();
    }

    // ---------------------------------------------------------------------------
    // Builder
    // ---------------------------------------------------------------------------

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String apiUrl;
        private String apiKey;
        private String storeId;

        public Builder apiUrl(String value) {
            this.apiUrl = value;
            return this;
        }

        public Builder apiKey(String value) {
            this.apiKey = value;
            return this;
        }

        public Builder storeId(String value) {
            this.storeId = value;
            return this;
        }

        public MemoryService build() {
            Objects.requireNonNull(apiUrl, "apiUrl is required");
            Objects.requireNonNull(apiKey, "apiKey is required");
            Objects.requireNonNull(storeId, "storeId is required");
            return new MemoryService(apiUrl, apiKey, storeId);
        }
    }
}
