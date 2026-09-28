package com.moakiee.ae2lt.blockentity;

import appeng.core.definitions.AEBlocks;
import appeng.core.definitions.AEItems;
import appeng.api.networking.ticking.IGridTickable;
import com.moakiee.ae2lt.grid.FrequencyBindingHost;
import com.moakiee.ae2lt.grid.WirelessFrequencyManager;
import com.moakiee.ae2lt.grid.wirelesslink.WirelessLinkRegistry;
import com.moakiee.ae2lt.machine.crystalcatalyzer.CrystalCatalyzerInventory;
import com.moakiee.ae2lt.machine.crystalcatalyzer.recipe.CrystalCatalyzerRecipeService;
import com.moakiee.ae2lt.machine.crystalcatalyzer.recipe.Mode;
import com.moakiee.ae2lt.registry.ModBlocks;
import com.moakiee.ae2lt.registry.ModItems;
import com.moakiee.ae2lt.registry.ModRecipeTypes;
import java.util.ArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Real world ticks/capabilities, plus repeated ticker calls to emulate external accelerators. */
@GameTestHolder("ae2lt_catalyzer")
@PrefixGameTestTemplate(false)
public final class PigmeeCrystalCatalyzerGameTests {
    private static final BlockPos POS = new BlockPos(2, 2, 2);
    private static final int CATALYST = CrystalCatalyzerInventory.SLOT_CATALYST;
    private static final int OUTPUT = CrystalCatalyzerInventory.SLOT_OUTPUT;

    private static CrystalCatalyzerBlockEntity machine(GameTestHelper helper) {
        helper.setBlock(POS, ModBlocks.PIGMEE_CRYSTAL_CATALYZER.get());
        return (CrystalCatalyzerBlockEntity) helper.getBlockEntity(POS);
    }

    private static void supply(CrystalCatalyzerBlockEntity host, int catalysts, int water) {
        host.getInventory().setItemDirect(CATALYST, AEBlocks.QUARTZ_BLOCK.stack(catalysts));
        host.getTank().setFluid(new FluidStack(Fluids.WATER, water));
    }

    private static int output(CrystalCatalyzerBlockEntity host) {
        var stack = host.getInventory().getStackInSlot(OUTPUT);
        if (!stack.isEmpty()) require(stack.is(AEItems.CERTUS_QUARTZ_CRYSTAL.asItem()), "wrong output item");
        return stack.getCount();
    }

    private static void require(boolean value, String message) {
        if (!value) throw new net.minecraft.gametest.framework.GameTestAssertException(message);
    }

