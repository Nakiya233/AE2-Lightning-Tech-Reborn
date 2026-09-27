package com.moakiee.ae2lt.client;

import java.util.List;
import java.util.function.LongSupplier;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;

import appeng.client.gui.style.Blitter;
import appeng.client.gui.widgets.ITooltip;

import com.moakiee.ae2lt.menu.OverloadProcessingFactoryMenu;

public class OverloadProcessingFactoryEnergyBar extends AbstractWidget implements ITooltip {
    private final LongSupplier storedEnergy;
    private final LongSupplier energyCapacity;
    private final Blitter fill;

    public OverloadProcessingFactoryEnergyBar(OverloadProcessingFactoryMenu menu, Blitter fill) {
        this(menu::getStoredEnergy, menu::getEnergyCapacity, fill);
    }

    public OverloadProcessingFactoryEnergyBar(LongSupplier storedEnergy, LongSupplier energyCapacity, Blitter fill) {
        super(0, 0, fill.getSrcWidth(), fill.getSrcHeight(), Component.empty());
        this.storedEnergy = storedEnergy;
        this.energyCapacity = energyCapacity;
        this.fill = fill.copy();
    }

    @Override
    protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        fill.copy().opacity(0.2f).dest(getX(), getY(), width, height).blit(guiGraphics);

        long capacity = Math.max(1L, energyCapacity.getAsLong());
        long stored = Math.min(storedEnergy.getAsLong(), capacity);
        int filled = (int) Math.round(height * (double) stored / (double) capacity);
        if (filled <= 0) {
            return;
        }

        int srcY = fill.getSrcY() + height - filled;
        int destY = getY() + height - filled;
        fill.copy()
                .src(fill.getSrcX(), srcY, width, filled)
                .dest(getX(), destY, width, filled)
                .blit(guiGraphics);
    }

    @Override
    public List<Component> getTooltipMessage() {
        return List.of(Component.translatable(
                "ae2lt.gui.overload_factory.energy.tooltip",
                storedEnergy.getAsLong(),
                energyCapacity.getAsLong()));
    }

    @Override
    public Rect2i getTooltipArea() {
        return new Rect2i(getX() - 2, getY() - 2, width + 4, height + 4);
    }

    @Override
    public boolean isTooltipAreaVisible() {
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
    }
}
