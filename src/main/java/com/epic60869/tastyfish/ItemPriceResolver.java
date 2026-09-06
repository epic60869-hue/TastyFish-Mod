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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ItemPriceResolver {
    private static final String ITEMS_URL = "https://api.hypixel.net/v2/resources/skyblock/items";
    private static final String BAZAAR_URL = "https://api.hypixel.net/v2/skyblock/bazaar";
    private static final long CACHE_TIME_MILLIS = 5 * 60 * 1000L;
    private static final Gson GSON = new Gson();
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final Map<String, Double> NPC_PRICES = new HashMap<>();
    private static final Map<String, Double> BAZAAR_PRICES = new HashMap<>();
    private static final Map<String, String> NAME_TO_ID = new HashMap<>();
    private static final AtomicBoolean REFRESHING = new AtomicBoolean(false);
    private static volatile long lastRefresh = 0L;

    private ItemPriceResolver() {}

    public static double value(String itemId) {
        if (itemId == null || itemId.isBlank()) return 0.0;
        refreshIfNeeded();
        Double npc = NPC_PRICES.get(itemId);
        if (npc != null) return npc;
        Double bazaar = BAZAAR_PRICES.get(itemId);
        return bazaar == null ? 0.0 : bazaar;
    }

    public static double valueByName(String name) {
        if (name == null || name.isBlank()) return 0.0;
        refreshIfNeeded();
        String normalized = normalize(name);
        String id = NAME_TO_ID.get(normalized);
        if (id != null) return value(id);
        String fallback = legacyId(normalized);
        return fallback == null ? 0.0 : value(fallback);
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", "").replaceAll("\\s+", " ").trim();
    }

    private static String legacyId(String name) {
        return switch (name) {
            case "wheat" -> "WHEAT"; case "carrot" -> "CARROT_ITEM"; case "potato" -> "POTATO_ITEM";
            case "pumpkin" -> "PUMPKIN"; case "melon" -> "MELON"; case "cactus" -> "CACTUS";
            case "sugar cane" -> "SUGAR_CANE"; case "nether wart" -> "NETHER_STALK";
            case "cocoa beans" -> "INK_SACK:3"; case "mushroom" -> "MUSHROOM_COLLECTION"; default -> null;
        };
    }

    private static void refreshIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastRefresh < CACHE_TIME_MILLIS || !REFRESHING.compareAndSet(false, true)) return;
        CompletableFuture<HttpResponse<String>> itemsRequest = get(ITEMS_URL);
        CompletableFuture<HttpResponse<String>> bazaarRequest = get(BAZAAR_URL);
        itemsRequest.thenCombine(bazaarRequest, (itemsResponse, bazaarResponse) -> {
            Map<String, Double> npc = parseNpcPrices(itemsResponse);
            Map<String, Double> bazaar = parseBazaarPrices(bazaarResponse);
            Map<String, String> names = parseNames(itemsResponse);
            synchronized (NPC_PRICES) { NPC_PRICES.clear(); NPC_PRICES.putAll(npc); }
            synchronized (BAZAAR_PRICES) { BAZAAR_PRICES.clear(); BAZAAR_PRICES.putAll(bazaar); }
            synchronized (NAME_TO_ID) { NAME_TO_ID.clear(); NAME_TO_ID.putAll(names); }
            lastRefresh = System.currentTimeMillis();
            return null;
        }).exceptionally(error -> { System.err.println("[TastyFish] Failed to refresh item prices: " + rootMessage(error)); return null; })
          .whenComplete((ignored, error) -> REFRESHING.set(false));
    }

    private static CompletableFuture<HttpResponse<String>> get(String url) {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(15)).header("Accept", "application/json").GET().build();
        return CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("HTTP " + response.statusCode());
            return response;
        });
    }

    private static Map<String, Double> parseNpcPrices(HttpResponse<String> response) {
        Map<String, Double> result = new HashMap<>(); JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!root.has("items") || !root.get("items").isJsonArray()) return result;
        for (JsonElement element : root.getAsJsonArray("items")) {
            if (!element.isJsonObject()) continue; JsonObject item = element.getAsJsonObject();
            if (!item.has("id") || !item.has("npc_sell_price")) continue;
            try { double p=item.get("npc_sell_price").getAsDouble(); if(p>0) result.put(item.get("id").getAsString(),p); } catch(Exception ignored) {}
        }
        return result;
    }

    private static Map<String, String> parseNames(HttpResponse<String> response) {
        Map<String, String> result = new HashMap<>(); JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!root.has("items") || !root.get("items").isJsonArray()) return result;
        for (JsonElement element : root.getAsJsonArray("items")) {
            if (!element.isJsonObject()) continue; JsonObject item=element.getAsJsonObject();
            if(!item.has("id")||!item.has("name"))continue;
            result.put(normalize(item.get("name").getAsString()),item.get("id").getAsString());
        }
        return result;
    }

    private static Map<String, Double> parseBazaarPrices(HttpResponse<String> response) {
        Map<String, Double> result = new HashMap<>(); JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!root.has("products") || !root.get("products").isJsonObject()) return result;
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("products").entrySet()) {
            if (!entry.getValue().isJsonObject()) continue; JsonObject product=entry.getValue().getAsJsonObject();
            if(!product.has("quick_status")||!product.get("quick_status").isJsonObject())continue;
            JsonObject status=product.getAsJsonObject("quick_status"); if(!status.has("buyPrice"))continue;
            try{double p=status.get("buyPrice").getAsDouble();if(p>0)result.put(entry.getKey(),p);}catch(Exception ignored){}
        }
        return result;
    }
    private static String rootMessage(Throwable error){Throwable c=error;while(c.getCause()!=null)c=c.getCause();return c.getMessage()==null?c.toString():c.getMessage();}
}
