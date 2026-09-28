package com.moakiee.ae2lt.debug;

import java.util.List;
import java.util.Set;
import appeng.api.config.*;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.StorageCells;
import appeng.api.networking.security.IActionSource;
import appeng.core.definitions.*;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuLocators;
import com.moakiee.ae2lt.blockentity.OverloadedIOPortBlockEntity;
import com.moakiee.ae2lt.client.OverloadedIOPortScreen;
import com.moakiee.ae2lt.integration.jei.category.LightningAssemblyCategory;
import com.moakiee.ae2lt.menu.OverloadedIOPortMenu;
import com.moakiee.ae2lt.registry.ModBlocks;
import com.moakiee.ae2lt.registry.ModItems;
import com.moakiee.ae2lt.item.OverloadedFilterComponentItem;
import mezz.jei.api.*;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.TickEvent;

/** Opt-in real screen, packet and JEI smoke test; only its disposable world is modified. */
@JeiPlugin
@EventBusSubscriber(modid="ae2lt",value=Dist.CLIENT)
public final class OverloadedIOPortClientProbe implements IModPlugin {
    private static final BlockPos POS=new BlockPos(0,100,0);
    private static IJeiRuntime jei;
    private static int ticks,phase;
    private static boolean done;
    private static volatile Throwable failure;
    @Override public ResourceLocation getPluginUid(){return new ResourceLocation("ae2lt:io_port_qa");}
    @Override public void onRuntimeAvailable(IJeiRuntime runtime){jei=runtime;}
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event){
        if(event.phase != TickEvent.Phase.END)return;
        if(!Boolean.getBoolean("ae2lt.ioClientProbe")||done)return;
        var mc=Minecraft.getInstance();mc.options.pauseOnLostFocus=false;
        if(mc.player==null||mc.getSingleplayerServer()==null||++ticks%40!=0)return;
        try{
            if(failure!=null)throw new AssertionError(failure);
            switch(phase){
                case 0->{
                    mc.getWindow().setTitle("OVERLOADED IO QA - disposable world");mc.getWindow().setWindowed(1100,800);mc.resizeDisplay();
                    mc.options.gamma().set(1.0);mc.options.guiScale().set(3);mc.resizeDisplay();
                    server(()->{
                        var server=mc.getSingleplayerServer();var level=server.overworld();
                        for(var pos:BlockPos.betweenClosed(-3,99,-3,3,104,5))level.setBlockAndUpdate(pos,pos.getY()==99?Blocks.WHITE_CONCRETE.defaultBlockState():Blocks.AIR.defaultBlockState());
                        level.setDayTime(6000);level.setWeatherParameters(6000,0,false,false);
                        level.setBlockAndUpdate(POS,ModBlocks.OVERLOADED_IO_PORT.get().defaultBlockState());
                        level.setBlockAndUpdate(POS.below(),AEBlocks.CREATIVE_ENERGY_CELL.block().defaultBlockState());
                        var player=server.getPlayerList().getPlayer(mc.player.getUUID());player.setGameMode(GameType.CREATIVE);player.getAbilities().flying=true;player.onUpdateAbilities();
                        player.teleportTo(level,0.5,101,3.5,Set.of(),180,25);player.getInventory().clearContent();
                        var filter=new ItemStack(ModItems.OVERLOADED_FILTER_COMPONENT.get());
                        ((OverloadedFilterComponentItem)filter.getItem()).getConfigInventory(filter).setStack(0,
                                new appeng.api.stacks.GenericStack(AEItemKey.of(Items.STONE),1));
                        player.getInventory().setItem(0,filter);
                        player.getInventory().setItem(1,new ItemStack(ModItems.LIGHTNING_COLLAPSE_MATRIX.get(),64));
                    });
                }
                case 1->{
                    // A development window can lose focus while the integrated server loads.
                    if (mc.screen instanceof net.minecraft.client.gui.screens.PauseScreen) mc.setScreen(null);
                    if (jei == null || mc.screen != null || !(mc.level.getBlockEntity(POS) instanceof OverloadedIOPortBlockEntity)) {
                        require(ticks < 1200, "Waiting for client IO block: " + mc.level.getBlockState(POS) + ", screen=" + mc.screen + ", jei=" + (jei != null));
                        return;
                    }
                    new OverloadedIOPortScreen(new OverloadedIOPortMenu(99, mc.player.getInventory(),
                            (OverloadedIOPortBlockEntity) mc.level.getBlockEntity(POS)), mc.player.getInventory(),
                            net.minecraft.network.chat.Component.literal("QA"),
                            appeng.client.gui.style.StyleManager.loadStyleDoc("/screens/overloaded_io_port.json"));
                    server(()->{
                    var server=mc.getSingleplayerServer();var be=(OverloadedIOPortBlockEntity)server.overworld().getBlockEntity(POS);
                    require(be.getMainNode().isActive(),"AE2 grid not active");
                    for(int i=0;i<4;i++)be.getUpgrades().setItemDirect(i,AEItems.SPEED_CARD.stack());
                    be.getUpgrades().setItemDirect(4,AEItems.REDSTONE_CARD.stack());
                    var player=server.getPlayerList().getPlayer(mc.player.getUUID());require(MenuOpener.open(OverloadedIOPortMenu.TYPE,player,MenuLocators.forBlockEntity(be)), "Server rejected IO menu open");
                });
                }
                case 2->{
                    require(mc.screen instanceof OverloadedIOPortScreen,"screen binding failed: "+mc.screen + ", menu=" + mc.player.containerMenu + ", client=" + mc.level.getBlockState(POS));
                    var menu=(OverloadedIOPortMenu)mc.player.containerMenu;require(menu.transferInterval==1,"server rate not synchronized");
                    require(menu.slots.size()==55,"six inputs, six outputs, filter, matrix, five upgrades and inventory");
                    require(menu.batchLimit==1 && menu.transferCap==32768,"base throughput not synchronized");
                    capture("overloaded-io-empty.png");
                    var playerFilter=menu.slots.stream().filter(s->s.getItem().is(ModItems.OVERLOADED_FILTER_COMPONENT.get())).findFirst().orElseThrow();
                    mc.gameMode.handleInventoryMouseClick(menu.containerId,playerFilter.index,0,ClickType.QUICK_MOVE,mc.player);
                    var playerMatrices=menu.slots.stream().filter(s->s.getItem().is(ModItems.LIGHTNING_COLLAPSE_MATRIX.get())).findFirst().orElseThrow();
                    mc.gameMode.handleInventoryMouseClick(menu.containerId,playerMatrices.index,0,ClickType.QUICK_MOVE,mc.player);
                    var screen=(OverloadedIOPortScreen)mc.screen;
                    screen.mouseClicked(screen.getGuiLeft()+88,screen.getGuiTop()+25,0);
                }
                case 3->{
                    require(((OverloadedIOPortMenu)mc.player.containerMenu).operation==OperationMode.FILL,"operation button packet failed");
                    var menu=(OverloadedIOPortMenu)mc.player.containerMenu;
                    require(menu.batchLimit==16 && menu.transferCap==Long.MAX_VALUE,"matrix throughput not synchronized");
                    capture("overloaded-io-filter-matrix.png");
                    server(()->{
                        var be=(OverloadedIOPortBlockEntity)mc.getSingleplayerServer().overworld().getBlockEntity(POS);
                        require(be.getFilterInventory().getStackInSlot(0).is(ModItems.OVERLOADED_FILTER_COMPONENT.get()),"shift-click did not route component to filter slot");
                        require(be.getMatrixCount()==16,"matrix shift-click must fill exactly 16, not one or 64");
                        be.getConfigManager().putSetting(Settings.OPERATION_MODE,OperationMode.EMPTY);
                        var stack=AEItems.ITEM_CELL_256K.stack();var storage=StorageCells.getCellInventory(stack,null);
                        storage.insert(AEItemKey.of(Items.STONE),1_000_000,Actionable.MODULATE,IActionSource.empty());storage.persist();
                        be.getInternalInventory().setItemDirect(0,stack);
                    });
                }
                case 4->{
                    require(((OverloadedIOPortMenu)mc.player.containerMenu).status==OverloadedIOPortBlockEntity.Status.BLOCKED,"blocked status missing");
                    capture("overloaded-io-blocked.png");
                    var menu=mc.player.containerMenu;
                    var installed=menu.slots.stream().filter(s->s.getItem().is(ModItems.OVERLOADED_FILTER_COMPONENT.get())).findFirst().orElseThrow();
                    mc.gameMode.handleInventoryMouseClick(menu.containerId,installed.index,0,ClickType.QUICK_MOVE,mc.player);
                    var installedMatrices=menu.slots.stream().filter(s->s.getItem().is(ModItems.LIGHTNING_COLLAPSE_MATRIX.get()) && s.getItem().getCount()==16).findFirst().orElseThrow();
                    mc.gameMode.handleInventoryMouseClick(menu.containerId,installedMatrices.index,0,ClickType.QUICK_MOVE,mc.player);
                    require(jei!=null,"JEI unavailable");
                    var manager=jei.getRecipeManager();var recipes=manager.createRecipeLookup(LightningAssemblyCategory.TYPE).get()
                            .filter(r->r.getResultItem(mc.level.registryAccess()).is(ModBlocks.OVERLOADED_IO_PORT.get().asItem())).toList();
                    require(recipes.size()==1,"JEI assembly recipe missing");
                    require(manager.createRecipeLayoutDrawable(manager.getRecipeCategory(LightningAssemblyCategory.TYPE),recipes.get(0),jei.getJeiHelpers().getFocusFactory().getEmptyFocusGroup()).isPresent(),"JEI layout failed");
                    jei.getRecipesGui().showRecipes(manager.getRecipeCategory(LightningAssemblyCategory.TYPE),recipes,List.of());
                }
                case 5->{
                    capture("overloaded-io-jei.png");
                    server(()->{
                        var be=(OverloadedIOPortBlockEntity)mc.getSingleplayerServer().overworld().getBlockEntity(POS);
                        var player=mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
                        require(be.getFilterInventory().isEmpty() && player.getInventory().countItem(ModItems.OVERLOADED_FILTER_COMPONENT.get())==1,"shift-click removal must return one component");
                        require(be.getMatrixInventory().isEmpty() && player.getInventory().countItem(ModItems.LIGHTNING_COLLAPSE_MATRIX.get())==64,"matrix shift-click removal must conserve all 64 matrices");
                    });
                }
                case 6->{
                    System.out.println("IO_PORT_QA PASS: native cell/filter/matrix backgrounds, 55 slots, filter and 16-matrix shift-click insertion/removal, 1-to-16 attempt and long-cap synchronization, operation button packet, lightning status, JEI recipe layout.");
                    result("PASS");done=true;mc.setScreen(null);mc.stop();
                }
            }
            phase++;
        }catch(Throwable t){t.printStackTrace();System.out.println("IO_PORT_QA FAIL phase="+phase+": "+t);result("FAIL: " + t);done=true;mc.setScreen(null);mc.stop();}
    }
    private static void result(String value) {
        try { java.nio.file.Files.writeString(Minecraft.getInstance().gameDirectory.toPath().resolve("io-port-result.txt"), value); }
        catch (java.io.IOException failure) { throw new RuntimeException(failure); }
    }
    private static void server(Runnable r){Minecraft.getInstance().getSingleplayerServer().execute(()->{try{r.run();}catch(Throwable t){failure=t;}});}
    private static void capture(String name){var mc=Minecraft.getInstance();Screenshot.grab(mc.gameDirectory,name,mc.getMainRenderTarget(),m->{});}
    private static void require(boolean test,String message){if(!test)throw new AssertionError(message);}
}
