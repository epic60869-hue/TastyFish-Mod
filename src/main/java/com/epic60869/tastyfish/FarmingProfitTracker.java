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

/**
 * TastyFish-owned farming tracker. It intentionally has no SkySoft dependency.
 *
 * The tracker watches farming block breaks, positive inventory changes and the
 * same style of pest-kill messages used by SkySoft. Values are resolved through
 * TastyFish's own price cache.
 */
public final class FarmingProfitTracker {
    private static final FarmingProfitTracker INSTANCE = new FarmingProfitTracker();
    private static final long ACTIVE_GRACE_MS = 2500L;

    private final Map<String, Long> itemCounts = new LinkedHashMap<>();
    private final Map<String, Long> pestKills = new LinkedHashMap<>();
    private final Map<String, Long> previousInventory = new HashMap<>();
    private final Map<String, String> displayToId = new HashMap<>();

    private long actions;
    private long activeMillis;
    private long lastActivityMillis;
    private long lastTickMillis;
    private long sessionStartedMillis;
    private double coins;
    private boolean initialized;

    private FarmingProfitTracker() {
        registerCropNames();
    }

    public static FarmingProfitTracker get() {
        return INSTANCE;
    }

    public void register() {
        ClientPlayerBlockBreakEvents.AFTER.register((world, player, pos, state) -> {
            if (!isFarmingBlock(state.getBlock().toString())) return;
            markAction();
        });

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            handleMessage(message);
        });
    }

    public void tick(Minecraft minecraft) {
        long now = System.currentTimeMillis();
        if (lastTickMillis != 0L && lastActivityMillis > 0L) {
            long end = Math.min(now, lastActivityMillis + ACTIVE_GRACE_MS);
            long start = Math.min(lastTickMillis, end);
            if (end > start) activeMillis += end - start;
        }
        lastTickMillis = now;

        if (minecraft.player == null) {
            previousInventory.clear();
            initialized = false;
            return;
        }

        Map<String, Long> current = readInventoryCounts(minecraft);
        if (!initialized) {
            previousInventory.clear();
            previousInventory.putAll(current);
            initialized = true;
            if (sessionStartedMillis == 0L) sessionStartedMillis = now;
            return;
        }

        for (Map.Entry<String, Long> entry : current.entrySet()) {
            long old = previousInventory.getOrDefault(entry.getKey(), 0L);
            long gained = entry.getValue() - old;
            if (gained > 0L) {
                String id = displayToId.get(normalize(entry.getKey()));
                if (id != null) {
                    itemCounts.merge(id, gained, Long::sum);
                    markActivityOnly();
                }
            }
        }
        previousInventory.clear();
        previousInventory.putAll(current);
    }

    public void reset() {
        itemCounts.clear();
        pestKills.clear();
        previousInventory.clear();
        actions = 0L;
        activeMillis = 0L;
        coins = 0.0;
        lastActivityMillis = 0L;
        lastTickMillis = 0L;
        sessionStartedMillis = System.currentTimeMillis();
        initialized = false;
    }

    public Map<String, Long> itemCounts() { return Map.copyOf(itemCounts); }
    public Map<String, Long> pestKills() { return Map.copyOf(pestKills); }
    public long actions() { return actions; }
    public long activeMillis() { return activeMillis; }
    public double coins() { return coins; }

    public double profit() {
        double revenue = coins;
        for (Map.Entry<String, Long> entry : itemCounts.entrySet()) {
            double unit = ItemPriceResolver.value(entry.getKey());
            if (unit > 0.0) revenue += unit * entry.getValue();
        }
        return revenue;
    }

    public double profitPerHour() {
        if (activeMillis <= 0L) return 0.0;
        return profit() / (activeMillis / 3_600_000.0);
    }

    public long sessionStartedMillis() { return sessionStartedMillis; }

    public List<PestRow> pestRows() {
        List<PestRow> rows = new ArrayList<>();
        long recorded = pestKills.values().stream().mapToLong(Long::longValue).sum();
        for (Map.Entry<String, Long> entry : pestKills.entrySet()) {
            rows.add(new PestRow(entry.getKey(), entry.getValue(), percentage(entry.getValue())));
        }
        long unrecorded = Math.max(0L, actions - recorded);
        if (unrecorded > 0L) rows.add(new PestRow("Not recorded", unrecorded, percentage(unrecorded)));
        return rows;
    }

    private double percentage(long count) {
        if (actions <= 0L) return 0.0;
        return count * 100.0 / actions;
    }

    private void handleMessage(Component message) {
        String clean = message.getString().replaceAll("§[0-9a-fk-or]", "");
        String lower = clean.toLowerCase(Locale.ROOT);
        if (lower.contains("pest") && (lower.contains("killed") || lower.contains("vacuumed") || lower.contains("slain"))) {
            String pest = extractPestName(clean);
            pestKills.merge(pest, 1L, Long::sum);
            markAction();
            return;
        }

        // SkyBlock sack messages are intentionally parsed without relying on SkySoft.
        // Examples include: "+1 Wheat" / "+1,024 Enchanted Wheat".
        if (lower.contains("sack") || lower.contains("[sacks]")) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?:\\+|added\\s+)([\\d,]+)\\s+(.+?)(?=$|,|\\s{2,})", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(clean);
            while (matcher.find()) {
                long amount;
                try { amount = Long.parseLong(matcher.group(1).replace(",", "")); }
                catch (NumberFormatException ignored) { continue; }
                String id = displayToId.get(normalize(matcher.group(2)));
                if (id != null && amount > 0L) {
                    itemCounts.merge(id, amount, Long::sum);
                    markActivityOnly();
                }
            }
        }
    }

    private String extractPestName(String message) {
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("(?:killed|vacuumed|slain)\\s+(?:a\\s+)?(.+?)(?:!|\\.|$)", java.util.regex.Pattern.CASE_INSENSITIVE)
            .matcher(message);
        return m.find() ? m.group(1).trim() : "Pest";
    }

    private void markAction() {
        actions++;
        markActivityOnly();
    }

    private void markActivityOnly() {
        lastActivityMillis = System.currentTimeMillis();
    }

    private boolean isFarmingBlock(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("wheat") || lower.contains("carrot") || lower.contains("potato")
            || lower.contains("beetroot") || lower.contains("nether_wart") || lower.contains("cocoa")
            || lower.contains("sugar_cane") || lower.contains("pumpkin") || lower.contains("melon")
            || lower.contains("cactus") || lower.contains("mushroom") || lower.contains("crop");
    }

    private Map<String, Long> readInventoryCounts(Minecraft minecraft) {
        Map<String, Long> result = new HashMap<>();
        Object inventory = minecraft.player.getInventory();
        try {
            Field items = findField(inventory.getClass(), "items");
            items.setAccessible(true);
            Object value = items.get(inventory);
            if (!(value instanceof Iterable<?> iterable)) return result;
            for (Object stack : iterable) {
                if (stack == null) continue;
                Method isEmpty = stack.getClass().getMethod("isEmpty");
                if ((Boolean) isEmpty.invoke(stack)) continue;
                Method count = stack.getClass().getMethod("getCount");
                Method name = stack.getClass().getMethod("getHoverName");
                long amount = ((Number) count.invoke(stack)).longValue();
                String display = ((Component) name.invoke(stack)).getString();
                result.merge(display, amount, Long::sum);
            }
        } catch (Throwable ignored) {
            // Inventory layout is version-sensitive; a failed poll should not crash the client.
        }
        return result;
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try { return current.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { current = current.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }

    private void registerCropNames() {
        add("Wheat", "WHEAT");
        add("Seeds", "SEEDS");
        add("Enchanted Bread", "ENCHANTED_BREAD");
        add("Enchanted Wheat", "ENCHANTED_WHEAT");
        add("Hay Bale", "HAY_BLOCK");
        add("Enchanted Hay Bale", "ENCHANTED_HAY_BLOCK");
        add("Carrot", "CARROT_ITEM");
        add("Enchanted Carrot", "ENCHANTED_CARROT");
        add("Enchanted Golden Carrot", "ENCHANTED_GOLDEN_CARROT");
        add("Potato", "POTATO_ITEM");
        add("Enchanted Potato", "ENCHANTED_POTATO");
        add("Enchanted Baked Potato", "ENCHANTED_BAKED_POTATO");
        add("Sugar Cane", "SUGAR_CANE");
        add("Enchanted Sugar", "ENCHANTED_SUGAR");
        add("Enchanted Sugar Cane", "ENCHANTED_SUGAR_CANE");
        add("Cocoa Beans", "INK_SACK:3");
        add("Enchanted Cocoa Bean", "ENCHANTED_COCOA_BEANS");
        add("Nether Wart", "NETHER_STALK");
        add("Enchanted Nether Wart", "ENCHANTED_NETHER_STALK");
        add("Pumpkin", "PUMPKIN");
        add("Enchanted Pumpkin", "ENCHANTED_PUMPKIN");
        add("Polished Pumpkin", "POLISHED_PUMPKIN");
        add("Melon", "MELON");
        add("Enchanted Melon", "ENCHANTED_MELON");
        add("Enchanted Melon Block", "ENCHANTED_MELON_BLOCK");
        add("Cactus", "CACTUS");
        add("Enchanted Cactus Green", "ENCHANTED_CACTUS_GREEN");
        add("Enchanted Cactus", "ENCHANTED_CACTUS");
        add("Red Mushroom", "RED_MUSHROOM");
        add("Brown Mushroom", "BROWN_MUSHROOM");
        add("Enchanted Red Mushroom", "ENCHANTED_RED_MUSHROOM");
        add("Enchanted Brown Mushroom", "ENCHANTED_BROWN_MUSHROOM");
        add("Feastfungus", "FEASTFUNGUS");
        add("Moonflower", "MOONFLOWER");
        add("Enchanted Moonflower", "ENCHANTED_MOONFLOWER");
        add("Wild Rose", "WILD_ROSE");
        add("Enchanted Wild Rose", "ENCHANTED_WILD_ROSE");
    }

    private void add(String display, String id) {
        displayToId.put(normalize(display), id);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("§[0-9a-fk-or]", "")
            .replace("✦", "").replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    public record PestRow(String name, long count, double percentage) {}
}
