package com.moakiee.ae2lt.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.EnumSet;
import appeng.api.orientation.RelativeSide;

import appeng.menu.AEBaseMenu;
import appeng.menu.SlotSemantics;
import appeng.menu.guisync.GuiSync;
import appeng.menu.implementations.MenuTypeBuilder;

import com.moakiee.ae2lt.AE2LightningTech;
import com.moakiee.ae2lt.blockentity.MiningFactoryBlockEntity;
import com.moakiee.ae2lt.machine.miningfactory.MiningFactoryInventory;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class MiningFactoryMenu extends AEBaseMenu implements FrequencyBindingMenu, MachineOutputConfigMenu {
    public static final MenuType<MiningFactoryMenu> TYPE = Ae2ltMenuBuilder.buildUnregistered(MenuTypeBuilder
            .create(MiningFactoryMenu::new, MiningFactoryBlockEntity.class)
            .withMenuTitle(host -> Component.translatable("block.ae2lt.mining_factory"))
            , new ResourceLocation(AE2LightningTech.MODID, "mining_factory"));

    @GuiSync(30) public int energy;
    @GuiSync(31) public int status;
    @GuiSync(32) public int parallelCapacity;
    @GuiSync(33) public boolean autoExport;
    @GuiSync(34) public int outputSideMask;
    @GuiSync(35) public int progressTicks;
    @GuiSync(36) public long lightning;

    private final MiningFactoryBlockEntity host;
    private final Slot inputSlot;
    private final Slot toolSlot;
    private final Slot matrixSlot;

    public MiningFactoryMenu(int id, Inventory playerInventory, MiningFactoryBlockEntity host) {
        super(TYPE, id, playerInventory, host);
        this.host = host;
        var blockInput = new LargeStackAppEngSlot(host.getInventory(), MiningFactoryInventory.INPUT);
        blockInput.setEmptyTooltip(() -> List.of(Component.translatable("gui.ae2lt.mining_factory.status.idle")));
        inputSlot = addSlot(blockInput, SlotSemantics.MACHINE_INPUT);
        Ae2ltSlotBackgrounds.withBackground(inputSlot, Ae2ltSlotBackgrounds.MINING_BLOCK);
        var toolInput = new LargeStackAppEngSlot(host.getInventory(), MiningFactoryInventory.TOOL);
        toolInput.setEmptyTooltip(() -> List.of(Component.translatable("gui.ae2lt.mining_factory.status.tool")));
        toolSlot = addSlot(toolInput, SlotSemantics.CONFIG);
        Ae2ltSlotBackgrounds.withBackground(toolSlot, Ae2ltSlotBackgrounds.MINING_TOOL);
        for (int slot = MiningFactoryInventory.OUTPUT; slot < MiningFactoryInventory.OUTPUT + MiningFactoryInventory.OUTPUT_COUNT; slot++) {
            addSlot(new LargeStackAppEngSlot(host.getInventory(), slot), SlotSemantics.MACHINE_OUTPUT);
        }
        matrixSlot = addSlot(new LargeStackAppEngSlot(host.getInventory(), MiningFactoryInventory.MATRIX),
                Ae2ltSlotSemantics.OVERLOAD_FACTORY_MATRIX);
        Ae2ltSlotBackgrounds.withBackground(matrixSlot, Ae2ltSlotBackgrounds.LIGHTNING_COLLAPSE_MATRIX);
        createPlayerInventorySlots(playerInventory);
        registerClientAction("toggleAutoExport", () -> {
            if (isServerSide()) host.setAutoExportEnabled(!host.isAutoExportEnabled());
        });
        registerClientAction("toggleOutputSide", Integer.class, this::toggleOutputSide);
        registerClientAction("clearOutputSides", () -> {
            if (isServerSide()) host.updateOutputSides(EnumSet.noneOf(RelativeSide.class));
        });
    }

    @Override
    public void broadcastChanges() {
        if (isServerSide()) {
            energy = host.getEnergyStorage().getEnergyStored();
            status = host.getStatus().ordinal();
            parallelCapacity = host.getInstalledParallelCapacity();
            progressTicks = host.getProgressTicks();
            lightning = host.getAvailableLightning();
            autoExport = host.isAutoExportEnabled();
            outputSideMask = 0;
            for (var side : host.getAllowedOutputs()) outputSideMask |= 1 << side.ordinal();
        }
        super.broadcastChanges();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (isClientSide() || index < 0 || index >= slots.size()) {
            return ItemStack.EMPTY;
        }

        Slot sourceSlot = getSlot(index);
        if (!sourceSlot.hasItem() || !sourceSlot.mayPickup(player)) {
            return ItemStack.EMPTY;
        }

        ItemStack sourceStack = sourceSlot.getItem();
        ItemStack original = sourceStack.copy();
        ItemStack remainder;

        if (((com.moakiee.ae2lt.mixin.AEBaseMenuAccessor) (Object) this).ae2lt$isPlayerSideSlot(sourceSlot)) {
            remainder = moveIntoSlots(sourceStack.copy(), List.of(matrixSlot, toolSlot, inputSlot));
        } else {
            remainder = moveIntoSlots(sourceStack.copy(), getPlayerDestinationSlots());
        }

        int moved = original.getCount() - remainder.getCount();
        if (moved <= 0) {
            return ItemStack.EMPTY;
        }

        sourceSlot.remove(moved);
        sourceSlot.setChanged();
        return original;
    }

    @Override
    public boolean stillValid(Player player) {
        if (host.isRemoved() || host.getLevel() == null) {
            return false;
        }

        return host.getLevel().getBlockEntity(host.getBlockPos()) == host
                && player.level() == host.getLevel()
                && player.distanceToSqr(
                        host.getBlockPos().getX() + 0.5D,
                        host.getBlockPos().getY() + 0.5D,
                        host.getBlockPos().getZ() + 0.5D) <= 64.0D;
    }

    public MiningFactoryBlockEntity getHost() {
        return host;
    }

    public double getProgress() { return (double) progressTicks / MiningFactoryBlockEntity.PROCESSING_TICKS; }
    public boolean isAutoExportEnabled() { return autoExport; }
    public void clientToggleAutoExport() { sendClientAction("toggleAutoExport"); }
    @Override public void clientClearOutputSides() { sendClientAction("clearOutputSides"); }
    @Override public void clientToggleOutputSide(RelativeSide side) { sendClientAction("toggleOutputSide", side.ordinal()); }
    @Override public boolean isOutputSideEnabled(RelativeSide side) { return (outputSideMask & (1 << side.ordinal())) != 0; }

    private void toggleOutputSide(Integer ordinal) {
        var sides = RelativeSide.values();
        if (!isServerSide() || ordinal == null || ordinal < 0 || ordinal >= sides.length) return;
        var updated = host.getAllowedOutputs();
        if (!updated.add(sides[ordinal])) updated.remove(sides[ordinal]);
        host.updateOutputSides(updated);
    }

    private List<Slot> getPlayerDestinationSlots() {
        var result = new ArrayList<Slot>(getSlots(SlotSemantics.PLAYER_INVENTORY));
        result.addAll(getSlots(SlotSemantics.PLAYER_HOTBAR));
        return result;
    }

    private static ItemStack moveIntoSlots(ItemStack stack, List<Slot> destinations) {
        ItemStack remainder = stack;

        for (Slot slot : destinations) {
            if (!slot.hasItem()) {
                continue;
            }
            remainder = slot.safeInsert(remainder);
            if (remainder.isEmpty()) {
                return ItemStack.EMPTY;
            }
        }

        for (Slot slot : destinations) {
            if (slot.hasItem()) {
                continue;
            }
            remainder = slot.safeInsert(remainder);
            if (remainder.isEmpty()) {
                return ItemStack.EMPTY;
            }
        }

        return remainder;
    }
}
