package com.epic60869.tastyfish;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public final class TastyFishScreen extends Screen {
    private final TastyFishConfig config;
    private boolean farmingPage;
    private float toggleProgress;
    private long lastFrame;

    public TastyFishScreen(TastyFishConfig config) {
        super(Component.literal("TastyFish Settings"));
        this.config = config;
        this.farmingPage = true;
        this.toggleProgress = config.farmingRngEnabled ? 1f : 0f;
    }

    @Override protected void init() {
        clearWidgets();
        addRenderableWidget(Button.builder(Component.literal("Farming"), b -> { farmingPage = true; rebuild(); })
            .bounds(12, 48, 96, 22).build());
        addRenderableWidget(Button.builder(Component.literal("GUI"), b -> Minecraft.getInstance().gui.setScreen(new TastyFishGuiEditor(config)))
            .bounds(12, 76, 96, 22).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
            .bounds(width - 92, height - 32, 80, 20).build());
    }

    private void rebuild() { clearWidgets(); init(); }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        long now = System.nanoTime();
        float step = lastFrame == 0L ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1_000_000_000f);
        lastFrame = now;
        float target = config.farmingRngEnabled ? 1f : 0f;
        toggleProgress += (target - toggleProgress) * Math.min(1f, step * 12f);

        graphics.fill(0, 0, width, height, 0xFF101014);
        graphics.fill(0, 0, 120, height, 0xFF17171C);
        graphics.text(font, "TastyFish", 14, 16, 0xFFFFFF55, true);
        graphics.text(font, "Settings", 14, 30, 0xFF888890, false);
        graphics.fill(112, 0, 113, height, 0xFF29292F);
        if (farmingPage) renderFarming(graphics);
    }

    private void renderFarming(GuiGraphicsExtractor graphics) {
        int left = 145;
        graphics.text(font, "Farming", left, 28, 0xFFFFFFFF, true);
        graphics.text(font, "Farming RNG Overlay", left, 56, 0xFFFFFFFF, true);
        graphics.text(font, "Show rare farming drops on screen", left, 70, 0xFF9999A2, false);
        int tx = width - 92, ty = 51;
        drawToggle(graphics, tx, ty, toggleProgress);
        graphics.text(font, "Background", left, 102, 0xFFFFFFFF, true);
        graphics.text(font, "Show a black background behind the overlay", left, 116, 0xFF9999A2, false);
        graphics.fill(tx, 96, tx + 44, 118, 0xFF303038);
        if (config.farmingRngBackground) graphics.fill(tx + 22, 96, tx + 44, 118, 0xFF6E6E78);
        graphics.fill(tx + (config.farmingRngBackground ? 24 : 2), 98, tx + (config.farmingRngBackground ? 42 : 20), 116, 0xFFE8E8EA);
        graphics.text(font, "Preview", left, 154, 0xFF777780, false);
        TastyFishRngHud.renderPreview(graphics, left, 172);
    }

    private void drawToggle(GuiGraphicsExtractor graphics, int x, int y, float progress) {
        graphics.fill(x, y, x + 44, y + 22, 0xFF303038);
        int knobX = x + 2 + Math.round(progress * 20f);
        graphics.fill(knobX, y + 2, knobX + 20, y + 20, 0xFFE8E8EA);
        if (progress > 0.5f) graphics.fill(x + 22, y, x + 44, y + 22, 0xFF6E6E78);
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && farmingPage) {
            int x = (int) event.x(), y = (int) event.y();
            if (x >= width - 100 && x <= width - 45 && y >= 45 && y <= 82) {
                config.farmingRngEnabled = !config.farmingRngEnabled;
                save();
                return true;
            }
            if (x >= width - 100 && x <= width - 45 && y >= 91 && y <= 126) {
                config.farmingRngBackground = !config.farmingRngBackground;
                save();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private void save() {
        Minecraft mc = Minecraft.getInstance();
        config.save(mc.gameDirectory.toPath().resolve("config").resolve("tastyfish-mod.json"));
    }
}
