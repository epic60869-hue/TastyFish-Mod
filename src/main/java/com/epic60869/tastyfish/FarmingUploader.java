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
    private static final String MOD_VERSION = "1.0.3";
    private static final long AUTH_COOLDOWN_MS = 30_000L;
    private static final long AUTH_RATE_LIMIT_BACKOFF_MS = 60_000L;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private volatile String token;
    private volatile String tokenUsername;
    private volatile String tokenUuid;
    private volatile String tokenSessionId;
    private volatile long authBlockedUntil;
    private final AtomicBoolean authenticationInProgress = new AtomicBoolean(false);

    public void upload(TastyFishConfig config, String username, String uuid, String profile, String sessionId,
                       SkysoftSessionReader.Snapshot snapshot) {
        if (!config.enabled || config.endpoint.isBlank()) return;
        if (username == null || username.isBlank() || uuid == null || uuid.isBlank() || sessionId == null || sessionId.isBlank()) {
            System.err.println("[TastyFish] Farming upload skipped: missing username, UUID, or session ID.");
            return;
        }

        if (token == null || !username.equalsIgnoreCase(tokenUsername) || !uuid.equalsIgnoreCase(tokenUuid) || !sessionId.equals(tokenSessionId)) {
            authenticate(config, username, uuid, sessionId, snapshot, profile);
            return;
        }

        sendUpdate(config, username, uuid, profile, sessionId, snapshot, false);
    }

    private void authenticate(TastyFishConfig config, String username, String uuid, String sessionId,
                              SkysoftSessionReader.Snapshot snapshot, String profile) {
        long now = System.currentTimeMillis();
        if (now < authBlockedUntil) {
            return;
        }

        if (!authenticationInProgress.compareAndSet(false, true)) {
            return;
        }

        try {
            JsonObject body = new JsonObject();
            body.addProperty("username", username);
            body.addProperty("uuid", uuid);
            body.addProperty("sessionId", sessionId);
            body.addProperty("modVersion", MOD_VERSION);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(authEndpoint(config.endpoint)))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build();

            client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenAccept(response -> {
                try {
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        JsonObject json = GSON.fromJson(response.body(), JsonObject.class);
                        if (json == null || !json.has("token")) {
                            System.err.println("[TastyFish] Farming authentication failed: no token returned.");
                            authBlockedUntil = System.currentTimeMillis() + AUTH_COOLDOWN_MS;
                            return;
                        }

                        token = json.get("token").getAsString();
                        tokenUsername = username;
                        tokenUuid = uuid.toLowerCase();
                        tokenSessionId = sessionId;
                        authBlockedUntil = System.currentTimeMillis() + AUTH_COOLDOWN_MS;

                        System.out.println("[TastyFish] Farming authentication successful.");
                        sendUpdate(config, username, uuid, profile, sessionId, snapshot, false);
                    } else if (response.statusCode() == 429) {
                        authBlockedUntil = System.currentTimeMillis() + AUTH_RATE_LIMIT_BACKOFF_MS;
                        System.err.println("[TastyFish] Farming authentication rate limited (429). Backing off for 60 seconds.");
                    } else {
                        authBlockedUntil = System.currentTimeMillis() + AUTH_COOLDOWN_MS;
                        System.err.println("[TastyFish] Farming authentication failed: HTTP " + response.statusCode() + ": " + response.body());
                    }
                } catch (Exception e) {
                    authBlockedUntil = System.currentTimeMillis() + AUTH_COOLDOWN_MS;
                    System.err.println("[TastyFish] Farming authentication response was invalid: " + e.getMessage());
                } finally {
                    authenticationInProgress.set(false);
                }
            }).exceptionally(error -> {
                authenticationInProgress.set(false);
                authBlockedUntil = System.currentTimeMillis() + AUTH_COOLDOWN_MS;
                System.err.println("[TastyFish] Farming authentication failed: " + rootMessage(error));
                return null;
            });
        } catch (Exception e) {
            authenticationInProgress.set(false);
            authBlockedUntil = System.currentTimeMillis() + AUTH_COOLDOWN_MS;
            System.err.println("[TastyFish] Farming authentication failed: " + e.getMessage());
        }
    }

    private void sendUpdate(TastyFishConfig config, String username, String uuid, String profile,
                            String sessionId, SkysoftSessionReader.Snapshot snapshot, boolean retry) {
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
            .header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(root)))
            .build();

        client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenAccept(response -> {
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                System.out.println("[TastyFish] Farming update uploaded: " + response.body());
            } else if (response.statusCode() == 401 && !retry) {
                token = null;
                tokenUsername = null;
                tokenUuid = null;
                tokenSessionId = null;
                System.out.println("[TastyFish] Farming token expired. Re-authenticating...");
                authenticate(config, username, uuid, sessionId, snapshot, profile);
            } else if (response.statusCode() == 429) {
                authBlockedUntil = System.currentTimeMillis() + AUTH_RATE_LIMIT_BACKOFF_MS;
                System.err.println("[TastyFish] Farming upload rate limited (429). Backing off for 60 seconds.");
            } else {
                System.err.println("[TastyFish] Farming upload failed: HTTP " + response.statusCode() + ": " + response.body());
            }
        }).exceptionally(error -> {
            System.err.println("[TastyFish] Farming upload failed: " + rootMessage(error));
            return null;
        });
    }

    private static String authEndpoint(String updateEndpoint) {
        if (updateEndpoint.endsWith("/update")) return updateEndpoint.substring(0, updateEndpoint.length() - 7) + "/auth";
        if (updateEndpoint.endsWith("/")) return updateEndpoint + "auth";
        return updateEndpoint + "/auth";
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }

    public static String newSessionId() {
        return UUID.randomUUID().toString();
    }
}
