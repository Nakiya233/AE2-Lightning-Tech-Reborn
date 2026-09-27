package com.moakiee.ae2lt.debug;

import java.util.*;
import java.util.function.Consumer;
import appeng.api.config.*;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.*;
import appeng.api.storage.*;
import appeng.api.storage.cells.*;
import appeng.core.definitions.*;
import appeng.util.SettingsFrom;
import com.moakiee.ae2lt.blockentity.OverloadedIOPortBlockEntity;
import com.moakiee.ae2lt.logic.OverloadedIOTransfer;
import com.moakiee.ae2lt.me.key.LightningKey;
import com.moakiee.ae2lt.registry.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.gametest.*;

@GameTestHolder("ae2lt")
@PrefixGameTestTemplate(false)
public final class OverloadedIOPortGameTests {
    private static final BlockPos POS = new BlockPos(2,2,2);
    private static final Map<Integer, Store> CELLS = new HashMap<>();
    private static boolean handlerRegistered;
    private static final AEKey STONE = AEItemKey.of(Items.STONE);
    private static final IActionSource SOURCE = IActionSource.empty();

    static final class Store implements StorageCell {
        final Map<AEKey,Long> amounts = new LinkedHashMap<>();
        long capacity = Long.MAX_VALUE;
        boolean insert = true, extract = true, rejectActual, rejectRefund;
        AEKey only;
        int probes, extractCalls, insertCalls, enumerations, persists;
        @Override public long insert(AEKey key,long n,Actionable mode,IActionSource source) {
            insertCalls++;
            if (mode == Actionable.SIMULATE) probes++;
            if (!insert || (only != null && !only.equals(key)) || (mode==Actionable.MODULATE && rejectActual)) return 0;
            long accepted=Math.min(n,capacity-amounts.getOrDefault(key,0L));
            if (mode==Actionable.MODULATE && accepted>0) amounts.merge(key,accepted,Math::addExact);
            return accepted;
        }
        @Override public long extract(AEKey key,long n,Actionable mode,IActionSource source) {
            extractCalls++;
            if (!extract) return 0;
            long taken=Math.min(n,amounts.getOrDefault(key,0L));
            if (mode==Actionable.MODULATE && taken>0) {
                amounts.compute(key,(k,v)->v==taken?null:v-taken);
                if(rejectRefund) insert=false;
            }
            return taken;
        }
        @Override public void getAvailableStacks(KeyCounter counter) {
            enumerations++; amounts.forEach(counter::add);
        }
        @Override public Component getDescription() { return Component.literal("IO test storage"); }
        @Override public CellState getStatus() { return amounts.isEmpty()?CellState.EMPTY:amounts.values().stream().anyMatch(n->n>=capacity)?CellState.FULL:CellState.NOT_EMPTY; }
        @Override public double getIdleDrain() { return 0; }
        @Override public void persist() { persists++; }
    }
    private static ItemStack cell(Store store) {
        if (!handlerRegistered) {
            StorageCells.addCellHandler(new ICellHandler() {
                @Override public boolean isCell(ItemStack stack) {
                    return stack.is(Items.PAPER) && stack.hasTag() && stack.getTag().contains("ioTestCell");
                }
                @Override public StorageCell getCellInventory(ItemStack stack,ISaveProvider save) {
                    return isCell(stack) ? CELLS.get(stack.getTag().getInt("ioTestCell")) : null;
                }
            }); handlerRegistered=true;
        }
        int id=CELLS.size()+1;CELLS.put(id,store);
        var stack=new ItemStack(Items.PAPER);var tag=new CompoundTag();tag.putInt("ioTestCell",id);
        stack.setTag(tag);return stack;
    }
    private static void fixture(GameTestHelper h,Consumer<OverloadedIOPortBlockEntity> run) {
        h.setBlock(POS,ModBlocks.OVERLOADED_IO_PORT.get());
        h.setBlock(POS.below(),AEBlocks.CREATIVE_ENERGY_CELL.block());
        OverloadedIOPortBlockEntity be = (OverloadedIOPortBlockEntity) h.getBlockEntity(POS);
        h.startSequence().thenWaitUntil(()->h.assertTrue(be.getMainNode().isActive(),"powered and channelled AE2 node"))
                .thenExecute(()->run.accept(be));
    }
    private static void mount(OverloadedIOPortBlockEntity be,MEStorage store) {
        be.getMainNode().getGrid().getStorageService().addGlobalStorageProvider(m->m.mount(store,0));
    }
    private static void tick(OverloadedIOPortBlockEntity be) { be.tickingRequest(be.getMainNode().getNode(),1); }
    private static void setMode(OverloadedIOPortBlockEntity be,OperationMode mode,FullnessMode fullness) {
        be.getConfigManager().putSetting(Settings.OPERATION_MODE,mode);
        be.getConfigManager().putSetting(Settings.FULLNESS_MODE,fullness);
    }
    private static long stored(OverloadedIOPortBlockEntity be,AEKey key) {
        return be.getMainNode().getGrid().getStorageService().getInventory().extract(key,Long.MAX_VALUE,Actionable.SIMULATE,SOURCE);
    }

