package com.moakiee.ae2lt.menu;

import appeng.api.config.*;
import appeng.api.inventories.ISegmentedInventory;
import appeng.api.util.IConfigManager;
import appeng.menu.SlotSemantics;
import appeng.menu.guisync.GuiSync;
import appeng.menu.implementations.MenuTypeBuilder;
import appeng.menu.implementations.UpgradeableMenu;
import appeng.menu.slot.OutputSlot;
import appeng.menu.slot.RestrictedInputSlot;
import com.moakiee.ae2lt.blockentity.OverloadedIOPortBlockEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;

public class OverloadedIOPortMenu extends UpgradeableMenu<OverloadedIOPortBlockEntity> {
    public static final MenuType<OverloadedIOPortMenu> TYPE = Ae2ltMenuBuilder.buildUnregistered(MenuTypeBuilder
            .create(OverloadedIOPortMenu::new, OverloadedIOPortBlockEntity.class)
            .withMenuTitle(host -> Component.translatable("block.ae2lt.overloaded_io_port"))
            , new ResourceLocation("ae2lt:overloaded_io_port"));
    @GuiSync(2) public FullnessMode fullness = FullnessMode.EMPTY;
    @GuiSync(3) public OperationMode operation = OperationMode.EMPTY;
    @GuiSync(7) public int transferInterval = 5;
    @GuiSync(9) public OverloadedIOPortBlockEntity.Status status = OverloadedIOPortBlockEntity.Status.IDLE;
    public OverloadedIOPortMenu(int id, Inventory player, OverloadedIOPortBlockEntity host) {
        super(TYPE, id, player, host);
    }
    @Override protected void setupConfig() {
        var cells = getHost().getSubInventory(ISegmentedInventory.CELLS);
        var type = RestrictedInputSlot.PlacableItemType.STORAGE_CELLS;
        for (int i = 0; i < 6; i++) addSlot(new RestrictedInputSlot(type, cells, i), SlotSemantics.MACHINE_INPUT);
        for (int i = 0; i < 6; i++) addSlot(new OutputSlot(cells, 6 + i, type.icon), SlotSemantics.MACHINE_OUTPUT);
    }
    @Override protected void loadSettingsFromHost(IConfigManager config) {
        fullness = config.getSetting(Settings.FULLNESS_MODE);
        operation = config.getSetting(Settings.OPERATION_MODE);
        setRedStoneMode(config.getSetting(Settings.REDSTONE_CONTROLLED));
        transferInterval = getHost().getTransferInterval();
        status = getHost().getStatus();
    }
}
