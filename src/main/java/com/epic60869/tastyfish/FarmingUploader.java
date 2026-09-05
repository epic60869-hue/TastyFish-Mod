package com.epic60869.tastyfish;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FarmingUploader {
    private static final Gson GSON = new Gson();
    private static final String MOD_VERSION = "1.0.5";
    private static final long AUTH_COOLDOWN_MS = 30_000L;
    private static final long AUTH_RATE_LIMIT_BACKOFF_MS = 60_000L;
    private static final long MAX_AUTH_BACKOFF_MS = 10 * 60_000L;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private volatile String token;
    private volatile String tokenUsername;
    private volatile String tokenUuid;
    private volatile String tokenSessionId;
    private volatile String baselinedSessionId;
    private volatile long authBlockedUntil;
    private volatile long authBackoffMs = AUTH_RATE_LIMIT_BACKOFF_MS;
    private final AtomicBoolean authenticationInProgress = new AtomicBoolean(false);
    private final AtomicBoolean updateInProgress = new AtomicBoolean(false);

    // Skysoft's profit value is cumulative for the current tracker session.
    // Keep the last value we submitted so an unchanged tracker value is never
    // uploaded twice. The server still receives the cumulative value when it
    // actually changes, allowing its delta logic to count only the increase.
    private volatile String trackedSessionId;
    private volatile double lastSubmittedProfit = -1.0D;

    public void upload(TastyFishConfig config, String username, String uuid, String profile, String sessionId,
                       SkysoftSessionReader.Snapshot snapshot) {
        if (!config.enabled || config.endpoint.isBlank()) return;
        if (!snapshot.valid()) return;
        if (username == null || username.isBlank() || uuid == null || uuid.isBlank() || sessionId == null || sessionId.isBlank()) {
            System.err.println("[TastyFish] Farming upload skipped: missing username, UUID, or session ID.");
            return;
        }

        if (!sessionId.equals(trackedSessionId)) {
            trackedSessionId = sessionId;
            lastSubmittedProfit = -1.0D;
        }

        // Never queue the same cumulative profit twice. This also protects against
        // two ticks reading the same Skysoft value while an HTTP request is pending.
        if (lastSubmittedProfit >= 0.0D && snapshot.profit() <= lastSubmittedProfit) {
            return;
        }

        if (updateInProgress.get()) return;

        if (token == null || !username.equalsIgnoreCase(tokenUsername)
                || !uuid.equalsIgnoreCase(tokenUuid) || !sessionId.equals(tokenSessionId)) {
            authenticate(config, username, uuid, sessionId, profile);
            return;
        }

        if (updateInProgress.compareAndSet(false, true)) {
            // Mark it before sending so the same snapshot cannot be submitted again
            // while the asynchronous HTTP request is in flight. If the request fails,
            // this is restored so the next interval can retry it.
            double previous = lastSubmittedProfit;
            lastSubmittedProfit = snapshot.profit();
            sendUpdate(config, username, uuid, profile, sessionId, snapshot, false, previous);
        }
    }

    private void authenticate(TastyFishConfig config, String username, String uuid, String sessionId,
                              String profile) {
        long now = System.currentTimeMillis();
        if (now < authBlockedUntil) return;
        if (!authenticationInProgress.compareAndSet(false, true)) return;

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
                            authBlockedUntil = System.currentTimeMillis() + AUTH_COOLDOWN_MS;
                            System.err.println("[TastyFish] Farming authentication failed: no token returned.");
                            return;
                        }

                        token = json.get("token").getAsString();
                        tokenUsername = username;
                        tokenUuid = uuid.toLowerCase();
                        tokenSessionId = sessionId;
                        authBackoffMs = AUTH_RATE_LIMIT_BACKOFF_MS;
                        authBlockedUntil = System.currentTimeMillis() + AUTH_COOLDOWN_MS;

                        System.out.println("[TastyFish] Farming authentication successful.");

                        // Establish the server-side baseline once for this session.
                        // This zero update is intentionally NOT treated as farming profit.
                        if (!sessionId.equals(baselinedSessionId)) {
                            sendBaseline(config, username, uuid, profile, sessionId);
                            baselinedSessionId = sessionId;
                        }
                    } else if (response.statusCode() == 429) {
                        long retryMs = retryAfterMillis(response);
                        authBlockedUntil = System.currentTimeMillis() + retryMs;
                        authBackoffMs = Math.min(Math.max(authBackoffMs * 2L, AUTH_RATE_LIMIT_BACKOFF_MS), MAX_AUTH_BACKOFF_MS);
                        System.err.println("[TastyFish] Farming authentication rate limited (429). Backing off for "
                            + (retryMs / 1000L) + " seconds.");
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

    private void sendBaseline(TastyFishConfig config, String username, String uuid, String profile, String sessionId) {
        if (!updateInProgress.compareAndSet(false, true)) return;

        JsonObject root = new JsonObject();
        root.addProperty("username", username);
        root.addProperty("uuid", uuid);
        root.addProperty("profile", profile == null ? "" : profile);
        root.addProperty("preset", "FARMING");
        root.addProperty("profit", 0.0);
        root.addProperty("activeMillis", 0L);
        root.addProperty("actions", 0L);
        root.addProperty("sessionId", sessionId);
        root.add("items", GSON.toJsonTree(Map.of()));
        root.add("pests", GSON.toJsonTree(Map.of()));

        sendJson(config, root, username, uuid, profile, sessionId, false, true, -1.0D);
    }

    private void sendUpdate(TastyFishConfig config, String username, String uuid, String profile,
                            String sessionId, SkysoftSessionReader.Snapshot snapshot, boolean retry,
                            double previousProfit) {
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

        sendJson(config, root, username, uuid, profile, sessionId, retry, false, previousProfit);
    }

    private void sendJson(TastyFishConfig config, JsonObject root, String username, String uuid, String profile,
                          String sessionId, boolean retry, boolean baseline, double previousProfit) {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(config.endpoint))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(root)))
            .build();

        client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenAccept(response -> {
            try {
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    System.out.println("[TastyFish] Farming " + (baseline ? "baseline" : "update") + " uploaded: " + response.body());
                } else if (response.statusCode() == 401 && !retry) {
                    if (!baseline) {
                        lastSubmittedProfit = previousProfit;
                    }
                    token = null;
                    tokenUsername = null;
                    tokenUuid = null;
                    tokenSessionId = null;
                    System.out.println("[TastyFish] Farming token expired. Re-authenticating...");
                    authenticate(config, username, uuid, sessionId, profile);
                } else if (response.statusCode() == 429) {
                    if (!baseline) lastSubmittedProfit = previousProfit;
                    long retryMs = retryAfterMillis(response);
                    authBlockedUntil = System.currentTimeMillis() + retryMs;
                    System.err.println("[TastyFish] Farming upload rate limited (429). Backing off for "
                        + (retryMs / 1000L) + " seconds.");
                } else {
                    if (!baseline) lastSubmittedProfit = previousProfit;
                    System.err.println("[TastyFish] Farming upload failed: HTTP " + response.statusCode() + ": " + response.body());
                }
            } finally {
                updateInProgress.set(false);
            }
        }).exceptionally(error -> {
            if (!baseline) lastSubmittedProfit = previousProfit;
            updateInProgress.set(false);
            System.err.println("[TastyFish] Farming upload failed: " + rootMessage(error));
            return null;
        });
    }

    private long retryAfterMillis(HttpResponse<?> response) {
        String retryAfter = response.headers().firstValue("Retry-After").orElse("").trim();
        if (!retryAfter.isEmpty()) {
            try {
                long seconds = Long.parseLong(retryAfter);
                if (seconds >= 0) return Math.min(seconds * 1000L, MAX_AUTH_BACKOFF_MS);
            } catch (NumberFormatException ignored) {
            }
        }
        return authBackoffMs;
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
