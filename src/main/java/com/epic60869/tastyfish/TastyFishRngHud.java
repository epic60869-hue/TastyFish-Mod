package com.epic60869.tastyfish;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.Locale;

public final class TastyFishRngHud {
    private static final Identifier ID = Identifier.fromNamespaceAndPath("tastyfish-mod", "farming_rng");
    private static final int BASE_WIDTH = 230;
    private static final int BASE_HEIGHT = 42;
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

    public static int width() { return Math.max(1, Math.round(BASE_WIDTH * scale())); }
    public static int height() { return Math.max(1, Math.round(BASE_HEIGHT * scale())); }
    public static float scale() { return config == null ? 1.0f : config.farmingRngScale; }

    public static void setPosition(int x, int y) {
        if (config == null) return;
        config.farmingRngX = Math.max(0, x);
        config.farmingRngY = Math.max(0, y);
        save();
    }

    public static void setScale(float value) {
        if (config == null) return;
        config.farmingRngScale = Math.max(0.5f, Math.min(3.0f, Math.round(value * 10.0f) / 10.0f));
        save();
    }

    public static void changeScale(float amount) {
        setScale(scale() + amount);
    }

    public static String scaleText() {
        return String.format(Locale.ROOT, "%.1fx", scale());
    }

    public static void renderPreview(GuiGraphicsExtractor graphics, int x, int y) {
        render(graphics, new FarmingRngTracker.Drop(1, "Overgrown Grass", "RARE DROP", 125000, Long.MAX_VALUE), x, y);
    }

    private static void render(GuiGraphicsExtractor graphics, FarmingRngTracker.Drop drop, int x, int y) {
        float s = scale();
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale(s, s, 1.0f);

        if (config != null && config.farmingRngBackground) {
            graphics.fill(-6, -5, BASE_WIDTH, BASE_HEIGHT, 0xB5101014);
            graphics.fill(0, 0, 3, BASE_HEIGHT, 0xFFFFD84D);
        }

        String item = (drop.amount() > 1 ? drop.amount() + "x " : "") + drop.name();
        String price = drop.unitPrice() < 0 ? "—" : formatCoins(drop.unitPrice() * drop.amount()) + " coins";

        graphics.text(Minecraft.getInstance().font, drop.rarity(), 9, 2, 0xFFFFFF55, true);
        graphics.text(Minecraft.getInstance().font, item, 9, 15, 0xFFFFFFFF, true);
        graphics.text(Minecraft.getInstance().font, price, 9, 29, 0xFFAAAAAA, false);

        graphics.pose().popPose();
    }

    private static String formatCoins(long value) {
        if (value >= 1_000_000_000L) return String.format(Locale.ROOT, "%.2fB", value / 1_000_000_000.0);
        if (value >= 1_000_000L) return String.format(Locale.ROOT, "%.2fM", value / 1_000_000.0);
        if (value >= 1_000L) return String.format(Locale.ROOT, "%.1fk", value / 1_000.0);
        return Long.toString(value);
    }

    private static void save() {
        Minecraft mc = Minecraft.getInstance();
        config.save(mc.gameDirectory.toPath().resolve("config").resolve("tastyfish-mod.json"));
    }
}
