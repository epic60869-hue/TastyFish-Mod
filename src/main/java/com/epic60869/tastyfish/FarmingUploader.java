package com.epic60869.tastyfish;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FarmingUploader {
    private static final Gson GSON = new Gson();
    private static final String MOD_VERSION = "1.0.6";
    private static final long AUTH_COOLDOWN_MS = 30_000L;
    private static final long AUTH_RATE_LIMIT_BACKOFF_MS = 60_000L;
    private static final long MAX_AUTH_BACKOFF_MS = 10 * 60_000L;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private volatile String token;
    private volatile String tokenUsername;
    private volatile String tokenUuid;
    private volatile String tokenSessionId;
    private volatile String initializedSessionId;
    private volatile long authBlockedUntil;
    private volatile long authBackoffMs = AUTH_RATE_LIMIT_BACKOFF_MS;
    private final AtomicBoolean authenticationInProgress = new AtomicBoolean(false);
    private final AtomicBoolean updateInProgress = new AtomicBoolean(false);

    private volatile String trackedSessionId;
    private volatile double lastProfit = -1.0D;
    private volatile long lastActiveMillis = -1L;
    private volatile long lastActions = -1L;
    private volatile Map<String, Long> lastItems = Map.of();
    private volatile Map<String, Long> lastPests = Map.of();

    public void upload(TastyFishConfig config, String username, String uuid, String profile, String sessionId,
                       SkysoftSessionReader.Snapshot snapshot) {
        if (!config.enabled || config.endpoint.isBlank() || !snapshot.valid()) return;
        if (username == null || username.isBlank() || uuid == null || uuid.isBlank() || sessionId == null || sessionId.isBlank()) return;

        if (!sessionId.equals(trackedSessionId)) {
            trackedSessionId = sessionId;
            lastProfit = -1.0D;
            lastActiveMillis = -1L;
            lastActions = -1L;
            lastItems = Map.of();
            lastPests = Map.of();
            initializedSessionId = null;
        }

        if (token == null || !username.equalsIgnoreCase(tokenUsername)
                || !uuid.equalsIgnoreCase(tokenUuid) || !sessionId.equals(tokenSessionId)) {
            authenticate(config, username, uuid, sessionId, profile, snapshot);
            return;
        }

        sendDeltaIfNeeded(config, username, uuid, profile, sessionId, snapshot, false);
    }

    private void authenticate(TastyFishConfig config, String username, String uuid, String sessionId,
                              String profile, SkysoftSessionReader.Snapshot snapshot) {
        long now = System.currentTimeMillis();
        if (now < authBlockedUntil || !authenticationInProgress.compareAndSet(false, true)) return;

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

                        // Do not send the current Skysoft total. The first successful
                        // read establishes the local baseline, so existing profit is not
                        // credited again. Only future increases are sent to the server.
                        if (!sessionId.equals(initializedSessionId)) {
                            establishBaseline(snapshot);
                            initializedSessionId = sessionId;
                        }
                    } else if (response.statusCode() == 429) {
                        long retryMs = retryAfterMillis(response);
                        authBlockedUntil = System.currentTimeMillis() + retryMs;
                        authBackoffMs = Math.min(Math.max(authBackoffMs * 2L, AUTH_RATE_LIMIT_BACKOFF_MS), MAX_AUTH_BACKOFF_MS);
                        System.err.println("[TastyFish] Farming authentication rate limited (429). Backing off for " + (retryMs / 1000L) + " seconds.");
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

    private void establishBaseline(SkysoftSessionReader.Snapshot snapshot) {
        lastProfit = snapshot.profit();
        lastActiveMillis = snapshot.activeMillis();
        lastActions = snapshot.actions();
        lastItems = copyMap(snapshot.items());
        lastPests = copyMap(snapshot.pests());
    }

    private void sendDeltaIfNeeded(TastyFishConfig config, String username, String uuid, String profile,
                                   String sessionId, SkysoftSessionReader.Snapshot snapshot, boolean retry) {
        if (updateInProgress.get()) return;

        if (lastProfit < 0.0D) {
            establishBaseline(snapshot);
            initializedSessionId = sessionId;
            return;
        }

        double profitDelta = Math.max(0.0D, snapshot.profit() - lastProfit);
        long activeDelta = Math.max(0L, snapshot.activeMillis() - lastActiveMillis);
        long actionsDelta = Math.max(0L, snapshot.actions() - lastActions);
        Map<String, Long> itemDelta = deltaMap(snapshot.items(), lastItems);
        Map<String, Long> pestDelta = deltaMap(snapshot.pests(), lastPests);

        if (profitDelta <= 0.0D && activeDelta <= 0L && actionsDelta <= 0L && itemDelta.isEmpty() && pestDelta.isEmpty()) return;

        if (!updateInProgress.compareAndSet(false, true)) return;

        double oldProfit = lastProfit;
        long oldActive = lastActiveMillis;
        long oldActions = lastActions;
        Map<String, Long> oldItems = lastItems;
        Map<String, Long> oldPests = lastPests;

        // Advance the local baseline before sending so duplicate ticks cannot submit
        // the same gain. Restore it if the request fails.
        lastProfit = snapshot.profit();
        lastActiveMillis = snapshot.activeMillis();
        lastActions = snapshot.actions();
        lastItems = copyMap(snapshot.items());
        lastPests = copyMap(snapshot.pests());

        JsonObject root = new JsonObject();
        root.addProperty("username", username);
        root.addProperty("uuid", uuid);
        root.addProperty("profile", profile == null ? "" : profile);
        root.addProperty("preset", "FARMING");
        root.addProperty("profit", profitDelta);
        root.addProperty("activeMillis", activeDelta);
        root.addProperty("actions", actionsDelta);
        root.addProperty("sessionId", sessionId);
        root.add("items", GSON.toJsonTree(itemDelta));
        root.add("pests", GSON.toJsonTree(pestDelta));

        sendJson(config, root, username, uuid, profile, sessionId, retry,
            oldProfit, oldActive, oldActions, oldItems, oldPests);
    }

    private void sendJson(TastyFishConfig config, JsonObject root, String username, String uuid, String profile,
                          String sessionId, boolean retry, double oldProfit, long oldActive, long oldActions,
                          Map<String, Long> oldItems, Map<String, Long> oldPests) {
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
                    System.out.println("[TastyFish] Farming delta uploaded: " + response.body());
                } else if (response.statusCode() == 401 && !retry) {
                    restore(oldProfit, oldActive, oldActions, oldItems, oldPests);
                    token = null;
                    tokenUsername = null;
                    tokenUuid = null;
                    tokenSessionId = null;
                    System.out.println("[TastyFish] Farming token expired. Re-authenticating...");
                    authenticate(config, username, uuid, sessionId, profile, SkysoftSessionReader.read());
                } else if (response.statusCode() == 429) {
                    restore(oldProfit, oldActive, oldActions, oldItems, oldPests);
                    long retryMs = retryAfterMillis(response);
                    authBlockedUntil = System.currentTimeMillis() + retryMs;
                    System.err.println("[TastyFish] Farming upload rate limited (429). Backing off for " + (retryMs / 1000L) + " seconds.");
                } else {
                    restore(oldProfit, oldActive, oldActions, oldItems, oldPests);
                    System.err.println("[TastyFish] Farming upload failed: HTTP " + response.statusCode() + ": " + response.body());
                }
            } finally {
                updateInProgress.set(false);
            }
        }).exceptionally(error -> {
            restore(oldProfit, oldActive, oldActions, oldItems, oldPests);
            updateInProgress.set(false);
            System.err.println("[TastyFish] Farming upload failed: " + rootMessage(error));
            return null;
        });
    }

    private void restore(double profit, long active, long actions, Map<String, Long> items, Map<String, Long> pests) {
        lastProfit = profit;
        lastActiveMillis = active;
        lastActions = actions;
        lastItems = items;
        lastPests = pests;
    }

    private static Map<String, Long> deltaMap(Map<String, Long> current, Map<String, Long> previous) {
        Map<String, Long> delta = new HashMap<>();
        for (Map.Entry<String, Long> entry : current.entrySet()) {
            long oldValue = previous.getOrDefault(entry.getKey(), 0L);
            long difference = entry.getValue() - oldValue;
            if (difference > 0L) delta.put(entry.getKey(), difference);
        }
        return delta;
    }

    private static Map<String, Long> copyMap(Map<String, Long> source) {
        return source == null || source.isEmpty() ? Map.of() : Map.copyOf(source);
    }

    private long retryAfterMillis(HttpResponse<?> response) {
        String retryAfter = response.headers().firstValue("Retry-After").orElse("").trim();
        if (!retryAfter.isEmpty()) {
            try {
                long seconds = Long.parseLong(retryAfter);
                if (seconds >= 0) return Math.min(seconds * 1000L, MAX_AUTH_BACKOFF_MS);
            } catch (NumberFormatException ignored) { }
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
