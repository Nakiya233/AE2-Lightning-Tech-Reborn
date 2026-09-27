package com.moakiee.ae2lt.blockentity;

import java.util.ArrayList;
import java.util.List;

import appeng.api.config.*;
import appeng.api.inventories.ISegmentedInventory;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.ticking.*;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.StorageCells;
import appeng.api.storage.cells.CellState;
import appeng.api.storage.cells.StorageCell;
import appeng.api.upgrades.*;
import appeng.api.util.*;
import appeng.blockentity.grid.AENetworkInvBlockEntity;
import appeng.core.definitions.AEItems;
import appeng.me.helpers.MachineSource;
import appeng.util.SettingsFrom;
import appeng.util.inv.*;
import appeng.util.inv.filter.AEItemFilters;
import com.moakiee.ae2lt.logic.OverloadedIOTransfer;
import com.moakiee.ae2lt.logic.energy.PowerCostUtil;
import com.moakiee.ae2lt.registry.ModBlockEntities;
import com.moakiee.ae2lt.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** AE2-style cell queues with a bounded, round-robin resource-key transfer driver. */
public class OverloadedIOPortBlockEntity extends AENetworkInvBlockEntity
        implements IUpgradeableObject, IConfigurableObject, IGridTickable {
    public static final int CELL_SLOTS = 6;
    public static final int UPGRADE_SLOTS = 5;
    public static final int SPEED_CARD_SLOTS = 4;
    private static final String LAST_BATCH_TICK = "lastBatchTick";
    public static final double BATCH_AE = 32;
    private static final String PENDING = "pendingTransfer";
    private final AppEngInternalInventory input = new AppEngInternalInventory(this, CELL_SLOTS, 1);
    private final AppEngInternalInventory output = new AppEngInternalInventory(this, CELL_SLOTS, 1);
    private final InternalInventory inventory = new CombinedInternalInventory(input, output);
    private final InternalInventory inputExternal = new FilteredInternalInventory(input, AEItemFilters.INSERT_ONLY);
    private final InternalInventory outputExternal = new FilteredInternalInventory(output, AEItemFilters.EXTRACT_ONLY);
    private final IUpgradeInventory upgrades = UpgradeInventories.forMachine(
            ModBlocks.OVERLOADED_IO_PORT.get(), UPGRADE_SLOTS, this::settingsChanged);
    private final IConfigManager settings = new appeng.util.ConfigManager(this::settingsChanged);
    private final IActionSource source = new MachineSource(this);
    private final Scan[] scans = new Scan[CELL_SLOTS];
    private int nextSlot;
    private long lastTick = Long.MIN_VALUE;
    private GenericStack pending;
    private boolean clientActive;
    private Status status = Status.IDLE;
    private int lastBatches;
    public enum Status { IDLE, ACTIVE, NO_NETWORK, REDSTONE, NO_POWER, BLOCKED, RECOVERING }

    public OverloadedIOPortBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.OVERLOADED_IO_PORT.get(), pos, state);
        settings.registerSetting(Settings.OPERATION_MODE, OperationMode.EMPTY);
        settings.registerSetting(Settings.FULLNESS_MODE, FullnessMode.EMPTY);
        settings.registerSetting(Settings.REDSTONE_CONTROLLED, RedstoneMode.IGNORE);
        input.setFilter(new appeng.util.inv.filter.IAEItemFilter() {
            @Override public boolean allowInsert(InternalInventory inv, int slot, ItemStack stack) {
                return StorageCells.isCellHandled(stack);
            }
        });
        getMainNode().setFlags(GridFlags.REQUIRE_CHANNEL).setIdlePowerUsage(4)
                .addService(IGridTickable.class, this);
    }

    public int getBatchLimit() { return 1; }
    public int getTransferInterval() {
        return Math.max(1, 5 - upgrades.getInstalledUpgrades(AEItems.SPEED_CARD));
    }
    public int getLastBatches() { return lastBatches; }
    public Status getStatus() { return status; }
    public boolean hasPendingTransfer() { return pending != null; }
    public boolean isActive() {
        return level != null && !level.isClientSide ? getMainNode().isActive() : clientActive;
    }
    @Override public IConfigManager getConfigManager() { return settings; }
    @Override public IUpgradeInventory getUpgrades() { return upgrades; }
    @Override public InternalInventory getInternalInventory() { return inventory; }
    @Override public AECableType getCableConnectionType(Direction side) { return AECableType.SMART; }
    @Override public @Nullable InternalInventory getSubInventory(ResourceLocation id) {
        if (id.equals(ISegmentedInventory.UPGRADES)) return upgrades;
        if (id.equals(ISegmentedInventory.CELLS)) return inventory;
        return super.getSubInventory(id);
    }
    @Override protected InternalInventory getExposedInventoryForSide(Direction side) {
        return side == getTop() || side == getTop().getOpposite() ? inputExternal : outputExternal;
    }
    @Override public void onChangeInventory(InternalInventory inv, int slot) {
        if (inv == input && scans != null) scans[slot] = null;
        wake();
    }
    private void wake() {
        getMainNode().ifPresent((grid, node) -> grid.getTickManager().alertDevice(node));
    }
    private void settingsChanged() {
        if (scans != null) java.util.Arrays.fill(scans, null);
        saveChanges();
        wake();
    }
    public void updateRedstoneState() { wake(); }
    private boolean enabled() {
        if (!upgrades.isInstalled(AEItems.REDSTONE_CARD)) return true;
        var mode = settings.getSetting(Settings.REDSTONE_CONTROLLED);
        return mode == RedstoneMode.IGNORE || (level.hasNeighborSignal(worldPosition)
                == (mode == RedstoneMode.HIGH_SIGNAL));
    }
    @Override public TickingRequest getTickingRequest(IGridNode node) {
        return new TickingRequest(1, 20, input.isEmpty() && pending == null, true);
    }
    @Override public TickRateModulation tickingRequest(IGridNode node, int elapsed) {
        if (level == null || level.isClientSide) return TickRateModulation.SLEEP;
        lastBatches = 0;
        var grid = getMainNode().getGrid();
        if (grid == null || !getMainNode().isActive()) {
            status = Status.NO_NETWORK;
            return TickRateModulation.IDLE;
        }
        if (!enabled()) { status = Status.REDSTONE; return TickRateModulation.SLEEP; }
        if (pending == null && input.isEmpty()) { status = Status.IDLE; return TickRateModulation.SLEEP; }
        long now = level.getGameTime();
        // Inventory/settings alerts cannot bypass the shared interval or accumulate catch-up batches.
        if (lastTick != Long.MIN_VALUE && now >= lastTick && now - lastTick < getTransferInterval()) {
            return TickRateModulation.URGENT;
        }
        lastTick = now;
        if (pending != null) {
            // Already paid for and extracted. Never start another batch until this is safely stored.
            long inserted = grid.getStorageService().getInventory().insert(
                    pending.what(), pending.amount(), Actionable.MODULATE, source);
            if (inserted > 0) {
                pending = inserted == pending.amount() ? null
                        : new GenericStack(pending.what(), pending.amount() - inserted);
                saveChanges();
            }
            status = Status.RECOVERING;
            return pending == null ? TickRateModulation.URGENT : TickRateModulation.SLOWER;
        }
        var mode = settings.getSetting(Settings.OPERATION_MODE);
        List<AEKey> networkKeys = null;
        boolean progressed = false;
        boolean unfinished = false;
        boolean noPower = false;
        boolean[] done = new boolean[CELL_SLOTS];
        int doneCount = 0;
        int attempts = 0;
        java.util.function.BooleanSupplier payment = () -> pay(grid);
        while (attempts < getBatchLimit() && doneCount < CELL_SLOTS) {
            int slot = nextSlot;
            nextSlot = (nextSlot + 1) % CELL_SLOTS;
            if (done[slot]) continue;
            var stack = input.getStackInSlot(slot);
            if (stack.isEmpty()) { done[slot] = true; doneCount++; continue; }
            var scan = scans[slot];
            if (scan == null || scan.stack != stack || scan.mode != mode) {
                var cell = StorageCells.getCellInventory(stack, this::saveChanges);
                scan = new Scan(stack, cell, mode);
                scans[slot] = scan;
            }
            if (scan.cell == null) {
                progressed |= moveCell(slot);
                done[slot] = true; doneCount++; continue;
            }
            if (scan.keys == null) {
                if (mode == OperationMode.EMPTY) {
                    scan.keys = keys(scan.cell.getAvailableStacks());
                } else {
                    // One enumeration per call, shared by all six cells. No per-key storage probes here.
                    if (networkKeys == null) networkKeys = keys(grid.getStorageService().getCachedInventory());
                    scan.keys = networkKeys;
                }
            }
            if (scan.cursor < scan.keys.size()) {
                var key = scan.keys.get(scan.cursor++);
                attempts++; // Rejected and missing keys also spend the work budget.
                var from = mode == OperationMode.EMPTY ? scan.cell : grid.getStorageService().getInventory();
                var to = mode == OperationMode.EMPTY ? grid.getStorageService().getInventory() : scan.cell;
                var result = OverloadedIOTransfer.move(from, to, key, source, payment);
                if (result.inserted() > 0 || result.remainder() > 0) {
                    progressed = true;
                    scan.moved = true;
                    lastBatches++;
                    scan.cell.persist();
                    saveChanges();
                }
                if (result.remainder() > 0) {
                    pending = new GenericStack(key, result.remainder());
                    saveChanges();
                    status = Status.RECOVERING;
                    return TickRateModulation.URGENT;
                }
                // Power loss must not be interpreted as a completed pass for the HALF eject mode.
                if (result.powerBlocked()) {
                    scan.cursor--;
                    noPower = true;
                    break;
                }
            }
            if (scan.cursor == scan.keys.size()) {
                boolean stalled = !scan.moved;
                // A multi-tick scan must not eject a HALF cell while newly arrived keys remain unseen.
                if (stalled && mode == OperationMode.FILL
                        && settings.getSetting(Settings.FULLNESS_MODE) == FullnessMode.HALF) {
                    var seen = new java.util.HashSet<>(scan.keys);
                    var fresh = keys(grid.getStorageService().getCachedInventory());
                    fresh.removeIf(seen::contains);
                    if (!fresh.isEmpty()) {
                        scan.keys = new ArrayList<>(scan.keys);
                        scan.keys.addAll(fresh);
                        unfinished = true;
                        continue;
                    }
                }
                scan.cell.persist();
                boolean eject = switch (settings.getSetting(Settings.FULLNESS_MODE)) {
                    case EMPTY -> scan.cell.getStatus() == CellState.EMPTY;
                    case FULL -> scan.cell.getStatus() == CellState.FULL;
                    case HALF -> stalled;
                };
                if (eject) progressed |= moveCell(slot);
                // Continue next tick. A full no-progress pass is needed for HALF, not a budget boundary.
                scans[slot] = null;
                done[slot] = true; doneCount++;
                unfinished |= !stalled;
            } else unfinished = true;
        }
        status = noPower ? Status.NO_POWER : progressed ? Status.ACTIVE : Status.BLOCKED;
        return progressed || unfinished ? TickRateModulation.URGENT : TickRateModulation.SLOWER;
    }
    private boolean pay(IGrid grid) {
        if (!PowerCostUtil.canAfford(grid, BATCH_AE)) return false;
        return grid.getEnergyService().extractAEPower(BATCH_AE, Actionable.MODULATE, PowerMultiplier.CONFIG)
                + 1e-6 >= BATCH_AE;
    }
    private static List<AEKey> keys(KeyCounter counter) {
        var result = new ArrayList<AEKey>();
        for (var entry : counter) if (entry.getLongValue() > 0) result.add(entry.getKey());
        return result;
    }
    private boolean moveCell(int slot) {
        var stack = input.getStackInSlot(slot);
        if (output.addItems(stack).isEmpty()) {
            input.setItemDirect(slot, ItemStack.EMPTY);
            saveChanges();
            return true;
        }
        return false;
    }
    private static final class Scan {
        final ItemStack stack;
        final StorageCell cell;
        final OperationMode mode;
        List<AEKey> keys;
        int cursor;
        boolean moved;
        Scan(ItemStack stack, StorageCell cell, OperationMode mode) {
            this.stack = stack; this.cell = cell; this.mode = mode;
        }
    }
    @Override public void saveAdditional(CompoundTag tag) {
        for (var scan : scans) if (scan != null && scan.cell != null) scan.cell.persist();
        super.saveAdditional(tag);
        settings.writeToNBT(tag);
        upgrades.writeToNBT(tag, "upgrades");
        if (lastTick != Long.MIN_VALUE) tag.putLong(LAST_BATCH_TICK, lastTick);
        if (pending != null) tag.put(PENDING, GenericStack.writeTag(pending));
    }
    @Override public void loadTag(CompoundTag tag) {
        super.loadTag(tag);
        settings.readFromNBT(tag);
        upgrades.readFromNBT(tag, "upgrades");
        pending = GenericStack.readTag( tag.getCompound(PENDING));
        java.util.Arrays.fill(scans, null);
        lastTick = tag.contains(LAST_BATCH_TICK) ? tag.getLong(LAST_BATCH_TICK) : Long.MIN_VALUE;
    }
    public static final String ITEM_PENDING_TAG = "ae2lt:io_port_pending";
    @Override public void exportSettings(SettingsFrom mode, CompoundTag data, @Nullable Player player) {
        super.exportSettings(mode, data, player);
        if (mode == SettingsFrom.DISMANTLE_ITEM && pending != null)
            data.put(ITEM_PENDING_TAG, GenericStack.writeTag(pending));
    }
    @Override public void importSettings(SettingsFrom mode, CompoundTag data, @Nullable Player player) {
        super.importSettings(mode, data, player);
        if (mode == SettingsFrom.DISMANTLE_ITEM && data.contains(ITEM_PENDING_TAG)) {
            pending = GenericStack.readTag(data.getCompound(ITEM_PENDING_TAG));
            saveChanges(); wake();
        }
    }
    @Override public void addAdditionalDrops(Level level, BlockPos pos, List<ItemStack> drops) {
        super.addAdditionalDrops(level, pos, drops);
        for (var stack : upgrades) if (!stack.isEmpty()) drops.add(stack);
    }
    @Override public void clearContent() {
        super.clearContent(); upgrades.clear(); pending = null;
        java.util.Arrays.fill(scans, null);
    }
    @Override public void onMainNodeStateChanged(IGridNodeListener.State reason) {
        if (reason != IGridNodeListener.State.GRID_BOOT) markForUpdate();
        wake();
    }
    @Override protected void writeToStream(FriendlyByteBuf data) {
        super.writeToStream(data); data.writeBoolean(isActive());
    }
    @Override protected boolean readFromStream(FriendlyByteBuf data) {
        boolean changed = super.readFromStream(data);
        boolean active = data.readBoolean();
        changed |= active != clientActive;
        clientActive = active;
        return changed;
    }
}