    @GameTest(templateNamespace = "ae2lt_catalyzer", template = "empty", timeoutTicks = 100)
    public static void pigmeeCapabilitiesAndSharedRecipe(GameTestHelper helper) {
        var host = machine(helper);
        helper.runAfterDelay(10, () -> {
            var level = helper.getLevel();
            var pos = helper.absolutePos(POS);
            for (var side : Direction.values()) {
                var items = host.getCapability(ForgeCapabilities.ITEM_HANDLER, side).orElse(null);
                require(items != null, "Pigmee item capability missing on " + side);
                var fluid = host.getCapability(ForgeCapabilities.FLUID_HANDLER, side).orElse(null);
                require(fluid != null, "Pigmee fluid capability missing on " + side);
                require(host.getCapability(ForgeCapabilities.ENERGY, side).orElse(null) == null,
                        "water-only Pigmee must not accept FE from pipes");
                require(items.insertItem(CATALYST, AEBlocks.QUARTZ_BLOCK.stack(65), true).getCount() == 1,
                        "catalyst insertion must stop at 64");
                require(items.extractItem(CATALYST, 64, false).isEmpty(), "pipes must not remove catalysts");
                require(!items.insertItem(1, new ItemStack(ModItems.LIGHTNING_COLLAPSE_MATRIX.get()), true).isEmpty(),
                        "Pigmee must reject the collapse matrix");
                require(fluid.fill(new FluidStack(Fluids.WATER, 1000), FluidAction.SIMULATE) == 1000,
                        "water pipe simulation must accept one bucket");
            }
            require(host.getGridNode(Direction.UP) == null,
                    "standalone Pigmee must not expose a grid node capability");
            require(host.getMainNode().getNode() == null && host.getActionableNode() == null,
                    "Pigmee must not create an internal grid node");
            require(!(host instanceof FrequencyBindingHost), "Pigmee still supports frequency binding");
            require(!WirelessLinkRegistry.get(level.getServer()).isPotentialLinkTarget(level, pos),
                    "frequency card still accepts Pigmee as a target");
            require(host.getFluid().isEmpty(), "simulated water insertion mutated the tank");
            require(host.getInventory().getStackInSlot(CATALYST).isEmpty(), "simulated catalyst insertion mutated inventory");
            supply(host, 64, 1000);
            host.cycleMode();
            require(host.getMode() == Mode.CRYSTAL, "Pigmee must remain in crystal mode");
            var pigmee = host.findProcessableRecipe().orElseThrow().recipe();
            var normal = CrystalCatalyzerRecipeService.findRecipe(level, host.getInventory(), Mode.CRYSTAL)
                    .orElseThrow().recipe();
            require(pigmee.getId().equals(normal.getId()) && pigmee == normal,
                    "both machines must reuse the same registered recipe");
            require(pigmee.energyPerCycle() == 100_000 && pigmee.lightningCost() == 1
                            && pigmee.catalystCount() == 1 && pigmee.getOutputTemplate().getCount() == 1,
                    "machine overrides must not rewrite shared recipe costs or quantities");
            var loaded = level.getRecipeManager().getAllRecipesFor(ModRecipeTypes.CRYSTAL_CATALYZER_TYPE.get());
            require(loaded.stream().noneMatch(r -> r.getId().getPath().startsWith("crystal_catalyzer/pigmee_")),
                    "duplicate Pigmee recipes must not be registered");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = "ae2lt_catalyzer", template = "empty", timeoutTicks = 350)
    public static void pigmeeRepeatedTicksDoNotAccelerate(GameTestHelper helper) {
        var host = machine(helper);
        long[] firstGameTime = {-1};
        helper.runAfterDelay(20, () -> supply(host, 64, 3000));
        helper.onEachTick(() -> {
            if (helper.getTick() < 20) return;
            long gameTime = helper.getLevel().getGameTime();
            int before = output(host) * 100 + host.getProcessingTicksSpent();
            for (int i = 0; i < 1000; i++) {
                CrystalCatalyzerBlockEntity.serverTick(helper.getLevel(), host.getBlockPos(), host.getBlockState(), host);
            }
            int after = output(host) * 100 + host.getProcessingTicksSpent();
            require(helper.getLevel().getGameTime() == gameTime, "fixture changed the global game clock");
            require(after >= before && after <= before + 1,
                    "1000 calls in one game tick advanced more than once: " + before + " -> " + after);
            if (after == 0) return;
            if (firstGameTime[0] < 0) firstGameTime[0] = gameTime;
            require(after == gameTime - firstGameTime[0] + 1,
                    "Pigmee work does not match distinct game ticks: " + after);
            require(host.getMainNode().getNode() == null && host.getMachineStoredEnergy() == 0,
                    "accelerated Pigmee created a node or used FE");
            if (output(host) == 3) {
                require(gameTime - firstGameTime[0] == 299 && host.getFluid().isEmpty(),
                        "three cycles must require 300 game ticks and three buckets");
                helper.succeed();
            }
        });
    }

    @GameTest(templateNamespace = "ae2lt_catalyzer", template = "empty", timeoutTicks = 100)
    public static void pigmeeDropsLegacyWirelessBindingOnLoad(GameTestHelper helper) {
        var host = machine(helper);
        var level = helper.getLevel();
        var manager = WirelessFrequencyManager.get();
        require(manager != null, "wireless manager missing");
        int frequency = 1_000_123;
        manager.registerDevice(frequency, new WirelessFrequencyManager.DeviceEntry(
                level.dimension(), host.getBlockPos(), false, false));
        var tag = new CompoundTag();
        host.saveAdditional(tag);
        tag.putInt("FrequencyId", frequency);
        var proxy = new CompoundTag();
        proxy.putInt("owner", 123);
        tag.put("proxy", proxy);
        host.loadTag(tag);
        helper.runAfterDelay(20, () -> {
            require(host.getMainNode().getNode() == null, "old proxy NBT recreated a Pigmee node");
            require(manager.getDevices(frequency).stream().noneMatch(d -> d.pos().equals(host.getBlockPos())),
                    "old Pigmee still appears in the wireless device list");
            host.saveAdditional(tag);
            require(!tag.contains("FrequencyId") && !tag.contains("proxy"), "legacy AE state was saved again");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = "ae2lt_catalyzer", template = "empty", timeoutTicks = 700)
    public static void pigmeeRunsTwoWaterOnlyCyclesWithoutNetworkPower(GameTestHelper helper) {
        var host = machine(helper);
        long[] firstProgress = {-1};
        long[] firstCompletion = {-1};
        helper.runAfterDelay(20, () -> supply(host, 64, 2000));
        helper.onEachTick(() -> {
            long tick = helper.getTick();
            int amount = output(host);
            if (host.getProcessingTicksSpent() > 0 && firstProgress[0] < 0) firstProgress[0] = tick;
            require(host.getMachineStoredEnergy() == 0 && host.getConsumedEnergy() == 0, "Pigmee used FE");
            host.getLockedRecipe().ifPresent(recipe -> require(
                    recipe.energyPerCycle() == 100_000 && recipe.lightningCost() == 1,
                    "Pigmee must bypass recipe costs without rewriting the locked metadata"));
            if (firstProgress[0] < 0) return;
            require(host.getInventory().getStackInSlot(CATALYST).getCount() == 64, "catalyst was consumed");
            if (amount == 0) require(host.getFluid().getAmount() == 2000, "water spent before completion");
            if (amount == 1 && firstCompletion[0] < 0) {
                require(tick - firstProgress[0] == 99, "first cycle must take exactly 100 active ticks: "
                        + firstProgress[0] + " -> " + tick);
                require(host.getFluid().getAmount() == 1000, "first cycle must consume exactly 1000 mB");
                firstCompletion[0] = tick;
            }
            if (amount == 2) {
                require(tick - firstCompletion[0] == 100, "continuous cycle must take exactly 100 ticks");
                require(host.getFluid().isEmpty(), "second cycle water accounting failed");
                helper.succeed();
            }
        });
    }

    @GameTest(templateNamespace = "ae2lt_catalyzer", template = "empty", timeoutTicks = 800)
    public static void pigmeeWaitsForFullCatalystStackAndWater(GameTestHelper helper) {
        var host = machine(helper);
        helper.runAfterDelay(20, () -> supply(host, 63, 1000));
        helper.runAfterDelay(350, () -> {
            require(output(host) == 0 && host.getProcessingTicksSpent() == 0, "63 catalysts started processing");
            require(host.getFluid().getAmount() == 1000, "incomplete catalyst stack spent water");
            supply(host, 64, 999);
        });
        helper.runAfterDelay(400, () -> {
            require(output(host) == 0 && host.getProcessingTicksSpent() == 0, "999 mB started processing");
            host.getTank().fill(new FluidStack(Fluids.WATER, 1), FluidAction.EXECUTE);
        });
        helper.onEachTick(() -> {
            if (output(host) == 1) {
                require(helper.getTick() >= 499, "waiting time was counted as active processing");
                require(host.getFluid().isEmpty(), "water recovery did not consume exactly one bucket");
                require(host.getInventory().getStackInSlot(CATALYST).getCount() == 64, "catalyst loss after recovery");
                helper.succeed();
            }
        });
    }

    @GameTest(templateNamespace = "ae2lt_catalyzer", template = "empty", timeoutTicks = 900)
    public static void pigmeeOutputBackpressurePausesAndResumes(GameTestHelper helper) {
        var host = machine(helper);
        require(host.getInventory().getSlotLimit(OUTPUT) == 64, "Pigmee output must hold only 64 items");
        int[] pausedAt = {-1};
        helper.runAfterDelay(20, () -> supply(host, 64, 1000));
        helper.runAfterDelay(60, () -> {
            require(host.getProcessingTicksSpent() > 0 && output(host) == 0, "fixture never started");
            pausedAt[0] = host.getProcessingTicksSpent();
            // Older saves may exceed the new cap. Keep their contents available for extraction.
            host.getInventory().setItemDirect(OUTPUT, AEItems.CERTUS_QUARTZ_CRYSTAL.stack(128));
            var saved = new CompoundTag();
            host.saveAdditional(saved);
            host.clearContent();
            host.loadTag(saved);
            require(output(host) == 128, "lower output cap deleted legacy saved items");
            require(!host.getInventory().canAcceptRecipeOutput(AEItems.CERTUS_QUARTZ_CRYSTAL.stack()),
                    "legacy over-cap output accepted more items");
            require(host.getAutomationInventory().extractItem(OUTPUT, 64, false).getCount() == 64,
                    "legacy output could not be extracted down to the new cap");
        });
        helper.runAfterDelay(450, () -> {
            require(host.getProcessingTicksSpent() == pausedAt[0], "full output failed to pause progress");
            require(host.getFluid().getAmount() == 1000 && output(host) == 64, "blocked cycle spent resources");
            var items = host.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.UP).orElse(null);
            require(items != null && items.extractItem(OUTPUT, 1, true).getCount() == 1,
                    "output simulation did not expose retained products");
            require(output(host) == 64, "simulated extraction changed ownership");
            require(items.extractItem(OUTPUT, 1, false).getCount() == 1, "output pipe extraction lost products");
        });
        helper.onEachTick(() -> {
            if (helper.getTick() > 450 && output(host) == 64) {
                require(helper.getTick() >= 450 + 100 - pausedAt[0] - 1, "paused time accelerated the recipe");
                require(host.getFluid().isEmpty() && host.getInventory().getStackInSlot(CATALYST).getCount() == 64,
                        "resumed cycle resource accounting failed");
                helper.succeed();
            }
        });
    }

    @GameTest(templateNamespace = "ae2lt_catalyzer", template = "empty", timeoutTicks = 500)
    public static void pigmeeSavedProgressAndLegacyRecipeIdResume(GameTestHelper helper) {
        var host = machine(helper);
        helper.runAfterDelay(20, () -> supply(host, 64, 1000));
        helper.runAfterDelay(50, () -> {
            require(host.getProcessingTicksSpent() > 0 && host.hasLockedRecipe(), "fixture never started");
            int progress = host.getProcessingTicksSpent();
            var tag = new CompoundTag();
            host.saveAdditional(tag);
            tag.getCompound("LockedRecipe").putString("RecipeId", "ae2lt:crystal_catalyzer/pigmee_quartz_block");
            tag.getCompound("LockedRecipe").putInt("Energy", 400_000);
            tag.getCompound("LockedRecipe").put("Output", AEItems.CERTUS_QUARTZ_CRYSTAL.stack(16)
                    .save(new CompoundTag()));
            tag.putLong("ConsumedEnergy", 123_456);
            host.clearContent();
            host.loadTag(tag);
            require(host.getProcessingTicksSpent() == progress, "NBT load lost progress");
            var restored = host.getLockedRecipe().orElseThrow();
            require(restored.recipeId().toString().equals("ae2lt:crystal_catalyzer/quartz_block")
                            && restored.totalEnergy() == 400_000 && host.getConsumedEnergy() == 0
                            && restored.output().getCount() == 1,
                    "legacy ID migration must preserve cost/progress, normalize yield and bypass FE");
            var migratedSave = tag.copy();
            migratedSave.remove("PigmeeBaseYield");
            migratedSave.getCompound("LockedRecipe").putString("RecipeId", "ae2lt:crystal_catalyzer/quartz_block");
            host.loadTag(migratedSave);
            require(host.getLockedRecipe().orElseThrow().output().getCount() == 1
                            && host.getProcessingTicksSpent() == progress,
                    "already-migrated legacy snapshot revived the retired 16-item yield");
            tag.getCompound("LockedRecipe").putInt("Energy", 0);
            host.loadTag(tag);
            require(host.getProcessingTicksSpent() == progress
                            && host.getLockedRecipe().orElseThrow().recipeId().toString()
                                    .equals("ae2lt:crystal_catalyzer/quartz_block"),
                    "legacy zero-energy snapshot must also migrate without losing progress");
            require(host.getFluid().getAmount() == 1000 && host.getInventory().getStackInSlot(CATALYST).getCount() == 64,
                    "NBT load lost inventory/fluid");
        });
        helper.onEachTick(() -> {
            if (output(host) == 1) {
                require(helper.getTick() < 200, "load restarted the entire cycle");
                var drops = new ArrayList<ItemStack>();
                host.addAdditionalDrops(helper.getLevel(), helper.absolutePos(POS), drops);
                require(drops.stream().filter(s -> s.is(AEBlocks.QUARTZ_BLOCK.asItem())).mapToInt(ItemStack::getCount).sum() == 64,
                        "breaking the machine would lose catalysts");
                require(drops.stream().filter(s -> s.is(AEItems.CERTUS_QUARTZ_CRYSTAL.asItem())).mapToInt(ItemStack::getCount).sum() == 1,
                        "breaking the machine would lose output");
                helper.succeed();
            }
        });
    }

    @GameTest(templateNamespace = "ae2lt_catalyzer", template = "empty", timeoutTicks = 400)
    public static void normalCatalyzerDoesNotGainFreeProcessing(GameTestHelper helper) {
        helper.setBlock(POS, ModBlocks.CRYSTAL_CATALYZER.get());
        CrystalCatalyzerBlockEntity host = (CrystalCatalyzerBlockEntity) helper.getBlockEntity(POS);
        helper.runAfterDelay(20, () -> supply(host, 64, 1000));
        helper.runAfterDelay(350, () -> {
            require(!host.isPigmeeVariant() && output(host) == 0, "normal machine produced without FE/lightning");
            require(host.getFluid().getAmount() == 1000 && host.getInventory().getStackInSlot(CATALYST).getCount() == 64,
                    "normal idle machine spent resources");
            require(host.getCapability(ForgeCapabilities.ENERGY, Direction.UP).orElse(null) != null,
                    "normal FE capability disappeared");
            require(host instanceof FrequencyBindingHost
                            && host.getMainNode().getNode() != null
                            && host.getMainNode().getNode().getService(IGridTickable.class) != null,
                    "normal machine lost wireless binding or AE processing service");
            require(host.getGridNode(Direction.UP) != null,
                    "normal AE cable capability disappeared");
            helper.succeed();
        });
    }
}
