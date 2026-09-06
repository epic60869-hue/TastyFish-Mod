package com.epic60869.tastyfish;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Standalone SkyBlock price cache: NPC -> Bazaar -> lowest-bin. */
public final class ItemPriceResolver {
    private static final String ITEMS_URL = "https://api.hypixel.net/v2/resources/skyblock/items";
    private static final String BAZAAR_URL = "https://api.hypixel.net/v2/skyblock/bazaar";
    private static final String LOWEST_BIN_URL = "https://moulberry.codes/lowestbin.json";
    private static final long CACHE_TIME_MILLIS = 5 * 60 * 1000L;

    private static final Gson GSON = new Gson();
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final Map<String, Double> NPC_PRICES = new HashMap<>();
    private static final Map<String, Double> BAZAAR_PRICES = new HashMap<>();
    private static final Map<String, Double> LOWEST_BIN_PRICES = new HashMap<>();
    private static final AtomicBoolean REFRESHING = new AtomicBoolean(false);
    private static volatile long lastRefresh = 0L;

    private ItemPriceResolver() {}

    public static double value(String itemId) {
        if (itemId == null || itemId.isBlank()) return 0.0;
        refreshIfNeeded();
        Double npc = NPC_PRICES.get(itemId);
        if (npc != null && npc > 0.0) return npc;
        Double bazaar = BAZAAR_PRICES.get(itemId);
        if (bazaar != null && bazaar > 0.0) return bazaar;
        Double bin = LOWEST_BIN_PRICES.get(itemId);
        return bin == null ? 0.0 : Math.max(0.0, bin);
    }

    private static void refreshIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastRefresh < CACHE_TIME_MILLIS || !REFRESHING.compareAndSet(false, true)) return;

        CompletableFuture<HttpResponse<String>> itemsRequest = get(ITEMS_URL);
        CompletableFuture<HttpResponse<String>> bazaarRequest = get(BAZAAR_URL);
        CompletableFuture<HttpResponse<String>> binRequest = get(LOWEST_BIN_URL);

        CompletableFuture.allOf(itemsRequest, bazaarRequest, binRequest).thenRun(() -> {
            try {
                Map<String, Double> npc = parseNpcPrices(itemsRequest.join());
                Map<String, Double> bazaar = parseBazaarPrices(bazaarRequest.join());
                Map<String, Double> bin = parseLowestBin(binRequest.join());
                synchronized (NPC_PRICES) { NPC_PRICES.clear(); NPC_PRICES.putAll(npc); }
                synchronized (BAZAAR_PRICES) { BAZAAR_PRICES.clear(); BAZAAR_PRICES.putAll(bazaar); }
                synchronized (LOWEST_BIN_PRICES) { LOWEST_BIN_PRICES.clear(); LOWEST_BIN_PRICES.putAll(bin); }
                lastRefresh = System.currentTimeMillis();
                System.out.println("[TastyFish] Price cache refreshed: " + npc.size() + " NPC, " + bazaar.size() + " Bazaar, " + bin.size() + " LBIN.");
            } catch (Throwable error) {
                System.err.println("[TastyFish] Failed to refresh item prices: " + rootMessage(error));
            }
        }).whenComplete((ignored, error) -> REFRESHING.set(false));
    }

    private static CompletableFuture<HttpResponse<String>> get(String url) {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(15))
            .header("Accept", "application/json").GET().build();
        return CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("HTTP " + response.statusCode() + " from " + url);
            }
            return response;
        });
    }

    private static Map<String, Double> parseNpcPrices(HttpResponse<String> response) {
        Map<String, Double> result = new HashMap<>();
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!root.has("items") || !root.get("items").isJsonArray()) return result;
        for (JsonElement element : root.getAsJsonArray("items")) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            if (!item.has("id") || !item.has("npc_sell_price")) continue;
            try {
                double price = item.get("npc_sell_price").getAsDouble();
                if (price > 0) result.put(item.get("id").getAsString(), price);
            } catch (Exception ignored) { }
        }
        return result;
    }

    private static Map<String, Double> parseBazaarPrices(HttpResponse<String> response) {
        Map<String, Double> result = new HashMap<>();
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!root.has("products") || !root.get("products").isJsonObject()) return result;
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("products").entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject product = entry.getValue().getAsJsonObject();
            JsonObject status = product.getAsJsonObject("quick_status");
            if (status == null || !status.has("buyPrice")) continue;
            try {
                double price = status.get("buyPrice").getAsDouble();
                if (price > 0) result.put(entry.getKey(), price);
            } catch (Exception ignored) { }
        }
        return result;
    }

    private static Map<String, Double> parseLowestBin(HttpResponse<String> response) {
        Map<String, Double> result = new HashMap<>();
        JsonElement root = JsonParser.parseString(response.body());
        if (!root.isJsonObject()) return result;
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
            try {
                if (entry.getValue().isJsonPrimitive()) {
                    double price = entry.getValue().getAsDouble();
                    if (price > 0) result.put(entry.getKey(), price);
                }
            } catch (Exception ignored) { }
        }
        return result;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }
}
