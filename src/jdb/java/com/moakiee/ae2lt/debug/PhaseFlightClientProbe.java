package com.moakiee.ae2lt.debug;

import java.util.Set;
import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.StorageCells;
import appeng.blockentity.storage.DriveBlockEntity;
import appeng.core.definitions.AEBlocks;
import com.moakiee.ae2lt.celestweave.*;
import com.moakiee.ae2lt.celestweave.module.*;
import com.moakiee.ae2lt.celestweave.phase.*;
import com.moakiee.ae2lt.device.network.ArmorNetworkBinding;
import com.moakiee.ae2lt.me.key.LightningKey;
import com.moakiee.ae2lt.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

/** Actual keyboard input, native movement packets and phase-lock synchronization in a disposable world. */
@EventBusSubscriber(modid="ae2lt",value=Dist.CLIENT)
public final class PhaseFlightClientProbe {
    private static int phase, wait, ticks;
    private static volatile boolean ready;
    private static volatile Throwable failure;
    private static boolean done;
    private static double hoverY;
    private static Vec3 stoppedAt;
    private static final BlockPos AP=new BlockPos(4,100,4);
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if(event.phase!=TickEvent.Phase.END||!Boolean.getBoolean("ae2lt.phaseClientProbe")||done)return;
        var mc=Minecraft.getInstance();mc.options.pauseOnLostFocus=false;
        if(mc.player==null||mc.getSingleplayerServer()==null)return;
        try {
            if (ticks % 100 == 0) {
                System.out.println("PHASE_CLIENT_PROGRESS phase=" + phase + " ready=" + ready
                        + " active=" + CelestweaveArmorState.isAnyClientFlightControlActive()
                        + " pos=" + mc.player.position() + " screen=" + mc.screen
                        + " legs=" + mc.player.getItemBySlot(EquipmentSlot.LEGS));
                if (ready) server(() -> {
                    var armor=CelestweaveEquipmentAccess.findArmor(player(),EquipmentSlot.LEGS);
                    var bound=ArmorNetworkBinding.INSTANCE.resolve(armor,player());
                    System.out.println("PHASE_SERVER_PROGRESS active=" + CelestweaveArmorState.isSubmoduleRuntimeActive(armor,"phase_flight")
                            + " energy=" + ArmorEnergyBuffer.read(armor,player().level().registryAccess())
                            + " binding=" + bound + " hv=" + (bound.success() ? bound.grid().getStorageService().getInventory()
                            .extract(LightningKey.HIGH_VOLTAGE,Long.MAX_VALUE,Actionable.SIMULATE,IActionSource.empty()) : -1));
                });
            }
            check(++ticks<2400,"Client phase test timed out at phase="+phase);
            if(failure!=null)throw new AssertionError("Server check failed",failure);
            if(wait-->0)return;
            switch(phase) {
                case 0 -> {
                    mc.getWindow().setTitle("PHASE FLIGHT QA - disposable world");
                    mc.options.autoJump().set(false);mc.options.hideGui=false;
                    server(()->fixture());wait=60;
                }
                case 1 -> {
                    if(!ready||mc.screen!=null||!CelestweaveArmorState.isAnyClientFlightControlActive())return;
                    check(mc.player.getItemBySlot(EquipmentSlot.LEGS).is(ModItems.PHASE_LOCK_PROJECTION_LEGS.get()),"Client did not receive locked leggings projection");
                    check(mc.player.getAbilities().mayfly&&mc.player.onGround(),"Equipped survival flight not ready");
                    mc.options.keyJump.setDown(true);wait=1;
                }
                case 2 -> {mc.options.keyJump.setDown(false);wait=1;}
                case 3 -> {mc.options.keyJump.setDown(true);wait=1;}
                case 4 -> {mc.options.keyJump.setDown(false);wait=30;}
                case 5 -> {
                    check(PhaseFlightPlayerState.isFlying(mc.player)&&mc.player.noPhysics,"Double jump did not start phase flight");
                    server(()->{check(PhaseFlightPlayerState.isFlying(player())&&player().noPhysics,"Takeoff did not reach server");
                        player().getAbilities().flying=false;});
                    mc.options.keyUp.setDown(true);wait=35;
                }
                case 6 -> {
                    mc.options.keyUp.setDown(false);
                    check(mc.player.getZ() < -.8,"Client did not traverse the solid wall: "+mc.player.position());
                    check(PhaseFlightPlayerState.isFlying(mc.player),"External ability write canceled flight");
                    mc.options.keyShift.setDown(true);mc.options.keyJump.setDown(true);wait=8;
                }
                case 7 -> {hoverY=mc.player.getY();stoppedAt=mc.player.position();wait=20;}
                case 8 -> {
                    check(Math.abs(mc.player.getY()-hoverY)<.01&&mc.player.position().distanceToSqr(stoppedAt)<.001,"Shift+space hover drifted");
                    check(mc.player.getPose()==Pose.CROUCHING,"Hover chord did not expose crouch pose");
                    mc.options.keyJump.setDown(false);mc.options.keyShift.setDown(false);
                    server(()->{var armor=CelestweaveEquipmentAccess.findArmor(player(),EquipmentSlot.LEGS);
                        PhaseFlightSubmodule.INSTANCE.setConfig(armor,PhaseFlightSubmodule.PHASE_MODE_CONFIG_KEY,PhaseFlightMode.OFF.toTag());
                        CelestweaveArmorState.syncFlightSettingsToClient(player());});wait=25;
                }
                case 9 -> {
                    check(!mc.player.noPhysics&&!mc.player.isNoGravity(),"Phase OFF did not restore client collision");
                    mc.options.keyShift.setDown(true);wait=40;
                }
                case 10 -> {
                    check(mc.player.onGround()&&mc.player.getPose()==Pose.CROUCHING,"Ground crouch contact/pose unstable");
                    mc.options.keyShift.setDown(false);
                    server(()->changeDimension(net.minecraft.world.level.Level.NETHER));wait=60;
                }
                case 11 -> {
                    check(mc.level.dimension().equals(net.minecraft.world.level.Level.NETHER)
                            &&PhaseFlightPlayerState.isControlled(mc.player),"Dimension transfer lost flight control synchronization");
                    check(!mc.player.noPhysics,"Phase OFF was lost on dimension change");
                    server(()->changeDimension(net.minecraft.world.level.Level.OVERWORLD));wait=60;
                }
                case 12 -> {
                    check(mc.level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD),"Return dimension transfer failed");
                    server(()->{var armor=CelestweaveEquipmentAccess.findArmor(player(),EquipmentSlot.LEGS);
                        CelestweaveArmorState.setSubmoduleEnabled(armor,PhaseFlightSubmodule.INSTANCE,false);});wait=30;
                }
                case 13 -> {
                    check(!mc.player.getAbilities().mayfly&&!PhaseFlightPlayerState.isControlled(mc.player),"Removing last flight source left client flight enabled");
                    mc.options.keyShift.setDown(false);
                    server(()->{
                        check(!player().getAbilities().mayfly&&!PhaseFlightPlayerState.isControlled(player()),"Server flight permission survived disable");
                        var armor=CelestweaveEquipmentAccess.findArmor(player(),EquipmentSlot.CHEST);
                        CelestweaveArmorState.setSubmoduleEnabled(armor,PhaseLockSubmodule.INSTANCE,false);
                    });wait=25;
                }
                case 14 -> {
                    check(mc.player.getItemBySlot(EquipmentSlot.CHEST).is(ModItems.CELESTWEAVE_CORE.get())
                            &&mc.player.getItemBySlot(EquipmentSlot.LEGS).is(ModItems.CELESTWEAVE_CONDUIT.get()),"Unlock did not restore both real armor slots");
                    System.out.println("PHASE_CLIENT_QA PASS: real double-jump takeoff; client/server wall traversal; locked ability intent; shift+space hover; ground crouch; phase OFF; dimension round-trip synchronization; last-source removal; private armor unlock.");
                    done=true;releaseKeys();mc.setScreen(null);mc.stop();
                }
            }
            phase++;
        } catch(Throwable t) {
            t.printStackTrace();System.out.println("PHASE_CLIENT_QA FAIL phase="+phase+": "+t);
            done=true;releaseKeys();mc.setScreen(null);mc.stop();
        }
    }
    private static void changeDimension(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) {
        var p=player();var target=p.getServer().getLevel(dimension);
        for(var pos:BlockPos.betweenClosed(-2,99,-2,2,104,2))target.setBlockAndUpdate(pos,pos.getY()==99?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState());
        PhaseFlightMovementGuard.runAsPlayerPayloadHandler(p,()->p.teleportTo(target,.5,100,.5,Set.of(),0,0));
    }
    private static void fixture() {
        var p=player();var level=p.serverLevel();
        for(var pos:BlockPos.betweenClosed(-10,99,-15,10,107,10))
            level.setBlockAndUpdate(pos,pos.getY()==99?Blocks.SMOOTH_STONE.defaultBlockState():Blocks.AIR.defaultBlockState());
        for(var pos:BlockPos.betweenClosed(-2,100,0,2,105,0))level.setBlockAndUpdate(pos,Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(AP,AEBlocks.WIRELESS_ACCESS_POINT.block().defaultBlockState());
        // AE2 1.20.1's access point connects exclusively through its back (default south).
        level.setBlockAndUpdate(AP.south(),AEBlocks.CREATIVE_ENERGY_CELL.block().defaultBlockState());
        level.setBlockAndUpdate(AP.south().east(),AEBlocks.DRIVE.block().defaultBlockState());
        var cell=new ItemStack(ModItems.LIGHTNING_STORAGE_COMPONENT_V.get());
        var storage=StorageCells.getCellInventory(cell,null);
        storage.insert(LightningKey.HIGH_VOLTAGE,50_000,Actionable.MODULATE,IActionSource.empty());
        storage.insert(LightningKey.EXTREME_HIGH_VOLTAGE,5_000,Actionable.MODULATE,IActionSource.empty());storage.persist();
        ((DriveBlockEntity)level.getBlockEntity(AP.south().east())).getInternalInventory().setItemDirect(0,cell);
        if(PhaseLockService.hasPrivateArmor(p)) {
            CelestweaveArmorState.setSubmoduleEnabled(CelestweaveEquipmentAccess.findArmor(p,EquipmentSlot.CHEST),PhaseLockSubmodule.INSTANCE,false);
            PhaseLockService.release(p);
        }
        p.getInventory().clearContent();p.setGameMode(GameType.SURVIVAL);
        p.teleportTo(level,.5,100,3.5,Set.of(),180,0);
        equip(p,EquipmentSlot.CHEST,new ItemStack(ModItems.CELESTWEAVE_CORE.get()),new ItemStack(ModItems.CELESTWEAVE_SUBMODULE_PHASE_LOCK.get()));
        equip(p,EquipmentSlot.LEGS,new ItemStack(ModItems.CELESTWEAVE_CONDUIT.get()),new ItemStack(ModItems.CELESTWEAVE_SUBMODULE_PHASE_FLIGHT.get()));
        ready=true;
    }
    private static void equip(ServerPlayer p,EquipmentSlot slot,ItemStack armor,ItemStack module) {
        var registry=p.level().registryAccess();
        CelestweaveArmorState.setSlot(armor,registry,CelestweaveArmorState.SLOT_CORE,new ItemStack(ModItems.ULTIMATE_OVERLOAD_CORE.get()));
        check(CelestweaveArmorState.installOneModule(armor,registry,new ItemStack(ModItems.ENERGY_MODULE_T3.get())),"Energy module install failed");
        check(CelestweaveArmorState.installOneModule(armor,registry,module),"Flight/lock module install failed");
        CelestweaveArmorState.setSubmoduleEnabled(armor,PhaseFlightSubmodule.INSTANCE,true);
        ArmorEnergyBuffer.write(armor,registry,100_000_000);
        ArmorNetworkBinding.INSTANCE.bind(armor,GlobalPos.of(p.level().dimension(),AP));
        p.setItemSlot(slot,armor);
    }
    private static void releaseKeys(){var options=Minecraft.getInstance().options;options.keyJump.setDown(false);options.keyShift.setDown(false);options.keyUp.setDown(false);}
    private static ServerPlayer player(){var mc=Minecraft.getInstance();return mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());}
    private static void server(Runnable action){Minecraft.getInstance().getSingleplayerServer().execute(()->{try{action.run();}catch(Throwable t){failure=t;}});}
    private static void check(boolean test,String message){if(!test)throw new AssertionError(message);}
}
