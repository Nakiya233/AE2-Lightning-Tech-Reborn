package com.moakiee.ae2lt.crafting.runtime.api;

import org.jetbrains.annotations.Nullable;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;
import com.moakiee.thunderbolt.api.crafting.batch.BatchJobView;

/** Internal synchronous execution contract. Delivery waits for the CPU to commit its dispatch. */
public interface DeferredCraftingProvider extends ICraftingProvider {
    boolean pushPattern(IPatternDetails pattern, KeyCounter[] inputs, OutputSink returns);

    interface Job extends BatchJobView {
        @Nullable OutputSink deferredOutputSink();
    }

    @FunctionalInterface
    interface OutputSink {
        /**
         * Transfers all physical outputs on success, without receiving them into the job yet.
         * On rejection the provider still owns every output. The counter is borrowed only for
         * this call; neither the sink nor the job view may be retained by the provider.
         */
        boolean enqueue(KeyCounter outputs);
    }
}
