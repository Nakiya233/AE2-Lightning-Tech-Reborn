package com.moakiee.ae2lt.blockentity;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.StorageCells;
import appeng.blockentity.storage.DriveBlockEntity;
import appeng.core.definitions.AEBlocks;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.moakiee.ae2lt.machine.crystalcatalyzer.CrystalCatalyzerInventory;
import com.moakiee.ae2lt.machine.crystalcatalyzer.recipe.*;
import com.moakiee.ae2lt.me.key.LightningKey;
import com.moakiee.ae2lt.registry.ModBlocks;
import com.moakiee.ae2lt.registry.ModItems;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Native recipes, codecs, AE lightning storage, machine ticks and persistence. Not shipped. */
@GameTestHolder("ae2lt_catalyzer")
@PrefixGameTestTemplate(false)
public final class CrystalCatalyzerFluidGameTests {
    private static final BlockPos POS = new BlockPos(2, 2, 2);
    private static final int CATALYST = CrystalCatalyzerInventory.SLOT_CATALYST;
    private static final int OUTPUT = CrystalCatalyzerInventory.SLOT_OUTPUT;
    private static final String WATER = "quartz_block";
    private record Route(String path, String mod, String fluid, String catalyst, String output) {}
    private static final List<Route> SPECIAL = List.of(
            new Route("fluxite_block", "oritech", "oritech:still_strange_matter",
                    "oritech:fluxite_block", "oritech:fluxite"),
            new Route("uranium_crystal", "oritech", "oritech:still_mineral_slurry",
                    "oritech:uranium_crystal", "oritech:uranium_crystal"),
            new Route("time_crystal_block", "justdirethings", "justdirethings:time_fluid_source",
                    "justdirethings:time_crystal_block", "justdirethings:time_crystal"));

    private static void check(boolean ok, String message) {
        if (!ok) throw new net.minecraft.gametest.framework.GameTestAssertException(message);
    }

    private static Optional<CrystalCatalyzerRecipeCandidate> find(GameTestHelper h, String path) {
        return CrystalCatalyzerRecipeService.findRecipeById(h.getLevel(),
                new ResourceLocation("ae2lt:crystal_catalyzer/" + path));
    }

    private static CrystalCatalyzerBlockEntity machine(GameTestHelper h, boolean pigmee) {
        h.setBlock(POS, pigmee ? ModBlocks.PIGMEE_CRYSTAL_CATALYZER.get() : ModBlocks.CRYSTAL_CATALYZER.get());
        return (CrystalCatalyzerBlockEntity) h.getBlockEntity(POS);
    }

    private static CrystalCatalyzerBlockEntity powered(GameTestHelper h, long lightning) {
        var host = machine(h, false);
        h.setBlock(POS.below(), AEBlocks.CREATIVE_ENERGY_CELL.block());
        h.setBlock(POS.east(), AEBlocks.DRIVE.block());
        var cell = new ItemStack(ModItems.LIGHTNING_STORAGE_COMPONENT_I.get());
        var storage = StorageCells.getCellInventory(cell, null);
        check(storage != null, "lightning cell is not registered");
        check(storage.insert(LightningKey.HIGH_VOLTAGE, lightning, Actionable.MODULATE,
                IActionSource.ofMachine(host)) == lightning, "could not seed lightning storage");
        storage.persist();
        DriveBlockEntity drive = (DriveBlockEntity) h.getBlockEntity(POS.east());
        drive.getInternalInventory().setItemDirect(0, cell);
        return host;
    }

    private static long lightning(CrystalCatalyzerBlockEntity host) {
        return host.getMainNode().getGrid().getStorageService().getInventory().extract(
                LightningKey.HIGH_VOLTAGE, Long.MAX_VALUE, Actionable.SIMULATE, IActionSource.ofMachine(host));
    }

    private static void supply(CrystalCatalyzerBlockEntity host, CrystalCatalyzerRecipe recipe) {
        host.getInventory().setItemDirect(CATALYST, recipe.catalyst().orElseThrow().getItems()[0].copyWithCount(256));
        host.getInventory().setItemDirect(CrystalCatalyzerInventory.SLOT_MATRIX,
                new ItemStack(ModItems.LIGHTNING_COLLAPSE_MATRIX.get()));
        check(host.getFluidHandlerCapability(Direction.UP).fill(recipe.fluidInput(), FluidAction.EXECUTE) == 1000,
                "pipe rejected the required fluid");
        host.getEnergyStorage().receiveEnergy(100_000, false);
    }

