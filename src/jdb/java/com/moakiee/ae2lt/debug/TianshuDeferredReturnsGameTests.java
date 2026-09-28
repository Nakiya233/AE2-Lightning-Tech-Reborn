package com.moakiee.ae2lt.debug;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
import appeng.crafting.CraftingPlan;
import appeng.me.service.CraftingService;
import com.moakiee.ae2lt.blockentity.MatrixControllerBlockEntity;
import com.moakiee.ae2lt.blockentity.MatrixPortBlockEntity;
import com.moakiee.ae2lt.crafting.matrix.core.CraftingCoreHost;
import com.moakiee.ae2lt.crafting.matrix.core.CraftingCoreRegistry;
import com.moakiee.ae2lt.crafting.matrix.core.MolecularCopyAssembler;
import com.moakiee.ae2lt.crafting.timewheel.TimeWheelCraftingCPU;
import com.moakiee.ae2lt.crafting.timewheel.TimeWheelCraftingCpuHost;
import com.moakiee.ae2lt.crafting.runtime.ExecuteLoopPattern;
import com.moakiee.thunderbolt.core.crafting.batch.BatchCopyLimitPattern;
import com.moakiee.thunderbolt.core.crafting.batch.SharedBatchInputPattern;
import com.moakiee.thunderbolt.core.crafting.loop.ClosedLoopBatchPatternDetails;
import com.moakiee.thunderbolt.core.crafting.loop.CraftingTaskPersistenceDefinition;
import com.moakiee.thunderbolt.core.crafting.loop.ISeedPreservingCraftingTask;
import com.moakiee.ae2lt.logic.craft.MatrixCraftCore;
import com.moakiee.ae2lt.logic.craft.MatrixCraftingCluster;
import com.moakiee.ae2lt.logic.craft.MatrixCraftingEnergy;
import com.moakiee.ae2lt.logic.craft.MatrixCraftingUnit;
import com.moakiee.ae2lt.registry.ModBlocks;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.level.Level;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Real AE2 recipes, transformed dispatch code, matrix port/controller/core and CPU accounting. */
@GameTestHolder("ae2lt_deferred")
@PrefixGameTestTemplate(false)
public final class TianshuDeferredReturnsGameTests {
    @GameTest(template = "empty")
    public static void globalAdapterRunsThroughActualTimeWheelCpu(GameTestHelper helper) throws Exception {
        var fixture = new Fixture(helper.getLevel(), true);
        var nativeProvider = fixture.service.getProviders(fixture.patterns.get(0)).iterator().next();
        fixture.providers.remove(nativeProvider);
        var ordinary = new ICraftingProvider() {
            @Override public List<IPatternDetails> getAvailablePatterns() { return nativeProvider.getAvailablePatterns(); }
            @Override public boolean isBusy() { return nativeProvider.isBusy(); }
            @Override public boolean pushPattern(IPatternDetails pattern, KeyCounter[] input) {
                throw new AssertionError("time-wheel CPU must use the global batch adapter");
            }
        };
        var id = new ResourceLocation("ae2lt_global_batch", "timewheel");
        com.moakiee.thunderbolt.api.crafting.batch.BatchProviderAdapters.register(id,
                (provider, pattern, job) -> provider == ordinary
                        ? (com.moakiee.thunderbolt.api.crafting.batch.IBatchCraftingProvider) nativeProvider : null);
        fixture.providers.add(ordinary);
        try {
            fixture.submitChain();
            helper.assertTrue(fixture.run(32) == 3, "three batch calls through the global adapter");
            fixture.assertFinished(helper);
        } finally {
            com.moakiee.thunderbolt.api.crafting.batch.BatchProviderAdapters.unregister(id);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void sharedClosedLoopSeedReturnsBetweenBatchesInSameTick(GameTestHelper helper) throws Exception {
        runSeedTest(helper, false, 2);
    }

    @GameTest(template = "empty")
    public static void concreteSeedSinglePushReturnsAfterItsLedgerCommit(GameTestHelper helper) throws Exception {
        runSeedTest(helper, true, 4);
    }

    private static void runSeedTest(GameTestHelper helper, boolean alternatives, int expectedDispatches) throws Exception {
        var recipe = new CatalystPattern(alternatives);
        var fixture = new Fixture(helper.getLevel(), true, List.of(recipe));
        var seed = AEItemKey.of(Items.EMERALD);
        var consumer = UUID.randomUUID();
        var seedAmount = new KeyCounter();
        seedAmount.add(seed, 1);
        var task = new ExecuteLoopPattern(recipe, consumer, seedAmount, seedAmount, Map.of(consumer, seedAmount));
        var used = new KeyCounter();
        used.add(seed, 1);
        used.add(AEItemKey.of(Items.REDSTONE), 4);
        fixture.disk.items.add(seed, 1);
        fixture.disk.items.add(AEItemKey.of(Items.REDSTONE), 4);
        var plan = new CraftingPlan(new GenericStack(AEItemKey.of(Items.DIAMOND), 4), 100,
                false, false, used, new KeyCounter(), new KeyCounter(), Map.of(task, 4L));
        var result = fixture.cpu.getCraftingLogic().trySubmitJob(fixture.host.getGrid(), plan, IActionSource.empty(), null);
        helper.assertTrue(result.successful(), "seed task submitted");
        helper.assertTrue(fixture.run(32) == expectedDispatches, "seed must return before next dispatch");
        helper.assertTrue(!fixture.cpu.getCraftingLogic().hasJob(), "seed task completed");
        helper.assertTrue(fixture.disk.items.get(AEItemKey.of(Items.DIAMOND)) == 4, "four produced outputs");
        helper.assertTrue(fixture.returnedSeeds.get(seed) == 1, "exactly one private seed returned");
        helper.assertTrue(fixture.disk.items.get(seed) == 0, "seed remains private");
        helper.assertTrue(!fixture.cpu.isBusy(), "no seed or return remains stranded");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void storageCallbackCannotReenterDispatchOrDrain(GameTestHelper helper) throws Exception {
        var fixture = new Fixture(helper.getLevel(), true);
        fixture.submitChain();
        fixture.disk.beforeInsert = () -> helper.assertTrue(fixture.run(32) == 0, "recursive dispatch rejected");
        fixture.run(32);
        fixture.assertFinished(helper);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void threeLayerBatchChainCompletesWithoutAdvancingTick(GameTestHelper helper) throws Exception {
        var fixture = new Fixture(helper.getLevel(), true);
        fixture.submitChain();
        long tick = helper.getLevel().getGameTime();
        int operations = fixture.run(32);
        helper.assertTrue(operations == 3, "one batch dispatch per layer: " + operations);
        fixture.assertFinished(helper);
        helper.assertTrue(helper.getLevel().getGameTime() == tick, "execution must stay in one tick");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void ordinaryPushPathAlsoCompletesInOneTick(GameTestHelper helper) throws Exception {
        var fixture = new Fixture(helper.getLevel(), false);
        fixture.submitChain();
        helper.assertTrue(fixture.run(32) == 8, "ordinary pushes charge all eight copies");
        fixture.assertFinished(helper);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void lastDispatchBudgetStillDrainsItsReturn(GameTestHelper helper) throws Exception {
        var fixture = new Fixture(helper.getLevel(), true);
        fixture.submitChain();
        helper.assertTrue(fixture.run(1) == 1, "exactly one dispatch");
        helper.assertTrue(fixture.cpu.getCraftingLogic().getInventory().list.get(AEItemKey.of(Items.OAK_PLANKS)) == 8,
                "last-budget return already available inside CPU");
        helper.assertTrue(fixture.disk.items.get(AEItemKey.of(Items.OAK_PLANKS)) == 0,
                "intermediate output does not pass through network storage");
        helper.assertTrue(fixture.cluster.threadsInFlight() == 0, "matrix has no five-tick pending output");
        helper.runAfterDelay(1, () -> {
            fixture.run(32);
            fixture.assertFinished(helper);
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void blockedFinalOutputSurvivesSaveAndReload(GameTestHelper helper) throws Exception {
        var fixture = new Fixture(helper.getLevel(), true);
        fixture.submitChain();
        fixture.disk.blocked = true;
        fixture.run(32);
        helper.assertTrue(!fixture.cpu.getCraftingLogic().hasJob(), "physical production completed");
        helper.assertTrue(fixture.cpu.isBusy(), "undelivered physical items keep the CPU alive");
        var data = new CompoundTag();
        fixture.cpu.getCraftingLogic().writeToNBT(data, helper.getLevel().registryAccess());
        helper.assertTrue(data.contains("deferredDeliveryRemainders"), "blocked direct outputs persisted");
        var restored = new TimeWheelCraftingCPU(fixture.host, Long.MAX_VALUE, 31, Long.MAX_VALUE, false);
        restored.getCraftingLogic().readFromNBT(data, helper.getLevel().registryAccess());
        fixture.disk.blocked = false;
        restored.getCraftingLogic().tickCraftingLogic(fixture.energy, fixture.service);
        helper.assertTrue(fixture.disk.items.get(AEItemKey.of(Items.LADDER)) == 6, "all six ladders delivered once");
        helper.assertTrue(fixture.disk.items.get(AEItemKey.of(Items.STICK)) == 2, "surplus sticks preserved");
        helper.assertTrue(!restored.isBusy(), "restored CPU drains fully");
        helper.succeed();
    }

    private static final class Fixture {
        final Disk disk = new Disk();
        final KeyCounter returnedSeeds = new KeyCounter();
        final IEnergyService energy;
        final CraftingService service;
        final TimeWheelCraftingCpuHost host;
        final TimeWheelCraftingCPU cpu;
        final List<ICraftingProvider> providers = new java.util.ArrayList<>();
        final MatrixCraftingCluster cluster;
        final List<IPatternDetails> patterns;

        Fixture(ServerLevel level, boolean batch) throws Exception {
            this(level, batch, null);
        }

        Fixture(ServerLevel level, boolean batch, List<IPatternDetails> customPatterns) throws Exception {
            patterns = customPatterns != null ? customPatterns : List.of(
                    pattern(level, "oak_planks", new ItemStack(Items.OAK_PLANKS, 4),
                            Map.of(0, new ItemStack(Items.OAK_LOG))),
                    pattern(level, "stick", new ItemStack(Items.STICK, 4),
                            Map.of(0, new ItemStack(Items.OAK_PLANKS), 3, new ItemStack(Items.OAK_PLANKS))),
                    pattern(level, "ladder", new ItemStack(Items.LADDER, 3),
                            Map.of(0, new ItemStack(Items.STICK), 2, new ItemStack(Items.STICK),
                                    3, new ItemStack(Items.STICK), 4, new ItemStack(Items.STICK),
                                    5, new ItemStack(Items.STICK), 6, new ItemStack(Items.STICK),
                                    8, new ItemStack(Items.STICK))));
            energy = (IEnergyService) Proxy.newProxyInstance(IEnergyService.class.getClassLoader(),
                    new Class<?>[]{IEnergyService.class}, (p, method, args) ->
                            method.getName().equals("extractAEPower") ? args[0] : defaultValue(method.getReturnType()));
            var storage = proxy(IStorageService.class, Map.of("getInventory", disk, "getCachedInventory", disk.items));
            var gridValues = new HashMap<String, Object>();
            gridValues.put("getStorageService", storage);
            gridValues.put("getEnergyService", energy);
            var grid = proxy(IGrid.class, gridValues);
            var matrixHost = new CraftingCoreHost() {
                @Override public long getGameTime() { return level.getGameTime(); }
                @Override public boolean isRemoved() { return false; }
                @Override public boolean isConnected() { return true; }
                @Override public long insertToNetwork(AEKey key, long amount) {
                    throw new AssertionError("direct matrix chain must not use delayed network delivery");
                }
                @Override public void spawnToWorld(AEKey key, long amount) { throw new AssertionError("lost output"); }
            };
            var units = new MatrixCraftCore() {
                @Override public List<MatrixCraftingUnit> craftingUnits() {
                    return List.of(MatrixCraftingUnit.stableCore(), MatrixCraftingUnit.t2Threader());
                }
            };
            cluster = new MatrixCraftingCluster(() -> true, List.of(() -> patterns), List.of(units),
                    matrixHost, new MolecularCopyAssembler(level), new CraftingCoreRegistry(), MatrixCraftingEnergy.UNLIMITED);
            var controller = new MatrixControllerBlockEntity(BlockPos.ZERO,
                    ModBlocks.MATTER_WARPING_MATRIX_CONTROLLER.get().defaultBlockState());
            var clusterField = MatrixControllerBlockEntity.class.getDeclaredField("cluster");
            clusterField.setAccessible(true);
            clusterField.set(controller, cluster);
            // Only replace world/structure discovery; dispatch uses the production port and controller.
            var port = new MatrixPortBlockEntity(BlockPos.ZERO,
                    ModBlocks.MATTER_WARPING_MATRIX_PORT.get().defaultBlockState()) {
                @Override public MatrixControllerBlockEntity getController() { return controller; }
                @Override public boolean isFormed() { return true; }
                @Override public long getBatchCapacity(IPatternDetails details) {
                    return batch ? super.getBatchCapacity(details) : 0L;
                }
            };
            providers.add(port);
            service = new CraftingService(grid, storage, energy) {
                @Override public Iterable<ICraftingProvider> getProviders(IPatternDetails details) { return providers; }
            };
            gridValues.put("getCraftingService", service);
            host = new TimeWheelCraftingCpuHost() {
                @Override public boolean isCpuActive() { return true; }
                @Override public IGrid getGrid() { return grid; }
                @Override public IActionSource getActionSource() { return IActionSource.empty(); }
                @Override public Level getCpuLevel() { return level; }
                @Override public void markCpuDirty() { }
                @Override public Component getCpuDisplayName() { return Component.literal("Deferred test CPU"); }
                @Override public long insertReusableSeed(AEKey key, long amount, Actionable mode) {
                    if (mode == Actionable.MODULATE) returnedSeeds.add(key, amount);
                    return amount;
                }
            };
            cpu = new TimeWheelCraftingCPU(host, Long.MAX_VALUE, 31, Long.MAX_VALUE, false);
        }

        void submitChain() {
            var used = new KeyCounter();
            used.add(AEItemKey.of(Items.OAK_LOG), 2);
            disk.items.add(AEItemKey.of(Items.OAK_LOG), 2);
            var plan = new CraftingPlan(new GenericStack(AEItemKey.of(Items.LADDER), 6), 100,
                    false, false, used, new KeyCounter(), new KeyCounter(),
                    Map.of(patterns.get(0), 2L, patterns.get(1), 4L, patterns.get(2), 2L));
            var result = cpu.getCraftingLogic().trySubmitJob(host.getGrid(), plan, IActionSource.empty(), null);
            if (!result.successful()) throw new AssertionError("submission failed: " + result);
        }

        int run(int budget) {
            return cpu.getCraftingLogic().tickCraftingLogic(energy, service, budget, Long.MAX_VALUE)
                    .successfulDispatches();
        }

        void assertFinished(GameTestHelper helper) {
            helper.assertTrue(!cpu.getCraftingLogic().hasJob(), "chain completed");
            helper.assertTrue(disk.items.get(AEItemKey.of(Items.LADDER)) == 6, "six real crafted ladders");
            helper.assertTrue(disk.items.get(AEItemKey.of(Items.STICK)) == 2, "two surplus sticks");
            helper.assertTrue(cluster.threadsInFlight() == 0, "no matrix outputs left buffered");
            helper.assertTrue(!cpu.isBusy(), "no CPU state stranded");
        }
    }

    /** Deterministic test recipe with one reusable catalyst, also exercising actual-key seed dispatch. */
    private static final class CatalystPattern implements IMolecularAssemblerSupportedPattern,
            SharedBatchInputPattern, BatchCopyLimitPattern, ISeedPreservingCraftingTask,
            CraftingTaskPersistenceDefinition, ClosedLoopBatchPatternDetails {
        private final UUID group = UUID.randomUUID();
        private final boolean alternatives;
        private final AEItemKey seed = AEItemKey.of(Items.EMERALD);
        CatalystPattern(boolean alternatives) { this.alternatives = alternatives; }
        @Override public IInput[] getInputs() {
            return new IInput[]{input(seed, true), input(AEItemKey.of(Items.REDSTONE), false)};
        }
        private IInput input(AEItemKey key, boolean catalyst) {
            return new IInput() {
                @Override public GenericStack[] getPossibleInputs() {
                    return catalyst && alternatives
                            ? new GenericStack[]{new GenericStack(key, 1), new GenericStack(AEItemKey.of(Items.AMETHYST_SHARD), 1)}
                            : new GenericStack[]{new GenericStack(key, 1)};
                }
                @Override public long getMultiplier() { return 1; }
                @Override public boolean isValid(AEKey actual, Level level) {
                    return Arrays.stream(getPossibleInputs()).anyMatch(candidate -> candidate.what().equals(actual));
                }
                @Override public AEKey getRemainingKey(AEKey actual) { return catalyst ? actual : null; }
            };
        }
        @Override public GenericStack[] getOutputs() { return new GenericStack[] {new GenericStack(AEItemKey.of(Items.DIAMOND), 1)}; }
        @Override public AEItemKey getDefinition() { return AEItemKey.of(Items.PAPER); }
        @Override public void fillCraftingGrid(KeyCounter[] inputs, CraftingGridAccessor grid) {
            for (int slot = 0; slot < inputs.length; slot++) {
                for (var entry : inputs[slot]) {
                    if (entry.getLongValue() > 0) {
                        grid.set(slot, ((AEItemKey) entry.getKey()).toStack());
                        entry.setValue(entry.getLongValue() - 1);
                        break;
                    }
                }
            }
        }
        @Override public ItemStack assemble(net.minecraft.world.Container input, Level level) { return new ItemStack(Items.DIAMOND); }
        @Override public NonNullList<ItemStack> getRemainingItems(CraftingContainer input) {
            var remaining = NonNullList.withSize(input.getContainerSize(), ItemStack.EMPTY);
            remaining.set(0, input.getItem(0).copy());
            return remaining;
        }
        @Override public boolean isItemValid(int slot, AEItemKey key, Level level) { return true; }
        @Override public boolean isSlotEnabled(int slot) { return slot < 2; }
        @Override public boolean isSharedBatchInput(int slot, AEKey key) { return slot == 0; }
        @Override public long maxBatchCopies() { return 2; }
        @Override public UUID reusableSeedGroupId() { return group; }
        @Override public Set<AEKey> reusableSeedCycleKeys() { return Set.of(seed); }
        @Override public boolean hasSingleSeedInputPerMember() { return true; }
        @Override public AEItemKey craftingTaskPersistenceDefinition() { return getDefinition(); }
    }

    private static IPatternDetails pattern(ServerLevel level, String id, ItemStack output,
                                            Map<Integer, ItemStack> slots) {
        var raw = level.getRecipeManager().byKey(new ResourceLocation(id)).orElseThrow();
        var holder = (CraftingRecipe) raw;
        var inputs = new ItemStack[9];
        Arrays.fill(inputs, ItemStack.EMPTY);
        slots.forEach((slot, stack) -> inputs[slot] = stack);
        return PatternDetailsHelper.decodePattern(
                PatternDetailsHelper.encodeCraftingPattern(holder, inputs, output, false, false), level);
    }

    private static final class Disk implements MEStorage {
        final KeyCounter items = new KeyCounter();
        boolean blocked;
        Runnable beforeInsert = () -> {};
        @Override public long insert(AEKey key, long amount, Actionable mode, IActionSource source) {
            if (blocked) return 0;
            if (mode == Actionable.MODULATE) {
                beforeInsert.run();
                items.add(key, amount);
            }
            return amount;
        }
        @Override public long extract(AEKey key, long amount, Actionable mode, IActionSource source) {
            long taken = Math.min(amount, items.get(key));
            if (mode == Actionable.MODULATE) items.remove(key, taken);
            return taken;
        }
        @Override public void getAvailableStacks(KeyCounter out) {
            for (var entry : items) out.add(entry.getKey(), entry.getLongValue());
        }
        @Override public Component getDescription() { return Component.literal("Deferred return test storage"); }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0D;
        if (type == Optional.class) return Optional.empty();
        return null;
    }

    private static <T> T proxy(Class<T> type, Map<String, Object> values) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> values.containsKey(method.getName())
                        ? values.get(method.getName()) : defaultValue(method.getReturnType())));
    }
}
