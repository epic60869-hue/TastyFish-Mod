package com.epic60869.tastyfish;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** TastyFish-owned farming RNG detection. Profit tracking remains SkySoft-owned. */
public final class FarmingRngTracker {
    private static final FarmingRngTracker INSTANCE = new FarmingRngTracker();
    private static final long SHOW_MILLIS = 5000L;
    private static final Pattern RARE_MESSAGE = Pattern.compile(
        "(?i)^(?:.*?)(?:RARE CROP!|(?:VERY |CRAZY |PRAY TO RNGESUS |RNGESUS INCARNATE )?RARE DROP!?|VERY RARE DROP!?|CRAZY RARE DROP!?|PRAY TO RNGESUS!?|RNGESUS INCARNATE!?)[ ]*(?<item>.+?)\\s*(?:\\(\\+[^)]*\\))?[! ]*$"
    );
    private static final Pattern OLDER_DROP = Pattern.compile(
        "(?i)(?:you (?:found|dropped|got)|you received|drop(?:ped)?[: ])\\s*(?:an? |some )?(?<item>.+?)(?:!|$)"
    );
    private static final Pattern QUANTITY = Pattern.compile("(?i)^(?:x(?<x1>\\d+)\\s+|(?<x2>\\d+)x\\s+)(?<item>.+)$");
    private static final Set<String> DROPS = Set.of(
        "Cornucopia", "Carrot Zest", "Deepfries", "Aggourdian", "Cane Knot", "Melon Juice", "Cactus Flower",
        "Designer Coffee Beans", "Feastfungus", "Botroot", "Salted Sunflower Seeds", "Crystalized Moonlight",
        "Floral Gelatin", "Cropie", "Squash", "Fermento", "Burrowing Spores", "Overgrown Grass", "Green Bandana",
        "Dedication IV", "Dedication 4", "Flowering Bouquet", "Rooted Spores", "Fruit Bowl", "Atmospheric Filter",
        "Beady Eyes", "Clipped Wings", "Mantid Claw", "Wriggling Larva", "Locust Larva", "Squeaky Toy",
        "Squeaky Mousemat", "Vermin Vaporizer", "Synthesis", "Evergreen", "Quickdraw", "Hypercharge", "Fire in a Bottle",
        "Iridium", "Overclocker 3000", "Rabbit Hat", "Lucky Clover Core", "Bulky Stone", "Turbo-Wheat V",
        "Turbo-Carrot V", "Turbo-Potato V", "Turbo-Pumpkin V", "Turbo-Melon V", "Turbo-Cocoa V", "Turbo-Cactus V",
        "Turbo-Mushrooms V", "Turbo-Cane V", "Turbo-Warts V", "Cultivating X", "Cultivating 10", "Seasoning"
    );

    private volatile Drop active;
    private FarmingRngTracker() {}
    public static FarmingRngTracker get() { return INSTANCE; }

    public void register() {
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> handle(message));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> handle(message));
    }

    public Drop active() {
        Drop current = active;
        if (current != null && System.currentTimeMillis() > current.shownUntil()) {
            active = null;
            return null;
        }
        return current;
    }

    private void handle(Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        String raw = message.getString().replaceAll("§[0-9a-fk-or]", "").trim();
        Parsed parsed = parse(raw);
        if (parsed == null) return;
        long unitPrice = "Seasoning".equalsIgnoreCase(parsed.name()) ? -1L : Math.round(ItemPriceResolver.valueByName(parsed.name()));
        active = new Drop(parsed.amount(), parsed.name(), rarity(raw), unitPrice, System.currentTimeMillis() + SHOW_MILLIS);
    }

    private Parsed parse(String raw) {
        Matcher match = RARE_MESSAGE.matcher(raw);
        String item;
        if (match.find()) item = match.group("item").trim();
        else {
            String lower = raw.toLowerCase(Locale.ROOT);
            if (!lower.contains("rare drop") && !lower.contains("rngesus")) return null;
            Matcher older = OLDER_DROP.matcher(raw);
            if (!older.find()) return null;
            item = older.group("item").trim();
        }
        item = item.replaceAll("\\s*\\(\\+[^)]*\\)\\s*$", "").replaceAll("!$", "").trim();
        int amount = 1;
        Matcher quantity = QUANTITY.matcher(item);
        if (quantity.matches()) {
            amount = parseInt(quantity.group("x1"), quantity.group("x2"));
            item = quantity.group("item").trim();
        }
        for (String known : DROPS) {
            if (item.equalsIgnoreCase(known) || item.toLowerCase(Locale.ROOT).contains(known.toLowerCase(Locale.ROOT))) {
                return new Parsed(amount, known);
            }
        }
        return null;
    }

    private static int parseInt(String first, String second) {
        try { return Integer.parseInt(first != null ? first : second); }
        catch (Exception ignored) { return 1; }
    }

    private static String rarity(String message) {
        String text = message.toLowerCase(Locale.ROOT);
        if (text.contains("rare crop")) return "RARE CROP";
        if (text.contains("crazy rare")) return "CRAZY RARE";
        if (text.contains("rngesus")) return "RNGESUS";
        return "RARE DROP";
    }

    public record Drop(int amount, String name, String rarity, long unitPrice, long shownUntil) {}
    private record Parsed(int amount, String name) {}
}
