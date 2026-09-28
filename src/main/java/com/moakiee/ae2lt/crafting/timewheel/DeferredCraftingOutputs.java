package com.moakiee.ae2lt.crafting.timewheel;

import java.util.function.ObjLongConsumer;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import com.moakiee.ae2lt.crafting.runtime.api.DeferredCraftingProvider;

/** One dispatch's physical returns. Closing the sink before draining prevents reentrant enqueue. */
final class DeferredCraftingOutputs implements DeferredCraftingProvider.OutputSink {
    private final KeyCounter pending = new KeyCounter();
    private boolean closed;

    @Override
    public boolean enqueue(KeyCounter outputs) {
        if (closed) return false;
        for (var entry : outputs) {
            if (entry.getLongValue() < 0
                    || pending.get(entry.getKey()) > Long.MAX_VALUE - entry.getLongValue()) return false;
        }
        for (var entry : outputs) {
            if (entry.getLongValue() > 0) pending.add(entry.getKey(), entry.getLongValue());
        }
        return true;
    }

    void drain(ObjLongConsumer<AEKey> receiver, ObjLongConsumer<AEKey> fallback) {
        closed = true;
        try {
            for (var entry : pending) {
                long amount = entry.getLongValue();
                // Ownership passes to the receiver, which must retain any unaccepted remainder.
                entry.setValue(0L);
                if (amount > 0) receiver.accept(entry.getKey(), amount);
            }
        } finally {
            // A failed callback must not strand later outputs in a stack-local queue.
            for (var entry : pending) {
                if (entry.getLongValue() > 0) fallback.accept(entry.getKey(), entry.getLongValue());
            }
            pending.clear();
        }
    }
}
