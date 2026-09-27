package com.moakiee.ae2lt.blockentity;

import appeng.core.definitions.AEParts;
import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.StorageCells;
import com.moakiee.ae2lt.me.key.LightningKey;
import appeng.api.orientation.RelativeSide;
import appeng.util.SettingsFrom;
import com.moakiee.ae2lt.registry.ModItems;
import net.minecraft.core.Direction;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import java.util.EnumSet;
import com.moakiee.ae2lt.machine.miningfactory.MiningFactoryInventory;
import com.moakiee.ae2lt.machine.miningfactory.MiningLoot;
import com.moakiee.ae2lt.registry.ModBlocks;
import java.util.ArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("ae2lt_mining")
@PrefixGameTestTemplate(false)
public final class MiningFactoryGameTests {
    private static final BlockPos POS = new BlockPos(2, 2, 2);
    static MiningFactoryBlockEntity fixture(GameTestHelper h, ItemStack tool, int count, int energy) {
        h.setBlock(POS, ModBlocks.MINING_FACTORY.get());
        MiningFactoryBlockEntity be = (MiningFactoryBlockEntity) h.getBlockEntity(POS);
        be.getInventory().setStackInSlot(0, new ItemStack(Items.IRON_ORE, count));
        be.getInventory().setStackInSlot(1, tool);
        be.getInventory().setStackInSlot(MiningFactoryInventory.MATRIX, new ItemStack(ModItems.LIGHTNING_COLLAPSE_MATRIX.get(), 8));
        be.getEnergyStorage().receiveEnergy(energy, false);
        // Drain AE2's queued initialization once, through its normal lifecycle.
        // Calling onReady directly would leave a second initialization queued.
        try {
            var ready = appeng.hooks.ticking.TickHandler.class.getDeclaredMethod("readyBlockEntities",
                    net.minecraft.server.level.ServerLevel.class);
            ready.setAccessible(true);
            ready.invoke(appeng.hooks.ticking.TickHandler.instance(), h.getLevel());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        var cell = StorageCells.getCellInventory(new ItemStack(ModItems.LIGHTNING_STORAGE_COMPONENT_II.get()), null);
        h.assertTrue(cell != null, "Native lightning cell missing");
        h.assertTrue(cell.insert(LightningKey.HIGH_VOLTAGE, 1000, Actionable.MODULATE, IActionSource.ofMachine(be)) == 1000, "Failed to seed 1000 lightning");
        be.getMainNode().getGrid().getStorageService().addGlobalStorageProvider(mounts -> mounts.mount(cell, 0));
        return be;
    }
    static void completeCycle(GameTestHelper h, MiningFactoryBlockEntity be, Runnable assertions) {
        be.processTick();
        h.runAfterDelay(MiningFactoryBlockEntity.PROCESSING_TICKS - 1, () -> {
            be.processTick();
            assertions.run();
        });
    }

    private static ItemStack enchanted(GameTestHelper h, Item item, net.minecraft.world.item.enchantment.Enchantment enchantment, int level) {
        var stack = new ItemStack(item);
        stack.enchant(enchantment, level);
        return stack;
    }
    private static long output(MiningFactoryBlockEntity be, Item item) {
        long count = 0;
        for (int i = 2; i < 11; i++) if (be.getInventory().getStackInSlot(i).is(item)) count += be.getInventory().getStackInSlot(i).getCount();
        for (var drop : be.getPendingDrops()) if (drop.stack().is(item)) count += drop.count();
        return count;
    }
    private static void blockOutputs(MiningFactoryBlockEntity be) {
        for (int i = 2; i < 11; i++) be.getInventory().setItemDirect(i, new ItemStack(Items.STONE, MiningFactoryInventory.CAPACITY));
    }

    @GameTest(template = "empty")
    public static void wiredNetworkSuppliesLightningFromActualDrive(GameTestHelper h) {
        h.setBlock(POS, ModBlocks.MINING_FACTORY.get());
        MiningFactoryBlockEntity be = (MiningFactoryBlockEntity) h.getBlockEntity(POS);
        h.setBlock(POS.below(), appeng.core.definitions.AEBlocks.CREATIVE_ENERGY_CELL.block());
        h.setBlock(POS.west(), appeng.core.definitions.AEBlocks.DRIVE.block());
        var stack = new ItemStack(ModItems.LIGHTNING_STORAGE_COMPONENT_I.get());
        var cell = StorageCells.getCellInventory(stack, null);
        h.assertTrue(cell.insert(LightningKey.HIGH_VOLTAGE, 1, Actionable.MODULATE, IActionSource.ofMachine(be)) == 1,
                "Could not seed physical drive cell");
        cell.persist();
        ((appeng.blockentity.storage.DriveBlockEntity) h.getBlockEntity(POS.west()))
                .getInternalInventory().setItemDirect(0, stack);
        be.getInventory().setStackInSlot(0, new ItemStack(Items.IRON_ORE, 64));
        be.getInventory().setStackInSlot(1, new ItemStack(Items.DIAMOND_PICKAXE));
        be.getInventory().setStackInSlot(MiningFactoryInventory.MATRIX,
                new ItemStack(ModItems.LIGHTNING_COLLAPSE_MATRIX.get(), 8));
        h.startSequence().thenWaitUntil(() -> {
            h.assertTrue(appeng.api.networking.GridHelper.getNodeHost(h.getLevel(), h.absolutePos(POS)) == be, "Machine is invisible to real ME connections");
            h.assertTrue(be.getAvailableLightning() == 1, "Waiting for physical drive to join the network");
        }).thenExecute(() -> be.getEnergyStorage().receiveEnergy(64 * 256, false))
                .thenExecuteAfter(6, () -> {
                    h.assertTrue(output(be, Items.RAW_IRON) == 64 && be.getAvailableLightning() == 0,
                            "Actual wired grid did not pay for one completed batch");
                }).thenSucceed();
    }

    @GameTest(template = "empty")
    public static void oneLightningPerBatchOnlyAtCompletion(GameTestHelper h) {
        var be = fixture(h, new ItemStack(Items.DIAMOND_PICKAXE), 512, 1_000_000);
        be.getInventory().setStackInSlot(MiningFactoryInventory.MATRIX,
                new ItemStack(ModItems.LIGHTNING_COLLAPSE_MATRIX.get(), 32));
        be.processTick();
        h.runAfterDelay(3, () -> {
            be.processTick();
            h.assertTrue(be.getAvailableLightning() == 1000 && output(be, Items.RAW_IRON) == 0,
                    "Unfinished batch lightning=" + be.getAvailableLightning() + ", output=" + output(be, Items.RAW_IRON));
        });
        h.runAfterDelay(4, () -> {
            be.processTick();
            h.assertTrue(be.getAvailableLightning() == 999 && output(be, Items.RAW_IRON) == 256,
                    "256 parallel blocks must cost exactly one lightning");
        });
        h.runAfterDelay(9, () -> {
            be.processTick();
            h.assertTrue(be.getAvailableLightning() == 998 && output(be, Items.RAW_IRON) == 512,
                    "Second batch must spend exactly one additional lightning");
            h.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void missingLightningStopsWithoutSpendingInputs(GameTestHelper h) {
        var be = fixture(h, new ItemStack(Items.DIAMOND_PICKAXE), 64, 1_000_000);
        be.getMainNode().getGrid().getStorageService().getInventory().extract(
                LightningKey.HIGH_VOLTAGE, 1000, Actionable.MODULATE, IActionSource.ofMachine(be));
        completeCycle(h, be, () -> {
            h.assertTrue(be.getStatus() == MiningFactoryBlockEntity.Status.LIGHTNING && be.getProgressTicks() == 0,
                    "No lightning must stop unpaid progress");
            h.assertTrue(be.getInventory().getStackInSlot(0).getCount() == 64 && output(be, Items.RAW_IRON) == 0
                    && be.getLastSamples() == 0 && be.getInventory().getStackInSlot(1).getDamageValue() == 0
                    && be.getEnergyStorage().getEnergyStored() == 1_000_000, "Missing lightning spent costs");
            h.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void missingLightningResetsProgressAndRecoveryCostsOnce(GameTestHelper h) {
        var be = fixture(h, new ItemStack(Items.DIAMOND_PICKAXE), 64, 1_000_000);
        be.processTick();
        var storage = be.getMainNode().getGrid().getStorageService().getInventory();
        storage.extract(LightningKey.HIGH_VOLTAGE, 1000, Actionable.MODULATE, IActionSource.ofMachine(be));
        h.runAfterDelay(1, () -> {
            be.processTick();
            h.assertTrue(be.getProgressTicks() == 0 && be.getStatus() == MiningFactoryBlockEntity.Status.LIGHTNING,
                    "Lightning loss must reset unpaid progress");
            storage.insert(LightningKey.HIGH_VOLTAGE, 1, Actionable.MODULATE, IActionSource.ofMachine(be));
        });
        h.runAfterDelay(5, () -> {
            be.processTick();
            h.assertTrue(be.getProgressTicks() == 4 && be.getAvailableLightning() == 1,
                    "Restored lightning was spent before a complete new cycle");
        });
        h.runAfterDelay(6, () -> {
            be.processTick();
            h.assertTrue(output(be, Items.RAW_IRON) == 64 && be.getAvailableLightning() == 0,
                    "Recovery must complete exactly one paid batch");
            h.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void samplesAreBoundedAndRepeatedTicksDoNotProcessTwice(GameTestHelper h) {
        var be = fixture(h, new ItemStack(Items.DIAMOND_PICKAXE), 128, 1_000_000);
        completeCycle(h, be, () -> {
            be.processTick();
            h.assertTrue(output(be, Items.RAW_IRON) == 64 && be.getInventory().getStackInSlot(0).getCount() == 64, "Input/output conservation and no duplicate completion on the same tick");
            h.assertTrue(be.getLastSamples() == 8 && be.getLastProcessed() == 64, "Expected eight native loot rolls for 64 inputs");
            h.assertTrue(be.getEnergyStorage().getEnergyStored() == 1_000_000 - 64 * 256, "Full energy debit");
            h.assertTrue(be.getInventory().getStackInSlot(1).getDamageValue() == 64, "Full durability debit");
            h.assertTrue(h.getBlockState(POS).is(ModBlocks.MINING_FACTORY.get()), "Virtual mining must not replace the machine");
            h.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void unevenGroupsAndSmallBatchesConserveInputs(GameTestHelper h) {
        var tool = new ItemStack(Items.DIAMOND_PICKAXE);
        var uneven = MiningLoot.roll(h.getLevel(), h.absolutePos(POS), Blocks.IRON_ORE.defaultBlockState(), tool, 67, 8);
        h.assertTrue(uneven.processed() == 67 && uneven.samples() == 8 && uneven.drops().stream().mapToLong(MiningLoot.Drop::count).sum() == 67, "Remainder must be allocated without loss");
        var small = MiningLoot.roll(h.getLevel(), h.absolutePos(POS), Blocks.IRON_ORE.defaultBlockState(), tool, 3, 8);
        h.assertTrue(small.samples() == 3 && small.drops().get(0).count() == 3, "Small batches use one roll per block");
        h.assertTrue(tool.getDamageValue() == 0, "Prospective evaluation must not mutate installed tool");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void brokenToolLeavesRemainingInput(GameTestHelper h) {
        var tool = new ItemStack(Items.DIAMOND_PICKAXE);
        tool.setDamageValue(tool.getMaxDamage() - 3);
        var be = fixture(h, tool, 64, 1_000_000);
        completeCycle(h, be, () -> {
            h.assertTrue(be.getInventory().getStackInSlot(1).isEmpty(), "Tool must break");
            h.assertTrue(be.getInventory().getStackInSlot(0).getCount() == 61 && output(be, Items.RAW_IRON) == 3, "Broken tool processed too much");
            h.assertTrue(be.getEnergyStorage().getEnergyStored() == 1_000_000 - 3 * 256, "Only completed blocks cost power");
            h.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void silkTouchUsesRealLootTables(GameTestHelper h) {
        var be = fixture(h, enchanted(h, Items.DIAMOND_PICKAXE, Enchantments.SILK_TOUCH, 1), 64, 1_000_000);
        completeCycle(h, be, () -> {
            h.assertTrue(output(be, Items.IRON_ORE) == 64 && output(be, Items.RAW_IRON) == 0, "Silk Touch was ignored");
            h.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void fortuneAndUnbreakingApply(GameTestHelper h) {
        var tool = enchanted(h, Items.DIAMOND_PICKAXE, Enchantments.BLOCK_FORTUNE, 3);
        tool.enchant(Enchantments.UNBREAKING, 3);
        long total = 0;
        int damage = 0;
        for (int i = 0; i < 32; i++) {
            var result = MiningLoot.roll(h.getLevel(), h.absolutePos(POS), Blocks.IRON_ORE.defaultBlockState(), tool, 64, 8);
            total += result.drops().stream().mapToLong(MiningLoot.Drop::count).sum();
            damage += result.tool().getDamageValue();
        }
        h.assertTrue(total > 2048 && total <= 8192, "Fortune did not increase real ore loot within legal bounds");
        h.assertTrue(damage > 0 && damage < 2048, "Unbreaking did not reduce actual durability loss");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void planePreservesEnchantmentAndCostsExtraPower(GameTestHelper h) {
        var plane = enchanted(h, AEParts.ANNIHILATION_PLANE.asItem(), Enchantments.SILK_TOUCH, 1);
        var be = fixture(h, plane, 64, 1_000_000);
        completeCycle(h, be, () -> {
            h.assertTrue(output(be, Items.IRON_ORE) == 64, "Plane enchantment lost");
            h.assertTrue(ItemStack.matches(plane, be.getInventory().getStackInSlot(1)), "Plane must remain unchanged");
            h.assertTrue(be.getEnergyStorage().getEnergyStored() == 1_000_000 - 64 * 1024, "Plane must pay the extra FE for all inputs");
            h.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void powerShortageLimitsBatch(GameTestHelper h) {
        var be = fixture(h, new ItemStack(Items.DIAMOND_PICKAXE), 64, 513);
        completeCycle(h, be, () -> {
            h.assertTrue(output(be, Items.RAW_IRON) == 2 && be.getInventory().getStackInSlot(0).getCount() == 62, "Power-limited batch consumed too much");
            h.assertTrue(be.getEnergyStorage().getEnergyStored() == 1, "Remainder FE was lost");
            h.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void fullOutputDoesNotConsumeOrRoll(GameTestHelper h) {
        var be = fixture(h, new ItemStack(Items.DIAMOND_PICKAXE), 64, 1_000_000);
        blockOutputs(be);
        completeCycle(h, be, () -> {
            h.assertTrue(be.getLastSamples() == 0 && be.getInventory().getStackInSlot(0).getCount() == 64, "Full machine consumed or rolled");
            h.assertTrue(be.getEnergyStorage().getEnergyStored() == 1_000_000 && be.getInventory().getStackInSlot(1).getDamageValue() == 0, "Full machine charged costs");
            h.assertTrue(be.getAvailableLightning() == 1000, "Full output lightning: " + be.getAvailableLightning());
            h.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void blockedResultSurvivesSaveLoadAndNeverRerolls(GameTestHelper h) {
        var be = fixture(h, new ItemStack(Items.DIAMOND_PICKAXE), 64, 1_000_000);
        blockOutputs(be);
        be.getInventory().setItemDirect(2, new ItemStack(Items.RAW_IRON, MiningFactoryInventory.CAPACITY - 1));
        completeCycle(h, be, () -> {
            h.assertTrue(be.getPendingDrops().size() == 1 && be.getPendingDrops().get(0).count() == 63, "Expected fixed overflow");
            var saved = be.saveWithFullMetadata();
            be.clearContent();
            be.load(saved);
            long expected = MiningFactoryInventory.CAPACITY + 63;
            h.assertTrue(output(be, Items.RAW_IRON) == expected, "Save/load lost pending loot");
            var drops = new ArrayList<ItemStack>();
            be.addAdditionalDrops(h.getLevel(), be.getBlockPos(), drops);
            h.assertTrue(drops.stream().filter(s -> s.is(Items.RAW_IRON)).mapToLong(ItemStack::getCount).sum() == expected, "Breaking loses pending output");
            h.runAfterDelay(2, () -> {
                h.assertTrue(be.getLastSamples() == 0 && output(be, Items.RAW_IRON) == expected, "Blocked reload rerolled loot");
                be.getInventory().extractItem(2, MiningFactoryInventory.CAPACITY, false);
                h.runAfterDelay(2, () -> {
                    h.assertTrue(be.getPendingDrops().isEmpty() && output(be, Items.RAW_IRON) == 63, "Fixed output failed to drain");
                    h.succeed();
                });
            });
        });
    }

    @GameTest(template = "empty")
    public static void invalidTierAndBlockEntitiesStayUntouched(GameTestHelper h) {
        var be = fixture(h, new ItemStack(Items.WOODEN_PICKAXE), 64, 1_000_000);
        completeCycle(h, be, () -> {
            h.assertTrue(be.getStatus() == MiningFactoryBlockEntity.Status.HARVEST && be.getInventory().getStackInSlot(0).getCount() == 64, "Insufficient tier should retain input");
            h.assertTrue(!be.getInventory().isItemValid(0, new ItemStack(Items.CHEST)), "Containers must be rejected");
            h.assertTrue(!be.getInventory().isItemValid(1, new ItemStack(Items.STICK)), "Non-tools must be rejected");
            h.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void automationOnlyExtractsOutputs(GameTestHelper h) {
        var be = fixture(h, new ItemStack(Items.DIAMOND_PICKAXE), 64, 1_000_000);
        var io = be.getAutomationInventory();
        h.assertTrue(io.extractItem(0, 64, false).isEmpty() && io.extractItem(1, 1, false).isEmpty()
                && io.extractItem(MiningFactoryInventory.MATRIX, 32, false).isEmpty(), "Automation must not steal tool/input/matrices");
        h.assertTrue(io.insertItem(2, new ItemStack(Items.STONE), false).getCount() == 1, "Output slot accepts external insert");
        completeCycle(h, be, () -> {
            h.assertTrue(io.extractItem(2, 64, false).is(Items.RAW_IRON), "Automation failed to extract output");
            h.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void noMatrixRunsOneParallel(GameTestHelper h) {
        var be = fixture(h, new ItemStack(Items.DIAMOND_PICKAXE), 512, 1_000_000);
        be.getInventory().setStackInSlot(MiningFactoryInventory.MATRIX, ItemStack.EMPTY);
        completeCycle(h, be, () -> {
            h.assertTrue(be.getInstalledParallelCapacity() == 1 && output(be, Items.RAW_IRON) == 1,
                    "Unupgraded factory must process one block");
            h.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void oneMatrixRunsEightParallel(GameTestHelper h) {
        var be = fixture(h, new ItemStack(Items.DIAMOND_PICKAXE), 512, 1_000_000);
        be.getInventory().setStackInSlot(MiningFactoryInventory.MATRIX, new ItemStack(ModItems.LIGHTNING_COLLAPSE_MATRIX.get()));
        completeCycle(h, be, () -> {
            h.assertTrue(be.getInstalledParallelCapacity() == 8 && output(be, Items.RAW_IRON) == 8, "One matrix must enable eight parallel");
            h.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void fullMatrixStackRuns256WithOnlyEightSamples(GameTestHelper h) {
        var be = fixture(h, new ItemStack(Items.DIAMOND_PICKAXE), 512, 1_000_000);
        be.getInventory().setStackInSlot(MiningFactoryInventory.MATRIX, ItemStack.EMPTY);
        ItemStack remainder = be.getAutomationInventory().insertItem(MiningFactoryInventory.MATRIX,
                new ItemStack(ModItems.LIGHTNING_COLLAPSE_MATRIX.get(), 40), false);
        h.assertTrue(remainder.getCount() == 8 && be.getInstalledMatrixCount() == 32, "Matrix slot must cap at 32");
        completeCycle(h, be, () -> {
            h.assertTrue(be.getInstalledParallelCapacity() == 256 && output(be, Items.RAW_IRON) == 256, "Full matrix stack must enable 256 parallel");
            h.assertTrue(be.getLastSamples() == 8 && be.getInventory().getStackInSlot(1).getDamageValue() == 256, "Sample budget and durability must remain independent of parallel upgrades");
            h.assertTrue(be.getInstalledMatrixCount() == 32, "Processing consumed matrices");
            h.succeed();
        });
    }

    private static ChestBlockEntity chest(GameTestHelper h, BlockPos pos) {
        h.setBlock(pos, Blocks.CHEST);
        return (ChestBlockEntity) h.getBlockEntity(pos);
    }

    private static long count(ChestBlockEntity chest, Item item) {
        long count = 0;
        for (int i = 0; i < chest.getContainerSize(); i++) if (chest.getItem(i).is(item)) count += chest.getItem(i).getCount();
        return count;
    }

    @GameTest(template = "empty")
    public static void autoExportRespectsRelativeFaceAndToggle(GameTestHelper h) {
        var be = fixture(h, new ItemStack(Items.DIAMOND_PICKAXE), 0, 0);
        var north = chest(h, POS.north());
        var south = chest(h, POS.south());
        be.getInventory().setItemDirect(2, new ItemStack(Items.RAW_IRON, 64));
        be.updateOutputSides(EnumSet.of(RelativeSide.FRONT));
        h.assertTrue(!be.pushOutResult() && count(north, Items.RAW_IRON) == 0, "Disabled export must not insert");
        be.setAutoExportEnabled(true);
        h.assertTrue(be.pushOutResult() && count(north, Items.RAW_IRON) == 64 && count(south, Items.RAW_IRON) == 0, "Only the configured front face may export");
        h.setBlock(POS, h.getBlockState(POS).setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
        be.getInventory().setItemDirect(2, new ItemStack(Items.RAW_IRON, 32));
        h.assertTrue(be.pushOutResult() && count(south, Items.RAW_IRON) == 32, "Relative front must rotate with the machine");
        h.assertTrue(be.getInstalledMatrixCount() == 8 && count(north, ModItems.LIGHTNING_COLLAPSE_MATRIX.get()) == 0, "Auto export stole matrices");
        be.updateOutputSides(EnumSet.noneOf(RelativeSide.class));
        be.getInventory().setItemDirect(2, new ItemStack(Items.RAW_IRON, 16));
        h.assertTrue(!be.pushOutResult() && output(be, Items.RAW_IRON) == 16, "Cleared sides must retain output");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void partialFullAndReplacedTargetsConserveOutput(GameTestHelper h) {
        var be = fixture(h, new ItemStack(Items.DIAMOND_PICKAXE), 0, 0);
        var target = chest(h, POS.north());
        for (int i = 0; i < target.getContainerSize(); i++) target.setItem(i, new ItemStack(Items.STONE, 64));
        target.setItem(0, new ItemStack(Items.RAW_IRON, 60));
        be.setAutoExportEnabled(true);
        be.updateOutputSides(EnumSet.of(RelativeSide.FRONT));
        be.getInventory().setItemDirect(2, new ItemStack(Items.RAW_IRON, 64));
        h.assertTrue(be.pushOutResult() && count(target, Items.RAW_IRON) == 64 && output(be, Items.RAW_IRON) == 60, "Partial insert lost its remainder");
        h.assertTrue(!be.pushOutResult() && output(be, Items.RAW_IRON) == 60, "Full target lost output");
        h.setBlock(POS.north(), Blocks.AIR);
        h.assertTrue(!be.pushOutResult() && output(be, Items.RAW_IRON) == 60, "Removed target lost output");
        var replacement = chest(h, POS.north());
        h.assertTrue(be.pushOutResult() && count(replacement, Items.RAW_IRON) == 60 && output(be, Items.RAW_IRON) == 0, "Replacement chest reused a stale target");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void exportingDrainsSavedOverflowWithoutReroll(GameTestHelper h) {
        var be = fixture(h, new ItemStack(Items.DIAMOND_PICKAXE), 64, 1_000_000);
        blockOutputs(be);
        be.getInventory().setItemDirect(2, new ItemStack(Items.RAW_IRON, MiningFactoryInventory.CAPACITY - 1));
        completeCycle(h, be, () -> {
            h.assertTrue(be.getPendingDrops().get(0).count() == 63, "Fixture needs fixed overflow");
            var target = chest(h, POS.north());
            be.setAutoExportEnabled(true);
            be.updateOutputSides(EnumSet.of(RelativeSide.FRONT));
            h.runAfterDelay(8, () -> {
                h.assertTrue(be.getLastSamples() == 0 && output(be, Items.RAW_IRON) + count(target, Items.RAW_IRON) == MiningFactoryInventory.CAPACITY + 63L,
                        "Exported overflow was rerolled, lost or duplicated");
                h.assertTrue(be.getInventory().getStackInSlot(1).getDamageValue() == 64, "Draining output damaged the tool again");
                h.succeed();
            });
        });
    }

    @GameTest(template = "empty")
    public static void oldSaveSlotIndicesArePreserved(GameTestHelper h) {
        var be = fixture(h, new ItemStack(Items.DIAMOND_PICKAXE), 37, 1234);
        be.getInventory().setStackInSlot(MiningFactoryInventory.MATRIX, ItemStack.EMPTY);
        be.getInventory().setItemDirect(10, new ItemStack(Items.DIAMOND, 123));
        var tag = be.saveWithFullMetadata();
        tag.remove("AutoExport");
        tag.remove("AllowedOutputs");
        be.getInventory().setStackInSlot(MiningFactoryInventory.MATRIX, new ItemStack(ModItems.LIGHTNING_COLLAPSE_MATRIX.get(), 8));
        be.load(tag);
        h.assertTrue(be.getInventory().getStackInSlot(0).getCount() == 37 && be.getInventory().getStackInSlot(1).is(Items.DIAMOND_PICKAXE)
                && be.getInventory().getStackInSlot(10).is(Items.DIAMOND) && be.getInventory().getStackInSlot(10).getCount() == 123,
                "Adding the matrix slot moved old contents");
        h.assertTrue(be.getInstalledMatrixCount() == 0 && be.getInstalledParallelCapacity() == 1 && !be.isAutoExportEnabled(), "Legacy save defaults changed");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void settingsPersistAndMemoryCardsUseRealMatrices(GameTestHelper h) {
        var be = fixture(h, new ItemStack(Items.DIAMOND_PICKAXE), 0, 0);
        be.setAutoExportEnabled(true);
        be.updateOutputSides(EnumSet.of(RelativeSide.FRONT, RelativeSide.TOP));
        var saved = be.saveWithFullMetadata();
        be.setAutoExportEnabled(false);
        be.updateOutputSides(EnumSet.noneOf(RelativeSide.class));
        be.load(saved);
        h.assertTrue(be.isAutoExportEnabled() && be.getAllowedOutputs().equals(EnumSet.of(RelativeSide.FRONT, RelativeSide.TOP)), "Export settings did not survive save/load");
        var template = new net.minecraft.nbt.CompoundTag();
        be.exportSettings(SettingsFrom.MEMORY_CARD, template, null);
        var player = h.makeMockPlayer();
        player.getInventory().setItem(0, new ItemStack(ModItems.LIGHTNING_COLLAPSE_MATRIX.get(), 3));
        be.getInventory().setStackInSlot(MiningFactoryInventory.MATRIX, ItemStack.EMPTY);
        be.setAutoExportEnabled(false);
        be.updateOutputSides(EnumSet.noneOf(RelativeSide.class));
        be.importSettings(SettingsFrom.MEMORY_CARD, template, player);
        h.assertTrue(be.getInstalledMatrixCount() == 3 && player.getInventory().countItem(ModItems.LIGHTNING_COLLAPSE_MATRIX.get()) == 0,
                "Memory card created matrices instead of consuming supplied items");
        h.assertTrue(be.isAutoExportEnabled() && be.getAllowedOutputs().contains(RelativeSide.TOP), "Memory card omitted export settings");
        h.assertTrue(be.restoreMatricesFromMemoryCard(player, 1) == 0 && be.getInstalledMatrixCount() == 1
                && player.getInventory().countItem(ModItems.LIGHTNING_COLLAPSE_MATRIX.get()) == 2, "Reducing matrix template lost excess items");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void sneakUseInstallsMatricesFromHand(GameTestHelper h) {
        var be = fixture(h, new ItemStack(Items.DIAMOND_PICKAXE), 0, 0);
        be.getInventory().setStackInSlot(MiningFactoryInventory.MATRIX, ItemStack.EMPTY);
        var player = h.makeMockPlayer();
        player.setShiftKeyDown(true);
        var hand = net.minecraft.world.InteractionHand.MAIN_HAND;
        player.setItemInHand(hand, new ItemStack(ModItems.LIGHTNING_COLLAPSE_MATRIX.get(), 40));
        var context = new net.minecraft.world.item.context.UseOnContext(player, hand,
                new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(be.getBlockPos()), Direction.UP, be.getBlockPos(), false));
        var result = ModItems.LIGHTNING_COLLAPSE_MATRIX.get().onItemUseFirst(player.getItemInHand(hand), context);
        h.assertTrue(result.consumesAction() && be.getInstalledMatrixCount() == 32 && player.getItemInHand(hand).getCount() == 8,
                "Shared matrix item shortcut did not honor capacity or hand count");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void cyclesTakeFiveTicksAndChargeOnlyAtCompletion(GameTestHelper h) {
        var be = fixture(h, new ItemStack(Items.DIAMOND_PICKAXE), 128, 1_000_000);
        be.processTick();
        for (int elapsed = 0; elapsed < 10; elapsed++) {
            final int tick = elapsed;
            Runnable check = () -> {
                be.processTick();
                be.processTick();
                int batches = (tick + 1) / 5;
                h.assertTrue(output(be, Items.RAW_IRON) == batches * 64L, "Batch finished before its fifth tick: " + tick);
                h.assertTrue(be.getInventory().getStackInSlot(0).getCount() == 128 - batches * 64, "Early input debit");
                h.assertTrue(be.getInventory().getStackInSlot(1).getDamageValue() == batches * 64, "Early durability debit");
                h.assertTrue(be.getEnergyStorage().getEnergyStored() == 1_000_000 - batches * 64 * 256, "Early FE debit");
                h.assertTrue(be.getProgressTicks() == (tick + 1) % 5, "Progress did not follow real ticks");
                if (tick == 9) h.succeed();
            };
            if (elapsed == 0) check.run(); else h.runAfterDelay(elapsed, check);
        }
    }

    @GameTest(template = "empty")
    public static void unfinishedProgressPersistsWithoutChargingOrSkippingTicks(GameTestHelper h) {
        var be = fixture(h, new ItemStack(Items.DIAMOND_PICKAXE), 64, 1_000_000);
        be.processTick();
        h.runAfterDelay(2, () -> {
            be.processTick();
            h.assertTrue(be.getProgressTicks() == 3 && output(be, Items.RAW_IRON) == 0, "Expected unfinished cycle");
            var saved = be.saveWithFullMetadata();
            be.clearContent();
            be.load(saved);
            h.assertTrue(be.getProgressTicks() == 3 && be.getInventory().getStackInSlot(0).getCount() == 64, "Reload lost progress or input");
            be.processTick();
            h.assertTrue(output(be, Items.RAW_IRON) == 0, "Reload processed twice on the same tick");
        });
        h.runAfterDelay(3, () -> {
            be.processTick();
            h.assertTrue(be.getProgressTicks() == 4 && output(be, Items.RAW_IRON) == 0, "Reload shortened the cycle");
        });
        h.runAfterDelay(4, () -> {
            be.processTick();
            h.assertTrue(output(be, Items.RAW_IRON) == 64 && be.getInventory().getStackInSlot(1).getDamageValue() == 64, "Resumed cycle failed to commit once");
            h.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void inputRefillAndOutputExtractionDoNotResetProgress(GameTestHelper h) {
        var be = fixture(h, new ItemStack(Items.DIAMOND_PICKAXE), 32, 1_000_000);
        be.getInventory().setItemDirect(2, new ItemStack(Items.RAW_IRON, 8));
        be.processTick();
        h.runAfterDelay(2, () -> {
            be.processTick();
            be.getInventory().insertItem(0, new ItemStack(Items.IRON_ORE, 32), false);
            be.getAutomationInventory().extractItem(2, 8, false);
            h.assertTrue(be.getProgressTicks() == 3, "Refill or output extraction stalled the cycle");
        });
        h.runAfterDelay(4, () -> {
            be.processTick();
            h.assertTrue(output(be, Items.RAW_IRON) == 64, "Continuous input never completed");
            h.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void changedInputToolAndMatricesRestartProgress(GameTestHelper h) {
        var be = fixture(h, new ItemStack(Items.DIAMOND_PICKAXE), 64, 1_000_000);
        be.processTick();
        be.getInventory().setStackInSlot(0, new ItemStack(Items.DIAMOND_ORE, 64));
        h.assertTrue(be.getProgressTicks() == 0, "Changing block type kept previous progress");
        h.runAfterDelay(1, () -> {
            be.processTick();
            be.getInventory().setStackInSlot(1, enchanted(h, Items.DIAMOND_PICKAXE, Enchantments.SILK_TOUCH, 1));
            h.assertTrue(be.getProgressTicks() == 0, "Changing tool kept previous progress");
        });
        h.runAfterDelay(2, () -> {
            be.processTick();
            be.getInventory().setStackInSlot(MiningFactoryInventory.MATRIX, new ItemStack(ModItems.LIGHTNING_COLLAPSE_MATRIX.get()));
            h.assertTrue(be.getProgressTicks() == 0, "Changing parallel capacity kept previous progress");
        });
        h.runAfterDelay(6, () -> {
            be.processTick();
            h.assertTrue(output(be, Items.DIAMOND_ORE) == 0 && be.getProgressTicks() == 4, "Replacement batch finished early");
        });
        h.runAfterDelay(7, () -> {
            be.processTick();
            h.assertTrue(output(be, Items.DIAMOND_ORE) == 8 && be.getInventory().getStackInSlot(0).getCount() == 56, "Changed batch did not use its current tool and parallel limit");
            h.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void blockedEnergyResetsUnpaidProgress(GameTestHelper h) {
        var be = fixture(h, new ItemStack(Items.DIAMOND_PICKAXE), 64, 1_000_000);
        be.processTick();
        be.getEnergyStorage().extractInternal(1_000_000, false);
        h.runAfterDelay(1, () -> {
            be.processTick();
            h.assertTrue(be.getProgressTicks() == 0 && be.getStatus() == MiningFactoryBlockEntity.Status.ENERGY, "No power must stop progress");
            be.getEnergyStorage().receiveEnergy(1_000_000, false);
        });
        h.runAfterDelay(5, () -> {
            be.processTick();
            h.assertTrue(output(be, Items.RAW_IRON) == 0 && be.getProgressTicks() == 4, "Power recovery completed too early");
        });
        h.runAfterDelay(6, () -> {
            be.processTick();
            h.assertTrue(output(be, Items.RAW_IRON) == 64, "Power recovery did not complete a new five-tick cycle");
            h.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void largeInputRetainsEveryUnprocessedBlockAcrossFiveTickBatches(GameTestHelper h) {
        var be = fixture(h, new ItemStack(Items.DIAMOND_PICKAXE), 4096, 1_000_000);
        be.processTick();
        h.runAfterDelay(4, () -> {
            be.processTick();
            h.assertTrue(be.getInventory().getStackInSlot(0).getCount() == 4032,
                    "4096 input must retain 4032 after the first 64-block batch");
            h.assertTrue(output(be, Items.RAW_IRON) == 64 && be.getLastProcessed() == 64,
                    "First five-tick batch output was not exactly 64");
        });
        h.runAfterDelay(9, () -> {
            be.processTick();
            h.assertTrue(be.getInventory().getStackInSlot(0).getCount() == 3968 && output(be, Items.RAW_IRON) == 128,
                    "Two batches must conserve all 4096 input blocks");
            h.assertTrue(be.getInventory().getStackInSlot(1).getDamageValue() == 128
                    && be.getEnergyStorage().getEnergyStored() == 1_000_000 - 128 * 256,
                    "Costs must only cover the 128 processed blocks");
            h.succeed();
        });
    }
}
