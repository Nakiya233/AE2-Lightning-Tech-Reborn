package com.moakiee.ae2lt.debug;

import appeng.menu.MenuOpener;
import appeng.menu.SlotSemantics;
import appeng.menu.locator.MenuLocators;
import com.moakiee.ae2lt.blockentity.MiningFactoryBlockEntity;
import com.moakiee.ae2lt.client.MiningFactoryScreen;
import com.moakiee.ae2lt.menu.MiningFactoryMenu;
import com.moakiee.ae2lt.registry.ModBlocks;
import com.moakiee.ae2lt.registry.ModItems;
import com.moakiee.ae2lt.machine.miningfactory.MiningFactoryInventory;
import com.moakiee.ae2lt.client.MachineOutputConfigScreen;
import com.moakiee.ae2lt.client.OverloadProcessingFactoryScreen;
import com.moakiee.ae2lt.client.OverloadProcessingFactoryOutputConfigScreen;
import com.moakiee.ae2lt.menu.OverloadProcessingFactoryMenu;
import com.moakiee.ae2lt.blockentity.OverloadProcessingFactoryBlockEntity;
import appeng.api.orientation.RelativeSide;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import java.nio.file.Files;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.TickEvent;

/**
 * Disposable-world probe using real menu opening, synchronization, and shift-click packets.
 * This replaces inventories between scenarios and must never be used for manual playtesting.
 */
