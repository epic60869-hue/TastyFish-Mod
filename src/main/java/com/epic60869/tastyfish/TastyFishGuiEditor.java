package com.epic60869.tastyfish;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public final class TastyFishGuiEditor extends Screen {
    private final TastyFishConfig config;
    private boolean dragging;
    private double dragOffsetX, dragOffsetY;

    public TastyFishGuiEditor(TastyFishConfig config) {
        super(Component.literal("TastyFish HUD Editor"));
        this.config = config;
    }

    @Override protected void init() {
        addRenderableWidget(Button.builder(Component.literal("Reset RNG"), b -> {
            TastyFishRngHud.setPosition(8, 8);
            TastyFishRngHud.setScale(1.0f);
        }).bounds(12, 42, 90, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
            .bounds(width - 82, height - 28, 70, 20).build());
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, width, height, 0xB0101014);
        g.fill(8, 8, width - 8, height - 38, 0x40101014);
        g.text(font, "TastyFish HUD Editor", 14, 15, 0xFFFFFFFF, true);
        g.text(font, "Drag the Farming RNG overlay. Scroll over it to change text size.", 14, 28, 0xFFAAAAAA, false);
        TastyFishRngHud.renderPreview(g, config.farmingRngX, config.farmingRngY);
        g.fill(Math.max(0, config.farmingRngX - 6), Math.max(0, config.farmingRngY - 6),
            Math.min(width, config.farmingRngX + TastyFishRngHud.width()),
            Math.min(height - 38, config.farmingRngY + TastyFishRngHud.height()), 0x12000000);
        g.text(font, "Scale: " + TastyFishRngHud.scaleText(), 14, height - 48, 0xFFCCCCCC, false);
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && hitHud(event.x(), event.y())) {
            dragging = true;
            dragOffsetX = event.x() - config.farmingRngX;
            dragOffsetY = event.y() - config.farmingRngY;
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragging && event.button() == 0) {
            int x = (int)Math.round(event.x() - dragOffsetX);
            int y = (int)Math.round(event.y() - dragOffsetY);
            x = Math.max(8, Math.min(width - TastyFishRngHud.width() - 8, x));
            y = Math.max(40, Math.min(height - TastyFishRngHud.height() - 38, y));
            TastyFishRngHud.setPosition(x, y);
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == 0 && dragging) {
            dragging = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (hitHud(mouseX, mouseY) && verticalAmount != 0) {
            TastyFishRngHud.changeScale(verticalAmount > 0 ? 0.1f : -0.1f);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private boolean hitHud(double x, double y) {
        return x >= config.farmingRngX - 8 && x <= config.farmingRngX + TastyFishRngHud.width()
            && y >= config.farmingRngY - 8 && y <= config.farmingRngY + TastyFishRngHud.height();
    }
}
