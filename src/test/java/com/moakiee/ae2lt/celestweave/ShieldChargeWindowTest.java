package com.moakiee.ae2lt.celestweave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static com.moakiee.ae2lt.celestweave.ShieldChargeWindow.Profile.*;

import org.junit.jupiter.api.Test;

class ShieldChargeWindowTest {
    @Test
    void phaseBillsOnlyTheNewHighWaterMarkInsideTwentyTicks() {
        var first = ShieldChargeWindow.quote(ShieldChargeWindow.State.EMPTY, PHASE, 100L, 100D);
        assertEquals(2_000_000L, first.feCost());
        assertEquals(200L, first.ehvCost());
        var smaller = ShieldChargeWindow.quote(first.nextState(), PHASE, 110L, 80D);
        assertEquals(0L, smaller.feCost());
        assertEquals(0L, smaller.ehvCost());
        var larger = ShieldChargeWindow.quote(smaller.nextState(), PHASE, 115L, 150D);
        assertEquals(1_000_000L, larger.feCost());
        assertEquals(100L, larger.ehvCost());
        assertEquals(120L, larger.nextState().windowUntil());
    }

    @Test
    void phasePaysForOnly1024AbsorbedDamageAtTwoEhvEach() {
        var first = ShieldChargeWindow.quote(ShieldChargeWindow.State.EMPTY, PHASE, 100L, 1024D);
        assertEquals(20_480_000L, first.feCost());
        assertEquals(2048L, first.ehvCost());
        var extreme = ShieldChargeWindow.quote(ShieldChargeWindow.State.EMPTY, PHASE, 100L, Float.MAX_VALUE);
        assertEquals(first, extreme);
        var repeated = ShieldChargeWindow.quote(first.nextState(), PHASE, 119L, Float.MAX_VALUE);
        assertEquals(0L, repeated.feCost());
        assertEquals(0L, repeated.ehvCost());
    }

    @Test
    void overloadDamageAlwaysStopsAtTierOneAndDoesNotAdvanceDeathCombo() {
        var state = ShieldChargeWindow.State.EMPTY;
        for (int hit = 1; hit <= 100; hit++) {
            var quote = ShieldChargeWindow.quote(state, OVERLOAD, 100L, Float.MAX_VALUE);
            assertEquals(hit == 1 ? 2_000_000_000L : 0L, quote.feCost());
            assertEquals(hit == 1 ? 1024L : 0L, quote.ehvCost());
            assertEquals(0, quote.nextState().combo());
            assertEquals(120L, quote.nextState().windowUntil());
            state = quote.nextState();
        }
        var firstDeath = ShieldChargeWindow.quoteLastStand(state, 119L);
        assertEquals(1, firstDeath.nextState().combo());
        assertEquals(0L, firstDeath.feCost());
        assertEquals(0L, firstDeath.ehvCost());
    }

    @Test
    void onlyDeathRaisesCapsAndInterveningDamageNeverAddsCharges() {
        var state = ShieldChargeWindow.State.EMPTY;
        for (int death = 1; death <= 20; death++) {
            var quote = ShieldChargeWindow.quoteLastStand(state, 100L);
            long fe = Math.min(20_000_000_000L, 2_000_000_000L * death);
            long ehv = Math.min(16_384L, 1024L * death);
            assertEquals(fe - state.paidFe(), quote.feCost());
            assertEquals(ehv - state.paidEhv(), quote.ehvCost());
            var damage = ShieldChargeWindow.quote(quote.nextState(), OVERLOAD, 100L, Float.MAX_VALUE);
            assertEquals(0L, damage.feCost());
            assertEquals(0L, damage.ehvCost());
            assertEquals(quote.nextState().combo(), damage.nextState().combo());
            state = damage.nextState();
        }
    }

