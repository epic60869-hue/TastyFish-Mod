package com.epic60869.tastyfish;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Standalone TastyFish farming tracker. */
public final class FarmingProfitTracker {
    private static final FarmingProfitTracker INSTANCE = new FarmingProfitTracker();
    private static final long ACTIVE_GRACE_MS = 2500L;
    private static final Pattern SACK_GAIN = Pattern.compile("(?:\\+|added\\s+)([\\d,]+)\\s+(.+?)(?=\\s*\\([^)]*\\)\\s*$|\\s*$)", Pattern.CASE_INSENSITIVE);

    private final Map<String, Long> itemCounts = new LinkedHashMap<>();
    private final Map<String, Long> pestKills = new LinkedHashMap<>();
    private final Map<String, Long> previousInventory = new HashMap<>();
    private final Map<String, String> displayToId = new HashMap<>();
    private long actions, activeMillis, lastActivityMillis, lastTickMillis, sessionStartedMillis;
    private double coins;
    private boolean initialized;

    private FarmingProfitTracker() { registerCropNames(); }
    public static FarmingProfitTracker get() { return INSTANCE; }

    public void register() {
        ClientPlayerBlockBreakEvents.AFTER.register((world, player, pos, state) -> {
            if (isFarmingBlock(state.getBlock().toString())) markAction();
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> handleMessage(message));
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, timestamp) -> handleMessage(message));
    }

    public void tick(Minecraft minecraft) {
        long now = System.currentTimeMillis();
        if (lastTickMillis != 0L && lastActivityMillis > 0L) {
            long end = Math.min(now, lastActivityMillis + ACTIVE_GRACE_MS);
            long start = Math.min(lastTickMillis, end);
            if (end > start) activeMillis += end - start;
        }
        lastTickMillis = now;
        if (minecraft.player == null) { previousInventory.clear(); initialized = false; return; }
        Map<String, Long> current = readInventoryCounts(minecraft);
        if (!initialized) {
            previousInventory.clear(); previousInventory.putAll(current); initialized = true;
            if (sessionStartedMillis == 0L) sessionStartedMillis = now;
            return;
        }
        for (Map.Entry<String, Long> entry : current.entrySet()) {
            long gained = entry.getValue() - previousInventory.getOrDefault(entry.getKey(), 0L);
            if (gained > 0L) {
                String id = displayToId.get(normalize(entry.getKey()));
                if (id != null) { itemCounts.merge(id, gained, Long::sum); markActivityOnly(); }
            }
        }
        previousInventory.clear(); previousInventory.putAll(current);
    }

    public void reset() {
        itemCounts.clear(); pestKills.clear(); previousInventory.clear();
        actions = activeMillis = 0L; coins = 0.0; lastActivityMillis = lastTickMillis = 0L;
        sessionStartedMillis = System.currentTimeMillis(); initialized = false;
    }
    public Map<String, Long> itemCounts() { return Map.copyOf(itemCounts); }
    public Map<String, Long> pestKills() { return Map.copyOf(pestKills); }
    public long actions() { return actions; }
    public long activeMillis() { return activeMillis; }
    public double coins() { return coins; }

    public double profit() {
        double total = coins;
        for (Map.Entry<String, Long> entry : itemCounts.entrySet()) {
            double unit = ItemPriceResolver.value(entry.getKey());
            if (unit > 0.0) total += unit * entry.getValue();
        }
        return total;
    }
    public double profitPerHour() { return activeMillis <= 0L ? 0.0 : profit() / (activeMillis / 3_600_000.0); }
    public long sessionStartedMillis() { return sessionStartedMillis; }

    public Snapshot snapshot() {
        return new Snapshot(itemCounts(), pestKills(), activeMillis(), actions(), coins(), profit(), true);
    }

    public List<PestRow> pestRows() {
        List<PestRow> rows = new ArrayList<>();
        long recorded = pestKills.values().stream().mapToLong(Long::longValue).sum();
        for (Map.Entry<String, Long> e : pestKills.entrySet()) rows.add(new PestRow(e.getKey(), e.getValue(), percentage(e.getValue())));
        long missing = Math.max(0L, actions - recorded);
        if (missing > 0L) rows.add(new PestRow("Not recorded", missing, percentage(missing)));
        return rows;
    }
    private double percentage(long count) { return actions <= 0L ? 0.0 : count * 100.0 / actions; }

    private void handleMessage(Component message) {
        String clean = message.getString().replaceAll("§[0-9a-fk-or]", "").trim();
        String lower = clean.toLowerCase(Locale.ROOT);
        if (lower.contains("pest") && (lower.contains("killed") || lower.contains("vacuumed") || lower.contains("slain"))) {
            pestKills.merge(extractPestName(clean), 1L, Long::sum); markAction(); return;
        }
        if (!lower.contains("sack")) return;

        Matcher matcher = SACK_GAIN.matcher(clean);
        while (matcher.find()) {
            long amount;
            try { amount = Long.parseLong(matcher.group(1).replace(",", "")); } catch (NumberFormatException ignored) { continue; }
            String name = matcher.group(2).replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim();
            String id = displayToId.get(normalize(name));
            if (id == null) id = guessId(name);
            if (id != null && amount > 0L) { itemCounts.merge(id, amount, Long::sum); markActivityOnly(); }
        }
    }

    private String extractPestName(String message) {
        Matcher m = Pattern.compile("(?:killed|vacuumed|slain)\\s+(?:a\\s+)?(.+?)(?:!|\\.|$)", Pattern.CASE_INSENSITIVE).matcher(message);
        return m.find() ? m.group(1).trim() : "Pest";
    }
    private String guessId(String name) {
        return switch (normalize(name)) {
            case "wheat" -> "WHEAT"; case "carrot" -> "CARROT_ITEM"; case "potato" -> "POTATO_ITEM";
            case "sugar cane" -> "SUGAR_CANE"; case "pumpkin" -> "PUMPKIN"; case "melon" -> "MELON";
            case "cactus" -> "CACTUS"; case "nether wart" -> "NETHER_STALK"; case "cocoa beans" -> "INK_SACK:3";
            default -> null;
        };
    }
    private void markAction() { actions++; markActivityOnly(); }
    private void markActivityOnly() { lastActivityMillis = System.currentTimeMillis(); }
    private boolean isFarmingBlock(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("wheat") || lower.contains("carrot") || lower.contains("potato") || lower.contains("beetroot")
            || lower.contains("nether_wart") || lower.contains("cocoa") || lower.contains("sugar_cane") || lower.contains("pumpkin")
            || lower.contains("melon") || lower.contains("cactus") || lower.contains("mushroom") || lower.contains("crop");
    }
    private Map<String, Long> readInventoryCounts(Minecraft minecraft) {
        Map<String, Long> result = new HashMap<>();
        try {
            Object inventory = minecraft.player.getInventory();
            Field items = findField(inventory.getClass(), "items"); items.setAccessible(true);
            Object value = items.get(inventory); if (!(value instanceof Iterable<?> iterable)) return result;
            for (Object stack : iterable) {
                if (stack == null) continue;
                if ((Boolean) stack.getClass().getMethod("isEmpty").invoke(stack)) continue;
                long amount = ((Number) stack.getClass().getMethod("getCount").invoke(stack)).longValue();
                String display = ((Component) stack.getClass().getMethod("getHoverName").invoke(stack)).getString();
                result.merge(display, amount, Long::sum);
            }
        } catch (Throwable ignored) { }
        return result;
    }
    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) try { return c.getDeclaredField(name); } catch (NoSuchFieldException ignored) { }
        throw new NoSuchFieldException(name);
    }
    private void registerCropNames() {
        add("Wheat","WHEAT"); add("Seeds","SEEDS"); add("Enchanted Bread","ENCHANTED_BREAD"); add("Enchanted Wheat","ENCHANTED_WHEAT");
        add("Hay Bale","HAY_BLOCK"); add("Enchanted Hay Bale","ENCHANTED_HAY_BLOCK"); add("Carrot","CARROT_ITEM"); add("Enchanted Carrot","ENCHANTED_CARROT");
        add("Enchanted Golden Carrot","ENCHANTED_GOLDEN_CARROT"); add("Potato","POTATO_ITEM"); add("Enchanted Potato","ENCHANTED_POTATO"); add("Enchanted Baked Potato","ENCHANTED_BAKED_POTATO");
        add("Sugar Cane","SUGAR_CANE"); add("Enchanted Sugar","ENCHANTED_SUGAR"); add("Enchanted Sugar Cane","ENCHANTED_SUGAR_CANE"); add("Cocoa Beans","INK_SACK:3"); add("Enchanted Cocoa Bean","ENCHANTED_COCOA_BEANS");
        add("Nether Wart","NETHER_STALK"); add("Enchanted Nether Wart","ENCHANTED_NETHER_STALK"); add("Pumpkin","PUMPKIN"); add("Enchanted Pumpkin","ENCHANTED_PUMPKIN"); add("Polished Pumpkin","POLISHED_PUMPKIN");
        add("Melon","MELON"); add("Enchanted Melon","ENCHANTED_MELON"); add("Enchanted Melon Block","ENCHANTED_MELON_BLOCK"); add("Cactus","CACTUS"); add("Enchanted Cactus Green","ENCHANTED_CACTUS_GREEN"); add("Enchanted Cactus","ENCHANTED_CACTUS");
        add("Red Mushroom","RED_MUSHROOM"); add("Brown Mushroom","BROWN_MUSHROOM"); add("Enchanted Red Mushroom","ENCHANTED_RED_MUSHROOM"); add("Enchanted Brown Mushroom","ENCHANTED_BROWN_MUSHROOM");
        add("Feastfungus","FEASTFUNGUS"); add("Moonflower","MOONFLOWER"); add("Enchanted Moonflower","ENCHANTED_MOONFLOWER"); add("Wild Rose","WILD_ROSE"); add("Enchanted Wild Rose","ENCHANTED_WILD_ROSE");
    }
    private void add(String display, String id) { displayToId.put(normalize(display), id); }
    private static String normalize(String value) { return value == null ? "" : value.replaceAll("§[0-9a-fk-or]", "").replace("✦", "").replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT); }
    public record Snapshot(Map<String, Long> items, Map<String, Long> pests, long activeMillis, long actions, double coins, double profit, boolean valid) {}
    public record PestRow(String name, long count, double percentage) {}
}
