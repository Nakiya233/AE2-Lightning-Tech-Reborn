package com.moakiee.ae2lt.celestweave.service;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import com.moakiee.ae2lt.celestweave.ShieldChargeWindow;
import com.moakiee.ae2lt.celestweave.CelestweaveArmorState;

/** Pays a shield/last-stand quote atomically, recording window credit only on success. */
public final class ArmorShieldPayment {
    // Incoming and Pre callbacks share the same Object. Weak keys release completed
    // attacks and avoid counting an uncancel/re-entry as another overload combo trigger.
    private static final Map<Object, Set<UUID>> PAID_DAMAGE = new WeakHashMap<>();

    private ArmorShieldPayment() {
    }

    public static boolean pay(ServerPlayer player, ItemStack armor, ShieldChargeWindow.Quote quote) {
        return pay(player, armor, quote, null);
    }

    public static boolean pay(ServerPlayer player, ItemStack armor, ShieldChargeWindow.Quote quote,
            Object damage) {
        var armorId = CelestweaveArmorState.ensureArmorId(armor);
        if (damage != null && PAID_DAMAGE.getOrDefault(damage, Set.of()).contains(armorId)) {
            return true;
        }
        var lightningCost = ArmorLightningService.LightningCost.ehv(quote.ehvCost());
        if (!ArmorLightningService.hasCost(player, armor, lightningCost)) {
            ArmorResourceFeedback.noExtremeHighVoltage(player);
            return false;
        }
        var payment = ArmorEnergyService.consumeActiveCostPayment(player, armor, quote.feCost());
        if (!payment.paid()) {
            ArmorResourceFeedback.noFe(player);
            return false;
        }
        if (!ArmorLightningService.consume(player, armor, lightningCost)) {
            payment.refund();
            ArmorResourceFeedback.noExtremeHighVoltage(player);
            return false;
        }
        ShieldChargeWindow.record(armor, quote);
        if (damage != null) {
            PAID_DAMAGE.computeIfAbsent(damage, ignored -> new HashSet<>()).add(armorId);
        }
        return true;
    }
}
