package com.moakiee.ae2lt.celestweave.phase;

import java.util.UUID;
import com.mojang.authlib.GameProfile;
import com.moakiee.ae2lt.celestweave.*;
import com.moakiee.ae2lt.celestweave.module.*;
import com.moakiee.ae2lt.celestweave.service.ArmorCapabilityCollector;
import com.moakiee.ae2lt.celestweave.state.ArmorRuntimeRegistry;
import com.moakiee.ae2lt.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Real Forge movement hooks, ability handoff and private-vault lifecycle regressions. */
@GameTestHolder("ae2lt_phase")
@PrefixGameTestTemplate(false)
public final class PhaseFlightAndLockGameTests {
    @GameTest(templateNamespace="ae2lt_mining", template="empty")
    public static void nativeInventoryTickKeepsEveryEquippedProjectionWithoutRegeneration(GameTestHelper h) {
        var p=player(h);var armor=chest(h,p,false);
        try {
            p.setItemSlot(EquipmentSlot.HEAD,new ItemStack(ModItems.CELESTWEAVE_OCULUS.get()));
            p.setItemSlot(EquipmentSlot.LEGS,new ItemStack(ModItems.CELESTWEAVE_CONDUIT.get()));
            p.setItemSlot(EquipmentSlot.FEET,new ItemStack(ModItems.CELESTWEAVE_STRIDE.get()));
            PhaseLockService.tick(p);
            var slots=java.util.List.of(EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET);
            var projections=slots.stream().map(p::getItemBySlot).toList();
            long before=ArmorEnergyBuffer.read(armor,h.getLevel().registryAccess());
            for(int tick=0;tick<20;tick++) {
                p.getInventory().tick();
                for(int i=0;i<slots.size();i++)check(!projections.get(i).isEmpty()
                        &&p.getItemBySlot(slots.get(i))==projections.get(i),"Native armor compartment tick removed projection "+slots.get(i));
                PhaseLockService.tick(p);
            }
            check(ArmorEnergyBuffer.read(armor,h.getLevel().registryAccess())==before,"Equipped projections charged regeneration without being removed");
            for(int i=0;i<slots.size();i++) {
                int localIndex=slots.get(i).getIndex();
                p.getInventory().setItem(localIndex,projections.get(i).copy());
                p.getInventory().tick();
                check(p.getInventory().getItem(localIndex).isEmpty(),"Matching hotbar index kept an illicit projection");
                check(!projections.get(i).isEmpty(),"Removing hotbar projection affected real equipped projection");
            }
            h.succeed();
        } finally {cleanup(p,armor);}
    }

    @GameTest(templateNamespace="ae2lt_mining", template="empty")
    public static void lockedFlightSurvivesPublicBitAndVanillaAbilityPackets(GameTestHelper h) {
        var p = player(h); var armor = chest(h,p,false);
        try {
            p.getAbilities().mayfly=true; p.getAbilities().flying=true;
            PhaseFlightPlayerState.activate(p);
            p.getAbilities().flying=false;
            check(PhaseFlightPlayerState.isFlying(p), "Public flying field overwrote locked intent");
            var input=new net.minecraft.world.entity.player.Abilities(); input.flying=false;
            p.connection.handlePlayerAbilities(new ServerboundPlayerAbilitiesPacket(input));
            check(PhaseFlightPlayerState.isFlying(p), "Vanilla abilities packet overwrote locked intent");
            PhaseFlightPlayerState.applyFlightInput(p,false);
            check(!p.getAbilities().flying,"Explicit landing input failed");
            h.succeed();
        } finally { cleanup(p,armor); }
    }

    @GameTest(templateNamespace="ae2lt_mining", template="empty")
    public static void externalForcesBlockedButNativeTravelAndSelfMovementWork(GameTestHelper h) {
        var p=player(h); var start=p.position();
        try {
            PhaseFlightMovementGuard.updatePhaseLockProtection(p,true,true);
            p.setDeltaMovement(new Vec3(1,2,3)); p.move(MoverType.PISTON,new Vec3(1,0,0));
            check(p.position().equals(start)&&p.getDeltaMovement().equals(Vec3.ZERO),"External force changed locked player");
            PhaseFlightMovementGuard.runAsSelfMovement(p,()->p.move(MoverType.SELF,new Vec3(.25,0,0)));
            check(p.getX()>start.x,"Authorized movement was blocked");
            check(!PhaseFlightMovementGuard.isSelfMovementAuthorized(p),"Movement authorization leaked");
            h.succeed();
        } finally { cleanup(p,ItemStack.EMPTY); }
    }