    @Test
    void lowDamageStillUsesPerDamageFeesInsteadOfPayingTheWholeTier() {
        var first = ShieldChargeWindow.quote(ShieldChargeWindow.State.EMPTY, OVERLOAD, 100L, 10D);
        assertEquals(200_000L, first.feCost());
        assertEquals(20L, first.ehvCost());
        var second = ShieldChargeWindow.quote(first.nextState(), OVERLOAD, 110L, 5D);
        assertEquals(0L, second.feCost());
        assertEquals(0L, second.ehvCost());
        assertEquals(0, second.nextState().combo());
    }

    @Test
    void directDeathAdvancesComboButNeverExtendsTheWindow() {
        var first = ShieldChargeWindow.quoteLastStand(ShieldChargeWindow.State.EMPTY, 100L);
        assertEquals(2_000_000_000L, first.feCost());
        assertEquals(1024L, first.ehvCost());
        var second = ShieldChargeWindow.quoteLastStand(first.nextState(), 119L);
        assertEquals(2_000_000_000L, second.feCost());
        assertEquals(1024L, second.ehvCost());
        assertEquals(120L, second.nextState().windowUntil());
        var nextWindow = ShieldChargeWindow.quoteLastStand(second.nextState(), 120L);
        assertEquals(2_000_000_000L, nextWindow.feCost());
        assertEquals(1024L, nextWindow.ehvCost());
        assertEquals(1, nextWindow.nextState().combo());
        assertEquals(140L, nextWindow.nextState().windowUntil());
    }

    @Test
    void shieldAndDeathSharePaidResourcesButOnlyDeathAdvancesItsTier() {
        var shield = ShieldChargeWindow.quote(ShieldChargeWindow.State.EMPTY, OVERLOAD, 100L, 400D);
        var death = ShieldChargeWindow.quoteLastStand(shield.nextState(), 119L);
        assertEquals(1_992_000_000L, death.feCost());
        assertEquals(224L, death.ehvCost());
        assertEquals(1, death.nextState().combo());
        assertEquals(120L, death.nextState().windowUntil());
        var damage = ShieldChargeWindow.quote(death.nextState(), OVERLOAD, 119L, Float.MAX_VALUE);
        assertEquals(0L, damage.feCost());
        assertEquals(0L, damage.ehvCost());
        assertEquals(1, damage.nextState().combo());
        var secondDeath = ShieldChargeWindow.quoteLastStand(damage.nextState(), 119L);
        assertEquals(2_000_000_000L, secondDeath.feCost());
        assertEquals(1024L, secondDeath.ehvCost());
        assertEquals(2, secondDeath.nextState().combo());
    }

    @Test
    void twentyTickBoundaryResetsBothPeakAndCombo() {
        var first = ShieldChargeWindow.quote(ShieldChargeWindow.State.EMPTY, OVERLOAD, 100L, Float.MAX_VALUE);
        var next = ShieldChargeWindow.quote(first.nextState(), OVERLOAD, 120L, 1D);
        assertEquals(20_000L, next.feCost());
        assertEquals(2L, next.ehvCost());
        assertEquals(0, next.nextState().combo());
        assertEquals(140L, next.nextState().windowUntil());
    }

    @Test
    void fractionalDamageRoundsUpAndInvalidDamageDoesNotCharge() {
        var fraction = ShieldChargeWindow.quote(ShieldChargeWindow.State.EMPTY, PHASE, 100L, 0.25D);
        assertEquals(5000L, fraction.feCost());
        assertEquals(1L, fraction.ehvCost());
        for (double damage : new double[] {Double.NaN, -1D, 0D}) {
            var invalid = ShieldChargeWindow.quote(ShieldChargeWindow.State.EMPTY, PHASE, 100L, damage);
            assertEquals(0L, invalid.feCost());
            assertEquals(0L, invalid.ehvCost());
        }
    }

    @Test
    void windowEndSaturatesInsteadOfOverflowing() {
        var quote = ShieldChargeWindow.quote(ShieldChargeWindow.State.EMPTY, OVERLOAD, Long.MAX_VALUE - 10L,
                Double.POSITIVE_INFINITY);
        assertEquals(Long.MAX_VALUE, quote.nextState().windowUntil());
        assertEquals(2_000_000_000L, quote.feCost());
        assertEquals(1024L, quote.ehvCost());
    }
}
