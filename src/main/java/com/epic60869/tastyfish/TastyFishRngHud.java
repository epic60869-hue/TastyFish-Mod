package com.epic60869.tastyfish;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.Locale;

public final class TastyFishRngHud {
    private static final Identifier ID = Identifier.fromNamespaceAndPath("tastyfish-mod", "farming_rng");
    private static final int BASE_WIDTH = 250;
    private static final int BASE_HEIGHT = 48;
    private static TastyFishConfig config;

    private TastyFishRngHud() {}

    public static void register(TastyFishConfig cfg) {
        config = cfg;
        HudElementRegistry.addLast(ID, TastyFishRngHud::extract);
    }

    private static void extract(GuiGraphicsExtractor graphics, net.minecraft.client.DeltaTracker deltaTracker) {
        if (config == null || !config.farmingRngEnabled || Minecraft.getInstance().player == null) return;
        FarmingRngTracker.Drop drop = FarmingRngTracker.get().active();
        if (drop == null) return;
        render(graphics, drop, config.farmingRngX, config.farmingRngY);
    }

    public static int width() {
        return Math.max(1, Math.round(BASE_WIDTH * scale()));
    }

    public static int height() {
        return Math.max(1, Math.round(BASE_HEIGHT * scale()));
    }

    public static float scale() {
        return config == null ? 1.0f : config.farmingRngScale;
    }

    public static void setPosition(int x, int y) {
        if (config == null) return;
        config.farmingRngX = Math.max(0, x);
        config.farmingRngY = Math.max(0, y);
        save();
    }

    public static void setScale(float value) {
        if (config == null) return;
        config.farmingRngScale = Math.max(0.5f, Math.min(3.0f,
            Math.round(value * 10.0f) / 10.0f));
        save();
    }

    public static void changeScale(float amount) {
        setScale(scale() + amount);
    }

    public static String scaleText() {
        return String.format(Locale.ROOT, "%.1fx", scale());
    }

    public static void renderPreview(GuiGraphicsExtractor graphics, int x, int y) {
        render(graphics,
            new FarmingRngTracker.Drop(1, "Overgrown Grass", "RARE DROP", 125000, Long.MAX_VALUE),
            x, y);
    }

    private static void render(GuiGraphicsExtractor graphics,
                               FarmingRngTracker.Drop drop,
                               int x,
                               int y) {
        float s = scale();
        graphics.pose().pushMatrix();
        graphics.pose().translate((float) x, (float) y);
        graphics.pose().scale(s, s);

        // Compact Nopo/Overflow-style info HUD: subtle dark panel, small yellow
        // category line, bold white result, and a muted value line.
        if (config != null && config.farmingRngBackground) {
            graphics.fill(-5, -4, BASE_WIDTH + 4, BASE_HEIGHT + 2, 0xA8000000);
            graphics.fill(-5, -4, BASE_WIDTH + 4, -3, 0x55FFFFFF);
            graphics.fill(-5, BASE_HEIGHT + 1, BASE_WIDTH + 4, BASE_HEIGHT + 2, 0x33000000);
        }

        String rarity = drop.rarity();
        String item = (drop.amount() > 1 ? drop.amount() + "x " : "") + drop.name();
        String price = drop.unitPrice() < 0
            ? "—"
            : formatCoins(drop.unitPrice() * drop.amount()) + " coins";

        drawShadowed(graphics, rarity, 6, 0, 0xFFFFD84D, true);
        drawShadowed(graphics, item, 6, 13, 0xFFFFFFFF, true);
        drawShadowed(graphics, price, 6, 28, 0xFFB8B8B8, false);

        graphics.pose().popMatrix();
    }

    private static void drawShadowed(GuiGraphicsExtractor graphics,
                                     String text,
                                     int x,
                                     int y,
                                     int color,
                                     boolean bold) {
        var font = Minecraft.getInstance().font;
        graphics.text(font, text, x + 1, y + 1, 0xAA000000, false);
        graphics.text(font, text, x, y, color, bold);
    }

    private static String formatCoins(long value) {
        if (value >= 1_000_000_000L) {
            return String.format(Locale.ROOT, "%.2fB", value / 1_000_000_000.0);
        }
        if (value >= 1_000_000L) {
            return String.format(Locale.ROOT, "%.2fM", value / 1_000_000.0);
        }
        if (value >= 1_000L) {
            return String.format(Locale.ROOT, "%.1fk", value / 1_000.0);
        }
        return Long.toString(value);
    }

    private static void save() {
        Minecraft mc = Minecraft.getInstance();
        config.save(mc.gameDirectory.toPath().resolve("config").resolve("tastyfish-mod.json"));
    }
}
