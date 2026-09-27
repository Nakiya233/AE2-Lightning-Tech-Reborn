package com.moakiee.ae2lt.blockentity;

import java.util.ArrayList;
import java.util.List;
import java.util.EnumSet;

import appeng.api.orientation.RelativeSide;
import com.moakiee.ae2lt.logic.AdjacentItemAutoExportHelper;
import com.moakiee.ae2lt.logic.MemoryCardConfigSupport;
import com.moakiee.ae2lt.machine.common.LightningCollapseMatrixHost;
import com.moakiee.ae2lt.registry.ModBlocks;
import net.minecraftforge.items.IItemHandlerModifiable;

import appeng.api.networking.IGridNodeListener;
import appeng.api.config.Actionable;
import appeng.api.storage.MEStorage;
import appeng.api.networking.security.IActionSource;
import appeng.blockentity.grid.AENetworkBlockEntity;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuLocator;
import com.moakiee.ae2lt.block.MiningFactoryBlock;
import com.moakiee.ae2lt.grid.FrequencyBindingHelper;
import com.moakiee.ae2lt.grid.FrequencyBindingHost;
import com.moakiee.ae2lt.logic.AppFluxHelper;
import com.moakiee.ae2lt.machine.miningfactory.MiningFactoryConfig;
import com.moakiee.ae2lt.machine.miningfactory.MiningFactoryInventory;
import com.moakiee.ae2lt.machine.miningfactory.MiningLoot;
import com.moakiee.ae2lt.me.key.LightningKey;
import com.moakiee.ae2lt.machine.overloadfactory.OverloadProcessingFactoryEnergyStorage;
import com.moakiee.ae2lt.menu.MiningFactoryMenu;
import com.moakiee.ae2lt.registry.ModBlockEntities;
import com.moakiee.ae2lt.util.NativeStackDropHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public final class MiningFactoryBlockEntity extends AENetworkBlockEntity implements FrequencyBindingHost, LightningCollapseMatrixHost {
    public static final int PROCESSING_TICKS = 5;
    public enum Status { IDLE, WORKING, TOOL, HARVEST, ENERGY, OUTPUT, UNSUPPORTED, ERROR, LIGHTNING }
    private final MiningFactoryInventory inventory = new MiningFactoryInventory(this::inventoryChanged);
    private final OverloadProcessingFactoryEnergyStorage energy =
            new OverloadProcessingFactoryEnergyStorage(1_000_000, this::saveChanges);
    private final FrequencyBindingHelper frequencyBinding = new FrequencyBindingHelper(this);
    private final List<MiningLoot.Drop> pending = new ArrayList<>();
    private final IItemHandlerModifiable automation = new IItemHandlerModifiable() {
        @Override public void setStackInSlot(int slot, ItemStack stack) { inventory.setStackInSlot(slot, stack); }
        @Override public int getSlots() { return inventory.getSlots(); }
        @Override public ItemStack getStackInSlot(int slot) { return inventory.getStackInSlot(slot); }
        @Override public int getSlotLimit(int slot) { return inventory.getSlotLimit(slot); }
        @Override public boolean isItemValid(int slot, ItemStack stack) { return inventory.isItemValid(slot, stack); }
        @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return inventory.insertItem(slot, stack, simulate);
        }
        @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
            // Inputs, tools and matrices stay installed; automation only extracts products.
            return MiningFactoryInventory.isOutputSlot(slot) ? inventory.extractItem(slot, amount, simulate) : ItemStack.EMPTY;
        }
    };
    private final AdjacentItemAutoExportHelper.DirectionalTargetCache exportTargets =
            new AdjacentItemAutoExportHelper.DirectionalTargetCache();
    private boolean autoExport;
    private EnumSet<RelativeSide> allowedOutputs = EnumSet.noneOf(RelativeSide.class);
    private Status status = Status.IDLE;
    private boolean processing;
    private boolean lootFailed;
    private long lastTick = Long.MIN_VALUE;
    private int lastProcessed;
    private int lastSamples;
    private int progressTicks;
    private ItemStack cycleInput = ItemStack.EMPTY;
    private ItemStack cycleTool = ItemStack.EMPTY;
    private int cycleParallel;

    public MiningFactoryBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MINING_FACTORY.get(), pos, state);
        getMainNode().setIdlePowerUsage(0);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, MiningFactoryBlockEntity factory) {
        factory.frequencyBinding.serverTick();
        factory.processTick();
    }

    public void processTick() {
        if (!(level instanceof ServerLevel server) || isRemoved() || processing || lastTick == server.getGameTime()) return;
        lastTick = server.getGameTime();
        lastProcessed = 0;
        lastSamples = 0;
        processing = true;
        try {
            process(server);
        } finally {
            processing = false;
            var state = getBlockState();
            boolean working = status == Status.WORKING;
            if (!working) resetProgress();
            if (state.getValue(MiningFactoryBlock.WORKING) != working) {
                level.setBlock(worldPosition, state.setValue(MiningFactoryBlock.WORKING, working), Block.UPDATE_CLIENTS);
            }
        }
    }

    private void process(ServerLevel server) {
        pushOutResult();
        flushPending();
        if (!pending.isEmpty() || !inventory.hasOutputRoom()) { status = Status.OUTPUT; return; }
        if (lootFailed) { status = Status.ERROR; return; }
        ItemStack input = inventory.getStackInSlot(MiningFactoryInventory.INPUT);
        ItemStack tool = inventory.getStackInSlot(MiningFactoryInventory.TOOL);
        if (input.isEmpty()) { status = Status.IDLE; return; }
        if (!MiningLoot.isTool(tool)) { status = Status.TOOL; return; }
        BlockState state = MiningLoot.stateOf(input);
        if (state == null || state.getDestroySpeed(server, worldPosition) < 0) { status = Status.UNSUPPORTED; return; }
        if (!MiningLoot.canHarvest(state, tool)) { status = Status.HARVEST; return; }
        int unitCost = MiningFactoryConfig.energyPerBlock(MiningLoot.isPlane(tool));
        int requested = Math.min(input.getCount(), getInstalledParallelCapacity());
        if (energy.getStoredEnergyLong() < (long) requested * unitCost && AppFluxHelper.isAvailable()) {
            getMainNode().ifPresent((grid, node) -> AppFluxHelper.pullPowerFromNetwork(
                    grid.getStorageService().getInventory(), energy, IActionSource.ofMachine(this)));
        }
        requested = (int) Math.min(requested, energy.getStoredEnergyLong() / unitCost);
        if (requested == 0) { status = Status.ENERGY; return; }
        var lightningStorage = lightningStorage();
        var actionSource = IActionSource.ofMachine(this);
        if (lightningStorage == null || lightningStorage.extract(LightningKey.HIGH_VOLTAGE, 1,
                Actionable.SIMULATE, actionSource) != 1) {
            status = Status.LIGHTNING;
            return;
        }

        // A cycle takes five valid server ticks. Do not roll loot or spend anything early.
        if (progressTicks > 0 && !matchesCycle()) resetProgress();
        if (progressTicks == 0) captureCycle();
        progressTicks++;
        saveChanges();
        if (progressTicks < PROCESSING_TICKS) {
            status = Status.WORKING;
            return;
        }
        resetProgress();

        // One lightning per completed batch, independent of its parallel count.
        // Reserve before rolling: a rejected extraction must not reroll loot for free.
        if (lightningStorage.extract(LightningKey.HIGH_VOLTAGE, 1, Actionable.MODULATE, actionSource) != 1) {
            status = Status.LIGHTNING;
            return;
        }
        MiningLoot.Result result;
        try {
            result = MiningLoot.roll(server, worldPosition, state, tool, requested, MiningFactoryConfig.samples());
        } catch (RuntimeException exception) {
            lightningStorage.insert(LightningKey.HIGH_VOLTAGE, 1, Actionable.MODULATE, actionSource);
            lootFailed = true;
            status = Status.ERROR;
            com.mojang.logging.LogUtils.getLogger().error("Mining factory loot failed at {} for {}; change input/tool to retry",
                    worldPosition, input, exception);
            return;
        }
        if (result.processed() == 0) {
            lightningStorage.insert(LightningKey.HIGH_VOLTAGE, 1, Actionable.MODULATE, actionSource);
            status = Status.TOOL;
            return;
        }
        // All prospective tool edits are on a copy. Commit costs and the fixed result together.
        inventory.extractItem(MiningFactoryInventory.INPUT, result.processed(), false);
        inventory.setStackInSlot(MiningFactoryInventory.TOOL, result.tool());
        energy.extractInternal((long) result.processed() * unitCost, false);
        pending.addAll(result.drops());
        lastProcessed = result.processed();
        lastSamples = result.samples();
        saveChanges();
        flushPending();
        pushOutResult();
        status = pending.isEmpty() ? Status.WORKING : Status.OUTPUT;
    }

    private MEStorage lightningStorage() {
        var grid = getMainNode().getGrid();
        return grid == null ? null : grid.getStorageService().getInventory();
    }

    public long getAvailableLightning() {
        var storage = lightningStorage();
        return storage == null ? 0 : storage.extract(LightningKey.HIGH_VOLTAGE, Long.MAX_VALUE,
                Actionable.SIMULATE, IActionSource.ofMachine(this));
    }

    private void flushPending() {
        boolean changed = false;
        for (int i = 0; i < pending.size();) {
            MiningLoot.Drop drop = pending.get(i);
            int offered = (int) Math.min(drop.count(), (long) MiningFactoryInventory.CAPACITY * MiningFactoryInventory.OUTPUT_COUNT);
            ItemStack remainder = inventory.insertOutput(drop.stack().copyWithCount(offered));
            long left = drop.count() - offered + remainder.getCount();
            if (left != drop.count()) changed = true;
            if (left == 0) {
                pending.remove(i);
            } else {
                pending.set(i++, new MiningLoot.Drop(drop.stack(), left));
            }
        }
        if (changed) saveChanges();
    }

    private void inventoryChanged() {
        if (!processing) {
            lootFailed = false;
            // Refilling the same block or draining outputs must not restart the cycle.
            if (progressTicks > 0 && !matchesCycle()) resetProgress();
        }
        saveChanges();
    }

    private boolean matchesCycle() {
        return ItemStack.isSameItemSameTags(cycleInput, inventory.getStackInSlot(MiningFactoryInventory.INPUT))
                && ItemStack.matches(cycleTool, inventory.getStackInSlot(MiningFactoryInventory.TOOL))
                && cycleParallel == getInstalledParallelCapacity();
    }

    private void captureCycle() {
        cycleInput = inventory.getStackInSlot(MiningFactoryInventory.INPUT).copyWithCount(1);
        cycleTool = inventory.getStackInSlot(MiningFactoryInventory.TOOL).copy();
        cycleParallel = getInstalledParallelCapacity();
    }

    private void resetProgress() {
        if (progressTicks != 0) {
            progressTicks = 0;
            cycleInput = ItemStack.EMPTY;
            cycleTool = ItemStack.EMPTY;
            saveChanges();
        }
    }

    @Override public IItemHandlerModifiable getMatrixInventory() { return inventory; }
    @Override public int getMatrixSlot() { return MiningFactoryInventory.MATRIX; }
    public int getInstalledParallelCapacity() { return inventory.getInstalledParallelCapacity(); }
    public boolean isAutoExportEnabled() { return autoExport; }

    public void setAutoExportEnabled(boolean enabled) {
        if (autoExport != enabled) {
            autoExport = enabled;
            saveChanges();
        }
    }

    public EnumSet<RelativeSide> getAllowedOutputs() { return EnumSet.copyOf(allowedOutputs); }

    public void updateOutputSides(EnumSet<RelativeSide> sides) {
        allowedOutputs = EnumSet.copyOf(sides);
        exportTargets.invalidate();
        saveChanges();
    }

    public void onNeighborChanged(BlockPos pos) {
        if (pos != null && worldPosition.distManhattan(pos) == 1) exportTargets.invalidate();
    }

    public boolean pushOutResult() {
        if (!(level instanceof ServerLevel server) || allowedOutputs.isEmpty()
                || !AdjacentItemAutoExportHelper.hasAnyOutput(autoExport, MiningFactoryInventory.OUTPUT,
                        MiningFactoryInventory.OUTPUT_COUNT, inventory::getStackInSlot)) return false;
        return AdjacentItemAutoExportHelper.pushOutResult(this, getOrientation(), allowedOutputs,
                MiningFactoryInventory.OUTPUT, MiningFactoryInventory.OUTPUT_COUNT,
                inventory::getStackInSlot, (slot, count) -> inventory.extractItem(slot, count, false),
                remainder -> {
                    ItemStack left = inventory.insertOutput(remainder);
                    if (!left.isEmpty()) {
                        pending.add(new MiningLoot.Drop(left.copyWithCount(1), left.getCount()));
                        saveChanges();
                    }
                }, direction -> exportTargets.resolve(server, worldPosition, direction));
    }

    @Override public void exportSettings(appeng.util.SettingsFrom mode,
            CompoundTag builder,
            @org.jetbrains.annotations.Nullable Player player) {
        super.exportSettings(mode, builder, player);
        MemoryCardConfigSupport.exportAutoExportSettings(mode, builder, autoExport, allowedOutputs, tag -> {
            FrequencyBindingHelper.writeMemoryFrequency(tag, getFrequencyId());
            MemoryCardConfigSupport.writeMatrixCount(tag, this);
        });
    }

    @Override public void importSettings(appeng.util.SettingsFrom mode,
            CompoundTag input,
            @org.jetbrains.annotations.Nullable Player player) {
        super.importSettings(mode, input, player);
        MemoryCardConfigSupport.importAutoExportSettings(mode, input,
                enabled -> autoExport = enabled, sides -> allowedOutputs = sides,
                tag -> {
                    FrequencyBindingHelper.importMemoryFrequency(tag, this::setFrequency);
                    MemoryCardConfigSupport.restoreMatrixCount(tag, player, this);
                }, () -> {
                    exportTargets.invalidate();
                    saveChanges();
                    markForUpdate();
                });
    }

    @Override protected net.minecraft.world.item.Item getItemFromBlockEntity() {
        return ModBlocks.MINING_FACTORY.get().asItem();
    }

    public MiningFactoryInventory getInventory() { return inventory; }
    public IItemHandlerModifiable getAutomationInventory() { return automation; }
    public OverloadProcessingFactoryEnergyStorage getEnergyStorage() { return energy; }
    public Status getStatus() { return status; }
    public int getLastProcessed() { return lastProcessed; }
    public int getProgressTicks() { return progressTicks; }
    public int getLastSamples() { return lastSamples; }
    public List<MiningLoot.Drop> getPendingDrops() { return List.copyOf(pending); }
    public void openMenu(Player player, MenuLocator locator) { MenuOpener.open(MiningFactoryMenu.TYPE, player, locator); }

    @Override public void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        inventory.saveToTag(tag, "Inventory");
        tag.putLong("Energy", energy.getStoredEnergyLong());
        tag.putInt("ProgressTicks", progressTicks);
        ListTag output = new ListTag();
        for (MiningLoot.Drop drop : pending) {
            CompoundTag entry = new CompoundTag();
            entry.put("Stack", drop.stack().copyWithCount(1).save(new CompoundTag()));
            entry.putLong("Count", drop.count());
            output.add(entry);
        }
        tag.put("Pending", output);
        tag.putBoolean("AutoExport", autoExport);
        MemoryCardConfigSupport.writeRelativeSideSet(tag, "AllowedOutputs", allowedOutputs);
        frequencyBinding.save(tag);
    }

    @Override public void loadTag(CompoundTag tag) {
        super.loadTag(tag);
        resetProgress();
        inventory.loadFromTag(tag, "Inventory");
        energy.loadStoredEnergy(tag.getLong("Energy"));
        pending.clear();
        for (Tag element : tag.getList("Pending", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) element;
            ItemStack stack = ItemStack.of( entry.getCompound("Stack"));
            long count = entry.getLong("Count");
            if (!stack.isEmpty() && count > 0) pending.add(new MiningLoot.Drop(stack.copyWithCount(1), count));
        }
        autoExport = tag.getBoolean("AutoExport");
        allowedOutputs = MemoryCardConfigSupport.readRelativeSideSet(tag, "AllowedOutputs");
        exportTargets.invalidate();
        frequencyBinding.load(tag);
        progressTicks = net.minecraft.util.Mth.clamp(tag.getInt("ProgressTicks"), 0, PROCESSING_TICKS - 1);
        if (progressTicks > 0) captureCycle();
    }

    @Override public void addAdditionalDrops(Level level, BlockPos pos, List<ItemStack> drops) {
        super.addAdditionalDrops(level, pos, drops);
        for (int i = 0; i < inventory.getSlots(); i++) NativeStackDropHelper.addDrops(drops, inventory.getStackInSlot(i));
        for (MiningLoot.Drop drop : pending) {
            long left = drop.count();
            while (left > 0) {
                int count = (int) Math.min(left, Integer.MAX_VALUE);
                NativeStackDropHelper.addDrops(drops, drop.stack().copyWithCount(count));
                left -= count;
            }
        }
    }

    @Override public void clearContent() { super.clearContent(); resetProgress(); inventory.clear(); pending.clear(); }
    @Override public FrequencyBindingHelper getFrequencyBinding() { return frequencyBinding; }
    @Override public AENetworkBlockEntity getFrequencyBindingBlockEntity() { return this; }
    @Override public void saveFrequencyBindingChanges() { saveChanges(); }
    @Override public void markFrequencyBindingForUpdate() { markForUpdate(); }
    @Override public void onMainNodeStateChanged(IGridNodeListener.State reason) {
        super.onMainNodeStateChanged(reason); frequencyBinding.onMainNodeStateChanged(reason);
    }
    @Override public void onReady() { super.onReady(); frequencyBinding.onReady(); }
    @Override public void setRemoved() { exportTargets.invalidate(); frequencyBinding.setRemoved(); super.setRemoved(); }
    @Override public void onChunkUnloaded() { exportTargets.invalidate(); frequencyBinding.onChunkUnloaded(); super.onChunkUnloaded(); }
    @Override public void clearRemoved() { super.clearRemoved(); frequencyBinding.clearRemoved(); }
}
