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

        JsonObject root = new JsonObject();
        root.addProperty("username", username);
        root.addProperty("uuid", uuid);
        root.addProperty("profile", profile);
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

        client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
            .exceptionally(error -> {
                System.err.println("[TastyFish] Farming upload failed: " + error.getMessage());
                return null;
            });
    }

    public static String newSessionId() {
        return UUID.randomUUID().toString();
    }
}
