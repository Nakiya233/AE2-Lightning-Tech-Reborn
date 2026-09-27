package com.moakiee.ae2lt.debug;

import com.moakiee.ae2lt.blockentity.FumoBlockEntity;
import com.moakiee.ae2lt.client.RainbowPigmeeColors;
import com.moakiee.ae2lt.client.RainbowPigmeeShader;
import com.moakiee.ae2lt.client.ctm.ConnectedTextureBakedModel;
import com.moakiee.ae2lt.registry.ModBlocks;
import com.moakiee.ae2lt.registry.ModFumos;
import com.moakiee.ae2lt.registry.ModItems;
import java.util.List;
import java.util.Set;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.client.model.data.ModelData;

/** Opt-in rendering and real JEI validation in its own disposable world. */
@JeiPlugin
@EventBusSubscriber(modid = "ae2lt", value = Dist.CLIENT)
public final class RainbowPigmeeClientProbe implements IModPlugin {
    private static IJeiRuntime runtime;
    private static int ticks, phase, firstColor, firstSheepColor, flowFrames;
    private static boolean done;
    private static volatile boolean ready;
    private static volatile Throwable serverFailure;

    @Override public ResourceLocation getPluginUid() { return new ResourceLocation("ae2lt:rainbow_qa"); }
    @Override public void onRuntimeAvailable(IJeiRuntime value) { runtime = value; }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!Boolean.getBoolean("ae2lt.rainbowClientProbe") || done) return;
        var mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        if (mc.player == null || mc.getSingleplayerServer() == null) return;
        ticks++;
        if (Boolean.getBoolean("ae2lt.rainbowFlowCapture") && mc.screen instanceof ArtPreview
                && ticks % 2 == 0 && flowFrames < 80) {
            capture(String.format(java.util.Locale.ROOT, "rainbow-flow-%03d.png", flowFrames++));
        }
        if (ticks % 40 != 0) return;
        try {
            if (serverFailure != null) throw new AssertionError(serverFailure);
            switch (phase) {
                case 0 -> {
                    mc.getWindow().setTitle("RAINBOW PIGMEE QA - disposable world");
                    mc.getWindow().setWindowed(1100, 800); mc.resizeDisplay();
                    mc.options.gamma().set(1.0); mc.options.fov().set(55);
                    mc.options.renderDistance().set(6); mc.options.hideGui = true;
                    mc.getSingleplayerServer().execute(() -> { try { fixture(); ready = true; } catch (Throwable t) { serverFailure = t; } });
                }
                case 1 -> {
                    if (!ready || runtime == null || !mc.level.getBlockState(new BlockPos(3,100,9)).is(ModFumos.RAINBOW_PIGMEE_FUMO.get())) {
                        require(ticks < 1600, "Client fixture/JEI timeout: ready=" + ready + ", jei=" + (runtime != null) + ", client=" + mc.level.getBlockState(new BlockPos(3,100,9)) + ", pos=" + mc.player.position()); return;
                    }
                    if (Boolean.getBoolean("ae2lt.rainbowManualTest")) {
                        mc.getWindow().setTitle("Minecraft AE2LT Alpha - Rainbow Pigmee Manual Test");
                        mc.options.hideGui = false;
                        mc.setScreen(null);
                        done = true;
                        System.out.println("RAINBOW_MANUAL_READY: world open; four Pigmee variants placed; test items in hotbar; automatic probe stopped.");
                        return;
                    }
                    checkSlabs(); checkJei();
                    require(RainbowPigmeeShader.isLoaded(), "Per-pixel rainbow shader did not load");
                    firstColor = RainbowPigmeeColors.currentColor();
                    firstSheepColor = RainbowPigmeeColors.sheepColor();
                    require(mc.getItemColors().getColor(new ItemStack(ModFumos.PIGMEE_FUMO_ITEM.get()),0) == -1,
                            "Ordinary Pigmee must retain its original colours");
                    require(mc.getItemColors().getColor(namedPigmee(),0) == RainbowPigmeeColors.sheepColor(),
                            "Named Pigmee item must use vanilla sheep colours");
                    var namedPos = new BlockPos(5,100,9);
                    require(mc.getBlockColors().getColor(mc.level.getBlockState(namedPos),mc.level,namedPos,0)
                            == RainbowPigmeeColors.sheepColor(), "Placed Pigmee name/tint did not reach the client");
                    require(mc.getItemRenderer().getModel(new ItemStack(ModFumos.RAINBOW_PIGMEE_FUMO_ITEM.get()),
                            mc.level,mc.player,0).isCustomRenderer(), "Rainbow item must use flowing surface renderer");
                    require(mc.getItemRenderer().getModel(new ItemStack(ModItems.DYE_BASE.get()), mc.level,mc.player,0)
                            .getParticleIcon().contents().name().equals(new ResourceLocation("ae2lt:item/dye_base")),
                            "Dye base is still using a placeholder sprite");

                }
                case 2 -> {
                    capture("rainbow-pigmee-slabs.png");
                    move(4.5, 102.0, 4.0, 0, 15);
                }
                case 3 -> {
                    require(firstColor != RainbowPigmeeColors.currentColor(), "Rainbow colour is frozen");
                    require(firstSheepColor != RainbowPigmeeColors.sheepColor(), "Named sheep colour is frozen");
                    require(mc.getItemColors().getColor(new ItemStack(ModFumos.RAINBOW_PIGMEE_FUMO_ITEM.get()),0) == RainbowPigmeeColors.currentColor(), "Item tint missing");
                    capture("rainbow-pigmee-close.png");
                    mc.options.hideGui = false;
                    var focus = runtime.getJeiHelpers().getFocusFactory().createFocus(RecipeIngredientRole.OUTPUT,
                            VanillaTypes.ITEM_STACK, new ItemStack(ModFumos.RAINBOW_PIGMEE_FUMO_ITEM.get()));
                    var info = runtime.getRecipeManager().createRecipeLookup(RecipeTypes.INFORMATION).limitFocus(List.of(focus)).get().toList();
                    runtime.getRecipesGui().showRecipes(runtime.getRecipeManager().getRecipeCategory(RecipeTypes.INFORMATION), info, List.of(focus));
                }
                case 4 -> {
                    capture("rainbow-pigmee-jei-hint.png");
                    var recipes = runtime.getRecipeManager().createRecipeLookup(RecipeTypes.CRAFTING).get()
                            .filter(h -> h.getId().getNamespace().equals("ae2lt") && h.getId().getPath().startsWith("rainbow_dye/")).toList();
                    runtime.getRecipesGui().showRecipes(runtime.getRecipeManager().getRecipeCategory(RecipeTypes.CRAFTING), recipes, List.of());
                }
                case 5 -> {
                    capture("rainbow-pigmee-jei-dyes.png");
                    mc.setScreen(new ArtPreview());
                }
                case 6 -> capture("rainbow-pigmee-art-items-a.png");
                case 7 -> {
                    if (Boolean.getBoolean("ae2lt.rainbowFlowCapture") && flowFrames < 80) return;
                    capture("rainbow-pigmee-art-items-b.png");
                    mc.setScreen(null);
                    mc.options.hideGui = false;
                    mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
                    mc.player.getInventory().selected = 0;
                }
                case 8 -> {
                    require(mc.player.getMainHandItem().is(ModFumos.RAINBOW_PIGMEE_FUMO_ITEM.get()), "Rainbow hand fixture missing");
                    capture("rainbow-pigmee-first-person.png");
                    mc.player.getInventory().selected = 2;
                }
                case 9 -> {
                    require(mc.player.getMainHandItem().is(ModFumos.HYPERDIMENSIONAL_PIGMEE_FUMO_ITEM.get()), "Hyper hand fixture missing");
                    capture("hyperdimensional-pigmee-first-person.png");
                    mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT);
                }
                case 10 -> {
                    capture("hyperdimensional-pigmee-third-person.png");
                    mc.player.getInventory().selected = 0;
                }
                case 11 -> {
                    capture("rainbow-pigmee-third-person.png");
                    System.out.println("RAINBOW_QA PASS: named sheep block/item colours; rainbow and portal block, GUI, first/third-person item rendering; dye base sprite; 99 slab states; all 16 JEI layouts and hint-only acquisition.");
                    done = true; mc.stop();
                }
            }
            phase++;
        } catch (Throwable t) {
            capture("rainbow-failure.png"); t.printStackTrace(); System.out.println("RAINBOW_QA FAIL phase=" + phase + ": " + t);
            done = true;
            if (!Boolean.getBoolean("ae2lt.rainbowManualTest")) mc.stop();
        }
    }

    private static void fixture() {
        var mc = Minecraft.getInstance(); var server = mc.getSingleplayerServer(); var level = server.overworld();
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        level.setDayTime(6000); level.setWeatherParameters(6000,0,false,false);
        for (var pos : BlockPos.betweenClosed(-2,99,-2,23,104,13)) level.setBlockAndUpdate(pos, pos.getY()==99 ? Blocks.WHITE_CONCRETE.defaultBlockState() : Blocks.AIR.defaultBlockState());
        for (var color : DyeColor.values()) {
            int x=color.getId();
            for (int row=0;row<3;row++) for (int framed=0;framed<2;framed++) {
                var block=(framed==0?ModBlocks.PIGMEE_BUILDING_SLABS:ModBlocks.PIGMEE_FRAMED_BUILDING_SLABS).get(color).get();
                level.setBlockAndUpdate(new BlockPos(x,100,row*2+framed),block.defaultBlockState().setValue(SlabBlock.TYPE,SlabType.values()[row]));
            }
        }
        level.setBlockAndUpdate(new BlockPos(1,100,9),ModFumos.PIGMEE_FUMO.get().defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(3,100,9),ModFumos.RAINBOW_PIGMEE_FUMO.get().defaultBlockState());
        ((FumoBlockEntity)level.getBlockEntity(new BlockPos(3,100,9))).toggleSpinning();
        level.setBlockAndUpdate(new BlockPos(5,100,9),ModFumos.PIGMEE_FUMO.get().defaultBlockState());
        ((FumoBlockEntity)level.getBlockEntity(new BlockPos(5,100,9))).setCustomName(Component.literal("jeb_"));
        level.setBlockAndUpdate(new BlockPos(7,100,9),ModFumos.HYPERDIMENSIONAL_PIGMEE_FUMO.get().defaultBlockState());
        var slab=ModBlocks.PIGMEE_FRAMED_BUILDING_SLABS.get(DyeColor.WHITE).get();
        level.setBlockAndUpdate(new BlockPos(20,100,0),slab.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(21,100,0),slab.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(20,100,-1),slab.defaultBlockState().setValue(SlabBlock.TYPE,SlabType.TOP));
        var player=server.getPlayerList().getPlayer(mc.player.getUUID()); player.setGameMode(GameType.CREATIVE);
        player.getAbilities().flying = true; player.onUpdateAbilities();
        player.getInventory().clearContent();
        player.getInventory().setItem(0,new ItemStack(ModFumos.RAINBOW_PIGMEE_FUMO_ITEM.get()));
        player.getInventory().setItem(1,new ItemStack(ModItems.DYE_BASE.get(),4));
        player.getInventory().setItem(2,new ItemStack(ModFumos.HYPERDIMENSIONAL_PIGMEE_FUMO_ITEM.get()));
        if (Boolean.getBoolean("ae2lt.rainbowManualTest")) {
            player.getInventory().setItem(0,namedPigmee());
            player.getInventory().setItem(1,new ItemStack(ModFumos.RAINBOW_PIGMEE_FUMO_ITEM.get()));
            player.getInventory().setItem(2,new ItemStack(ModItems.DYE_BASE.get(),64));
            player.getInventory().setItem(3,new ItemStack(ModFumos.PIGMEE_FUMO_ITEM.get()));
            player.getInventory().setItem(4,new ItemStack(ModFumos.HYPERDIMENSIONAL_PIGMEE_FUMO_ITEM.get()));
            player.getInventory().setItem(5,new ItemStack(Blocks.ANVIL));
            player.getInventory().setItem(6,new ItemStack(Blocks.LIGHTNING_ROD));
            player.teleportTo(level,4.5,101.5,4.0,Set.of(),0,12);
        } else {
            player.teleportTo(level,8,109,20,Set.of(),180,35);
        }
    }

    private static void checkSlabs() {
        var mc=Minecraft.getInstance(); var random=RandomSource.create(0);
        var blocks=new java.util.ArrayList<Block>(); blocks.add(ModBlocks.PIGMEE_BUILDING_SLAB.get());
        for(var color:DyeColor.values()) {blocks.add(ModBlocks.PIGMEE_BUILDING_SLABS.get(color).get());blocks.add(ModBlocks.PIGMEE_FRAMED_BUILDING_SLABS.get(color).get());}
        for(var block:blocks) {
            var itemModel = mc.getItemRenderer().getModel(new ItemStack(block), mc.level, mc.player, 0);
            require(!itemModel.getParticleIcon().contents().name().getPath().contains("missing"), "Missing slab item model");
        }
        for(var block:blocks) for(var type:SlabType.values()) {
            var state=block.defaultBlockState().setValue(SlabBlock.TYPE,type);var model=mc.getBlockRenderer().getBlockModel(state);
            require(!model.getParticleIcon().contents().name().getPath().contains("missing"),"Missing slab texture");
            var quads=new java.util.ArrayList<>(model.getQuads(state,null,random,ModelData.EMPTY,null));
            for(var face:Direction.values())quads.addAll(model.getQuads(state,face,random,ModelData.EMPTY,null));
            require(!quads.isEmpty(),"Empty slab model");
            double min=type==SlabType.TOP?.5:0,max=type==SlabType.BOTTOM?.5:1;
            for(var q:quads) {var v=q.getVertices();int stride=v.length/4;for(int k=0;k<4;k++) {float y=Float.intBitsToFloat(v[k*stride+1]);require(y>=min-.0001&&y<=max+.0001,"Slab vertex outside collision shape");}}
            if(model instanceof ConnectedTextureBakedModel && type!=SlabType.DOUBLE) {
                require(model.getQuads(state,type==SlabType.BOTTOM?Direction.UP:Direction.DOWN,random,ModelData.EMPTY,null).isEmpty(),"Inset face incorrectly neighbour-culled");
                require(!model.getQuads(state,null,random,ModelData.EMPTY,null).isEmpty(),"Inset face missing");
            }
        }
        var pos=new BlockPos(20,100,0);var state=mc.level.getBlockState(pos);var model=mc.getBlockRenderer().getBlockModel(state);
        var data=model.getModelData(mc.level,pos,state,ModelData.EMPTY);var conn=data.get(ConnectedTextureBakedModel.CONNECTION);
        require(conn!=null&&Integer.bitCount(conn.edges(Direction.UP))==1&&!conn.culled(Direction.UP),"Different slab heights must not join");
    }

    private static void checkJei() {
        var mc=Minecraft.getInstance();var manager=runtime.getRecipeManager();
        var recipes=manager.createRecipeLookup(RecipeTypes.CRAFTING).get().filter(h->h.getId().getNamespace().equals("ae2lt")&&h.getId().getPath().startsWith("rainbow_dye/")).toList();
        require(recipes.size()==16,"JEI is missing dyes: "+recipes.size());
        for(var recipe:recipes) require(manager.createRecipeLayoutDrawable(manager.getRecipeCategory(RecipeTypes.CRAFTING),recipe,
                runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup()).isPresent(),"JEI cannot lay out dye "+recipe.getId());
        var focus=runtime.getJeiHelpers().getFocusFactory().createFocus(RecipeIngredientRole.OUTPUT,VanillaTypes.ITEM_STACK,new ItemStack(ModFumos.RAINBOW_PIGMEE_FUMO_ITEM.get()));
        var info=manager.createRecipeLookup(RecipeTypes.INFORMATION).limitFocus(List.of(focus)).get().toList();
        require(!info.isEmpty(),"JEI hint missing");
        var text=info.stream().flatMap(i->i.getDescription().stream()).map(net.minecraft.network.chat.FormattedText::getString).collect(java.util.stream.Collectors.joining());
        require(!text.contains("jeb_")&&!text.contains("Jeb_"),"JEI exposes the exact easter egg name");
        require(manager.createRecipeLookup(RecipeTypes.CRAFTING).get().noneMatch(h->h.getResultItem(mc.level.registryAccess()).is(ModFumos.RAINBOW_PIGMEE_FUMO_ITEM.get())),"Acquisition must not be a crafting recipe");
    }

    private static void move(double x,double y,double z,float yaw,float pitch) {var mc=Minecraft.getInstance();mc.getSingleplayerServer().execute(()->{var p=mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());p.teleportTo(p.serverLevel(),x,y,z,Set.of(),yaw,pitch);});}
    private static ItemStack namedPigmee() {
        var stack = new ItemStack(ModFumos.PIGMEE_FUMO_ITEM.get());
        stack.setHoverName(Component.literal("jeb_"));
        return stack;
    }
    private static final class ArtPreview extends Screen {
        ArtPreview() { super(Component.literal("Pigmee art preview")); }
        @Override public boolean isPauseScreen() { return false; }
        @Override public void render(GuiGraphics graphics, int x, int y, float partialTick) {
            graphics.fill(0,0,width,height,0xff282b34);
            var items = List.of(new ItemStack(ModFumos.PIGMEE_FUMO_ITEM.get()), namedPigmee(),
                    new ItemStack(ModFumos.RAINBOW_PIGMEE_FUMO_ITEM.get()),
                    new ItemStack(ModFumos.HYPERDIMENSIONAL_PIGMEE_FUMO_ITEM.get()), new ItemStack(ModItems.DYE_BASE.get()));
            String[] labels = {"Original", "jeb_", "Rainbow", "Hyper", "Dye base"};
            for (int i = 0; i < items.size(); i++) {
                int cx = width * (i + 1) / 6;
                graphics.drawCenteredString(font,labels[i],cx,height/2-45,0xffffff);
                graphics.pose().pushPose();
                graphics.pose().translate(cx-24,height/2-24,0); graphics.pose().scale(3,3,3);
                graphics.renderItem(items.get(i),0,0); graphics.pose().popPose();
                graphics.renderItem(items.get(i),cx-8,height/2+42);
            }
            graphics.drawCenteredString(font,"Native item render - enlarged and inventory size",width/2,24,0xffffff);
        }
    }
    private static void capture(String name) {var mc=Minecraft.getInstance();Screenshot.grab(mc.gameDirectory,name,mc.getMainRenderTarget(),text->{});}
    private static void require(boolean value,String message) {if(!value)throw new AssertionError(message);}
}