@EventBusSubscriber(modid = "ae2lt", value = Dist.CLIENT)
public final class MiningFactoryClientProbe {
    private static final BlockPos POS = new BlockPos(0, 100, 0);
    private static int phase;
    private static int ticks;
    private static boolean finished;
    private static boolean progressCaptured;
    private static volatile Throwable serverFailure;

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!Boolean.getBoolean("ae2lt.miningClientProbe") || finished) return;
        var mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        if (mc.player == null || mc.getSingleplayerServer() == null) return;
        if (!progressCaptured && mc.screen instanceof MiningFactoryScreen screen
                && screen.getMenu().progressTicks > 0) {
            require(screen.getMenu().getProgress() > 0 && screen.getMenu().getProgress() < 1,
                    "Five-tick progress synchronization failed");
            capture("mining-factory-progress.png");
            progressCaptured = true;
        }
        if (++ticks % 40 != 0) return;
        if (ticks == 40) mc.getWindow().setTitle("AE2LT 自动回归测试（脚本会修改库存，请勿人工操作）");
        try {
            if (serverFailure != null) throw new AssertionError("Server fixture failed", serverFailure);
            switch (phase) {
                case 0 -> server(() -> {
                    var player = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
                    var level = player.serverLevel();
                    level.setDayTime(6000);
                    level.setWeatherParameters(0, 6000, false, false);
                    for (int x = -4; x <= 4; x++) for (int z = -3; z <= 5; z++) {
                        level.setBlockAndUpdate(new BlockPos(x, 99, z), Blocks.SMOOTH_STONE.defaultBlockState());
                        for (int y = 100; y <= 104; y++) level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                    }
                    level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                            new net.minecraft.world.phys.AABB(POS).inflate(16)).forEach(net.minecraft.world.entity.Entity::discard);
                    level.setBlockAndUpdate(POS, ModBlocks.MINING_FACTORY.get().defaultBlockState()
                            .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING,
                                    net.minecraft.core.Direction.SOUTH));
                    var host = (MiningFactoryBlockEntity) level.getBlockEntity(POS);
                    level.setBlockAndUpdate(POS.below(), appeng.core.definitions.AEBlocks.CREATIVE_ENERGY_CELL.block().defaultBlockState());
                    level.setBlockAndUpdate(POS.west(), appeng.core.definitions.AEBlocks.DRIVE.block().defaultBlockState());
                    var cell = new ItemStack(ModItems.LIGHTNING_STORAGE_COMPONENT_II.get());
                    var lightning = appeng.api.storage.StorageCells.getCellInventory(cell, null);
                    lightning.insert(com.moakiee.ae2lt.me.key.LightningKey.HIGH_VOLTAGE, 1000,
                            appeng.api.config.Actionable.MODULATE, appeng.api.networking.security.IActionSource.ofMachine(host));
                    lightning.persist();
                    ((appeng.blockentity.storage.DriveBlockEntity) level.getBlockEntity(POS.west()))
                            .getInternalInventory().setItemDirect(0, cell);
                    host.getEnergyStorage().receiveEnergy(64 * 256, false);
                    player.getInventory().clearContent();
                    player.getInventory().setItem(0, new ItemStack(Items.DIAMOND_PICKAXE));
                    player.getInventory().setItem(1, new ItemStack(Items.IRON_ORE, 64));
                    player.getInventory().setItem(2, new ItemStack(ModItems.LIGHTNING_COLLAPSE_MATRIX.get(), 8));
                    player.setGameMode(GameType.SURVIVAL);
                    player.teleportTo(level, 0.5, 100, 3.5, java.util.Set.of(), 180, 15);
                });
                case 1 -> {
                    if (!(mc.level.getBlockEntity(POS) instanceof MiningFactoryBlockEntity)) {
                        require(ticks < 1200, "Timed out waiting for client chunk");
                        return;
                    }
                    capture("mining-factory-block.png");
                    server(() -> {
                        var player = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
                        var host = (MiningFactoryBlockEntity) player.serverLevel().getBlockEntity(POS);
                        MenuOpener.open(MiningFactoryMenu.TYPE, player, MenuLocators.forBlockEntity(host));
                    });
                }
                case 2 -> {
                    var menu = menu();
                    var sprite = mc.getTextureAtlas(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS)
                            .apply(com.moakiee.ae2lt.menu.Ae2ltSlotBackgrounds.MINING_BLOCK);
                    require(sprite.contents().name().equals(com.moakiee.ae2lt.menu.Ae2ltSlotBackgrounds.MINING_BLOCK),
                            "Block slot background is missing from the atlas");
                    capture("mining-factory-empty.png");
                    for (int i : new int[] {2, 0, 1}) {
                        var slot = menu.getSlots(SlotSemantics.PLAYER_HOTBAR).get(i);
                        mc.gameMode.handleInventoryMouseClick(menu.containerId, slot.index, 0, ClickType.QUICK_MOVE, mc.player);
                    }
                }
                case 3 -> {
                    var menu = menu();
                    require(progressCaptured, "No in-flight processing progress reached the client");
                    require(menu.lightning == 999, "Batch lightning balance failed to synchronize");
                    require(menu.slots.get(0).getItem().isEmpty(), "Input shift-click/processing failed");
                    require(menu.slots.get(1).getItem().is(Items.DIAMOND_PICKAXE)
                            && menu.slots.get(1).getItem().getDamageValue() == 64, "Tool shift-click/durability sync failed");
                    require(menu.slots.get(2).getItem().is(Items.RAW_IRON)
                            && menu.slots.get(2).getItem().getCount() == 64, "Output synchronization failed");
                    require(menu.energy == 0, "FE synchronization failed");
                    require(menu.parallelCapacity == 64 && menu.slots.get(MiningFactoryInventory.MATRIX).getItem().getCount() == 8,
                            "Matrix shift-click or parallel capacity sync failed");
                    capture("mining-factory-completed.png");
                    mc.gameMode.handleInventoryMouseClick(menu.containerId, 2, 0, ClickType.QUICK_MOVE, mc.player);
                    server(() -> {
                        var host = (MiningFactoryBlockEntity) mc.getSingleplayerServer().overworld().getBlockEntity(POS);
                        host.getInventory().setStackInSlot(0, new ItemStack(Items.IRON_ORE, 4096));
                    });
                }
                case 4 -> {
                    require(((com.moakiee.ae2lt.menu.LargeStackAppEngSlot) menu().slots.get(0)).getDisplayedAmount() == 4096, "Large input count synchronization failed");
                    require(menu().slots.get(2).getItem().isEmpty(), "Output shift-click failed");
                    require(mc.player.getInventory().countItem(Items.RAW_IRON) == 64, "Output transfer lost or duplicated items");
                    require(mc.player.getInventory().countItem(Items.IRON_ORE) == 0, "Input leaked to the player");
                    require(menu().status == MiningFactoryBlockEntity.Status.ENERGY.ordinal(), "Machine status synchronization failed");
                    capture("mining-factory-large-stack.png");
                    server(() -> {
                        var level = mc.getSingleplayerServer().overworld();
                        var host = (MiningFactoryBlockEntity) level.getBlockEntity(POS);
                        host.getInventory().setStackInSlot(0, ItemStack.EMPTY);
                        host.getEnergyStorage().receiveEnergy(1_000_000, false);
                        host.getInventory().setItemDirect(2, new ItemStack(Items.RAW_IRON, 64));
                        level.setBlockAndUpdate(POS.south(), Blocks.CHEST.defaultBlockState());
                    });
                }
                case 5 -> {
                    require(!menu().isAutoExportEnabled() && menu().slots.get(2).getItem().getCount() == 64,
                            "Auto export must initially be disabled");
                    menu().clientToggleAutoExport();
                }
                case 6 -> {
                    require(menu().isAutoExportEnabled() && menu().slots.get(2).getItem().getCount() == 64,
                            "Enabling export without selected sides must retain output");
                    var parent = (MiningFactoryScreen) mc.screen;
                    openOutputConfiguration(parent);
                }
                case 7 -> {
                    require(mc.screen instanceof MachineOutputConfigScreen, "Shared output configuration screen failed to open");
                    ((MiningFactoryMenu) mc.player.containerMenu).clientToggleOutputSide(RelativeSide.FRONT);
                }
                case 8 -> {
                    var menu = (MiningFactoryMenu) mc.player.containerMenu;
                    require(menu.isOutputSideEnabled(RelativeSide.FRONT) && menu.slots.get(2).getItem().isEmpty(),
                            "Side selection packet did not export items");
                    capture("mining-factory-output-config.png");
                    ((MachineOutputConfigScreen<?, ?>) mc.screen).onClose();
                    server(() -> {
                        var level = mc.getSingleplayerServer().overworld();
                        var target = (ChestBlockEntity) level.getBlockEntity(POS.south());
                        int count = 0;
                        for (int i = 0; i < target.getContainerSize(); i++) if (target.getItem(i).is(Items.RAW_IRON)) count += target.getItem(i).getCount();
                        require(count == 64, "Configured export lost or duplicated items");
                        require(((MiningFactoryBlockEntity) level.getBlockEntity(POS)).getInstalledMatrixCount() == 8,
                                "Auto export removed installed matrices");
                    });
                }
                case 9 -> {
                    require(menu().isAutoExportEnabled() && menu().isOutputSideEnabled(RelativeSide.FRONT),
                            "Returning to the factory lost output settings");
                    capture("mining-factory-auto-export.png");
                    menu().clientClearOutputSides();
                }
                case 10 -> {
                    require(menu().outputSideMask == 0, "Clear output sides packet failed");
                    server(() -> mc.getSingleplayerServer().overworld().setBlockAndUpdate(POS.east(3),
                            ModBlocks.OVERLOAD_PROCESSING_FACTORY.get().defaultBlockState()));
                }
                case 11 -> {
                    if (!(mc.level.getBlockEntity(POS.east(3)) instanceof OverloadProcessingFactoryBlockEntity)) {
                        require(ticks < 1200, "Overload factory client chunk missing");
                        return;
                    }
                    server(() -> {
                        var player = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
                        var host = (OverloadProcessingFactoryBlockEntity) player.serverLevel().getBlockEntity(POS.east(3));
                        MenuOpener.open(OverloadProcessingFactoryMenu.TYPE, player, MenuLocators.forBlockEntity(host));
                    });
                }
                case 12 -> {
                    require(mc.screen instanceof OverloadProcessingFactoryScreen, "Original overload factory screen failed");
                    var parent = (OverloadProcessingFactoryScreen) mc.screen;
                    openOutputConfiguration(parent);
                }
                case 13 -> {
                    require(mc.screen instanceof OverloadProcessingFactoryOutputConfigScreen,
                            "Original factory shared output screen failed: " + mc.screen);
                    ((OverloadProcessingFactoryMenu) mc.player.containerMenu).clientToggleOutputSide(RelativeSide.TOP);
                }
                case 14 -> {
                    require(((OverloadProcessingFactoryMenu) mc.player.containerMenu).isOutputSideEnabled(RelativeSide.TOP),
                            "Original factory output-side packet regressed");
                    capture("overload-factory-shared-output-config.png");
                    ((OverloadProcessingFactoryOutputConfigScreen) mc.screen).onClose();
                    report("PASS: five-tick progress sync and compact factory UI; matrix shift-click and parallel sync; tool/input/output transfers; 4096-count sync; auto-export toggle, side selection, clear, actual chest insertion and return to parent; original Overload Processing Factory shared output screen and packets verified.");
                }
                case 15 -> { finished = true; mc.stop(); }
                default -> throw new AssertionError("Unexpected phase");
            }
            phase++;
        } catch (Throwable error) {
            capture("mining-factory-failure-phase-" + phase + ".png");
            report("FAIL phase=" + phase + ": " + error);
            error.printStackTrace();
            finished = true;
            mc.stop();
        }
    }
    private static MiningFactoryMenu menu() {
        var screen = Minecraft.getInstance().screen;
        require(screen instanceof MiningFactoryScreen, "Expected mining factory screen, got " + screen);
        return ((MiningFactoryScreen) screen).getMenu();
    }
    private static void server(Runnable action) {
        Minecraft.getInstance().getSingleplayerServer().execute(() -> {
            try { action.run(); } catch (Throwable error) { serverFailure = error; }
        });
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void openOutputConfiguration(Object screen) throws ReflectiveOperationException {
        var field = screen.getClass().getDeclaredField("configureOutputButton");
        field.setAccessible(true);
        ((net.minecraft.client.gui.components.Button) field.get(screen)).onPress();
    }

    private static void capture(String name) {
        var mc = Minecraft.getInstance();
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), text -> {});
    }
    private static void report(String text) {
        System.out.println("MINING_CLIENT_PROBE " + text);
        try { Files.writeString(Minecraft.getInstance().gameDirectory.toPath().resolve("mining-client-probe.txt"), text + "\n"); }
        catch (Exception error) { error.printStackTrace(); }
    }
}
