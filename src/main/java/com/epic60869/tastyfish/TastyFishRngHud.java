package com.epic60869.tastyfish;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.Locale;

/** Farming RNG overlay using the normal Minecraft font with a slight shadow/bold treatment. */
public final class TastyFishRngHud {
    private static final Identifier ID = Identifier.fromNamespaceAndPath("tastyfish-mod", "farming_rng");
    private static int x = 8;
    private static int y = 95;
    private static TastyFishConfig config;

    private TastyFishRngHud() {}

    public static void register(TastyFishConfig tastyFishConfig) {
        config = tastyFishConfig;
        x = config.rngHudX;
        y = config.rngHudY;
        FarmingRngTracker.get().register();
        HudElementRegistry.addLast(ID, TastyFishRngHud::extract);
    }

    public static int x() { return x; }
    public static int y() { return y; }
    public static int width() { return 240; }
    public static int height() { return 52; }

    public static void setPosition(int newX, int newY) {
        x = newX;
        y = newY;
        if (config != null) {
            config.rngHudX = newX;
            config.rngHudY = newY;
            config.save();
        }
    }

    public static void extract(GuiGraphicsExtractor graphics, net.minecraft.client.DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gui.hud.isHidden()) return;
        FarmingRngTracker.Drop drop = FarmingRngTracker.get().active();
        if (drop == null) return;

        graphics.fill(x - 3, y - 3, x + width(), y + height(), 0x88000000);
        bold(graphics, "Farming RNG", x, y, 0xFFFFFF55);
        int color = switch (drop.rarity()) {
            case "RNGESUS" -> 0xFFAA00AA;
            case "CRAZY RARE" -> 0xFFFF55FF;
            case "RARE CROP" -> 0xFF5555FF;
            default -> 0xFFFFFF55;
        };
        bold(graphics, drop.amount() + "x " + drop.name(), x, y + 13, color);

        String value;
        if ("Seasoning".equalsIgnoreCase(drop.name())) {
            value = "—";
        } else if (drop.unitPrice() > 0L) {
            value = formatCoins(drop.unitPrice() * drop.amount());
        } else {
            // Keep a price visible for every non-Seasoning crop while the cache warms.
            value = "0 coins";
        }
        bold(graphics, value, x, y + 26, 0xFFFFAA00);
        bold(graphics, "Price source: NPC / Bazaar / LBIN", x, y + 39, 0xFFAAAAAA);
    }

    public static void renderPreview(GuiGraphicsExtractor graphics, int px, int py, boolean selected) {
        int outline = selected ? 0xFFFFFF55 : 0xFF777777;
        graphics.fill(px - 4, py - 4, px + width() + 1, py + height() + 1, 0x55000000);
        graphics.fill(px - 4, py - 4, px + width() + 1, py - 3, outline);
        graphics.fill(px - 4, py + height(), px + width() + 1, py + height() + 1, outline);
        bold(graphics, "Farming RNG", px, py, 0xFFFFFF55);
        bold(graphics, "1x Crystalized Moonlight", px, py + 13, 0xFF5555FF);
        bold(graphics, "2.00M coins", px, py + 26, 0xFFFFAA00);
        bold(graphics, "Seasoning: —", px, py + 39, 0xFFAAAAAA);
    }

    private static void bold(GuiGraphicsExtractor graphics, String text, int px, int py, int color) {
        graphics.text(Minecraft.getInstance().font, text, px, py, color, true);
    }

    private static String formatCoins(long value) {
        if (value >= 1_000_000_000L) return String.format(Locale.ROOT, "%.2fB coins", value / 1_000_000_000.0);
        if (value >= 1_000_000L) return String.format(Locale.ROOT, "%.2fM coins", value / 1_000_000.0);
        if (value >= 1_000L) return String.format(Locale.ROOT, "%.1fk coins", value / 1_000.0);
        return value + " coins";
    }
}
