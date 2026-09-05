package com.epic60869.tastyfish;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

public final class FarmingUploader {
    private static final Gson GSON = new Gson();
    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    public void upload(TastyFishConfig config, String username, String uuid, String profile, String sessionId,
                       SkysoftSessionReader.Snapshot snapshot) {
        if (!config.enabled || config.endpoint.isBlank() || config.apiKey.isBlank() ||
            config.apiKey.equals("PUT_YOUR_FARMING_API_KEY_HERE")) {
            return;
        }

        if (username == null || username.isBlank() || uuid == null || uuid.isBlank() || sessionId == null || sessionId.isBlank()) {
            System.err.println("[TastyFish] Farming upload skipped: missing username, UUID, or session ID.");
            return;
        }

        JsonObject root = new JsonObject();
        root.addProperty("username", username);
        root.addProperty("uuid", uuid);
        root.addProperty("profile", profile == null ? "" : profile);
        root.addProperty("preset", "FARMING");
        root.addProperty("profit", snapshot.profit());
        root.addProperty("activeMillis", snapshot.activeMillis());
        root.addProperty("actions", snapshot.actions());
        root.addProperty("sessionId", sessionId);
        root.add("items", GSON.toJsonTree(snapshot.items()));
        root.add("pests", GSON.toJsonTree(snapshot.pests()));

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(config.endpoint))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("X-Farming-API-Key", config.apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(root)))
            .build();

        client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenAccept(response -> {
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    System.out.println("[TastyFish] Farming update uploaded: " + response.body());
                } else {
                    System.err.println("[TastyFish] Farming upload failed: HTTP " + response.statusCode() + ": " + response.body());
                }
            })
            .exceptionally(error -> {
                System.err.println("[TastyFish] Farming upload failed: " + rootMessage(error));
                return null;
            });
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }

    public static String newSessionId() {
        return UUID.randomUUID().toString();
    }
}
