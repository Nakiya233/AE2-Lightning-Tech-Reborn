package com.moakiee.ae2lt.crafting.timewheel;

import static org.junit.jupiter.api.Assertions.*;

import appeng.api.stacks.KeyCounter;
import com.moakiee.ae2lt.me.key.LightningKey;
import org.junit.jupiter.api.Test;

class DeferredCraftingOutputsTest {
    @Test
    void copiesBorrowedCounterAndClosesSinkBeforeDelivery() {
        var queue = new DeferredCraftingOutputs();
        var offered = new KeyCounter();
        offered.add(LightningKey.HIGH_VOLTAGE, 7);
        assertTrue(queue.enqueue(offered));
        offered.clear();
        long[] delivered = {0};
        queue.drain((key, amount) -> {
            assertFalse(queue.enqueue(offered));
            delivered[0] += amount;
        }, (key, amount) -> fail("unexpected fallback"));
        assertEquals(7, delivered[0]);
        assertFalse(queue.enqueue(offered));
        queue.drain((key, amount) -> fail("duplicate return"), (key, amount) -> fail());
    }

    @Test
    void overflowingOfferIsRejectedWithoutTransferringAnyOfItsOutputs() {
        var queue = new DeferredCraftingOutputs();
        var offered = new KeyCounter();
        offered.add(LightningKey.HIGH_VOLTAGE, Long.MAX_VALUE);
        assertTrue(queue.enqueue(offered));
        offered.clear();
        offered.add(LightningKey.HIGH_VOLTAGE, 1);
        offered.add(LightningKey.EXTREME_HIGH_VOLTAGE, 2);
        assertFalse(queue.enqueue(offered));
        var received = new KeyCounter();
        queue.drain(received::add, (key, amount) -> fail());
        assertEquals(Long.MAX_VALUE, received.get(LightningKey.HIGH_VOLTAGE));
        assertEquals(0, received.get(LightningKey.EXTREME_HIGH_VOLTAGE));
    }
}
