package com.moakiee.ae2lt.machine.overloadfactory.recipe;

import java.util.List;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import com.moakiee.ae2lt.machine.overloadfactory.recipe.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraftforge.fluids.FluidStack;
import net.pedroksl.advanced_ae.recipes.ReactionChamberRecipe;

/** Loaded only when AdvancedAE is installed. */
final class AdvancedAeReactionAdapter {
    private AdvancedAeReactionAdapter() {
    }

    static List<RecipeHolder<ReactionChamberRecipe>> sourceRecipes(RecipeManager manager) {
        // Use the exact type object used by the upstream machine instead of relying
        // on a registry ID lookup to expose that same object in every AAE version.
        return manager.getAllRecipesFor(ReactionChamberRecipe.TYPE).stream().map(RecipeHolder::of).toList();
    }

    static RecipeHolder<OverloadProcessingRecipe> convert(RecipeHolder<?> holder) {
        if (holder.value().getClass() != ReactionChamberRecipe.class) {
            throw new IllegalArgumentException("unsupported reaction recipe implementation");
        }
        var source = (ReactionChamberRecipe) holder.value();
        var inputs = source.getInputs().stream()
                .map(input -> new OverloadProcessingIngredient(input.getIngredient(), input.getAmount()))
                .toList();
        var sourceFluid = source.getFluid();
        var fluid = sourceFluid == null ? null
                : new BorrowedFluidInput(sourceFluid.getStack(), sourceFluid::test);
        var output = source.output;
        if (output == null || output.amount() <= 0 || output.amount() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("reaction output amount is outside the factory range");
        }
        int amount = (int) output.amount();
        OverloadProcessingRecipe recipe;
        if (output.what() instanceof AEItemKey item) {
            recipe = OverloadProcessingRecipe.borrowed(
                    holder.id(), inputs, fluid, List.of(item.toStack(amount)), FluidStack.EMPTY, source.getEnergy());
        } else if (output.what() instanceof AEFluidKey resultFluid) {
            recipe = OverloadProcessingRecipe.borrowed(
                    holder.id(), inputs, fluid, List.of(), resultFluid.toStack(amount), source.getEnergy());
        } else {
            throw new IllegalArgumentException("reaction output is neither an item nor a fluid");
        }
        return new RecipeHolder<>(holder.id(), recipe);
    }
}
