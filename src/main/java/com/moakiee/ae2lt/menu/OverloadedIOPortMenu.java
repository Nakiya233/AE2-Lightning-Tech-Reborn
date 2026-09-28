package com.moakiee.ae2lt.menu;

import appeng.api.config.*;
import appeng.api.inventories.ISegmentedInventory;
import appeng.api.util.IConfigManager;
import appeng.menu.SlotSemantics;
import appeng.menu.guisync.GuiSync;
import appeng.menu.implementations.MenuTypeBuilder;
import appeng.menu.implementations.UpgradeableMenu;
import appeng.menu.slot.OutputSlot;
import appeng.menu.slot.AppEngSlot;
import appeng.menu.slot.RestrictedInputSlot;
import com.moakiee.ae2lt.blockentity.OverloadedIOPortBlockEntity;
import com.moakiee.ae2lt.item.OverloadedFilterComponentItem;
import com.moakiee.ae2lt.registry.ModItems;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import java.util.List;

public class OverloadedIOPortMenu extends UpgradeableMenu<OverloadedIOPortBlockEntity> {
    public static final MenuType<OverloadedIOPortMenu> TYPE = Ae2ltMenuBuilder.buildUnregistered(MenuTypeBuilder
            .create(OverloadedIOPortMenu::new, OverloadedIOPortBlockEntity.class)
            .withMenuTitle(host -> Component.translatable("block.ae2lt.overloaded_io_port"))
            , new ResourceLocation("ae2lt:overloaded_io_port"));
    @GuiSync(2) public FullnessMode fullness = FullnessMode.EMPTY;
    @GuiSync(3) public OperationMode operation = OperationMode.EMPTY;
    @GuiSync(7) public int transferInterval = 5;
    @GuiSync(9) public OverloadedIOPortBlockEntity.Status status = OverloadedIOPortBlockEntity.Status.IDLE;
    @GuiSync(13) public int batchLimit = 1;
    @GuiSync(14) public long transferCap = 32_768;
    public OverloadedIOPortMenu(int id, Inventory player, OverloadedIOPortBlockEntity host) {
        super(TYPE, id, player, host);
    }
    @Override protected void setupConfig() {
        var cells = getHost().getSubInventory(ISegmentedInventory.CELLS);
        var type = RestrictedInputSlot.PlacableItemType.STORAGE_CELLS;
        for (int i = 0; i < 6; i++) addSlot(new RestrictedInputSlot(type, cells, i), SlotSemantics.MACHINE_INPUT);
        for (int i = 0; i < 6; i++) addSlot(new OutputSlot(cells, 6 + i, type.icon), SlotSemantics.MACHINE_OUTPUT);
        var filter = new AppEngSlot(getHost().getFilterInventory(), 0) {
            @Override public boolean mayPlace(ItemStack stack) {
                return stack.getItem() instanceof OverloadedFilterComponentItem;
            }
            @Override public int getMaxStackSize() { return 1; }
        };
        filter.setNotDraggable();
        filter.setEmptyTooltip(() -> List.of(Component.translatable("ae2lt.gui.overloaded_io_port.filter")));
        addSlot(filter, Ae2ltSlotSemantics.OVERLOADED_IO_FILTER);
        Ae2ltSlotBackgrounds.withBackground(filter, Ae2ltSlotBackgrounds.FILTER_COMPONENT);
        var matrix = new AppEngSlot(getHost().getMatrixInventory(), 0) {
            @Override public boolean mayPlace(ItemStack stack) {
                return stack.is(ModItems.LIGHTNING_COLLAPSE_MATRIX.get());
            }
            @Override public int getMaxStackSize() { return OverloadedIOPortBlockEntity.MAX_MATRICES; }
        };
        matrix.setNotDraggable();
        matrix.setEmptyTooltip(() -> List.of(Component.translatable("ae2lt.gui.overloaded_io_port.matrix")));
        addSlot(matrix, Ae2ltSlotSemantics.OVERLOADED_IO_MATRIX);
        Ae2ltSlotBackgrounds.withBackground(matrix, Ae2ltSlotBackgrounds.LIGHTNING_COLLAPSE_MATRIX);
    }
    @Override protected void loadSettingsFromHost(IConfigManager config) {
        fullness = config.getSetting(Settings.FULLNESS_MODE);
        operation = config.getSetting(Settings.OPERATION_MODE);
        setRedStoneMode(config.getSetting(Settings.REDSTONE_CONTROLLED));
        transferInterval = getHost().getTransferInterval();
        status = getHost().getStatus();
        batchLimit = getHost().getBatchLimit();
        transferCap = getHost().getTransferCap();
    }
}
