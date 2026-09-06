package com.epic60869.tastyfish;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Locale;

public final class TastyFishRngHud {
    private static final Identifier ID = Identifier.fromNamespaceAndPath("tastyfish-mod", "farming_rng");
    private static final int BASE_WIDTH = 300;
    private static final int LINE_HEIGHT = 14;
    private static final int BASE_HEIGHT = 18;
    private static TastyFishConfig config;

    private TastyFishRngHud() {}

    public static void register(TastyFishConfig cfg) {
        config = cfg;
        HudElementRegistry.addLast(ID, TastyFishRngHud::extract);
    }

    private static void extract(GuiGraphicsExtractor graphics, net.minecraft.client.DeltaTracker deltaTracker) {
        if (config == null || !config.farmingRngEnabled || Minecraft.getInstance().player == null) return;
        List<FarmingRngTracker.Drop> drops = FarmingRngTracker.get().active();
        if (drops.isEmpty()) return;
        render(graphics, drops, config.farmingRngX, config.farmingRngY);
    }

    public static int width() {
        return Math.max(1, Math.round(BASE_WIDTH * scale()));
    }

    public static int height() {
        int lines = Math.max(1, FarmingRngTracker.get().active().size());
        return Math.max(1, Math.round((4 + lines * LINE_HEIGHT) * scale()));
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
        render(graphics, List.of(
            new FarmingRngTracker.Drop(1, "Crystalized Moonlight", "RARE DROP", 500000, Long.MAX_VALUE),
            new FarmingRngTracker.Drop(2, "Designer Coffee Beans", "RARE DROP", 500000, Long.MAX_VALUE)
        ), x, y);
    }

    private static void render(GuiGraphicsExtractor graphics,
                               List<FarmingRngTracker.Drop> drops,
                               int x,
                               int y) {
        float s = scale();
        graphics.pose().pushMatrix();
        graphics.pose().translate((float) x, (float) y);
        graphics.pose().scale(s, s);

        int contentHeight = 4 + drops.size() * LINE_HEIGHT;
        if (config != null && config.farmingRngBackground) {
            graphics.fill(-5, -3, BASE_WIDTH + 4, contentHeight + 1, 0xA8000000);
            graphics.fill(-5, -3, BASE_WIDTH + 4, -2, 0x55FFFFFF);
            graphics.fill(-5, contentHeight, BASE_WIDTH + 4, contentHeight + 1, 0x33000000);
        }

        int yOffset = 0;
        for (FarmingRngTracker.Drop drop : drops) {
            String item = drop.amount() + "x " + drop.name();
            String price = drop.unitPrice() < 0
                ? "—"
                : formatCoins(drop.unitPrice() * drop.amount());

            // One complete drop per line: amount, item name, then total value.
            drawShadowed(graphics, item, 4, yOffset, 0xFFFFFFFF, true);
            int priceX = Math.min(BASE_WIDTH - 4 - Minecraft.getInstance().font.width(price),
                4 + Minecraft.getInstance().font.width(item) + 8);
            drawShadowed(graphics, price, priceX, yOffset, 0xFFB8B8B8, false);
            yOffset += LINE_HEIGHT;
        }

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
            return compactNumber(value / 1_000_000_000.0, "b");
        }
        if (value >= 1_000_000L) {
            return compactNumber(value / 1_000_000.0, "m");
        }
        if (value >= 1_000L) {
            return compactNumber(value / 1_000.0, "k");
        }
        return Long.toString(value);
    }

    private static String compactNumber(double value, String suffix) {
        String formatted = String.format(Locale.ROOT, "%.2f", value)
            .replaceAll("0+$", "")
            .replaceAll("\\.$", "");
        return formatted + suffix;
    }

    private static void save() {
        Minecraft mc = Minecraft.getInstance();
        config.save(mc.gameDirectory.toPath().resolve("config").resolve("tastyfish-mod.json"));
    }
}
