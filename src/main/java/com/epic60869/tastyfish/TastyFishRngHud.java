package com.epic60869.tastyfish;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Locale;

/**
 * TastyFish farming RNG overlay. It uses the normal Minecraft font with a small
 * shadow/bold treatment and shares the same position editor as the profit HUD.
 */
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
        HudElementRegistry.addLast(ID, TastyFishRngHud::extract);
    }

    public static int x() { return x; }
    public static int y() { return y; }
    public static int width() { return 210; }
    public static int height() { return 60; }

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
        graphics.fill(x - 3, y - 3, x + width(), y + height(), 0x66000000);
        bold(graphics, "Farming RNG", x, y, 0xFFFFFF55);
        bold(graphics, "Rare Crop Drops", x, y + 13, 0xFFFFFFFF);
        bold(graphics, "Tracked prices: NPC / Bazaar", x, y + 25, 0xFFAAAAAA);
        bold(graphics, "Seasoning: —", x, y + 37, 0xFFAAAAAA);
    }

    public static void renderPreview(GuiGraphicsExtractor graphics, int px, int py, boolean selected) {
        int outline = selected ? 0xFFFFFF55 : 0xFF777777;
        graphics.fill(px - 4, py - 4, px + width() + 1, py + height() + 1, 0x55000000);
        graphics.fill(px - 4, py - 4, px + width() + 1, py - 3, outline);
        graphics.fill(px - 4, py + height(), px + width() + 1, py + height() + 1, outline);
        bold(graphics, "Farming RNG", px, py, 0xFFFFFF55);
        bold(graphics, "Rare Crop Drop", px, py + 13, 0xFFFFFFFF);
        bold(graphics, "Example Crop   100k", px, py + 25, 0xFFDDDDDD);
        bold(graphics, "Seasoning       —", px, py + 37, 0xFFAAAAAA);
    }

    private static void bold(GuiGraphicsExtractor graphics, String text, int px, int py, int color) {
        graphics.text(Minecraft.getInstance().font, text, px, py, color, true);
    }
}
