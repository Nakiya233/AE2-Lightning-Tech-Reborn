package com.moakiee.ae2lt.integration.jei.compat;

import com.moakiee.ae2lt.registry.ModBlocks;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import net.pedroksl.advanced_ae.xmod.jei.ReactionChamberCategory;

/** Client-only optional integration: reuse AdvancedAE's category and original recipe instances. */
public final class AdvancedAeFactoryJeiCompat {
    private AdvancedAeFactoryJeiCompat() {
    }

    public static void registerCatalyst(IRecipeCatalystRegistration registration) {
        registration.addRecipeCatalyst(new net.minecraft.world.item.ItemStack(ModBlocks.OVERLOAD_PROCESSING_FACTORY.get()), recipeType());
    }

    public static RecipeType<?> recipeType() {
        return ReactionChamberCategory.RECIPE_TYPE;
    }
}
