package com.moakiee.ae2lt.celestweave;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import com.moakiee.ae2lt.celestweave.module.CelestweaveArmorSubmodule;
import com.moakiee.ae2lt.celestweave.module.OverloadProtectionSubmodule;
import com.moakiee.ae2lt.celestweave.module.ResistanceSubmodule;

/** Shared high-water billing in fixed 20-tick windows; only last stand advances the overload combo. */
public final class ShieldChargeWindow {
    public static final int WINDOW_TICKS = 20;
    public static final long OVERLOAD_MAX_FE = 20_000_000_000L;
    public static final long OVERLOAD_MAX_EHV = 16_384L;
    private static final int MAX_COMBO = 16;
    private static final String TAG_UNTIL = "ProtectionChargeUntil";
    private static final String TAG_FE = "ProtectionChargeFe";
    private static final String TAG_EHV = "ProtectionChargeEhv";
    // The previous counter included damage hits; never inherit that as death history.
    private static final String TAG_COMBO = "ProtectionDeathCombo";
    private static final String TAG_PEAK_FE = "ProtectionPeakFe";
    private static final String TAG_PEAK_EHV = "ProtectionPeakEhv";

    public enum Profile {
        PHASE("phase_shield", 1_024D, 20_480_000L, 2_048L),
        OVERLOAD("overload_protection", Float.MAX_VALUE, OVERLOAD_MAX_FE, OVERLOAD_MAX_EHV);

        private final String stage;
        private final double maxDamage;
        private final long maxFe;
        private final long maxEhv;

        Profile(String stage, double maxDamage, long maxFe, long maxEhv) {
            this.stage = stage;
            this.maxDamage = maxDamage;
            this.maxFe = maxFe;
            this.maxEhv = maxEhv;
        }

        private CelestweaveArmorSubmodule submodule() {
            return this == PHASE ? ResistanceSubmodule.T2 : OverloadProtectionSubmodule.INSTANCE;
        }

        public static Profile forStage(String stage) {
            for (Profile profile : values()) {
                if (profile.stage.equals(stage)) return profile;
            }
            throw new IllegalArgumentException("No paid shield profile for " + stage);
        }
    }

    private ShieldChargeWindow() {
    }

    public static Quote quote(ItemStack armor, Profile profile, long gameTime, float preventedDamage) {
        return quote(readState(armor, profile), profile, gameTime, preventedDamage);
    }

    static Quote quote(State state, Profile profile, long gameTime, double preventedDamage) {
        double damage = Double.isNaN(preventedDamage) ? 0D
                : Math.min(profile.maxDamage, Math.max(0D, preventedDamage));
        return quoteCosts(state, profile, gameTime,
                totalCost(damage, ArmorOverloadRules.PHASE_SHIELD_ACTIVE_COST_FE_PER_DAMAGE, profile.maxFe),
                totalCost(damage, ArmorOverloadRules.PHASE_SHIELD_COST_EHV_PER_DAMAGE, profile.maxEhv), false);
    }

    public static Quote quoteLastStand(ItemStack armor, long gameTime) {
        return quoteLastStand(readState(armor, Profile.OVERLOAD), gameTime);
    }

    static Quote quoteLastStand(State state, long gameTime) {
        // A direct death fills the current combo tier, sharing earlier shielding credit.
        return quoteCosts(state, Profile.OVERLOAD, gameTime, OVERLOAD_MAX_FE, OVERLOAD_MAX_EHV, true);
    }

