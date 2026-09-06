package com.epic60869.tastyfish;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Main /tf GUI plus a safe hover-to-drag HUD editor. */
public final class TastyFishScreen extends Screen {
    private final TastyFishConfig config;
    private final boolean editor;
    private String dragging;

    public TastyFishScreen(TastyFishConfig config) {
        this(config, false);
    }

    private TastyFishScreen(TastyFishConfig config, boolean editor) {
        super(Component.literal("TastyFish"));
        this.config = config;
        this.editor = editor;
    }

    @Override
    protected void init() {
        if (editor) {
            addRenderableWidget(Button.builder(Component.literal("Done"), button -> minecraft.gui.setScreen(new TastyFishScreen(config)))
                .bounds(width / 2 - 50, height - 32, 100, 20).build());
            return;
        }

        addRenderableWidget(Button.builder(Component.literal("GUI Editor"), button -> minecraft.gui.setScreen(new TastyFishScreen(config, true)))
            .bounds(width / 2 - 60, height / 2 - 10, 120, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Reset Farming Data"), button -> FarmingProfitTracker.get().reset())
            .bounds(width / 2 - 60, height / 2 + 16, 120, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), button -> onClose())
            .bounds(width / 2 - 60, height / 2 + 42, 120, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        if (!editor) {
            graphics.centeredText(font, "TastyFish", width / 2, height / 2 - 60, 0xFFFFFF55);
            graphics.centeredText(font, "Standalone farming tracker", width / 2, height / 2 - 45, 0xFFAAAAAA);
            return;
        }

        graphics.centeredText(font, "GUI Editor", width / 2, 14, 0xFFFFFF55);
        graphics.centeredText(font, "Hover an element, then drag it. Empty space does nothing.", width / 2, 27, 0xFFAAAAAA);
        graphics.centeredText(font, "Release the mouse to place it.", width / 2, 39, 0xFFAAAAAA);

        boolean profitHovered = isOverProfit(mouseX, mouseY);
        FarmingProfitHud.renderPreview(graphics, FarmingProfitHud.x(), FarmingProfitHud.y(), profitHovered || "profit".equals(dragging));

        boolean rngHovered = isOverRng(mouseX, mouseY);
        TastyFishRngHud.renderPreview(graphics, TastyFishRngHud.x(), TastyFishRngHud.y(), rngHovered || "rng".equals(dragging));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (editor && event.button() == 0) {
            if (isOverProfit(event.x(), event.y())) {
                dragging = "profit";
                return true;
            }
            if (isOverRng(event.x(), event.y())) {
                dragging = "rng";
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        if (editor && dragging != null && event.button() == 0) {
            int nx = (int) event.x() - (dragging.equals("profit") ? FarmingProfitHud.width() / 2 : TastyFishRngHud.width() / 2);
            int ny = (int) event.y() - (dragging.equals("profit") ? 30 : TastyFishRngHud.height() / 2);
            if (dragging.equals("profit")) FarmingProfitHud.setPosition(nx, ny);
            else TastyFishRngHud.setPosition(nx, ny);
            return true;
        }
        return super.mouseDragged(event, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (editor && event.button() == 0 && dragging != null) {
            dragging = null;
            return true;
        }
        return super.mouseReleased(event);
    }

    private boolean isOverProfit(double mx, double my) {
        return mx >= FarmingProfitHud.x() && mx <= FarmingProfitHud.x() + FarmingProfitHud.width()
            && my >= FarmingProfitHud.y() && my <= FarmingProfitHud.y() + FarmingProfitHud.height();
    }

    private boolean isOverRng(double mx, double my) {
        return mx >= TastyFishRngHud.x() && mx <= TastyFishRngHud.x() + TastyFishRngHud.width()
            && my >= TastyFishRngHud.y() && my <= TastyFishRngHud.y() + TastyFishRngHud.height();
    }
}
