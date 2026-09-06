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
    private static int x = 8, y = 8;
    private static TastyFishConfig config;
    private FarmingProfitHud() {}

    public static void register(TastyFishConfig cfg) {
        config = cfg; x = cfg.profitHudX; y = cfg.profitHudY;
        HudElementRegistry.addLast(ID, FarmingProfitHud::extract);
    }
    public static int x() { return x; } public static int y() { return y; }
    public static int width() { return 190; }
    public static int height() { return 72; }
    public static void setPosition(int nx, int ny) { x = nx; y = ny; if (config != null) { config.profitHudX = nx; config.profitHudY = ny; config.save(); } }

    public static void extract(GuiGraphicsExtractor graphics, net.minecraft.client.DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || hudHidden(mc)) return;
        FarmingProfitTracker t = FarmingProfitTracker.get();
        graphics.fill(x - 3, y - 3, x + width(), y + 69, 0x88000000);
        bold(graphics, "TastyFish Farming", x, y, 0xFFFFFF55);
        bold(graphics, "Total Profit: " + coinFormat(t.profit()), x, y + 13, 0xFFFFFFFF);
        bold(graphics, "Profit/h: " + coinFormat(t.profitPerHour()), x, y + 25, 0xFFAAAAAA);
        bold(graphics, "Items Tracked: " + t.itemCounts().values().stream().mapToLong(Long::longValue).sum(), x, y + 37, 0xFFAAAAAA);
        bold(graphics, "Pests Killed: " + t.pestKills().values().stream().mapToLong(Long::longValue).sum(), x, y + 49, 0xFFFFFFFF);
        if (isMouseOverPests(graphics)) renderPestTooltip(graphics, t.pestRows(), y + 64);
    }

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

    private static boolean hudHidden(Minecraft mc) {
        try { return (Boolean) mc.gui.getClass().getMethod("isHidden").invoke(mc.gui); }
        catch (Throwable ignored) { return false; }
    }
    private static boolean isMouseOverPests(GuiGraphicsExtractor graphics) {
        try {
            Minecraft mc = Minecraft.getInstance(); Object mouse = mc.mouseHandler;
            double sx = ((Number) mouse.getClass().getMethod("xpos").invoke(mouse)).doubleValue();
            double sy = ((Number) mouse.getClass().getMethod("ypos").invoke(mouse)).doubleValue();
            int mx = (int) Math.floor(sx * graphics.guiWidth() / mc.getWindow().getScreenWidth());
            int my = (int) Math.floor(sy * graphics.guiHeight() / mc.getWindow().getScreenHeight());
            return mx >= x && mx <= x + width() && my >= y + 47 && my <= y + 63;
        } catch (Throwable ignored) { return false; }
    }
    private static void renderPestTooltip(GuiGraphicsExtractor graphics, List<FarmingProfitTracker.PestRow> rows, int top) {
        int width = 170, height = 8 + Math.max(1, rows.size()) * 12, left = x;
        graphics.fill(left - 4, top, left + width, top + height, 0xEE111111);
        bold(graphics, "Pests Vacuumed", left, top + 3, 0xFFFFFF55);
        int lineY = top + 15;
        if (rows.isEmpty()) { bold(graphics, "No pests recorded yet.", left, lineY, 0xFFAAAAAA); return; }
        for (FarmingProfitTracker.PestRow row : rows) {
            String p = String.format(Locale.ROOT, "%.1f", row.percentage()).replace(".0", "");
            bold(graphics, row.name() + " " + row.count() + " (" + p + "%)", left, lineY, 0xFFDDDDDD); lineY += 12;
        }
    }
    private static void bold(GuiGraphicsExtractor graphics, String text, int px, int py, int color) { graphics.text(Minecraft.getInstance().font, text, px, py, color, true); }
    private static String coinFormat(double v) {
        if (!Double.isFinite(v)) return "0";
        if (Math.abs(v) >= 1e9) return String.format(Locale.ROOT, "%.2fB", v / 1e9);
        if (Math.abs(v) >= 1e6) return String.format(Locale.ROOT, "%.2fM", v / 1e6);
        if (Math.abs(v) >= 1e3) return String.format(Locale.ROOT, "%.1fk", v / 1e3);
        return String.format(Locale.ROOT, "%.0f", v);
    }
}