    private static Quote quoteCosts(State state, Profile profile, long gameTime, long feCost, long ehvCost,
            boolean lastStand) {
        State safe = state == null ? State.EMPTY : state;
        boolean active = safe.windowUntil() > gameTime;
        long previousFe = active ? Math.min(profile.maxFe, safe.paidFe()) : 0L;
        long previousEhv = active ? Math.min(profile.maxEhv, safe.paidEhv()) : 0L;
        long peakFe = Math.min(profile.maxFe, Math.max(active ? safe.peakFe() : 0L, feCost));
        long peakEhv = Math.min(profile.maxEhv, Math.max(active ? safe.peakEhv() : 0L, ehvCost));
        int combo = profile == Profile.OVERLOAD
                ? Math.min(MAX_COMBO, (active ? safe.combo() : 0) + (lastStand ? 1 : 0)) : 0;
        // Damage always stays at tier one, including after a higher-tier death payment.
        int chargedTier = lastStand ? combo : 1;
        long feCap = profile == Profile.OVERLOAD
                ? Math.min(profile.maxFe, ArmorOverloadRules.UNDYING_TRIGGER_COST_FE * chargedTier) : profile.maxFe;
        long ehvCap = profile == Profile.OVERLOAD
                ? Math.min(profile.maxEhv, ArmorOverloadRules.UNDYING_TRIGGER_COST_EHV * chargedTier) : profile.maxEhv;
        long nextFe = Math.max(previousFe, Math.min(feCap, peakFe));
        long nextEhv = Math.max(previousEhv, Math.min(ehvCap, peakEhv));
        long until = active ? safe.windowUntil() : windowEnd(gameTime);
        return new Quote(profile, nextFe - previousFe, nextEhv - previousEhv,
                new State(until, nextFe, nextEhv, combo, peakFe, peakEhv));
    }

    public static void record(ItemStack armor, Quote quote) {
        CompoundTag data = CelestweaveArmorState.getSubmoduleData(armor, quote.profile().submodule());
        State next = quote.nextState();
        data.putLong(TAG_UNTIL, next.windowUntil());
        data.putLong(TAG_FE, next.paidFe());
        data.putLong(TAG_EHV, next.paidEhv());
        data.putInt(TAG_COMBO, next.combo());
        data.putLong(TAG_PEAK_FE, next.peakFe());
        data.putLong(TAG_PEAK_EHV, next.peakEhv());
        CelestweaveArmorState.setSubmoduleData(armor, quote.profile().submodule(), data);
    }

    public static boolean remainsActiveWithoutPassivePower(ItemStack armor, String submoduleId, long gameTime) {
        if ("multidimensional_protection".equals(submoduleId)) return true;
        for (Profile profile : Profile.values()) {
            if (profile.stage.equals(submoduleId)) {
                State state = readState(armor, profile);
                return state.windowUntil() > gameTime && (state.paidFe() > 0 || state.paidEhv() > 0);
            }
        }
        return false;
    }

    private static State readState(ItemStack armor, Profile profile) {
        CompoundTag data = CelestweaveArmorState.getSubmoduleData(armor, profile.submodule());
        return data.contains(TAG_UNTIL)
                ? new State(data.getLong(TAG_UNTIL), data.getLong(TAG_FE), data.getLong(TAG_EHV),
                        data.getInt(TAG_COMBO), data.getLong(TAG_PEAK_FE), data.getLong(TAG_PEAK_EHV))
                : State.EMPTY;
    }

    private static long windowEnd(long gameTime) {
        return gameTime > Long.MAX_VALUE - WINDOW_TICKS ? Long.MAX_VALUE : gameTime + WINDOW_TICKS;
    }

    private static long totalCost(double damage, long rate, long cap) {
        double cost = damage * rate;
        return cost >= cap ? cap : Math.max(0L, (long) Math.ceil(cost));
    }

    record State(long windowUntil, long paidFe, long paidEhv, int combo, long peakFe, long peakEhv) {
        static final State EMPTY = new State(Long.MIN_VALUE, 0L, 0L, 0, 0L, 0L);

        State {
            paidFe = Math.max(0L, paidFe);
            paidEhv = Math.max(0L, paidEhv);
            combo = Math.max(0, Math.min(combo, MAX_COMBO));
            // Older paid windows lack peak fields. Keep their existing credit during migration.
            peakFe = Math.max(paidFe, peakFe);
            peakEhv = Math.max(paidEhv, peakEhv);
        }
    }

    public record Quote(Profile profile, long feCost, long ehvCost, State nextState) {
    }
}
