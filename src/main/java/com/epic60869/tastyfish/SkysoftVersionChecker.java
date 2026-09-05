package com.epic60869.tastyfish;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SkysoftVersionChecker {
    private static final String MODRINTH_VERSIONS_URL = "https://api.modrinth.com/v2/project/skysoft/version";
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private SkysoftVersionChecker() {}

    public static void check(Minecraft minecraft) {
        if (!STARTED.compareAndSet(false, true)) return;

        String current = installedSkysoftVersion();
        if (current == null || current.isBlank()) return;
        String minecraftVersion = installedMinecraftVersion();
        if (minecraftVersion == null || minecraftVersion.isBlank()) return;

        HttpRequest request = HttpRequest.newBuilder(URI.create(MODRINTH_VERSIONS_URL))
            .timeout(Duration.ofSeconds(8))
            .header("User-Agent", "TastyFish-Mod/3 (Skysoft update checker)")
            .GET()
            .build();

        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("Modrinth returned HTTP " + response.statusCode());
                }
                return response.body();
            })
            .thenApply(body -> findNewestCompatibleRelease(body, minecraftVersion))
            .thenAccept(latest -> {
                if (latest == null || compareVersions(latest, current) <= 0) return;
                minecraft.execute(() -> {
                    if (minecraft.player == null) return;
                    minecraft.gui.chat.addClientSystemMessage(
                        Component.literal("§e[TastyFish] §cSkysoft outdated §7(" + current + ") §f-> §a" + latest)
                    );
                });
            })
            .exceptionally(error -> {
                System.out.println("[TastyFish] Skysoft update check failed: " + rootMessage(error));
                return null;
            });
    }

    private static String installedSkysoftVersion() {
        return FabricLoader.getInstance().getModContainer("skysoft")
            .map(container -> container.getMetadata().getVersion().getFriendlyString()).orElse(null);
    }

    private static String installedMinecraftVersion() {
        return FabricLoader.getInstance().getModContainer("minecraft")
            .map(container -> container.getMetadata().getVersion().getFriendlyString()).orElse(null);
    }

    private static String findNewestCompatibleRelease(String body, String minecraftVersion) {
        JsonArray versions = JsonParser.parseString(body).getAsJsonArray();
        List<String> candidates = new ArrayList<>();
        for (JsonElement element : versions) {
            if (!element.isJsonObject()) continue;
            JsonObject version = element.getAsJsonObject();
            String status = version.has("status") ? version.get("status").getAsString() : "";
            if (!"release".equalsIgnoreCase(status)) continue;
            if (!contains(version.getAsJsonArray("game_versions"), minecraftVersion)) continue;
            if (!containsIgnoreCase(version.getAsJsonArray("loaders"), "fabric")) continue;
            if (version.has("version_number")) candidates.add(version.get("version_number").getAsString());
        }
        return candidates.stream().max(SkysoftVersionChecker::compareVersions).orElse(null);
    }

    private static boolean contains(JsonArray array, String wanted) {
        if (array == null) return false;
        for (JsonElement element : array) if (wanted.equals(element.getAsString())) return true;
        return false;
    }

    private static boolean containsIgnoreCase(JsonArray array, String wanted) {
        if (array == null) return false;
        for (JsonElement element : array) if (wanted.equalsIgnoreCase(element.getAsString())) return true;
        return false;
    }

    private static int compareVersions(String left, String right) {
        List<Integer> a = numericParts(left);
        List<Integer> b = numericParts(right);
        int count = Math.max(a.size(), b.size());
        for (int i = 0; i < count; i++) {
            int av = i < a.size() ? a.get(i) : 0;
            int bv = i < b.size() ? b.get(i) : 0;
            int comparison = Integer.compare(av, bv);
            if (comparison != 0) return comparison;
        }
        return 0;
    }

    private static List<Integer> numericParts(String version) {
        List<Integer> parts = new ArrayList<>();
        StringBuilder number = new StringBuilder();
        for (int i = 0; i < version.length(); i++) {
            char c = version.charAt(i);
            if (Character.isDigit(c)) number.append(c);
            else if (!number.isEmpty()) {
                parts.add(parsePart(number));
                number.setLength(0);
            }
        }
        if (!number.isEmpty()) parts.add(parsePart(number));
        return parts;
    }

    private static int parsePart(StringBuilder value) {
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null ? current.toString() : message;
    }
}
