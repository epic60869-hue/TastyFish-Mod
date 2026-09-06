package com.epic60869.tastyfish;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Locale;

/** Standalone SkySoft-style farming profit HUD owned by TastyFish. */
public final class FarmingProfitHud {
    private static final Identifier ID = Identifier.fromNamespaceAndPath("tastyfish-mod", "farming_profit");
    private static final int DEFAULT_X = 8;
    private static final int DEFAULT_Y = 8;
    private static int x = DEFAULT_X;
    private static int y = DEFAULT_Y;
    private static TastyFishConfig config;

    private FarmingProfitHud() {}

    public static void register(TastyFishConfig tastyFishConfig) {
        config = tastyFishConfig;
        x = config.profitHudX;
        y = config.profitHudY;
        HudElementRegistry.addLast(ID, FarmingProfitHud::extract);
    }

    public static int x() { return x; }
    public static int y() { return y; }
    public static int width() { return 190; }
    public static int height() { return 72 + FarmingProfitTracker.get().pestRows().size() * 12; }

    public static void setPosition(int newX, int newY) {
        x = newX;
        y = newY;
        if (config != null) {
            config.profitHudX = newX;
            config.profitHudY = newY;
            config.save();
        }
    }

    public static void extract(GuiGraphicsExtractor graphics, net.minecraft.client.DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.gui.hud.isHidden()) return;

        FarmingProfitTracker tracker = FarmingProfitTracker.get();
        double profit = tracker.profit();
        double perHour = tracker.profitPerHour();
        String profitText = "Total Profit: " + coinFormat(profit);
        String hourlyText = "Profit/h: " + coinFormat(perHour);
        String pestsText = "Pests Killed: " + tracker.pestKills().values().stream().mapToLong(Long::longValue).sum();

        graphics.fill(x - 3, y - 3, x + width(), y + 69, 0x88000000);
        bold(graphics, "TastyFish Farming", x, y, 0xFFFFFF55);
        bold(graphics, profitText, x, y + 13, 0xFFFFFFFF);
        bold(graphics, hourlyText, x, y + 25, 0xFFAAAAAA);
        bold(graphics, "Items Tracked: " + tracker.itemCounts().values().stream().mapToLong(Long::longValue).sum(), x, y + 37, 0xFFAAAAAA);
        bold(graphics, pestsText, x, y + 49, 0xFFFFFFFF);

        if (isMouseOverPests(graphics)) {
            renderPestTooltip(graphics, tracker.pestRows(), y + 64);
        }
    }

    /** Used by the GUI editor; renders a stable preview even when no session is active. */
    public static void renderPreview(GuiGraphicsExtractor graphics, int px, int py, boolean selected) {
        int outline = selected ? 0xFFFFFF55 : 0xFF777777;
        graphics.fill(px - 3, py - 3, px + width(), py + 69, 0x66000000);
        graphics.fill(px - 4, py - 4, px + width() + 1, py - 3, outline);
        graphics.fill(px - 4, py + 69, px + width() + 1, py + 70, outline);
        bold(graphics, "TastyFish Farming", px, py, 0xFFFFFF55);
        bold(graphics, "Total Profit: 12.3M", px, py + 13, 0xFFFFFFFF);
        bold(graphics, "Profit/h: 81.6M", px, py + 25, 0xFFAAAAAA);
        bold(graphics, "Items Tracked: 12,345", px, py + 37, 0xFFAAAAAA);
        bold(graphics, "Pests Killed: 11", px, py + 49, 0xFFFFFFFF);
    }

    private static boolean isMouseOverPests(GuiGraphicsExtractor graphics) {
        try {
            Minecraft mc = Minecraft.getInstance();
            Object mouse = mc.mouseHandler;
            var xpos = mouse.getClass().getMethod("xpos");
            var ypos = mouse.getClass().getMethod("ypos");
            double sx = ((Number) xpos.invoke(mouse)).doubleValue();
            double sy = ((Number) ypos.invoke(mouse)).doubleValue();
            double screenW = mc.getWindow().getScreenWidth();
            double screenH = mc.getWindow().getScreenHeight();
            int mx = (int) Math.floor(sx * graphics.guiWidth() / screenW);
            int my = (int) Math.floor(sy * graphics.guiHeight() / screenH);
            return mx >= x && mx <= x + width() && my >= y + 47 && my <= y + 63;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void renderPestTooltip(GuiGraphicsExtractor graphics, List<FarmingProfitTracker.PestRow> rows, int top) {
        int width = 170;
        int height = 8 + Math.max(1, rows.size()) * 12;
        int left = x;
        graphics.fill(left - 4, top, left + width, top + height, 0xEE111111);
        bold(graphics, "Pests Vacuumed", left, top + 3, 0xFFFFFF55);
        int lineY = top + 15;
        if (rows.isEmpty()) {
            bold(graphics, "No pests recorded yet.", left, lineY, 0xFFAAAAAA);
            return;
        }
        for (FarmingProfitTracker.PestRow row : rows) {
            String percentage = String.format(Locale.ROOT, "%.1f", row.percentage()).replace(".0", "");
            bold(graphics, row.name() + " " + row.count() + " (" + percentage + "%)", left, lineY, 0xFFDDDDDD);
            lineY += 12;
        }
    }

    private static void bold(GuiGraphicsExtractor graphics, String text, int px, int py, int color) {
        graphics.text(Minecraft.getInstance().font, text, px, py, color, true);
    }

    private static String coinFormat(double value) {
        if (!Double.isFinite(value)) return "0";
        if (Math.abs(value) >= 1_000_000_000) return String.format(Locale.ROOT, "%.2fB", value / 1_000_000_000.0);
        if (Math.abs(value) >= 1_000_000) return String.format(Locale.ROOT, "%.2fM", value / 1_000_000.0);
        if (Math.abs(value) >= 1_000) return String.format(Locale.ROOT, "%.1fk", value / 1_000.0);
        return String.format(Locale.ROOT, "%.0f", value);
    }
}
