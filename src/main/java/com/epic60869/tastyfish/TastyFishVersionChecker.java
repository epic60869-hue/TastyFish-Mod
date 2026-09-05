package com.epic60869.tastyfish;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TastyFishVersionChecker {
    private static final String RELEASE_URL = "https://api.github.com/repos/epic60869-hue/TastyFish-Mod/releases/latest";
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    private TastyFishVersionChecker() {}

    public static void check(Minecraft minecraft) {
        if (!STARTED.compareAndSet(false, true)) return;

        String current = installedVersion();
        if (current == null || current.isBlank()) return;

        HttpRequest request = HttpRequest.newBuilder(URI.create(RELEASE_URL))
            .timeout(Duration.ofSeconds(8))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "TastyFish-Mod/3 (version checker)")
            .GET()
            .build();

        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("GitHub returned HTTP " + response.statusCode());
                }
                return response.body();
            })
            .thenApply(TastyFishVersionChecker::latestVersion)
            .thenAccept(latest -> {
                if (latest == null || compareVersions(latest, current) <= 0) return;
                minecraft.execute(() -> showChatMessage(minecraft, current, latest));
            })
            .exceptionally(error -> {
                System.out.println("[TastyFish] Version check failed: " + rootMessage(error));
                return null;
            });
    }

    private static String installedVersion() {
        return FabricLoader.getInstance().getModContainer("tastyfish-mod")
            .map(container -> container.getMetadata().getVersion().getFriendlyString())
            .orElse(null);
    }

    private static String latestVersion(String body) {
        JsonObject release = JsonParser.parseString(body).getAsJsonObject();
        if (!release.has("tag_name")) return null;
        String tag = release.get("tag_name").getAsString().trim();
        if (tag.startsWith("v") || tag.startsWith("V")) tag = tag.substring(1);
        return tag.isBlank() ? null : tag;
    }

    private static void showChatMessage(Minecraft minecraft, String current, String latest) {
        if (minecraft.player == null) return;

        Component message = Component.literal(
            "§e[TastyFish] §cTastyFish outdated §7(" + current + ") §f-> §a" + latest
        );

        try {
            Object chat = null;
            try {
                Method getter = minecraft.gui.getClass().getMethod("getChat");
                chat = getter.invoke(minecraft.gui);
            } catch (NoSuchMethodException ignored) {
                Field field = findField(minecraft.gui.getClass(), "chat");
                if (field != null) {
                    field.setAccessible(true);
                    chat = field.get(minecraft.gui);
                }
            }

            if (chat == null) {
                throw new IllegalStateException("Minecraft chat component could not be located");
            }

            Method add = chat.getClass().getMethod("addClientSystemMessage", Component.class);
            add.invoke(chat, message);
        } catch (Throwable error) {
            System.out.println("[TastyFish] Could not display version notice: " + rootMessage(error));
        }
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static int compareVersions(String left, String right) {
        List<Integer> a = numericParts(left);
        List<Integer> b = numericParts(right);
        int count = Math.max(a.size(), b.size());
        for (int i = 0; i < count; i++) {
            int comparison = Integer.compare(
                i < a.size() ? a.get(i) : 0,
                i < b.size() ? b.get(i) : 0
            );
            if (comparison != 0) return comparison;
        }
        return 0;
    }

    private static List<Integer> numericParts(String version) {
        List<Integer> parts = new ArrayList<>();
        StringBuilder number = new StringBuilder();
        for (int i = 0; i < version.length(); i++) {
            char c = version.charAt(i);
            if (Character.isDigit(c)) {
                number.append(c);
            } else if (!number.isEmpty()) {
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
