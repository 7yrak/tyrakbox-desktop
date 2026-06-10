package com.tyrak.box.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public final class SessionStore {
    private static final Path SESSION_PATH = Path.of(System.getProperty("user.home"), ".tyrakbox-desktop-session.json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SessionStore() {
    }

    public static Optional<SessionData> load() {
        if (!Files.exists(SESSION_PATH)) {
            return Optional.empty();
        }
        try {
            JsonNode node = MAPPER.readTree(Files.readString(SESSION_PATH, StandardCharsets.UTF_8));
            String serverUrl = node.path("serverUrl").asText("");
            String token = node.path("token").asText("");
            String username = node.path("username").asText("");
            String userId = node.path("userId").asText("");
            if (token.isBlank() || username.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new SessionData(serverUrl, token, username, userId));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public static void save(String serverUrl, String token, String username, String userId) {
        try {
            var root = MAPPER.createObjectNode();
            root.put("serverUrl", serverUrl != null ? serverUrl : "");
            root.put("token", token != null ? token : "");
            root.put("username", username != null ? username : "");
            root.put("userId", userId != null ? userId : "");
            Files.writeString(SESSION_PATH, MAPPER.writeValueAsString(root), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    public static void clear() {
        try {
            Files.deleteIfExists(SESSION_PATH);
        } catch (IOException ignored) {
        }
    }

    public record SessionData(String serverUrl, String token, String username, String userId) {
    }
}