    @GameTest(templateNamespace="ae2lt_mining", template="empty")
    public static void rawAndPacketTeleportsBlockedUntilPlayerAuthorizes(GameTestHelper h) {
        var p=player(h);var start=p.position();
        try {
            PhaseFlightMovementGuard.updatePhaseLockProtection(p,false,true);
            p.setPos(start.add(2,0,0));
            p.connection.teleport(start.x+2,start.y,start.z,0,0);
            check(p.position().equals(start),"External teleport bypassed the native hook");
            PhaseFlightMovementGuard.runAsPlayerPayloadHandler(p,()->p.connection.teleport(start.x+2,start.y,start.z,0,0));
            check(p.getX()==start.x+2,"Sender-authorized teleport was blocked");
            check(!PhaseFlightMovementGuard.isSelfTeleportAuthorized(p),"Payload authorization leaked");
            h.succeed();
        } finally { cleanup(p,ItemStack.EMPTY); }
    }

    @GameTest(templateNamespace="ae2lt_mining", template="empty")
    public static void foreignPayloadCannotMoveAnotherLockedPlayer(GameTestHelper h) {
        var p=player(h);var other=player(h);var start=other.position();
        try {
            PhaseFlightMovementGuard.updatePhaseLockProtection(other,false,true);
            PhaseFlightMovementGuard.runAsPlayerPayloadHandler(p,()->other.setPos(start.add(3,0,0)));
            check(other.position().equals(start),"A different sender bypassed teleport protection");
            h.succeed();
        } finally { cleanup(p,ItemStack.EMPTY);cleanup(other,ItemStack.EMPTY); }
    }

    @GameTest(templateNamespace="ae2lt_mining", template="empty")
    public static void forgeDimensionEntryIsBlockedBeforeWorldRemoval(GameTestHelper h) {
        var p=player(h);var source=p.serverLevel();var target=h.getLevel().getServer().getLevel(Level.NETHER);
        try {
            check(target!=null,"Nether fixture missing");
            PhaseFlightMovementGuard.updatePhaseLockProtection(p,false,true);
            check(p.changeDimension(target)==null,"External Forge dimension transition was not canceled");
            check(p.serverLevel()==source&&!p.isRemoved(),"Canceled transition detached player from original world");
            h.succeed();
        } finally { cleanup(p,ItemStack.EMPTY); }
    }

    @GameTest(templateNamespace="ae2lt_mining", template="empty")
    public static void phaseModeChangesRestoreCollisionAndPreserveGravityForGliding(GameTestHelper h) {
        var p=player(h);var armor=chest(h,p,true);
        try {
            p.getAbilities().flying=true;
            PhaseFlightSubmodule.INSTANCE.onActivated(p,Dist.DEDICATED_SERVER,armor);
            check(p.noPhysics&&p.isNoGravity(),"Hover phase did not activate");
            PhaseFlightSubmodule.INSTANCE.setConfig(armor,PhaseFlightSubmodule.PHASE_MODE_CONFIG_KEY,PhaseFlightMode.OFF.toTag());
            PhaseFlightSubmodule.INSTANCE.tickActive(p,Dist.DEDICATED_SERVER,armor);
            check(!p.noPhysics&&!p.isNoGravity(),"OFF left no-clip or no-gravity active");
            PhaseFlightSubmodule.INSTANCE.setConfig(armor,PhaseFlightSubmodule.PHASE_MODE_CONFIG_KEY,PhaseFlightMode.CREATIVE_FLIGHT_ONLY.toTag());
            PhaseFlightPlayerState.applyFlightInput(p,false);p.startFallFlying();
            PhaseFlightSubmodule.INSTANCE.tickActive(p,Dist.DEDICATED_SERVER,armor);
            check(!p.noPhysics,"Hover-only mode enabled elytra traversal");
            PhaseFlightSubmodule.INSTANCE.setConfig(armor,PhaseFlightSubmodule.PHASE_MODE_CONFIG_KEY,PhaseFlightMode.ALL.toTag());
            PhaseFlightSubmodule.INSTANCE.tickActive(p,Dist.DEDICATED_SERVER,armor);
            check(p.noPhysics&&!p.isNoGravity(),"Elytra traversal must preserve gravity");
            h.succeed();
        } finally { cleanup(p,armor); }
    }

    @GameTest(templateNamespace="ae2lt_mining", template="empty")
    public static void disablingPhaseInsideBlockFindsCollisionFreeExit(GameTestHelper h) {
        var p=player(h);var armor=chest(h,p,true);var pos=p.blockPosition();
        h.getLevel().setBlockAndUpdate(pos,Blocks.STONE.defaultBlockState());
        try {
            p.getAbilities().flying=true;
            PhaseFlightSubmodule.INSTANCE.onActivated(p,Dist.DEDICATED_SERVER,armor);
            check(p.noPhysics,"Phase fixture not active");
            PhaseFlightSubmodule.INSTANCE.onDeactivated(p,Dist.DEDICATED_SERVER,armor);
            check(h.getLevel().noCollision(p,p.getBoundingBox()),"Disabling phase left player trapped in stone");
            check(!p.noPhysics&&!p.isNoGravity(),"Escape failed to restore normal collision");
            h.succeed();
        } finally { cleanup(p,armor); }
    }

