package com.epic60869.tastyfish;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class FarmingUploader {
    private static final Gson GSON = new Gson();
    private static final String MOD_VERSION = "1.0.2";

    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private String token;
    private long tokenExpiresAt = 0L;
    private CompletableFuture<String> authenticationInProgress;

    public void upload(TastyFishConfig config, String username, String uuid, String profile, String sessionId,
                       SkysoftSessionReader.Snapshot snapshot) {
        if (!config.enabled || config.endpoint.isBlank()) {
            return;
        }

        if (username == null || username.isBlank() || uuid == null || uuid.isBlank() || sessionId == null || sessionId.isBlank()) {
            System.err.println("[TastyFish] Farming upload skipped: missing username, UUID, or session ID.");
            return;
        }

        authenticate(config, username, uuid, sessionId)
            .thenCompose(authToken -> sendUpdate(config, authToken, username, uuid, profile, sessionId, snapshot))
            .thenAccept(response -> {
                System.out.println("[TastyFish] Farming update uploaded: " + response);
            })
            .exceptionally(error -> {
                System.err.println("[TastyFish] Farming upload failed: " + rootMessage(error));
                return null;
            });
    }

    private synchronized CompletableFuture<String> authenticate(TastyFishConfig config, String username, String uuid, String sessionId) {
        long now = System.currentTimeMillis();
        if (token != null && now + 30_000L < tokenExpiresAt) {
            return CompletableFuture.completedFuture(token);
        }

        if (authenticationInProgress != null && !authenticationInProgress.isDone()) {
            return authenticationInProgress;
        }

        URI authUri;
        try {
            authUri = authUri(config.endpoint);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid farming endpoint: " + config.endpoint, e));
        }

        JsonObject root = new JsonObject();
        root.addProperty("username", username);
        root.addProperty("uuid", uuid.toLowerCase());
        root.addProperty("sessionId", sessionId);
        root.addProperty("modVersion", MOD_VERSION);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(authUri)
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(root)))
            .build();

        authenticationInProgress = client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenCompose(response -> {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    return CompletableFuture.failedFuture(
                        new IllegalStateException("Authentication HTTP " + response.statusCode() + ": " + response.body())
                    );
                }

                try {
                    JsonObject json = GSON.fromJson(response.body(), JsonObject.class);
                    String newToken = json.has("token") ? json.get("token").getAsString() : "";
                    long expiresIn = json.has("expiresIn") ? json.get("expiresIn").getAsLong() : 300L;

                    if (newToken.isBlank()) {
                        return CompletableFuture.failedFuture(new IllegalStateException("Authentication response did not contain a token."));
                    }

                    synchronized (this) {
                        token = newToken;
                        tokenExpiresAt = System.currentTimeMillis() + Math.max(1L, expiresIn) * 1000L;
                    }

                    System.out.println("[TastyFish] Farming authentication successful.");
                    return CompletableFuture.completedFuture(newToken);
                } catch (Exception e) {
                    return CompletableFuture.failedFuture(new IllegalStateException("Invalid authentication response.", e));
                }
            });

        authenticationInProgress.whenComplete((ignored, error) -> {
            synchronized (this) {
                authenticationInProgress = null;
                if (error != null) {
                    token = null;
                    tokenExpiresAt = 0L;
                }
            }
        });

        return authenticationInProgress;
    }

    private CompletableFuture<String> sendUpdate(TastyFishConfig config, String authToken, String username, String uuid,
                                                   String profile, String sessionId, SkysoftSessionReader.Snapshot snapshot) {
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
            .header("Authorization", "Bearer " + authToken)
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(root)))
            .build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenCompose(response -> {
                if (response.statusCode() == 401) {
                    synchronized (this) {
                        token = null;
                        tokenExpiresAt = 0L;
                    }

                    return authenticate(config, username, uuid, sessionId)
                        .thenCompose(newToken -> sendUpdateOnce(config, newToken, root));
                }

                return handleUpdateResponse(response);
            });
    }

    private CompletableFuture<String> sendUpdateOnce(TastyFishConfig config, String authToken, JsonObject root) {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(config.endpoint))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + authToken)
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(root)))
            .build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenCompose(this::handleUpdateResponse);
    }

    private CompletableFuture<String> handleUpdateResponse(HttpResponse<String> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Update HTTP " + response.statusCode() + ": " + response.body())
            );
        }

        return CompletableFuture.completedFuture(response.body().isBlank() ? "HTTP " + response.statusCode() : response.body());
    }

    private static URI authUri(String endpoint) {
        URI updateUri = URI.create(endpoint);
        String path = updateUri.getPath();
        if (path == null || !path.endsWith("/update")) {
            throw new IllegalArgumentException("Farming endpoint must end with /update");
        }

        String authPath = path.substring(0, path.length() - "/update".length()) + "/auth";
        try {
            return new URI(updateUri.getScheme(), updateUri.getAuthority(), authPath, updateUri.getQuery(), null);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not build farming auth URL", e);
        }
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
