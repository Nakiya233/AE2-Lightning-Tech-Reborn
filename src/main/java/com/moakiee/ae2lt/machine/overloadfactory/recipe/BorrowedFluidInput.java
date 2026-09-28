package com.moakiee.ae2lt.machine.overloadfactory.recipe;

import java.util.function.Predicate;
import net.minecraftforge.fluids.FluidStack;

/** Execution-only predicate copied from the native AdvancedAE fluid input. */
public record BorrowedFluidInput(FluidStack sample, Predicate<FluidStack> ingredient) {
    public BorrowedFluidInput {
        if (sample.isEmpty()) throw new IllegalArgumentException("empty borrowed fluid input");
        sample = sample.copy();
    }
    public int amount() { return sample.getAmount(); }
    public FluidStack[] getFluids() { return new FluidStack[] {sample.copy()}; }
}