    @GameTest(templateNamespace="ae2lt_mining", template="empty")
    public static void removingFlightRevokesSurvivalPermission(GameTestHelper h) { flightRelease(h,false); }
    @GameTest(templateNamespace="ae2lt_mining", template="empty")
    public static void removingFlightPreservesOtherProvidersPermission(GameTestHelper h) { flightRelease(h,true); }
    private static void flightRelease(GameTestHelper h,boolean external) {
        var p=player(h);var armor=chest(h,p,true);
        try {
            PhaseFlightSubmodule.INSTANCE.onActivated(p,Dist.DEDICATED_SERVER,armor);
            PhaseFlightPlayerState.applyFlightInput(p,true);
            CelestweaveArmorState.setSubmoduleEnabled(armor,PhaseFlightSubmodule.INSTANCE,false);
            ArmorRuntimeRegistry.setSubmoduleRuntimeActive(CelestweaveArmorState.getArmorId(armor),"phase_flight",false);
            PhaseFlightSubmodule.INSTANCE.onDeactivated(p,Dist.DEDICATED_SERVER,armor);
            // The real probe runs on the following world tick, which expires capability snapshots.
            ArmorCapabilityCollector.clearCache(p);
            ForgeFlightPermissionHandoff.onPlayerTickStart(new TickEvent.PlayerTickEvent(TickEvent.Phase.START,p));
            check(!p.getAbilities().mayfly,"Handoff did not withdraw this provider's grant");
            if(external)p.getAbilities().mayfly=true;
            ForgeFlightPermissionHandoff.onPlayerTickEnd(new TickEvent.PlayerTickEvent(TickEvent.Phase.END,p));
            check(p.getAbilities().mayfly==external,"Handoff retained stale permission or removed external permission");
            if(!external)check(!p.getAbilities().flying&&!PhaseFlightPlayerState.isControlled(p),"Flight remained after its last source disappeared");
            h.succeed();
        } finally { cleanup(p,armor); }
    }

    @GameTest(templateNamespace="ae2lt_mining", template="empty")
    public static void vaultLocksTheOriginalAndRestoresOnceWhenDisabled(GameTestHelper h) {
        var p=player(h);var armor=chest(h,p,false);
        try {
            PhaseLockService.tick(p);
            check(PhaseLockService.getPrivateArmor(p,EquipmentSlot.CHEST)==armor,"Vault copied or lost authoritative armor");
            check(p.getItemBySlot(EquipmentSlot.CHEST).is(ModItems.PHASE_LOCK_PROJECTION.get()),"Projection missing");
            check(CelestweaveEquipmentAccess.findArmor(p,EquipmentSlot.CHEST)==armor,"Logical equipment no longer resolves private armor");
            CelestweaveArmorState.setSubmoduleEnabled(armor,PhaseLockSubmodule.INSTANCE,false);
            PhaseLockService.tick(p);
            check(!PhaseLockService.hasPrivateArmor(p)&&p.getItemBySlot(EquipmentSlot.CHEST)==armor,"Disable failed to restore exact armor");
            check(!PhaseLockService.release(p),"Second release duplicated armor");
            h.succeed();
        } finally { cleanup(p,armor); }
    }

    @GameTest(templateNamespace="ae2lt_mining", template="empty")
    public static void destroyedProjectionWithoutResourcesRestoresArmorWithoutDuplication(GameTestHelper h) {
        var p=player(h);var armor=chest(h,p,false);
        try {
            PhaseLockService.tick(p);p.setItemSlot(EquipmentSlot.CHEST,ItemStack.EMPTY);
            ArmorEnergyBuffer.write(armor,h.getLevel().registryAccess(),0);
            PhaseLockService.tick(p);
            check(!PhaseLockService.hasPrivateArmor(p)&&p.getItemBySlot(EquipmentSlot.CHEST)==armor,"Unpaid regeneration lost or retained private armor");
            h.succeed();
        } finally { cleanup(p,armor); }
    }

