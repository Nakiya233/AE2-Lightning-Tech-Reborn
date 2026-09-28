package com.moakiee.ae2lt.celestweave;

import java.util.List;

import net.minecraft.tags.DamageTypeTags;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.TickEvent.PlayerTickEvent;

import com.moakiee.ae2lt.AE2LightningTech;
import com.moakiee.ae2lt.device.capability.DeviceCapability;
import com.moakiee.ae2lt.celestweave.module.MultidimensionalProtectionSubmodule;
import com.moakiee.ae2lt.celestweave.service.ArmorCapabilityCollector;
import com.moakiee.ae2lt.celestweave.service.ArmorShieldPayment;
import com.moakiee.ae2lt.registry.ModDamageTypes;

@EventBusSubscriber(modid = AE2LightningTech.MODID)
public final class CelestweaveArmorUndyingHandler {
    private static final String TAG_PROTECTED_TICK = "ae2lt.undying_protected_tick";
    // Untyped forced-death hooks may re-enter via setHealth/kill/loot in the same tick.
    // Typed damage callbacks instead deduplicate by their shared Object.

    private CelestweaveArmorUndyingHandler() {
    }

    // 1.20.1 has no LivingIncomingDamageEvent; LivingHurtEvent is its earliest hook (pre-armor).
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onIncomingFatalDamage(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.level().isClientSide()) {
            return;
        }
        float damage = event.getAmount();
        if (damage <= 0.0F
                || !isIncomingUndyingCandidate(event.getSource())
                || damage < player.getHealth() + player.getAbsorptionAmount()) {
            return;
        }
        // The old incoming last-stand shortcut must not price a huge hit as a flat revival.
        // Both replacement tiers carry a shield; bill the actual hit through that path first.
        // Direct death and post-mitigation fatal damage retain the last-stand hooks below.
        if (hasActiveLastStand(player)) {
            var result = CelestweaveArmorDamageHandler.onIncomingDamage(player, event.getSource(), damage);
            if (result.canceled()) event.setCanceled(true);
            else event.setAmount(result.amount());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onFatalDamage(LivingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.level().isClientSide()) {
            return;
        }
        float damage = event.getAmount();
        if (damage <= 0.0F || damage < player.getHealth() + player.getAbsorptionAmount()) {
            return;
        }
        // Forge uses different event objects for Hurt and Damage. A reopened shielded hit
        // must reuse its ordinary-damage credit instead of advancing the death combo.
        if (hasActiveLastStand(player)
                && CelestweaveArmorDamageHandler.onIncomingDamage(player, event.getSource(), damage).canceled()) {
            event.setAmount(0.0F);
            return;
        }
        long now = player.level().getGameTime();
        if (tryTrigger(player, now, event)) {
            event.setAmount(0.0F);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.level().isClientSide()) {
            return;
        }
        if (tryProtectForcedDeath(player)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent event) {
        // 1.20.1 fires one PlayerTickEvent per phase instead of the 1.21 Pre/Post pair;
        // both original handlers ran the same dead/dying rescue, so phases are not filtered.
        tryProtectDeadOrDying(event.player);
    }

    private static void tryProtectDeadOrDying(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer) || serverPlayer.level().isClientSide()) {
            return;
        }
        if (serverPlayer.isDeadOrDying() || serverPlayer.getHealth() <= 0.0F) {
            tryProtectForcedDeath(serverPlayer);
        }
    }

    public static boolean tryProtectForcedDeath(ServerPlayer player) {
        if (player == null || player.level().isClientSide()) {
            return false;
        }
        if (wasProtectedThisTick(player)) {
            restoreSurvivalState(player);
            return true;
        }
        long now = player.level().getGameTime();
        return tryTrigger(player, now, null);
    }

    public static boolean wasProtectedThisTick(LivingEntity entity) {
        if (!(entity instanceof ServerPlayer player)) {
            return false;
        }
        var data = player.getPersistentData();
        return data.contains(TAG_PROTECTED_TICK)
                && data.getLong(TAG_PROTECTED_TICK) == player.level().getGameTime();
    }

    /**
     * Protects a player before an externally inlined death routine commits loot or visual
     * side effects. Vanilla death normally never reaches these hooks because the earlier
     * damage/death guards cancel it; this closes routines that copy LivingEntity#die instead.
     */
    public static boolean protectBeforeDeathSideEffect(ServerPlayer player) {
        if (player == null || player.level().isClientSide()) {
            return false;
        }
        if (wasProtectedThisTick(player)) {
            restoreSurvivalState(player);
            return true;
        }
        if (!player.isDeadOrDying() && player.getHealth() > 0.0F) {
            return false;
        }
        return tryProtectForcedDeath(player);
    }

    private static boolean tryTrigger(ServerPlayer player, long now,
            Object damage) {
        for (var active : collectActiveLastStand(player)) {
            if (MultidimensionalProtectionSubmodule.ID.equals(active.submoduleId())) {
                recordProtectedTick(player, now);
                restoreSurvivalState(player);
                return true;
            }
            var quote = ShieldChargeWindow.quoteLastStand(active.armor(), now);
            if (!ArmorShieldPayment.pay(player, active.armor(), quote, damage)) {
                continue;
            }
            recordProtectedTick(player, now);
            restoreSurvivalState(player);
            return true;
        }
        return false;
    }

    private static boolean hasActiveLastStand(ServerPlayer player) {
        return !collectActiveLastStand(player).isEmpty();
    }

    private static boolean isIncomingUndyingCandidate(DamageSource source) {
        return source.is(DamageTypeTags.BYPASSES_ARMOR)
                || source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)
                || source.is(DamageTypeTags.BYPASSES_EFFECTS)
                || source.is(DamageTypeTags.BYPASSES_RESISTANCE)
                || source.is(DamageTypeTags.BYPASSES_ENCHANTMENTS)
                || source.is(DamageTypes.FELL_OUT_OF_WORLD)
                || source.is(DamageTypes.GENERIC_KILL)
                || source.is(DamageTypes.STARVE)
                || source.is(DamageTypes.MAGIC)
                || source.is(DamageTypes.INDIRECT_MAGIC)
                || source.is(DamageTypes.WITHER)
                || source.is(DamageTypes.WITHER_SKULL)
                || source.is(ModDamageTypes.ELECTROMAGNETIC);
    }

    private static void recordProtectedTick(ServerPlayer player, long now) {
        player.getPersistentData().putLong(TAG_PROTECTED_TICK, now);
    }

    private static void restoreSurvivalState(ServerPlayer player) {
        player.clearFire();
        player.setRemainingFireTicks(0);
        player.resetFallDistance();
        float targetHealth = Math.max(1.0F, player.getMaxHealth());
        if (player.getHealth() < targetHealth) {
            player.setHealth(targetHealth);
        }
        player.invulnerableTime = Math.max(player.invulnerableTime, 20);
        player.hurtTime = 0;
        player.hurtDuration = 0;
    }

    private static List<ActiveLastStand> collectActiveLastStand(ServerPlayer player) {
        return ArmorCapabilityCollector.collectPerInstalledStack(player).stream()
                .flatMap(active -> {
                    if (active.capability() instanceof DeviceCapability.LastStandTuning) {
                        return java.util.stream.Stream.of(new ActiveLastStand(
                                active.armor(),
                                active.submoduleId()));
                    }
                    return java.util.stream.Stream.empty();
                })
                .toList();
    }

    private record ActiveLastStand(
            ItemStack armor,
            String submoduleId) {
    }
}
