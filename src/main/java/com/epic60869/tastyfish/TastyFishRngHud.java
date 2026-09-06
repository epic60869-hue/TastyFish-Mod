package com.epic60869.tastyfish;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.Locale;

public final class TastyFishRngHud {
    private static final Identifier ID = Identifier.fromNamespaceAndPath("tastyfish-mod", "farming_rng");
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

    public static int width() { return 230; }
    public static int height() { return 48; }

    public static void setPosition(int x, int y) {
        if (config == null) return;
        config.farmingRngX = Math.max(0, x);
        config.farmingRngY = Math.max(0, y);
        save();
    }

    public static void renderPreview(GuiGraphicsExtractor graphics, int x, int y) {
        render(graphics, new FarmingRngTracker.Drop(1, "Overgrown Grass", "RARE DROP", 125000, Long.MAX_VALUE), x, y);
    }

    private static void render(GuiGraphicsExtractor graphics, FarmingRngTracker.Drop drop, int x, int y) {
        if (config != null && config.farmingRngBackground) graphics.fill(x - 5, y - 5, x + width(), y + height(), 0xB5000000);
        graphics.text(Minecraft.getInstance().font, drop.rarity(), x, y, 0xFFFFFF55, true);
        graphics.text(Minecraft.getInstance().font, (drop.amount() > 1 ? drop.amount() + "x " : "") + drop.name(), x, y + 15, 0xFFFFFFFF, true);
        String price = drop.unitPrice() < 0 ? "—" : formatCoins(drop.unitPrice() * drop.amount());
        graphics.text(Minecraft.getInstance().font, price.equals("—") ? price : price + " coins", x, y + 30, 0xFFAAAAAA, true);
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
