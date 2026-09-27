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
import mezz.jei.api.*;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
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
                    });
                }
                case 1->{
                    if (jei == null || mc.screen != null || !(mc.level.getBlockEntity(POS) instanceof OverloadedIOPortBlockEntity)) {
                        require(ticks < 1200, "Waiting for client IO block: " + mc.level.getBlockState(POS));
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
                    require(menu.slots.size()==53,"six inputs, six outputs, five upgrades and inventory");
                    capture("overloaded-io-empty.png");
                    var screen=(OverloadedIOPortScreen)mc.screen;
                    screen.mouseClicked(screen.getGuiLeft()+88,screen.getGuiTop()+25,0);
                }
                case 3->{
                    require(((OverloadedIOPortMenu)mc.player.containerMenu).operation==OperationMode.FILL,"operation button packet failed");
                    server(()->{
                        var be=(OverloadedIOPortBlockEntity)mc.getSingleplayerServer().overworld().getBlockEntity(POS);
                        be.getConfigManager().putSetting(Settings.OPERATION_MODE,OperationMode.EMPTY);
                        var stack=AEItems.ITEM_CELL_256K.stack();var storage=StorageCells.getCellInventory(stack,null);
                        storage.insert(AEItemKey.of(Items.STONE),1_000_000,Actionable.MODULATE,IActionSource.empty());storage.persist();
                        be.getInternalInventory().setItemDirect(0,stack);
                    });
                }
                case 4->{
                    require(((OverloadedIOPortMenu)mc.player.containerMenu).status==OverloadedIOPortBlockEntity.Status.BLOCKED,"blocked status missing");
                    capture("overloaded-io-blocked.png");
                    require(jei!=null,"JEI unavailable");
                    var manager=jei.getRecipeManager();var recipes=manager.createRecipeLookup(LightningAssemblyCategory.TYPE).get()
                            .filter(r->r.getResultItem(mc.level.registryAccess()).is(ModBlocks.OVERLOADED_IO_PORT.get().asItem())).toList();
                    require(recipes.size()==1,"JEI assembly recipe missing");
                    require(manager.createRecipeLayoutDrawable(manager.getRecipeCategory(LightningAssemblyCategory.TYPE),recipes.get(0),jei.getJeiHelpers().getFocusFactory().getEmptyFocusGroup()).isPresent(),"JEI layout failed");
                    jei.getRecipesGui().showRecipes(manager.getRecipeCategory(LightningAssemblyCategory.TYPE),recipes,List.of());
                }
                case 5->{
                    capture("overloaded-io-jei.png");
                    System.out.println("IO_PORT_QA PASS: native cell backgrounds, 53 slots, 1 type/t synchronization, operation button packet, blocked status, JEI recipe layout.");
                    done=true;mc.setScreen(null);mc.stop();
                }
            }
            phase++;
        }catch(Throwable t){t.printStackTrace();System.out.println("IO_PORT_QA FAIL phase="+phase+": "+t);done=true;mc.setScreen(null);mc.stop();}
    }
    private static void server(Runnable r){Minecraft.getInstance().getSingleplayerServer().execute(()->{try{r.run();}catch(Throwable t){failure=t;}});}
    private static void capture(String name){var mc=Minecraft.getInstance();Screenshot.grab(mc.gameDirectory,name,mc.getMainRenderTarget(),m->{});}
    private static void require(boolean test,String message){if(!test)throw new AssertionError(message);}
}
