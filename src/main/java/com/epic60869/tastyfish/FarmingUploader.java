package com.epic60869.tastyfish;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FarmingUploader {
    private static final Gson GSON = new Gson();
    private static final String MOD_VERSION = "1.0.0";

    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private final AtomicBoolean authInFlight = new AtomicBoolean(false);

    private volatile String accessToken = null;
    private volatile long tokenExpiresAtMillis = 0L;

    public void upload(TastyFishConfig config, String username, String uuid, String profile, String sessionId,
                       SkysoftSessionReader.Snapshot snapshot) {
        if (!config.enabled || config.authEndpoint.isBlank() || config.updateEndpoint.isBlank()) return;

        if (!hasUsableToken()) {
            authenticate(config, username, uuid, sessionId,
                () -> sendUpdate(config, username, uuid, profile, sessionId, snapshot));
            return;
        }

        sendUpdate(config, username, uuid, profile, sessionId, snapshot);
    }

    private boolean hasUsableToken() {
        // Refresh a little before expiry so an upload does not race the expiry boundary.
        return accessToken != null && System.currentTimeMillis() + 60_000L < tokenExpiresAtMillis;
    }

    private void authenticate(TastyFishConfig config, String username, String uuid, String sessionId, Runnable afterAuth) {
        if (!authInFlight.compareAndSet(false, true)) return;

        JsonObject body = new JsonObject();
        body.addProperty("username", username);
        body.addProperty("uuid", uuid);
        body.addProperty("sessionId", sessionId);
        body.addProperty("modVersion", MOD_VERSION);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(config.authEndpoint))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
            .build();

        client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenAccept(response -> {
                try {
                    if (response.statusCode() == 429) {
                        System.err.println("[TastyFish] Farming authentication rate limited.");
                        return;
                    }
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        System.err.println("[TastyFish] Farming authentication failed (HTTP " + response.statusCode() + ").");
                        return;
                    }

                    JsonObject json = GSON.fromJson(response.body(), JsonObject.class);
                    String token = json.get("token").getAsString();
                    long expiresIn = json.has("expiresIn") ? json.get("expiresIn").getAsLong() : 1800L;

                    accessToken = token;
                    tokenExpiresAtMillis = System.currentTimeMillis() + Math.max(60L, expiresIn) * 1000L;
                    afterAuth.run();
                } catch (Exception e) {
                    System.err.println("[TastyFish] Invalid farming authentication response: " + e.getMessage());
                } finally {
                    authInFlight.set(false);
                }
            })
            .exceptionally(error -> {
                authInFlight.set(false);
                System.err.println("[TastyFish] Farming authentication failed: " + error.getMessage());
                return null;
            });
    }

    private void sendUpdate(TastyFishConfig config, String username, String uuid, String profile, String sessionId,
                            SkysoftSessionReader.Snapshot snapshot) {
        String token = accessToken;
        if (token == null) return;

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
            .uri(URI.create(config.updateEndpoint))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(root)))
            .build();

        client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenAccept(response -> {
                if (response.statusCode() == 401) {
                    accessToken = null;
                    tokenExpiresAtMillis = 0L;
                    return;
                }
                if (response.statusCode() == 429) {
                    System.err.println("[TastyFish] Farming upload rate limited.");
                    return;
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    System.err.println("[TastyFish] Farming upload failed (HTTP " + response.statusCode() + ").");
                }
            })
            .exceptionally(error -> {
                System.err.println("[TastyFish] Farming upload failed: " + error.getMessage());
                return null;
            });
    }

    public static String newSessionId() {
        return UUID.randomUUID().toString();
    }
}
