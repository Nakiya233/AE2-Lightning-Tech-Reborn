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
import com.moakiee.ae2lt.blockentity.OverloadedInterfaceBlockEntity;
import com.moakiee.ae2lt.item.OverloadedFilterComponentItem;
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
    private static ItemStack filter(boolean inverted, boolean fuzzy, AEKey... keys) {
        var stack = new ItemStack(ModItems.OVERLOADED_FILTER_COMPONENT.get());
        var item = (OverloadedFilterComponentItem) stack.getItem();
        var config = item.getConfigInventory(stack);
        for (int i=0; i<keys.length; i++) config.setStack(i, new GenericStack(keys[i], 1));
        if (inverted) item.getUpgrades(stack).setItemDirect(0, AEItems.INVERTER_CARD.stack());
        if (fuzzy) item.getUpgrades(stack).setItemDirect(1, AEItems.FUZZY_CARD.stack());
        return stack;
    }

    @GameTest(template="pigmee_station_empty",timeoutTicks=160)
    public static void filterEmptyEjectsRealCellAfterAllowedContentsAreDrained(GameTestHelper h) {
        fixture(h,be->{
            var target=new Store();mount(be,target);
            var dirt=AEItemKey.of(Items.DIRT);var stack=AEItems.ITEM_CELL_256K.stack();
            var from=StorageCells.getCellInventory(stack,null);
            h.assertTrue(from.insert(dirt,128,Actionable.MODULATE,SOURCE)==128
                    && from.insert(STONE,1000,Actionable.MODULATE,SOURCE)==1000,"seed real filtered cell");
            from.persist();
            be.getFilterInventory().setItemDirect(0,filter(false,false,STONE));
            be.getInternalInventory().setItemDirect(0,stack);tick(be);
            h.assertTrue(target.amounts.getOrDefault(STONE,0L)==1000 && !target.amounts.containsKey(dirt),"only whitelisted type empties");
            h.assertTrue(be.getInternalInventory().getStackInSlot(0).isEmpty(),"filtered EMPTY ejects once allowed contents are drained");
            var output=StorageCells.getCellInventory(be.getInternalInventory().getStackInSlot(6),null);
            h.assertTrue(output!=null && output.getStatus()!=CellState.EMPTY
                    && output.getAvailableStacks().get(dirt)==128 && output.getAvailableStacks().get(STONE)==0,
                    "output cell persists excluded contents without retaining allowed contents");h.succeed();
        });
    }
    @GameTest(template="pigmee_station_empty",timeoutTicks=160)
    public static void filterEmptyBlacklistLeavesExcludedContentsInOutputCell(GameTestHelper h) {
        fixture(h,be->{
            var target=new Store();mount(be,target);var from=new Store();
            var dirt=AEItemKey.of(Items.DIRT);from.amounts.put(dirt,128L);from.amounts.put(STONE,1000L);
            var stack=cell(from);be.getFilterInventory().setItemDirect(0,filter(true,false,dirt));
            be.getInternalInventory().setItemDirect(0,stack);tick(be);
            h.assertTrue(be.getInternalInventory().getStackInSlot(0).isEmpty()
                    && ItemStack.isSameItemSameTags(stack,be.getInternalInventory().getStackInSlot(6)),"blacklist also defines filtered EMPTY");
            h.assertTrue(from.amounts.size()==1 && from.amounts.get(dirt)==128
                    && target.amounts.getOrDefault(STONE,0L)==1000 && !target.amounts.containsKey(dirt),"blacklisted contents remain owned by the output cell");h.succeed();
        });
    }
    @GameTest(template="pigmee_station_empty",timeoutTicks=160)
    public static void filterEmptyWithNoMatchingContentsEjectsWithoutTransfer(GameTestHelper h) {
        fixture(h,be->{
            var target=new Store();mount(be,target);var from=new Store();
            var dirt=AEItemKey.of(Items.DIRT);from.amounts.put(dirt,128L);var stack=cell(from);
            be.getFilterInventory().setItemDirect(0,filter(false,false,STONE));
            be.getInternalInventory().setItemDirect(0,stack);tick(be);
            h.assertTrue(be.getInternalInventory().getStackInSlot(0).isEmpty()
                    && ItemStack.isSameItemSameTags(stack,be.getInternalInventory().getStackInSlot(6)),"no matching contents is already empty for the filter");
            h.assertTrue(from.extractCalls==0 && target.insertCalls==0 && be.getLastBatches()==0
                    && from.amounts.get(dirt)==128,"moving a filtered-empty cell does not move or charge a resource batch");h.succeed();
        });
    }
    @GameTest(template="pigmee_station_empty",timeoutTicks=160)
    public static void filterEmptyWaitsForRejectedResourcesButHalfStillEjects(GameTestHelper h) {
        fixture(h,be->{
            var target=new Store();target.insert=false;mount(be,target);var from=new Store();
            var dirt=AEItemKey.of(Items.DIRT);from.amounts.put(dirt,128L);from.amounts.put(STONE,1000L);
            var stack=cell(from);be.getFilterInventory().setItemDirect(0,filter(false,false,STONE));
            be.getInternalInventory().setItemDirect(0,stack);tick(be);
            h.assertTrue(!be.getInternalInventory().getStackInSlot(0).isEmpty(),"destination rejection is not filtered EMPTY");
            h.runAfterDelay(5,()->{tick(be);
                h.assertTrue(!be.getInternalInventory().getStackInSlot(0).isEmpty()
                        && be.getInternalInventory().getStackInSlot(6).isEmpty(),"EMPTY waits while allowed contents remain blocked");
                setMode(be,OperationMode.EMPTY,FullnessMode.HALF);
            });
            h.runAfterDelay(10,()->{tick(be);
                h.assertTrue(be.getInternalInventory().getStackInSlot(0).isEmpty()
                        && ItemStack.isSameItemSameTags(stack,be.getInternalInventory().getStackInSlot(6)),"HALF still ejects after a no-progress pass");
                h.assertTrue(from.amounts.get(STONE)==1000 && from.amounts.get(dirt)==128
                        && target.amounts.isEmpty(),"blocked HALF output retains both allowed and excluded resources");h.succeed();});
        });
    }
    @GameTest(template="pigmee_station_empty",timeoutTicks=160)
    public static void emptyFilterRulesStillRequireAnEntirelyEmptyCell(GameTestHelper h) {
        fixture(h,be->{
            var target=new Store();target.insert=false;mount(be,target);var from=new Store();from.amounts.put(STONE,1000L);
            be.getFilterInventory().setItemDirect(0,filter(false,false));
            be.getInternalInventory().setItemDirect(0,cell(from));tick(be);
            h.assertTrue(!be.getInternalInventory().getStackInSlot(0).isEmpty(),"empty whitelist is unrestricted, not an empty set of allowed resources");
            h.runAfterDelay(5,()->{
                be.getFilterInventory().setItemDirect(0,filter(true,false));tick(be);
                h.assertTrue(!be.getInternalInventory().getStackInSlot(0).isEmpty(),"empty blacklist also waits for remaining resources");
                target.insert=true;
            });
            h.runAfterDelay(10,()->{tick(be);
                h.assertTrue(be.getInternalInventory().getStackInSlot(0).isEmpty() && from.amounts.isEmpty()
                        && target.amounts.getOrDefault(STONE,0L)==1000,"unrestricted filter ejects after all contents are drained");h.succeed();});
        });
    }
    @GameTest(template="pigmee_station_empty",timeoutTicks=160)
    public static void filteredEmptyChecksNewContentsAfterMultiTickScan(GameTestHelper h) {
        fixture(h,be->{
            var target=new Store();mount(be,target);var from=new Store();
            var dirt=AEItemKey.of(Items.DIRT);var diamond=AEItemKey.of(Items.DIAMOND);var iron=AEItemKey.of(Items.IRON_INGOT);
            from.amounts.put(STONE,100L);from.amounts.put(dirt,200L);from.amounts.put(iron,400L);
            be.getFilterInventory().setItemDirect(0,filter(false,false,STONE,dirt,diamond));
            be.getInternalInventory().setItemDirect(0,cell(from));tick(be);
            from.amounts.put(diamond,300L);
            h.runAfterDelay(5,()->{tick(be);
                h.assertTrue(!be.getInternalInventory().getStackInSlot(0).isEmpty()
                        && from.amounts.getOrDefault(diamond,0L)==300,"new allowed contents prevent EMPTY even after the original scan completes");});
            h.runAfterDelay(10,()->{tick(be);
                h.assertTrue(be.getInternalInventory().getStackInSlot(0).isEmpty()
                        && target.amounts.getOrDefault(diamond,0L)==300 && from.amounts.size()==1
                        && from.amounts.get(iron)==400,"next pass transfers the new key before ejecting with excluded contents");h.succeed();});
        });
    }
    @GameTest(template="pigmee_station_empty",timeoutTicks=160)
    public static void filteredEmptyInFillModeChecksTheCellNotTheNetwork(GameTestHelper h) {
        fixture(h,be->{
            var network=new Store();mount(be,network);var destination=new Store();var dirt=AEItemKey.of(Items.DIRT);
            destination.amounts.put(dirt,128L);destination.amounts.put(STONE,1000L);
            setMode(be,OperationMode.FILL,FullnessMode.EMPTY);
            be.getFilterInventory().setItemDirect(0,filter(false,false,STONE));
            be.getInternalInventory().setItemDirect(0,cell(destination));tick(be);
            h.assertTrue(!be.getInternalInventory().getStackInSlot(0).isEmpty(),"empty network does not make a cell with matching contents empty");
            destination.amounts.remove(STONE);
            h.runAfterDelay(5,()->{tick(be);
                h.assertTrue(be.getInternalInventory().getStackInSlot(0).isEmpty()
                        && !be.getInternalInventory().getStackInSlot(6).isEmpty()
                        && destination.amounts.get(dirt)==128,"FILL uses the same filtered cell-empty condition");h.succeed();});
        });
    }
    @GameTest(template="pigmee_station_empty",timeoutTicks=160)
    public static void filterFillBlacklistSkipsUnwantedTypes(GameTestHelper h) {
        fixture(h,be->{
            var network=new Store();var dirt=AEItemKey.of(Items.DIRT);
            network.amounts.put(STONE,1000L);network.amounts.put(dirt,2000L);mount(be,network);
            var destination=new Store();setMode(be,OperationMode.FILL,FullnessMode.FULL);
            be.getFilterInventory().setItemDirect(0,filter(true,false,STONE));
            be.getInternalInventory().setItemDirect(0,cell(destination));tick(be);
            h.assertTrue(destination.amounts.getOrDefault(dirt,0L)==2000 && !destination.amounts.containsKey(STONE),"blacklist applies to network-to-cell transfers immediately");
            h.assertTrue(network.amounts.get(STONE)==1000 && destination.probes==1,"ignored keys do not spend the one-type interval");
            h.assertTrue(!be.getInternalInventory().getStackInSlot(0).isEmpty(),"filtered completion does not mean physically full");h.succeed();
        });
    }
    @GameTest(template="pigmee_station_empty",timeoutTicks=160)
    public static void filterReplacementInvalidatesScanWithoutResettingCooldown(GameTestHelper h) {
        fixture(h,be->{
            var target=new Store();mount(be,target);var from=new Store();
            // Keep the cell queued so this test can replace rules after a filtered drain completes.
            setMode(be,OperationMode.EMPTY,FullnessMode.FULL);
            var dirt=AEItemKey.of(Items.DIRT);var diamond=AEItemKey.of(Items.DIAMOND);
            from.amounts.put(STONE,100L);from.amounts.put(dirt,200L);from.amounts.put(diamond,300L);
            be.getFilterInventory().setItemDirect(0,filter(false,false,STONE,dirt));
            be.getInternalInventory().setItemDirect(0,cell(from));tick(be);
            h.assertTrue(target.amounts.size()==1 && !target.amounts.containsKey(diamond),"initial whitelist applies");
            be.getFilterInventory().setItemDirect(0,filter(false,false,diamond));tick(be);
            h.assertTrue(target.amounts.size()==1,"filter swap cannot reset cooldown");
            h.runAfterDelay(5,()->{tick(be);
                h.assertTrue(target.amounts.getOrDefault(diamond,0L)==300 && from.amounts.size()==1,"old scan invalidated and cached rejection replaced");
                be.getFilterInventory().setItemDirect(0,ItemStack.EMPTY);
            });
            h.runAfterDelay(10,()->{tick(be);h.assertTrue(from.amounts.isEmpty() && target.amounts.size()==3,"removing filter allows remaining resource");h.succeed();});
        });
    }
    @GameTest(template="pigmee_station_empty",timeoutTicks=160)
    public static void filterSupportsFluidLightningAndExactComponents(GameTestHelper h) {
        var water=AEFluidKey.of(Fluids.WATER);
        var mixed=filter(false,false,water,LightningKey.HIGH_VOLTAGE);
        var item=(OverloadedFilterComponentItem)mixed.getItem();var matcher=item.createMatcher(mixed);
        h.assertTrue(matcher.test(water) && matcher.test(LightningKey.HIGH_VOLTAGE) && !matcher.test(STONE),"all registered key types share filter semantics");
        var pristine=new ItemStack(Items.DIAMOND_PICKAXE);var damaged=pristine.copy();damaged.setDamageValue(25);
        var exact=filter(false,false,AEItemKey.of(pristine));
        h.assertTrue(!item.createMatcher(exact).test(AEItemKey.of(damaged)),"precise comparison retains damage components");
        item.getUpgrades(exact).setItemDirect(0,AEItems.FUZZY_CARD.stack());
        h.assertTrue(item.createMatcher(exact).test(AEItemKey.of(damaged)),"fuzzy card permits damage variation");
        h.assertTrue(item.createMatcher(filter(true,false)).test(STONE),"empty inverted filter remains unrestricted");
        fixture(h,be->{
            var from=new Store();var target=new Store();mount(be,target);
            from.amounts.put(STONE,50L);from.amounts.put(water,10_000L);from.amounts.put(LightningKey.HIGH_VOLTAGE,200L);
            be.getFilterInventory().setItemDirect(0,mixed);be.getInternalInventory().setItemDirect(0,cell(from));tick(be);
            h.runAfterDelay(5,()->{tick(be);
                h.assertTrue(target.amounts.getOrDefault(water,0L)==10_000 && target.amounts.getOrDefault(LightningKey.HIGH_VOLTAGE,0L)==200 && !target.amounts.containsKey(STONE),"fluid and lightning move, excluded items remain");h.succeed();});
        });
    }
    @GameTest(template="pigmee_station_empty",timeoutTicks=160)
    public static void filterInventoryPersistsAndDropsExactlyOnce(GameTestHelper h) {
        fixture(h,be->{
            var configured=filter(true,true,STONE);
            h.assertTrue(!be.getFilterInventory().insertItem(0,new ItemStack(Items.DIRT),false).isEmpty(),"filter slot rejects unrelated items");
            var two=configured.copyWithCount(2);
            h.assertTrue(be.getFilterInventory().insertItem(0,two,false).getCount()==1,"filter slot accepts exactly one component");
            var saved=be.saveWithFullMetadata();
            var restored=new OverloadedIOPortBlockEntity(be.getBlockPos(),be.getBlockState());restored.setLevel(h.getLevel());restored.loadTag(saved);
            h.assertTrue(ItemStack.isSameItemSameTags(restored.getFilterInventory().getStackInSlot(0),configured),"rules and upgrade cards survive save/load");
            var copied=new OverloadedIOPortBlockEntity(be.getBlockPos(),be.getBlockState());copied.setLevel(h.getLevel());
            var settingsTag = new CompoundTag(); be.exportSettings(SettingsFrom.MEMORY_CARD, settingsTag, null);
            copied.importSettings(SettingsFrom.MEMORY_CARD, settingsTag, null);
            h.assertTrue(copied.getFilterInventory().isEmpty(),"memory card cannot duplicate the component");
            saved.remove("filterInventory");copied.loadTag(saved);
            h.assertTrue(copied.getFilterInventory().isEmpty(),"old saves without a filter remain compatible");
            // AE2 spawns inventory drops from onRemove, separately from the block loot table.
            h.getLevel().destroyBlock(be.getBlockPos(),true);
            var drops=h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    new net.minecraft.world.phys.AABB(be.getBlockPos()).inflate(1));
            h.assertTrue(drops.stream().map(net.minecraft.world.entity.item.ItemEntity::getItem)
                    .filter(s->ItemStack.isSameItemSameTags(s,configured)).mapToInt(ItemStack::getCount).sum()==1,"ordinary break drops one configured filter");
            be.clearContent();h.assertTrue(be.getFilterInventory().isEmpty(),"clearing block removes filter");h.succeed();
        });
    }

    /** Counts structural work without relying on AEItemKey interning or a GC timing assumption. */
    private static final class CountingKey extends AEKey {
        final int value;
        int structuralCalls;
        CountingKey(int value) { this.value=value; }
        @Override public int hashCode() { structuralCalls++;return value; }
        @Override public boolean equals(Object other) { structuralCalls++;return other instanceof CountingKey k && value==k.value; }
        @Override public AEKeyType getType() { return AEKeyType.items(); }
        @Override public AEKey dropSecondary() { return this; }
        @Override public CompoundTag toTag() { throw new UnsupportedOperationException(); }
        @Override public Object getPrimaryKey() { structuralCalls++;return Items.STONE; }
        @Override public ResourceLocation getId() { return new ResourceLocation("ae2lt:test_key"); }
        @Override public void writeToPacket(net.minecraft.network.FriendlyByteBuf out) { throw new UnsupportedOperationException(); }
        @Override protected Component computeDisplayName() { return Component.literal("test key"); }
        @Override public void addDrops(long amount,List<ItemStack> drops,net.minecraft.world.level.Level level,BlockPos pos) { throw new UnsupportedOperationException(); }
    }
    @GameTest(template="pigmee_station_empty")
    public static void filterCacheUsesIdentityForBothOutcomes(GameTestHelper h) {
        var configured=new CountingKey(1);
        var matcher=OverloadedFilterComponentItem.createMatcher(Set.of(configured),null,false);
        for (int value : new int[]{1,2}) {
            var key=new CountingKey(value);boolean expected=value==1;
            h.assertTrue(matcher.test(key)==expected && key.structuralCalls>0,"first encounter computes precise rule");
            int coldCalls=key.structuralCalls;
            for(int i=0;i<1000;i++) h.assertTrue(matcher.test(key)==expected,"cached result stable");
            h.assertTrue(key.structuralCalls==coldCalls,"identity hits avoid all structural hashing/equality for true and false");
            var equalButDistinct=new CountingKey(value);
            h.assertTrue(matcher.test(equalButDistinct)==expected && equalButDistinct.structuralCalls>0,"equal distinct instances must miss identity cache");
            var inverted=OverloadedFilterComponentItem.createMatcher(Set.of(configured),null,true);
            h.assertTrue(inverted.test(key)!=expected,"new rule snapshot never reuses stale cached outcome");
        }
        var fuzzy=OverloadedFilterComponentItem.createMatcher(Set.of(configured),FuzzyMode.IGNORE_ALL,false);
        var variant=new CountingKey(3);h.assertTrue(fuzzy.test(variant),"fuzzy primary-key rule matches");
        int coldCalls=variant.structuralCalls;for(int i=0;i<1000;i++)fuzzy.test(variant);
        h.assertTrue(variant.structuralCalls==coldCalls,"fuzzy cache hit avoids repeated rule scans");h.succeed();
    }
    @GameTest(template="pigmee_station_empty")
    public static void interfaceFilterRebuildInvalidatesCachedDecisions(GameTestHelper h) {
        h.setBlock(POS,ModBlocks.OVERLOADED_INTERFACE.get());
        OverloadedInterfaceBlockEntity be=(OverloadedInterfaceBlockEntity) h.getBlockEntity(POS);var dirt=AEItemKey.of(Items.DIRT);
        be.getFilterInv().setItemDirect(0,filter(false,false,STONE));
        for(int i=0;i<10;i++)h.assertTrue(be.isInsertAllowedByFilter(STONE) && !be.isInsertAllowedByFilter(dirt),"interface uses same whitelist");
        be.getFilterInv().setItemDirect(0,filter(true,false,STONE));
        h.assertTrue(!be.isInsertAllowedByFilter(STONE) && be.isInsertAllowedByFilter(dirt),"interface cached true/false both invalidated on replacement");
        be.getFilterInv().setItemDirect(0,ItemStack.EMPTY);
        h.assertTrue(be.isInsertAllowedByFilter(STONE) && be.isInsertAllowedByFilter(dirt),"interface removal discards cached rejection");h.succeed();
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
            be.getMatrixInventory().setItemDirect(0,new ItemStack(ModItems.LIGHTNING_COLLAPSE_MATRIX.get(),2));
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
            long water=fluid.insert(AEFluidKey.of(Fluids.WATER),20_000,Actionable.MODULATE,SOURCE);fluid.persist();
            var lightningStack=new ItemStack(ModItems.LIGHTNING_STORAGE_COMPONENT_II.get());
            var lightning=StorageCells.getCellInventory(lightningStack,null);
            long amount=lightning.insert(LightningKey.HIGH_VOLTAGE,5000,Actionable.MODULATE,SOURCE);lightning.persist();
            h.assertTrue(water==20_000&&amount>0,"seed real fluid/lightning cells");
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
            for(int i=0;i<6;i++){var from=new Store();from.amounts.put(STONE,10_000L);be.getInternalInventory().setItemDirect(i,cell(from));}
            tick(be);h.assertTrue(target.amounts.get(STONE)==10_000,"six cells share one batch per five ticks");
            tick(be);h.assertTrue(target.amounts.get(STONE)==10_000,"same tick cannot spend budget twice");
            h.runAfterDelay(4,()->{be.updateRedstoneState();tick(be);h.assertTrue(target.amounts.get(STONE)==10_000,"alerts cannot shorten default five-tick gap");});
            h.runAfterDelay(5,()->{tick(be);h.assertTrue(target.amounts.get(STONE)==20_000,"next cell starts exactly after five ticks");});
            h.runAfterDelay(25,()->{tick(be);h.assertTrue(target.amounts.get(STONE)==60_000,"all six cells served in rotation");h.succeed();});
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
            be.getMatrixInventory().setItemDirect(0,new ItemStack(ModItems.LIGHTNING_COLLAPSE_MATRIX.get(),1));
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
            be.getMatrixInventory().setItemDirect(0,new ItemStack(ModItems.LIGHTNING_COLLAPSE_MATRIX.get(),2));
            be.getInternalInventory().setItemDirect(0,cell(from));tick(be);
            h.assertTrue(from.amounts.isEmpty()&&target.amounts.isEmpty()&&be.hasPendingTransfer(),"uncertain transfer held, not lost");
            be.getFilterInventory().setItemDirect(0,filter(false,false,AEItemKey.of(Items.DIRT)));
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
            h.runAfterDelay(5,()->{tick(be);h.assertTrue(!be.hasPendingTransfer()&&target.amounts.get(STONE)==1_000_000,"pending retries exactly once even after filter excludes its key");h.succeed();});
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
                    be.getMatrixInventory().setItemDirect(0,new ItemStack(ModItems.LIGHTNING_COLLAPSE_MATRIX.get(),16));
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
    public static void matrixInventoryLimitsPersistsAndDrops(GameTestHelper h) {
        fixture(h,be->{
            var matrix=ModItems.LIGHTNING_COLLAPSE_MATRIX.get();
            h.assertTrue(!be.getMatrixInventory().insertItem(0,new ItemStack(Items.DIRT),false).isEmpty(),"matrix slot rejects unrelated items");
            int[] counts={0,1,15,16};
            long[] caps={32768L,262144L,1L<<60,Long.MAX_VALUE};
            for(int i=0;i<counts.length;i++) {
                be.getMatrixInventory().setItemDirect(0,counts[i]==0?ItemStack.EMPTY:new ItemStack(matrix,counts[i]));
                h.assertTrue(be.getBatchLimit()==Math.min(16,1+counts[i]) && be.getTransferCap()==caps[i],"matrix tier and long saturation: "+counts[i]);
            }
            h.assertTrue(be.getBatchLimit()==16 && be.getTransferCap()==Long.MAX_VALUE,"16th matrix increases amount only, not attempts");
            be.getMatrixInventory().clear();
            h.assertTrue(be.getMatrixInventory().insertItem(0,new ItemStack(matrix,64),true).getCount()==48
                    && be.getMatrixInventory().isEmpty(),"simulation respects 16-matrix slot capacity");
            h.assertTrue(be.getMatrixInventory().insertItem(0,new ItemStack(matrix,64),false).getCount()==48
                    && be.getMatrixCount()==16,"real inventory and menu share the 16-matrix limit");
            var saved=be.saveWithFullMetadata();
            var restored=new OverloadedIOPortBlockEntity(be.getBlockPos(),be.getBlockState());restored.setLevel(h.getLevel());
            restored.loadTag(saved);
            h.assertTrue(restored.getMatrixCount()==16 && restored.getBatchLimit()==16 && restored.getTransferCap()==Long.MAX_VALUE,"matrix stack and throughput survive save/load");
            var copied=new OverloadedIOPortBlockEntity(be.getBlockPos(),be.getBlockState());copied.setLevel(h.getLevel());
            var settingsTag = new CompoundTag(); be.exportSettings(SettingsFrom.MEMORY_CARD, settingsTag, null);
            copied.importSettings(SettingsFrom.MEMORY_CARD, settingsTag, null);
            h.assertTrue(copied.getMatrixInventory().isEmpty(),"memory card cannot duplicate matrices");
            saved.remove("matrix");copied.loadTag(saved);
            h.assertTrue(copied.getBatchLimit()==1 && copied.getTransferCap()==32768,"legacy saves without matrices retain base throughput");
            h.getLevel().destroyBlock(be.getBlockPos(),true);
            var drops=h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    new net.minecraft.world.phys.AABB(be.getBlockPos()).inflate(1));
            h.assertTrue(drops.stream().map(net.minecraft.world.entity.item.ItemEntity::getItem)
                    .filter(s->s.is(matrix)).mapToInt(ItemStack::getCount).sum()==16,"ordinary break returns all matrices exactly once");
            be.clearContent();h.assertTrue(be.getMatrixInventory().isEmpty(),"clearing block clears matrix inventory");h.succeed();
        });
    }
    @GameTest(template="pigmee_station_empty",timeoutTicks=160)
    public static void maximumMatricesShareSixteenAttemptsAcrossSixInputs(GameTestHelper h) {
        fixture(h,be->{
            be.getMatrixInventory().setItemDirect(0,new ItemStack(ModItems.LIGHTNING_COLLAPSE_MATRIX.get(),16));
            for(int i=0;i<4;i++)be.getUpgrades().setItemDirect(i,AEItems.SPEED_CARD.stack());
            var target=new Store();mount(be,target);var sources=new ArrayList<Store>();
            for(int slot=0;slot<6;slot++) {
                var from=new Store();sources.add(from);
                BuiltInRegistries.ITEM.stream().filter(i->i!=Items.AIR).limit(20).forEach(i->from.amounts.put(AEItemKey.of(i),100L));
                be.getInternalInventory().setItemDirect(slot,cell(from));
            }
            // Output cells remain output cells even at maximum throughput.
            var outputStore=new Store();outputStore.amounts.put(AEItemKey.of(Items.DIAMOND),1234L);
            be.getInternalInventory().setItemDirect(6,cell(outputStore));tick(be);
            h.assertTrue(target.amounts.values().stream().mapToLong(Long::longValue).sum()==1600 && be.getLastBatches()==16,"six cells share 16 attempts, not 16 each");
            h.assertTrue(sources.stream().allMatch(s->s.amounts.size()==17||s.amounts.size()==18),"all six inputs receive round-robin service");
            tick(be);h.assertTrue(target.amounts.values().stream().mapToLong(Long::longValue).sum()==1600,"same-tick alert cannot spend another 16 attempts");
            h.runAfterDelay(1,()->{tick(be);
                h.assertTrue(target.amounts.values().stream().mapToLong(Long::longValue).sum()==3200,"next tick permits the next 16 attempts");
                h.assertTrue(outputStore.extractCalls==0 && outputStore.amounts.get(AEItemKey.of(Items.DIAMOND))==1234,"output slots are never parallel inputs");h.succeed();});
        });
    }
    @GameTest(template="pigmee_station_empty",timeoutTicks=160)
    public static void rejectedKeysSpendMatrixBudgetWithoutPrematureHalfEjection(GameTestHelper h) {
        fixture(h,be->{
            be.getMatrixInventory().setItemDirect(0,new ItemStack(ModItems.LIGHTNING_COLLAPSE_MATRIX.get(),16));
            var network=new Store();BuiltInRegistries.ITEM.stream().filter(i->i!=Items.AIR).limit(20)
                    .forEach(i->network.amounts.put(AEItemKey.of(i),123L));mount(be,network);
            var ordered=new ArrayList<AEKey>();for(var e:be.getMainNode().getGrid().getStorageService().getCachedInventory())ordered.add(e.getKey());
            var destination=new Store();destination.only=ordered.get(ordered.size()-1);setMode(be,OperationMode.FILL,FullnessMode.HALF);
            be.getInternalInventory().setItemDirect(0,cell(destination));tick(be);
            h.assertTrue(destination.probes==16 && destination.amounts.isEmpty() && !be.getInternalInventory().getStackInSlot(0).isEmpty(),"16 rejected types exhaust budget without completing HALF");
            h.runAfterDelay(5,()->{tick(be);
                h.assertTrue(destination.amounts.getOrDefault(destination.only,0L)==123 && !be.getInternalInventory().getStackInSlot(0).isEmpty(),"next round reaches late key and retains a progressing HALF cell");h.succeed();});
        });
    }
    @GameTest(template="pigmee_station_empty",timeoutTicks=160)
    public static void matrixChangesPreserveCooldownAndUpdateAmountCap(GameTestHelper h) {
        fixture(h,be->{
            var target=new Store();mount(be,target);var from=new Store();from.amounts.put(STONE,1_000_000L);
            from.amounts.put(AEItemKey.of(Items.DIRT),1_000_000L);from.amounts.put(AEItemKey.of(Items.DIAMOND),1_000_000L);
            be.getInternalInventory().setItemDirect(0,cell(from));tick(be);
            h.assertTrue(target.amounts.values().stream().mapToLong(Long::longValue).sum()==32768,"zero matrices cap the first type at 32K");
            be.getMatrixInventory().setItemDirect(0,new ItemStack(ModItems.LIGHTNING_COLLAPSE_MATRIX.get(),1));tick(be);
            h.assertTrue(target.amounts.size()==1,"matrix insertion cannot reset cooldown");
            h.runAfterDelay(4,()->{tick(be);h.assertTrue(target.amounts.size()==1,"default interval still five ticks with a matrix");});
            h.runAfterDelay(5,()->{tick(be);
                // The grid may already have run this round before this explicit duplicate tick.
                h.assertTrue(target.amounts.size()==3
                        && target.amounts.values().stream().filter(n->n==262144).count()==2
                        && target.amounts.values().stream().mapToLong(Long::longValue).sum()==32768+2*262144,
                        "one matrix adds one attempt and multiplies amount by eight: "+target.amounts);
                h.assertTrue(!be.getInternalInventory().getStackInSlot(0).isEmpty(),"partially drained cell remains in input");
                be.getMatrixInventory().clear();tick(be);
            });
            h.runAfterDelay(10,()->{tick(be);
                h.assertTrue(target.amounts.values().stream().mapToLong(Long::longValue).sum()==2*32768+2*262144,"removal restores both limits for the next round");h.succeed();});
        });
    }
    @GameTest(template="pigmee_station_empty",timeoutTicks=160)
    public static void cappedTransfersKeepFilteredCellUntilAllowedResourcesAreGone(GameTestHelper h) {
        fixture(h,be->{
            var target=new Store();mount(be,target);var stack=AEItems.ITEM_CELL_256K.stack();var from=StorageCells.getCellInventory(stack,null);
            var dirt=AEItemKey.of(Items.DIRT);from.insert(STONE,65553,Actionable.MODULATE,SOURCE);from.insert(dirt,128,Actionable.MODULATE,SOURCE);from.persist();
            be.getFilterInventory().setItemDirect(0,filter(false,false,STONE));be.getInternalInventory().setItemDirect(0,stack);tick(be);
            h.assertTrue(target.amounts.getOrDefault(STONE,0L)==32768 && !be.getInternalInventory().getStackInSlot(0).isEmpty(),"cap does not make a partially drained cell filtered-empty");
            h.runAfterDelay(5,()->{tick(be);h.assertTrue(target.amounts.getOrDefault(STONE,0L)==65536 && !be.getInternalInventory().getStackInSlot(0).isEmpty(),"second capped batch still waits for remainder");});
            h.runAfterDelay(10,()->{tick(be);
                var output=StorageCells.getCellInventory(be.getInternalInventory().getStackInSlot(6),null);
                h.assertTrue(target.amounts.getOrDefault(STONE,0L)==65553 && be.getInternalInventory().getStackInSlot(0).isEmpty()
                        && output!=null && output.getAvailableStacks().get(dirt)==128 && output.getAvailableStacks().get(STONE)==0,"final partial batch ejects the real cell with only excluded contents");h.succeed();});
        });
    }
    @GameTest(template="pigmee_station_empty")
    public static void cappedTransferRollbackConservesRemainder(GameTestHelper h) {
        var from=new Store();var to=new Store();from.amounts.put(STONE,1_000_000L);to.rejectActual=true;int[] paid={0};
        var result=OverloadedIOTransfer.move(from,to,STONE,SOURCE,()->{paid[0]++;return true;},32768);
        h.assertTrue(result.inserted()==0 && result.remainder()==0 && from.amounts.get(STONE)==1_000_000 && paid[0]==1,"capped rejected insertion refunds the entire extracted batch");
        from.rejectRefund=true;result=OverloadedIOTransfer.move(from,to,STONE,SOURCE,()->true,32768);
        h.assertTrue(result.remainder()==32768 && from.amounts.get(STONE)==967232 && to.amounts.isEmpty(),"unrefundable pending amount is capped and conserves total resources");
        var noWork=OverloadedIOTransfer.move(from,to,STONE,SOURCE,()->{throw new AssertionError("zero cap must not charge");},0);
        h.assertTrue(noWork.inserted()==0 && noWork.remainder()==0,"zero cap does no work");h.succeed();
    }

    @GameTest(template="pigmee_station_empty",timeoutTicks=160)
    public static void recipeAndMenuRegistration(GameTestHelper h) {
        h.assertTrue(h.getLevel().getRecipeManager().byKey(new ResourceLocation("ae2lt:lightning_assembly/overloaded_io_port")).isPresent(),"assembly recipe loaded");
        h.assertTrue(ModMenuTypes.OVERLOADED_IO_PORT.get()==com.moakiee.ae2lt.menu.OverloadedIOPortMenu.TYPE,"menu registered");
        fixture(h,be->{h.assertTrue(be.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.UP).isPresent(),"item capability registered");h.succeed();});
    }
}
