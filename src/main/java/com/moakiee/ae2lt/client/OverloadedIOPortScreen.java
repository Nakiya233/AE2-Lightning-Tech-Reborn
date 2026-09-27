package com.moakiee.ae2lt.client;

import appeng.api.config.*;
import appeng.client.gui.implementations.UpgradeableScreen;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.ServerSettingToggleButton;
import appeng.core.definitions.AEBlocks;
import appeng.core.definitions.AEItems;
import com.moakiee.ae2lt.menu.OverloadedIOPortMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public class OverloadedIOPortScreen extends UpgradeableScreen<OverloadedIOPortMenu> {
    private final ServerSettingToggleButton<FullnessMode> fullness =
            new ServerSettingToggleButton<>(Settings.FULLNESS_MODE, FullnessMode.EMPTY);
    private final ServerSettingToggleButton<OperationMode> operation =
            new ServerSettingToggleButton<>(Settings.OPERATION_MODE, OperationMode.EMPTY);
    private final ServerSettingToggleButton<RedstoneMode> redstone =
            new ServerSettingToggleButton<>(Settings.REDSTONE_CONTROLLED, RedstoneMode.IGNORE);
    public OverloadedIOPortScreen(OverloadedIOPortMenu menu, Inventory inventory, Component title, ScreenStyle style) {
        super(menu, inventory, title, style);
        addToLeftToolbar(fullness); addToLeftToolbar(redstone);
        widgets.add("operationMode", operation);
    }
    @Override protected void updateBeforeRender() {
        super.updateBeforeRender();
        fullness.set(menu.fullness); operation.set(menu.operation);
        redstone.set(menu.getRedStoneMode()); redstone.setVisibility(menu.hasUpgrade(AEItems.REDSTONE_CARD));
        setTextContent("rate", Component.translatable("ae2lt.gui.overloaded_io_port.rate", menu.transferInterval));
        setTextContent("status", Component.translatable("ae2lt.gui.overloaded_io_port.status."
                + menu.status.name().toLowerCase(java.util.Locale.ROOT)));
    }
    @Override public void drawBG(GuiGraphics graphics, int x, int y, int mx, int my, float partial) {
        super.drawBG(graphics, x, y, mx, my, partial);
        // The 1.20.1 AE2 texture has a large arrow where the live rate/status labels go.
        graphics.fill(x + 56, y + 34, x + 121, y + 69, 0xffc6c6c6);
        drawItem(graphics, x + 58, y + 17, AEItems.ITEM_CELL_1K.stack());
        drawItem(graphics, x + 102, y + 17, AEBlocks.DRIVE.stack());
    }
}
