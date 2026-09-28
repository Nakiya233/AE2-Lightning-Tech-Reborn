package com.moakiee.ae2lt.client;

import java.util.List;
import java.util.Locale;

import appeng.api.config.*;
import appeng.client.gui.implementations.UpgradeableScreen;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.widgets.ServerSettingToggleButton;
import appeng.core.definitions.AEBlocks;
import appeng.core.definitions.AEItems;
import com.moakiee.ae2lt.client.gui.LightningStatusIconWidget;
import com.moakiee.ae2lt.client.gui.LightningStatusLines;
import com.moakiee.ae2lt.menu.OverloadedIOPortMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public class OverloadedIOPortScreen extends UpgradeableScreen<OverloadedIOPortMenu> {
    private final Blitter filterSlotFrame;
    private final ServerSettingToggleButton<FullnessMode> fullness =
            new ServerSettingToggleButton<>(Settings.FULLNESS_MODE, FullnessMode.EMPTY);
    private final ServerSettingToggleButton<OperationMode> operation =
            new ServerSettingToggleButton<>(Settings.OPERATION_MODE, OperationMode.EMPTY);
    private final ServerSettingToggleButton<RedstoneMode> redstone =
            new ServerSettingToggleButton<>(Settings.REDSTONE_CONTROLLED, RedstoneMode.IGNORE);
    public OverloadedIOPortScreen(OverloadedIOPortMenu menu, Inventory inventory, Component title, ScreenStyle style) {
        super(menu, inventory, title, style);
        filterSlotFrame = style.getImage("filterSlotFrame");
        addToLeftToolbar(fullness); addToLeftToolbar(redstone);
        widgets.add("operationMode", operation);
        widgets.add("lightningStatus", new LightningStatusIconWidget(() -> List.of(
                LightningStatusLines.title(),
                Component.translatable("ae2lt.gui.status.label", Component.translatable(
                        "ae2lt.gui.overloaded_io_port.status." + menu.status.name().toLowerCase(Locale.ROOT))),
                Component.translatable("ae2lt.gui.overloaded_io_port.rate", menu.batchLimit, menu.transferInterval),
                Component.translatable("ae2lt.gui.overloaded_io_port.cap", String.format("%,d", menu.transferCap)))));
    }
    @Override protected void updateBeforeRender() {
        super.updateBeforeRender();
        fullness.set(menu.fullness); operation.set(menu.operation);
        redstone.set(menu.getRedStoneMode()); redstone.setVisibility(menu.hasUpgrade(AEItems.REDSTONE_CARD));
    }
    @Override public void drawBG(GuiGraphics graphics, int x, int y, int mx, int my, float partial) {
        super.drawBG(graphics, x, y, mx, my, partial);
        // The 1.20.1 AE2 texture has a large arrow where the live rate/status labels go.
        graphics.fill(x + 56, y + 34, x + 121, y + 69, 0xffc6c6c6);
        filterSlotFrame.copy().dest(x + 65, y + 52).blit(graphics);
        filterSlotFrame.copy().dest(x + 91, y + 52).blit(graphics);
        drawItem(graphics, x + 58, y + 17, AEItems.ITEM_CELL_1K.stack());
        drawItem(graphics, x + 102, y + 17, AEBlocks.DRIVE.stack());
    }
}