    @GameTest(templateNamespace = "ae2lt_catalyzer", template = "empty")
    public static void optionalRecipeDataAndCodecs(GameTestHelper h) {
        var serializer = new CrystalCatalyzerRecipe.Serializer();
        var legacyJson = JsonParser.parseString("""
                {"catalyst":{"item":"ae2:quartz_block"},"catalystCount":1,
                 "output":{"id":"ae2:certus_quartz_crystal","count":1},
                 "energyPerCycle":100000,"lightningCost":1}
                """);
        var legacy = serializer.fromJson(new ResourceLocation("ae2lt:test_legacy"), legacyJson.getAsJsonObject());
        check(legacy.isWaterRecipe() && legacy.fluidInput().getAmount() == 1000, "legacy JSON lost water default");
        var recipes = new ArrayList<CrystalCatalyzerRecipe>();
        recipes.add(legacy);
        for (var route : SPECIAL) {
            var candidate = find(h, route.path());
            check(candidate.isPresent() == ModList.get().isLoaded(route.mod()), "optional recipe boundary " + route.path());
            if (candidate.isEmpty()) continue;
            var recipe = candidate.get().recipe();
            check(BuiltInRegistries.FLUID.getKey(recipe.fluidInput().getFluid()).toString().equals(route.fluid()),
                    "wrong fluid " + route.path());
            check(recipe.fluidInput().getAmount() == 1000 && recipe.catalystCount() == 1
                    && recipe.getOutputTemplate().getCount() == 1 && recipe.energyPerCycle() == 100_000
                    && recipe.lightningCost() == 1 && recipe.lightningTier() == LightningKey.Tier.HIGH_VOLTAGE,
                    "per-cycle costs changed " + route.path());
            check(recipe.catalystMatches(new ItemStack(BuiltInRegistries.ITEM.get(new ResourceLocation(route.catalyst())))),
                    "wrong catalyst " + route.path());
            check(BuiltInRegistries.ITEM.getKey(recipe.getOutputTemplate().getItem()).toString().equals(route.output()),
                    "wrong output " + route.path());
            recipes.add(recipe);
        }
        for (var recipe : recipes) {
            var buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                serializer.toNetwork(buffer, recipe);
                var wire = serializer.fromNetwork(recipe.getId(), buffer);
                check(wire.fluidInput().isFluidStackIdentical(recipe.fluidInput()), "recipe sync changed fluid");
            } finally { buffer.release(); }
            var lock = new CrystalCatalyzerLockedRecipe(new ResourceLocation("ae2lt:test"),
                    recipe.getOutputTemplate(), recipe.energyPerCycle(), 1024, 1,
                    LightningKey.Tier.HIGH_VOLTAGE, recipe.fluidInput());
            var saved = lock.toTag();
            var restored = CrystalCatalyzerLockedRecipe.fromTag(saved);
            check(restored != null && restored.matchesFluidInput(recipe) && restored.outputMultiplier() == 1024,
                    "locked fluid or output multiplier lost on save");
            var copy = restored.fluidInput();
            copy.shrink(999);
            check(restored.fluidInput().getAmount() == 1000, "fluid accessor leaked mutable snapshot");
            saved.remove("InputFluid");
            check(CrystalCatalyzerLockedRecipe.fromTag(saved).matchesFluidInput(legacy),
                    "legacy in-flight NBT lost default water");
            saved.put("InputFluid", new CompoundTag());
            check(CrystalCatalyzerLockedRecipe.fromTag(saved) == null,
                    "invalid saved fluid was silently changed to water");
        }
        h.succeed();
    }

    @GameTest(templateNamespace = "ae2lt_catalyzer", template = "empty")
    public static void sameCatalystDifferentFluidsSelectCorrectOutput(GameTestHelper h) {
        var host = machine(h, false);
        var water = find(h, WATER).orElseThrow().recipe();
        var lava = new CrystalCatalyzerRecipe(new ResourceLocation("ae2lt_catalyzer:test_lava"), water.catalyst(), 1,
                CrystalCatalyzerOutput.ofItem(new ItemStack(net.minecraft.world.item.Items.DIAMOND)),
                100_000, 1, LightningKey.Tier.HIGH_VOLTAGE, Mode.CRYSTAL, new FluidStack(Fluids.LAVA, 1000));
        var manager = h.getLevel().getRecipeManager();
        var original = new ArrayList<>(manager.getRecipes());
        var modified = new ArrayList<>(original);
        var lavaId = new ResourceLocation("ae2lt_catalyzer:test_lava");
        modified.add(lava);
        try {
            manager.replaceRecipes(modified);
            host.getInventory().setItemDirect(CATALYST, AEBlocks.QUARTZ_BLOCK.stack());
            host.getTank().setFluid(new FluidStack(Fluids.LAVA, 1000));
            check(host.findProcessableRecipe().orElseThrow().recipe().getId().equals(lavaId), "lava selected water recipe");
            host.getTank().setFluid(new FluidStack(Fluids.WATER, 1000));
            check(host.findProcessableRecipe().orElseThrow().recipe() == water, "water selected lava recipe");
            host.getTank().setFluid(new FluidStack(Fluids.LAVA, 999));
            check(host.findProcessableRecipe().isEmpty(), "insufficient fluid matched");
        } finally { manager.replaceRecipes(original); }
        h.succeed();
    }

    private static void fullCycle(GameTestHelper h, String path) {
        fullCycle(h, path, 2048);
    }

    private static void fullCycle(GameTestHelper h, String path, int expectedCount) {
        var candidate = find(h, path);
        if (candidate.isEmpty()) { h.succeed(); return; }
        var recipe = candidate.get().recipe();
        var host = powered(h, 10);
        h.runAfterDelay(20, () -> supply(host, recipe));
        h.succeedWhen(() -> {
            var result = host.getInventory().getStackInSlot(OUTPUT);
            check(result.getCount() == expectedCount, "waiting for " + expectedCount + " products: " + path);
            check(ItemStack.isSameItemSameTags(result, recipe.getOutputTemplate()), "wrong product");
            check(host.getFluid().isEmpty() && host.getMachineStoredEnergy() == 0 && lightning(host) == 9,
                    "one cycle must cost exactly 1 B, 100000 FE and one lightning");
            check(host.getInventory().getStackInSlot(CATALYST).getCount() == 256, "catalysts were consumed");
            if (expectedCount == 16384) {
                check(host.getInventory().getSlotLimit(OUTPUT) == 16384, "full batch exceeds output capacity");
                check(host.getInventory().insertRecipeOutput(recipe.getOutputTemplate().copyWithCount(1), false)
                                .getCount() == 1,
                        "full output accepted an extra item");
                var saved = new CompoundTag();
                host.saveAdditional(saved);
                host.clearContent();
                host.loadTag(saved);
                check(host.getInventory().getStackInSlot(OUTPUT).getCount() == 16384,
                        "saving the full output truncated its count");
                var automation = host.getAutomationInventory();
                check(automation.extractItem(OUTPUT, 64, true).getCount() == 64
                                && host.getInventory().getStackInSlot(OUTPUT).getCount() == 16384,
                        "simulated extraction changed the full output");
                check(automation.extractItem(OUTPUT, 64, false).getCount() == 64
                                && host.getInventory().getStackInSlot(OUTPUT).getCount() == 16320,
                        "automation extraction lost output items");
            }
        });
    }

    @GameTest(templateNamespace = "ae2lt_catalyzer", template = "empty", timeoutTicks = 200)
    public static void legacyWater2048(GameTestHelper h) { fullCycle(h, WATER); }
    @GameTest(templateNamespace = "ae2lt_catalyzer", template = "empty", timeoutTicks = 200)
    public static void fluxite2048(GameTestHelper h) { fullCycle(h, "fluxite_block"); }
    @GameTest(templateNamespace = "ae2lt_catalyzer", template = "empty", timeoutTicks = 200)
    public static void uranium2048(GameTestHelper h) { fullCycle(h, "uranium_crystal"); }
    @GameTest(templateNamespace = "ae2lt_catalyzer", template = "empty", timeoutTicks = 200)
    public static void timeCrystal2048(GameTestHelper h) { fullCycle(h, "time_crystal_block"); }

    @GameTest(templateNamespace = "ae2lt_catalyzer", template = "empty", timeoutTicks = 300)
    public static void savedCycleWaitsForFluidAndOutput(GameTestHelper h) {
        var recipe = find(h, "time_crystal_block").orElseGet(() -> find(h, WATER).orElseThrow()).recipe();
        var host = powered(h, 10);
        long[] paid = {-1};
        h.runAfterDelay(20, () -> supply(host, recipe));
        h.runAfterDelay(28, () -> {
            check(host.hasLockedRecipe() && host.getConsumedEnergy() > 0, "cycle never started");
            var tag = new CompoundTag();
            host.saveAdditional(tag);
            paid[0] = host.getConsumedEnergy();
            host.clearContent();
            host.loadTag(tag);
            check(host.getLockedRecipe().orElseThrow().matchesFluidInput(recipe), "restored wrong fluid");
            var insufficient = recipe.fluidInput();
            insufficient.setAmount(999);
            host.getTank().setFluid(insufficient);
        });
        h.runAfterDelay(65, () -> {
            check(host.hasLockedRecipe() && host.getConsumedEnergy() == paid[0], "missing fluid lost/advanced progress");
            check(host.getFluid().getAmount() == 999 && lightning(host) == 10, "missing fluid spent materials");
            host.getInventory().setItemDirect(OUTPUT, recipe.getOutputTemplate().copyWithCount(CrystalCatalyzerInventory.OUTPUT_SLOT_LIMIT));
            host.getTank().setFluid(recipe.fluidInput());
        });
        h.runAfterDelay(100, () -> {
            check(host.getConsumedEnergy() == paid[0] && host.getFluid().getAmount() == 1000 && lightning(host) == 10,
                    "blocked output advanced or spent resources");
            host.getInventory().extractItem(OUTPUT, CrystalCatalyzerInventory.OUTPUT_SLOT_LIMIT, false);
        });
        h.succeedWhen(() -> {
            check(h.getTick() > 100 && host.getInventory().getStackInSlot(OUTPUT).getCount() == 2048, "waiting for recovery");
            check(host.getFluid().isEmpty() && host.getMachineStoredEnergy() == 0 && lightning(host) == 9,
                    "recovered cycle did not settle once");
        });
    }

    @GameTest(templateNamespace = "ae2lt_catalyzer", template = "empty", timeoutTicks = 200)
    public static void pigmeeRejectsSpecialFluidsAndLegacyFluxite(GameTestHelper h) {
        var host = machine(h, true);
        h.runAfterDelay(15, () -> {
            var fluids = new ArrayList<FluidStack>();
            fluids.add(new FluidStack(Fluids.LAVA, 1000));
            for (var route : SPECIAL) find(h, route.path()).ifPresent(c -> fluids.add(c.recipe().fluidInput()));
            for (var fluid : fluids) {
                check(!host.getTank().isFluidValid(fluid), "GUI tank accepted non-water");
                check(host.getTank().fill(fluid, FluidAction.EXECUTE) == 0, "direct fill accepted non-water");
                for (var side : Direction.values()) {
                    var handler = host.getFluidHandlerCapability(side);
                    check(!handler.isFluidValid(0, fluid)
                            && handler.fill(fluid, FluidAction.SIMULATE) == 0
                            && handler.fill(fluid, FluidAction.EXECUTE) == 0, "pipe accepted non-water");
                }
            }
            for (var route : SPECIAL) {
                var candidate = find(h, route.path());
                if (candidate.isEmpty()) continue;
                var recipe = candidate.get().recipe();
                var catalyst = recipe.catalyst().orElseThrow().getItems()[0].copyWithCount(64);
                check(!host.getInventory().isItemValid(CATALYST, catalyst), "Pigmee accepted special catalyst");
                // Simulate old NBT bypassing insertion validation.
                host.getInventory().setItemDirect(CATALYST, catalyst);
                host.getTank().setFluid(recipe.fluidInput());
                check(host.findProcessableRecipe().isEmpty(), "NBT bypass enabled non-water recipe");
                var old = new CrystalCatalyzerLockedRecipe(candidate.get().recipe().getId(), recipe.getOutputTemplate(),
                        100_000, 1, 1, LightningKey.Tier.HIGH_VOLTAGE);
                host.getTank().setFluid(new FluidStack(Fluids.WATER, 1000));
                check(!host.completeLockedRecipe(old, candidate.get()) && host.getFluid().getAmount() == 1000,
                        "legacy water snapshot completed special-fluid recipe");
            }
            h.succeed();
        });
    }

    @GameTest(templateNamespace = "ae2lt_catalyzer", template = "empty", timeoutTicks = 100)
    public static void changedRecipeFluidDoesNotStrandSavedCycle(GameTestHelper h) {
        var host = machine(h, false);
        h.runAfterDelay(15, () -> {
            var candidate = find(h, "fluxite_block").orElseGet(() -> find(h, WATER).orElseThrow());
            var recipe = candidate.recipe();
            host.getInventory().setItemDirect(CATALYST, recipe.catalyst().orElseThrow().getItems()[0]);
            var staleFluid = recipe.isWaterRecipe() ? new FluidStack(Fluids.LAVA, 1000)
                    : CrystalCatalyzerRecipe.defaultFluidInput();
            var stale = new CrystalCatalyzerLockedRecipe(candidate.recipe().getId(), recipe.getOutputTemplate(),
                    100_000, 1, 1, LightningKey.Tier.HIGH_VOLTAGE, staleFluid);
            var tag = new CompoundTag();
            host.saveAdditional(tag);
            tag.put("LockedRecipe", stale.toTag());
            tag.putLong("ConsumedEnergy", 5000);
            host.loadTag(tag);
            host.getTank().setFluid(recipe.fluidInput());
            // No FE/lightning and none of the old fluid: invalidation must precede waiting.
            host.getLogic().tickingRequest(host.getActionableNode(), 1);
            check(!host.hasLockedRecipe(), "stale fluid contract remained locked waiting for unavailable resources");
            check(host.getFluid().getAmount() == 1000 && host.getInventory().getStackInSlot(OUTPUT).isEmpty(),
                    "invalidating the stale cycle spent fluid or produced free output");
            check(host.lockCurrentRecipe().orElseThrow().matchesFluidInput(recipe), "replacement recipe did not relock");
            h.succeed();
        });
    }

    @GameTest(templateNamespace = "ae2lt_catalyzer", template = "empty", timeoutTicks = 350)
    public static void pigmeeStillNeeds100DistinctTicks(GameTestHelper h) {
        PigmeeCrystalCatalyzerGameTests.pigmeeRepeatedTicksDoNotAccelerate(h);
    }
    @GameTest(templateNamespace = "ae2lt_catalyzer", template = "empty", timeoutTicks = 500)
    public static void pigmeeLegacyProgressStillResumes(GameTestHelper h) {
        PigmeeCrystalCatalyzerGameTests.pigmeeSavedProgressAndLegacyRecipeIdResume(h);
    }
    @GameTest(templateNamespace = "ae2lt_catalyzer", template = "empty", timeoutTicks = 900)
    public static void pigmeeOutputCapacityStillPausesAndResumes(GameTestHelper h) {
        PigmeeCrystalCatalyzerGameTests.pigmeeOutputBackpressurePausesAndResumes(h);
    }
    @GameTest(templateNamespace = "ae2lt_catalyzer", template = "empty", timeoutTicks = 400)
    public static void normalStillRequiresEnergyAndLightning(GameTestHelper h) {
        PigmeeCrystalCatalyzerGameTests.normalCatalyzerDoesNotGainFreeProcessing(h);
    }

    /** Compare upstream loot definitions, before modpack global loot modifiers, including Entro. */
    @GameTest(template = "empty")
    public static void ae2csMotherRocksMatchNaturalHarvest(GameTestHelper h) {
        var host = machine(h, false);
        host.getTank().setFluid(new FluidStack(Fluids.WATER, 1000));
        for (var family : List.of("nether_quartz", "energized_certus_quartz", "ender_quartz",
                "energized_fluix", "fluix", "redstone", "resonating", "quantum", "link", "meteor", "entro")) {
            var catalystId = new ResourceLocation("ae2cs:" + family + "_mother_rock");
            var candidate = find(h, "ae2cs/" + family + "_mother_rock");
            boolean available = BuiltInRegistries.ITEM.containsKey(catalystId)
                    && (!family.equals("entro") || ModList.get().isLoaded("extendedae"));
            check(candidate.isPresent() == available, "incorrect optional recipe presence: " + family);
            if (!available) continue;

            var recipe = candidate.orElseThrow().recipe();
            var clusterId = new ResourceLocation(family.equals("entro")
                    ? "extendedae:entro_cluster" : "ae2cs:" + family + "_crystal_cluster");
            check(BuiltInRegistries.BLOCK.containsKey(clusterId), "upstream cluster is missing: " + family);
            var rawDrops = new ArrayList<ItemStack>();
            var lootParams = new net.minecraft.world.level.storage.loot.LootParams.Builder(h.getLevel())
                    .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.ORIGIN,
                            net.minecraft.world.phys.Vec3.atCenterOf(h.absolutePos(POS)))
                    .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.BLOCK_STATE,
                            BuiltInRegistries.BLOCK.get(clusterId).defaultBlockState())
                    .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.TOOL,
                            new ItemStack(net.minecraft.world.item.Items.DIAMOND_PICKAXE))
                    .create(net.minecraft.world.level.storage.loot.parameters.LootContextParamSets.BLOCK);
            h.getLevel().getServer().getLootData()
                    .getLootTable(BuiltInRegistries.BLOCK.get(clusterId).getLootTable())
                    .getRandomItemsRaw(lootParams, rawDrops::add);
            check(rawDrops.stream().anyMatch(drop -> ItemStack.isSameItemSameTags(drop, recipe.getOutputTemplate())),
                    "mature loot definition differs from catalyzer output: " + family + " drops=" + rawDrops);

            var catalyst = new ItemStack(BuiltInRegistries.ITEM.get(catalystId));
            host.getInventory().setItemDirect(CATALYST, catalyst);
            check(host.findProcessableRecipe().orElseThrow().recipe().getId().equals(candidate.get().recipe().getId()),
                    "machine did not select the mother rock recipe: " + family);
            check(recipe.isWaterRecipe() && recipe.fluidInput().getAmount() == 1000,
                    "mother rock must retain the standard water cost: " + family);
        }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void ae2csMotherRock16384(GameTestHelper h) {
        fullCycle(h, "ae2cs/quantum_mother_rock", 16384);
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void ae2csSingleMotherRockProducesEight(GameTestHelper h) {
        var candidate = find(h, "ae2cs/quantum_mother_rock");
        if (candidate.isEmpty()) { h.succeed(); return; }
        var recipe = candidate.get().recipe();
        var host = powered(h, 10);
        h.runAfterDelay(20, () -> {
            host.getInventory().setItemDirect(CATALYST, recipe.catalyst().orElseThrow().getItems()[0].copyWithCount(1));
            host.getTank().setFluid(recipe.fluidInput());
            host.getEnergyStorage().receiveEnergy(100_000, false);
        });
        h.succeedWhen(() -> {
            var result = host.getInventory().getStackInSlot(OUTPUT);
            check(result.getCount() == 8 && ItemStack.isSameItemSameTags(result, recipe.getOutputTemplate()),
                    "waiting for eight purified crystals from one mother rock");
            check(host.getInventory().getStackInSlot(CATALYST).getCount() == 1, "mother rock was consumed");
            check(host.getFluid().isEmpty() && host.getMachineStoredEnergy() == 0 && lightning(host) == 9,
                    "eight crystals must retain the standard cycle cost");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void pigmeeAe2csMotherRockRemainsReusable(GameTestHelper h) {
        var candidate = find(h, "ae2cs/quantum_mother_rock");
        if (candidate.isEmpty()) { h.succeed(); return; }
        var recipe = candidate.get().recipe();
        var host = machine(h, true);
        check(host.getInventory().getSlotLimit(OUTPUT) == 64, "Pigmee output cap must be 64");
        host.getInventory().setItemDirect(OUTPUT, recipe.getOutputTemplate().copyWithCount(57));
        check(!host.getInventory().canAcceptRecipeOutput(recipe.getOutputTemplate()),
                "eight-item mother rock output fit into only seven free spaces");
        host.getInventory().setItemDirect(OUTPUT, recipe.getOutputTemplate().copyWithCount(56));
        var catalyst = recipe.catalyst().orElseThrow().getItems()[0].copyWithCount(64);
        host.getInventory().setItemDirect(CATALYST, catalyst);
        host.getTank().setFluid(recipe.fluidInput());
        h.runAfterDelay(35, () -> {
            check(host.hasLockedRecipe() && host.getProcessingTicksSpent() > 0, "Pigmee cycle did not start");
            var tag = new CompoundTag();
            host.saveAdditional(tag);
            tag.getCompound("LockedRecipe").putInt("OutputMultiplier", 64);
            host.clearContent();
            host.loadTag(tag);
            check(host.getLockedRecipe().orElseThrow().output().getCount() == 8, "reload lost the base yield");
        });
        h.succeedWhen(() -> {
            var result = host.getInventory().getStackInSlot(OUTPUT);
            check(result.getCount() == 64 && ItemStack.isSameItemSameTags(result, recipe.getOutputTemplate()),
                    "waiting for eight purified crystals to fill the 64-item output");
            check(host.getFluid().isEmpty() && host.getMachineStoredEnergy() == 0,
                    "Pigmee must consume only one bucket of water");
            var remaining = host.getInventory().getStackInSlot(CATALYST);
            check(remaining.getCount() == 64 && ItemStack.isSameItemSameTags(remaining, catalyst),
                    "Pigmee consumed or replaced the mother rocks");
        });
    }
}