    @GameTest(template="pigmee_station_empty")
    public static void trillionItemsOneBatch(GameTestHelper h) {
        var from=new Store();var to=new Store();from.amounts.put(STONE,1_000_000_000_000L);
        int[] payments={0};var result=OverloadedIOTransfer.move(from,to,STONE,SOURCE,()->{payments[0]++;return true;});
        h.assertTrue(result.inserted()==1_000_000_000_000L && result.remainder()==0,"no item-count throttle");
        h.assertTrue(from.amounts.isEmpty()&&to.amounts.get(STONE)==result.inserted(),"exact conservation");
        h.assertTrue(from.extractCalls==2&&to.insertCalls==2&&payments[0]==1,"constant calls and one power payment");h.succeed();
    }
    @GameTest(template="pigmee_station_empty")
    public static void nativeLongLimitDoesNotOverflow(GameTestHelper h) {
        var from=new Store();var to=new Store();from.amounts.put(STONE,Long.MAX_VALUE);
        var r=OverloadedIOTransfer.move(from,to,STONE,SOURCE,()->true);
        h.assertTrue(r.inserted()==Long.MAX_VALUE&&from.amounts.isEmpty()&&to.amounts.get(STONE)==Long.MAX_VALUE,"exact native long batch");h.succeed();
    }
    @GameTest(template="pigmee_station_empty")
    public static void partialCapacityAndRollback(GameTestHelper h) {
        var from=new Store();var to=new Store();from.amounts.put(STONE,1000L);to.capacity=37;
        var r=OverloadedIOTransfer.move(from,to,STONE,SOURCE,()->true);
        h.assertTrue(r.inserted()==37&&from.amounts.get(STONE)==963&&to.amounts.get(STONE)==37,"capacity bound before extraction");
        to.amounts.clear();to.rejectActual=true;r=OverloadedIOTransfer.move(from,to,STONE,SOURCE,()->true);
        h.assertTrue(r.inserted()==0&&r.remainder()==0&&from.amounts.get(STONE)==963,"sim/actual mismatch refunded");
        from.rejectRefund=true;r=OverloadedIOTransfer.move(from,to,STONE,SOURCE,()->true);
        h.assertTrue(r.remainder()==37&&from.amounts.get(STONE)==926,"unrefundable remainder remains owned");h.succeed();
    }
    @GameTest(template="pigmee_station_empty")
    public static void deniedExtractionAndInsufficientPowerDoNotMove(GameTestHelper h) {
        var from=new Store();var to=new Store();from.amounts.put(STONE,999L);from.extract=false;
        var r=OverloadedIOTransfer.move(from,to,STONE,SOURCE,()->{throw new AssertionError("paid for denied extraction");});
        h.assertTrue(r.inserted()==0&&to.amounts.isEmpty(),"extraction permissions honored");
        from.extract=true;r=OverloadedIOTransfer.move(from,to,STONE,SOURCE,()->false);
        h.assertTrue(from.amounts.get(STONE)==999&&to.amounts.isEmpty(),"no power means no mutation");h.succeed();
    }
    @GameTest(template="pigmee_station_empty",timeoutTicks=160)
    public static void actualItemCellAndDriveTransferAndPersist(GameTestHelper h) {
        h.setBlock(POS.west(),AEBlocks.DRIVE.block());
        var drive=(appeng.blockentity.storage.DriveBlockEntity)h.getBlockEntity(POS.west());
        drive.getInternalInventory().setItemDirect(0,AEItems.ITEM_CELL_256K.stack());
        fixture(h,be->{
            var stack=AEItems.ITEM_CELL_256K.stack();var cell=StorageCells.getCellInventory(stack,null);
            h.assertTrue(cell.insert(STONE,1_000_000,Actionable.MODULATE,SOURCE)==1_000_000,"seed real cell");cell.persist();
            be.getInternalInventory().setItemDirect(0,stack);tick(be);
            h.assertTrue(stored(be,STONE)==1_000_000,"million items sent to physical drive in one tick");
            h.assertTrue(be.getInternalInventory().getStackInSlot(0).isEmpty(),"empty disk moved to output");
            var output=be.getInternalInventory().getStackInSlot(6);
            h.assertTrue(StorageCells.getCellInventory(output,null).getStatus()==CellState.EMPTY,"output NBT persisted");
            h.assertTrue(be.getLastBatches()==1,"one resource batch");h.succeed();
        });
    }
    @GameTest(template="pigmee_station_empty",timeoutTicks=160)
    public static void fluidAndLightningUseSameBulkPath(GameTestHelper h) {
        fixture(h,be->{
            var target=new Store();mount(be,target);
            var fluidStack=AEItems.FLUID_CELL_256K.stack();var fluid=StorageCells.getCellInventory(fluidStack,null);
            long water=fluid.insert(AEFluidKey.of(Fluids.WATER),10_000_000,Actionable.MODULATE,SOURCE);fluid.persist();
            var lightningStack=new ItemStack(ModItems.LIGHTNING_STORAGE_COMPONENT_II.get());
            var lightning=StorageCells.getCellInventory(lightningStack,null);
            long amount=lightning.insert(LightningKey.HIGH_VOLTAGE,5000,Actionable.MODULATE,SOURCE);lightning.persist();
            h.assertTrue(water>65536&&amount>0,"seed real fluid/lightning cells");
            be.getInternalInventory().setItemDirect(0,fluidStack);be.getInternalInventory().setItemDirect(1,lightningStack);tick(be);
            h.assertTrue(target.amounts.get(AEFluidKey.of(Fluids.WATER))==water,"fluid batch uses native mB exactly");
            h.assertTrue(!target.amounts.containsKey(LightningKey.HIGH_VOLTAGE),"second type waits for its interval");
            h.runAfterDelay(5,()->{tick(be);h.assertTrue(target.amounts.get(LightningKey.HIGH_VOLTAGE)==amount,"lightning key transferred without conversion");h.succeed();});
        });
    }
    @GameTest(template="pigmee_station_empty",timeoutTicks=160)
    public static void sixCellsShareBudgetAndRotate(GameTestHelper h) {
        fixture(h,be->{
            var target=new Store();mount(be,target);
            for(int i=0;i<6;i++){var from=new Store();from.amounts.put(STONE,1_000_000L);be.getInternalInventory().setItemDirect(i,cell(from));}
            tick(be);h.assertTrue(target.amounts.get(STONE)==1_000_000,"six cells share one batch per five ticks");
            tick(be);h.assertTrue(target.amounts.get(STONE)==1_000_000,"same tick cannot spend budget twice");
            h.runAfterDelay(4,()->{be.updateRedstoneState();tick(be);h.assertTrue(target.amounts.get(STONE)==1_000_000,"alerts cannot shorten default five-tick gap");});
            h.runAfterDelay(5,()->{tick(be);h.assertTrue(target.amounts.get(STONE)==2_000_000,"next cell starts exactly after five ticks");});
            h.runAfterDelay(25,()->{tick(be);h.assertTrue(target.amounts.get(STONE)==6_000_000,"all six cells served in rotation");h.succeed();});
        });
    }
    @GameTest(template="pigmee_station_empty",timeoutTicks=160)
    public static void accelerationShortensIntervalWithoutRaisingBatchLimit(GameTestHelper h) {
        fixture(h,be->{
            var target=new Store();mount(be,target);var from=new Store();
            BuiltInRegistries.ITEM.stream().filter(i->i!=Items.AIR).limit(200).forEach(i->from.amounts.put(AEItemKey.of(i),100L));
            be.getInternalInventory().setItemDirect(0,cell(from));
            h.assertTrue(be.getTransferInterval()==5,"default interval");
            for(int i=0;i<4;i++){
                h.assertTrue(be.getUpgrades().insertItem(i,AEItems.SPEED_CARD.stack(),false).isEmpty(),"native speed upgrade accepted");
                h.assertTrue(be.getTransferInterval()==4-i,"each card subtracts one tick");
            }
            h.assertTrue(!be.getUpgrades().insertItem(4,AEItems.SPEED_CARD.stack(),false).isEmpty(),"fifth acceleration card rejected");
            h.assertTrue(be.getUpgrades().insertItem(4,AEItems.REDSTONE_CARD.stack(),false).isEmpty(),"redstone card fits alongside maximum speed");
            h.assertTrue(be.getTransferInterval()==1,"four speed cards plus redstone retain maximum rate");tick(be);
            h.assertTrue(target.amounts.size()==1&&from.amounts.size()==199,"at most one type even at maximum speed");
            tick(be);h.assertTrue(target.amounts.size()==1,"maximum speed still rejects duplicate same-tick work");
            h.runAfterDelay(1,()->{tick(be);h.assertTrue(target.amounts.size()==2&&from.amounts.size()==198,"maximum speed processes next type on next tick");h.succeed();});
        });
    }
    @GameTest(template="pigmee_station_empty",timeoutTicks=160)
    public static void removingAccelerationCannotResetCooldown(GameTestHelper h) {
        fixture(h,be->{
            var target=new Store();mount(be,target);var from=new Store();
            from.amounts.put(STONE,1000L);from.amounts.put(AEItemKey.of(Items.DIAMOND),1000L);
            for(int i=0;i<4;i++)be.getUpgrades().setItemDirect(i,AEItems.SPEED_CARD.stack());
            be.getInternalInventory().setItemDirect(0,cell(from));tick(be);
            be.getUpgrades().clear();
            be.getConfigManager().putSetting(Settings.FULLNESS_MODE,FullnessMode.HALF);
            be.getConfigManager().putSetting(Settings.FULLNESS_MODE,FullnessMode.EMPTY);
            var remaining=be.getInternalInventory().getStackInSlot(0);
            be.getInternalInventory().setItemDirect(0,ItemStack.EMPTY);
            be.getInternalInventory().setItemDirect(0,remaining);
            h.runAfterDelay(4,()->{tick(be);h.assertTrue(target.amounts.size()==1,"card/settings/cell changes preserve default cooldown");});
            h.runAfterDelay(5,()->{tick(be);h.assertTrue(target.amounts.size()==2,"new default interval is applied without resetting progress");h.succeed();});
        });
    }
    @GameTest(template="pigmee_station_empty",timeoutTicks=200)
    public static void blockedPrefixEventuallyReachesLateNetworkKey(GameTestHelper h) {
        fixture(h,be->{
            for(int i=0;i<4;i++)be.getUpgrades().setItemDirect(i,AEItems.SPEED_CARD.stack());
            var network=new Store();BuiltInRegistries.ITEM.stream().filter(i->i!=Items.AIR).limit(36)
                    .forEach(i->network.amounts.put(AEItemKey.of(i),123L));mount(be,network);
            var ordered=new ArrayList<AEKey>();for(var e:be.getMainNode().getGrid().getStorageService().getCachedInventory())ordered.add(e.getKey());
            var destination=new Store();destination.only=ordered.get(ordered.size() - 1);
            setMode(be,OperationMode.FILL,FullnessMode.HALF);be.getInternalInventory().setItemDirect(0,cell(destination));tick(be);
            h.assertTrue(!be.getInternalInventory().getStackInSlot(0).isEmpty(),"budget exhaustion cannot prematurely eject HALF cell");
            h.assertTrue(destination.probes<=1,"rejected keys spend the budget");
            h.runAfterDelay(60,()->{h.assertTrue(destination.amounts.getOrDefault(destination.only,0L)==123,"late key reached through rejected prefix");h.succeed();});
        });
    }
    @GameTest(template="pigmee_station_empty",timeoutTicks=160)
    public static void filledOutputRetainsInputCell(GameTestHelper h) {
        fixture(h,be->{
            var target=new Store();mount(be,target);
            for(int i=6;i<12;i++)be.getInternalInventory().setItemDirect(i,AEItems.ITEM_CELL_1K.stack());
            var from=new Store();from.amounts.put(STONE,500L);var stack=cell(from);be.getInternalInventory().setItemDirect(0,stack);tick(be);
            h.assertTrue(ItemStack.isSameItemSameTags(be.getInternalInventory().getStackInSlot(0),stack)&&from.amounts.isEmpty(),"disk held when output full");
            be.getInternalInventory().setItemDirect(6,ItemStack.EMPTY);
            h.runAfterDelay(5,()->{tick(be);h.assertTrue(be.getInternalInventory().getStackInSlot(0).isEmpty(),"output removal wakes port");h.succeed();});
        });
    }
    @GameTest(template="pigmee_station_empty",timeoutTicks=160)
    public static void redstonePauseAndIgnore(GameTestHelper h) {
        fixture(h,be->{
            var target=new Store();mount(be,target);var from=new Store();from.amounts.put(STONE,500L);
            be.getUpgrades().setItemDirect(0,AEItems.REDSTONE_CARD.stack());
            be.getConfigManager().putSetting(Settings.REDSTONE_CONTROLLED,RedstoneMode.HIGH_SIGNAL);
            setMode(be,OperationMode.EMPTY,FullnessMode.HALF);be.getInternalInventory().setItemDirect(0,cell(from));tick(be);
            h.assertTrue(target.amounts.isEmpty()&&!be.getInternalInventory().getStackInSlot(0).isEmpty(),"paused port neither extracts nor ejects");
            be.getConfigManager().putSetting(Settings.REDSTONE_CONTROLLED,RedstoneMode.IGNORE);
            h.runAfterDelay(2,()->{tick(be);h.assertTrue(target.amounts.get(STONE)==500,"IGNORE works with redstone card installed");h.succeed();});
        });
    }
    @GameTest(template="pigmee_station_empty",timeoutTicks=160)
    public static void fullModeFillAndAutomationSides(GameTestHelper h) {
        fixture(h,be->{
            var network=new Store();network.amounts.put(STONE,1_000_000L);mount(be,network);
            var destination=new Store();destination.capacity=123456;setMode(be,OperationMode.FILL,FullnessMode.FULL);
            var stack=cell(destination);var top=be.getCapability(ForgeCapabilities.ITEM_HANDLER, be.getTop()).orElseThrow(AssertionError::new);var side=be.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.NORTH).orElseThrow(AssertionError::new);
            h.assertTrue(top.insertItem(0,new ItemStack(Items.DIRT),false).getCount()==1,"automation rejects non-cells");
            h.assertTrue(top.insertItem(0,stack,false).isEmpty(),"top accepts cell");
            h.assertTrue(top.extractItem(0,1,false).isEmpty(),"input automation cannot pull unfinished cells");tick(be);
            h.assertTrue(destination.amounts.get(STONE)==123456&&network.amounts.get(STONE)==876544,"full mode fits available capacity");
            h.assertTrue(!side.extractItem(0,1,false).isEmpty(),"side exposes completed output cell");h.succeed();
        });
    }
    @GameTest(template="pigmee_station_empty",timeoutTicks=160)
    public static void unrefundableContentsSurviveSaveAndDismantle(GameTestHelper h) {
        fixture(h,be->{
            var target=new Store();target.rejectActual=true;mount(be,target);var from=new Store();from.amounts.put(STONE,1_000_000L);from.rejectRefund=true;
            be.getInternalInventory().setItemDirect(0,cell(from));tick(be);
            h.assertTrue(from.amounts.isEmpty()&&target.amounts.isEmpty()&&be.hasPendingTransfer(),"uncertain transfer held, not lost");
            var saved=be.saveWithFullMetadata();
            var restored=new OverloadedIOPortBlockEntity(be.getBlockPos(),be.getBlockState());restored.setLevel(h.getLevel());restored.load(saved);
            h.assertTrue(restored.hasPendingTransfer(),"pending survives save/load");
            h.assertTrue(saved.contains("lastBatchTick") && restored.saveWithFullMetadata().getLong("lastBatchTick")==saved.getLong("lastBatchTick"),"cooldown timestamp survives save/load");
            var card = new CompoundTag(); be.exportSettings(SettingsFrom.MEMORY_CARD, card, null);
            h.assertTrue(!card.contains(OverloadedIOPortBlockEntity.ITEM_PENDING_TAG),"memory card cannot copy owned resources");
            var drops=net.minecraft.world.level.block.Block.getDrops(be.getBlockState(),h.getLevel(),be.getBlockPos(),be);
            var drop=drops.stream().filter(s->s.is(ModBlocks.OVERLOADED_IO_PORT.get().asItem())).findFirst().orElseThrow();
            h.assertTrue(drop.hasTag() && drop.getTag().contains(OverloadedIOPortBlockEntity.ITEM_PENDING_TAG),"ordinary break preserves pending in port item");
            var replacement=new OverloadedIOPortBlockEntity(be.getBlockPos(),be.getBlockState());replacement.setLevel(h.getLevel());
            replacement.importSettings(SettingsFrom.DISMANTLE_ITEM,drop.getTag(),null);
            h.assertTrue(replacement.hasPendingTransfer(),"placement restores owned contents");
            target.rejectActual=false;
            h.runAfterDelay(5,()->{tick(be);h.assertTrue(!be.hasPendingTransfer()&&target.amounts.get(STONE)==1_000_000,"pending retries exactly once");h.succeed();});
        });
    }
    @GameTest(template="pigmee_station_empty",timeoutTicks=200)
    public static void newlyArrivedTypeIsCheckedBeforeHalfEjection(GameTestHelper h) {
        fixture(h,be->{
            for(int i=0;i<4;i++)be.getUpgrades().setItemDirect(i,AEItems.SPEED_CARD.stack());
            var network=new Store();BuiltInRegistries.ITEM.stream().filter(i->i!=Items.AIR && i!=Items.DIAMOND).limit(12)
                    .forEach(i->network.amounts.put(AEItemKey.of(i),123L));mount(be,network);
            var destination=new Store();destination.only=AEItemKey.of(Items.DIAMOND);
            setMode(be,OperationMode.FILL,FullnessMode.HALF);be.getInternalInventory().setItemDirect(0,cell(destination));tick(be);
            network.amounts.put(destination.only,456L);
            be.getMainNode().getGrid().getStorageService().invalidateCache();
            h.runAfterDelay(20,()->{
                h.assertTrue(destination.amounts.getOrDefault(destination.only,0L)==456,"key arriving during scan cannot be skipped by HALF ejection: stored="+destination.amounts+", probes="+destination.probes+", remaining="+network.amounts.get(destination.only)+", input="+be.getInternalInventory().getStackInSlot(0)+", status="+be.getStatus()+", cache="+be.getMainNode().getGrid().getStorageService().getCachedInventory().get(destination.only));h.succeed();
            });
        });
    }
    @GameTest(template="pigmee_station_empty",timeoutTicks=160)
    public static void nativeEnergyChargeIsPerBatchAndPreservesIdleReserve(GameTestHelper h) {
        h.setBlock(POS,ModBlocks.OVERLOADED_IO_PORT.get());h.setBlock(POS.below(),AEBlocks.ENERGY_CELL.block());
        var battery=(appeng.blockentity.networking.EnergyCellBlockEntity)h.getBlockEntity(POS.below());
        battery.injectAEPower(10000,Actionable.MODULATE);
        OverloadedIOPortBlockEntity be = (OverloadedIOPortBlockEntity) h.getBlockEntity(POS);
        h.startSequence().thenWaitUntil(()->h.assertTrue(be.getMainNode().isActive(),"finite energy network boots"))
                .thenExecute(()->{
                    var target=new Store();mount(be,target);var from=new Store();from.amounts.put(STONE,1_000_000_000_000L);
                    var energy=be.getMainNode().getGrid().getEnergyService();double before=energy.getStoredPower();
                    be.getInternalInventory().setItemDirect(0,cell(from));tick(be);
                    h.assertTrue(Math.abs(before-energy.getStoredPower()-32)<0.0001,"trillion items costs exactly 32 AE");
                    h.assertTrue(target.amounts.get(STONE)==1_000_000_000_000L,"finite energy can afford large batch");
                    energy.extractAEPower(energy.getStoredPower()-20,Actionable.MODULATE,PowerMultiplier.ONE);
                    var another=new Store();another.amounts.put(STONE,1000L);
                    setMode(be,OperationMode.EMPTY,FullnessMode.HALF);be.getInternalInventory().setItemDirect(0,cell(another));
                    h.runAfterDelay(5,()->{
                        tick(be);h.assertTrue(another.amounts.get(STONE)==1000&&!be.getInternalInventory().getStackInSlot(0).isEmpty(),"low power does not extract or eject HALF cell");h.succeed();
                    });
                });
    }

    @GameTest(template="pigmee_station_empty",timeoutTicks=160)
    public static void recipeAndMenuRegistration(GameTestHelper h) {
        h.assertTrue(h.getLevel().getRecipeManager().byKey(new ResourceLocation("ae2lt:lightning_assembly/overloaded_io_port")).isPresent(),"assembly recipe loaded");
        h.assertTrue(ModMenuTypes.OVERLOADED_IO_PORT.get()==com.moakiee.ae2lt.menu.OverloadedIOPortMenu.TYPE,"menu registered");
        fixture(h,be->{h.assertTrue(be.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.UP).isPresent(),"item capability registered");h.succeed();});
    }
}