    @GameTest(templateNamespace="ae2lt_mining", template="empty")
    public static void vaultNbtRoundTripPreservesArmorAndOwner(GameTestHelper h) {
        var p=player(h);var armor=chest(h,p,false);
        try {
            armor.getOrCreateTag().putString("other_mod_data","retained");PhaseLockService.tick(p);
            var saved=PhaseArmorVaultSavedData.get(h.getLevel().getServer()).save(new CompoundTag());
            var loaded=PhaseArmorVaultSavedData.load(saved);
            var restored=loaded.getMutable(p.getUUID(),EquipmentSlot.CHEST);
            check(restored!=null&&ItemStack.isSameItemSameTags(armor,restored),"SavedData roundtrip lost armor NBT");
            check(loaded.getMutable(UUID.randomUUID(),EquipmentSlot.CHEST)==null,"Vault leaked to another owner");
            h.succeed();
        } finally { cleanup(p,armor); }
    }

    @GameTest(templateNamespace="ae2lt_mining", template="empty")
    public static void logoutClearsMovementRuntimeButKeepsAuthoritativeVault(GameTestHelper h) {
        var p=player(h);var armor=chest(h,p,true);
        try {
            PhaseLockService.tick(p);PhaseFlightPlayerState.activate(p);
            PhaseFlightMovementGuard.updatePhaseLockProtection(p,true,true);
            PhaseFlightSubmodule.applyTransientPhaseState(p);
            MinecraftForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(p));
            check(!p.noPhysics&&!p.isNoGravity(),"Logout left transient collision state");
            check(!PhaseFlightMovementGuard.blocksExternalForces(p)&&!PhaseFlightMovementGuard.blocksExternalTeleports(p),"Logout left movement locks");
            check(!PhaseFlightPlayerState.isControlled(p),"Logout left private flight intent active");
            check(PhaseLockService.getPrivateArmor(p,EquipmentSlot.LEGS)==armor,"Logout lost private armor");
            h.succeed();
        } finally { cleanup(p,armor); }
    }

    private static ServerPlayer player(GameTestHelper h) {
        var level=h.getLevel();var p=new ServerPlayer(level.getServer(),level,new GameProfile(UUID.randomUUID(),"PhaseQA"));
        p.connection=new ServerGamePacketListenerImpl(level.getServer(),new Connection(PacketFlow.SERVERBOUND),p);
        p.setGameMode(GameType.SURVIVAL);p.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(2,2,2))));
        return p;
    }
    private static ItemStack chest(GameTestHelper h,ServerPlayer p,boolean flight) {
        var armor=new ItemStack(ModItems.CELESTWEAVE_CORE.get());var registries=h.getLevel().registryAccess();
        CelestweaveArmorState.setSlot(armor,registries,CelestweaveArmorState.SLOT_CORE,new ItemStack(ModItems.ULTIMATE_OVERLOAD_CORE.get()));
        check(CelestweaveArmorState.installOneModule(armor,registries,new ItemStack(ModItems.CELESTWEAVE_SUBMODULE_PHASE_LOCK.get())),"Install lock failed");
        var id=CelestweaveArmorState.ensureArmorId(armor);
        ArmorRuntimeRegistry.setSubmoduleRuntimeActive(id,"phase_lock",true);
        ArmorEnergyBuffer.write(armor,registries,1_000_000);
        p.setItemSlot(EquipmentSlot.CHEST,armor);ArmorCapabilityCollector.clearCache(p);
        if(flight) {
            armor=new ItemStack(ModItems.CELESTWEAVE_CONDUIT.get());
            CelestweaveArmorState.setSlot(armor,registries,CelestweaveArmorState.SLOT_CORE,new ItemStack(ModItems.ULTIMATE_OVERLOAD_CORE.get()));
            check(CelestweaveArmorState.installOneModule(armor,registries,new ItemStack(ModItems.CELESTWEAVE_SUBMODULE_PHASE_FLIGHT.get())),"Install flight failed");
            CelestweaveArmorState.setSubmoduleEnabled(armor,PhaseFlightSubmodule.INSTANCE,true);
            ArmorRuntimeRegistry.setSubmoduleRuntimeActive(CelestweaveArmorState.ensureArmorId(armor),"phase_flight",true);
            ArmorEnergyBuffer.write(armor,registries,1_000_000);
            p.setItemSlot(EquipmentSlot.LEGS,armor);ArmorCapabilityCollector.clearCache(p);
        }
        return armor;
    }
    private static void cleanup(ServerPlayer p,ItemStack armor) {
        CelestweaveArmorState.setSubmoduleEnabled(CelestweaveEquipmentAccess.findArmor(p,EquipmentSlot.CHEST),PhaseLockSubmodule.INSTANCE,false);
        if(PhaseLockService.hasPrivateArmor(p))PhaseLockService.release(p);
        MinecraftForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(p));
        PhaseFlightPlayerState.endControl(p,false);
        p.getInventory().clearContent();
    }
    private static void check(boolean test,String message) {if(!test)throw new AssertionError(message);}
}
