package com.moakiee.ae2lt.machine.overloadfactory.recipe;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import com.moakiee.ae2lt.me.key.LightningKey;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fml.loading.LoadingModList;
import net.minecraftforge.fluids.FluidStack;
import net.pedroksl.advanced_ae.recipes.ReactionChamberRecipe;
import net.pedroksl.ae2addonlib.recipes.IngredientStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class AdvancedAeReactionAdapterTest {
    @BeforeAll
    static void bootstrap() {
        if (LoadingModList.get() == null) {
            LoadingModList.of(List.of(), List.of(), null);
        }
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void keepsSourceIdentityCountsComponentsAndEnergyButChargesPerOperation() {
        var ingredient = Ingredient.of(Items.GOLD_INGOT, Items.IRON_INGOT);
        var output = new ItemStack(Items.DIAMOND, 64);
        output.setHoverName(Component.literal("Source output"));
        var source = holder("third_party:custom", new ReactionChamberRecipe(new ResourceLocation("test:fixture"),
                new GenericStack(AEItemKey.of(output), 64),
                List.of(new IngredientStack.Item(ingredient, 32)), null, 123456));
        var converted = AdvancedAeReactionAdapter.convert(source);
        var recipe = converted.value();
        assertEquals(source.id(), converted.id());
        assertSame(ingredient, recipe.itemInputs().get(0).ingredient());
        assertEquals(32, recipe.itemInputs().get(0).count());
        assertEquals(123456, recipe.totalEnergy());
        assertEquals(1, recipe.lightningCost());
        assertEquals(LightningKey.Tier.HIGH_VOLTAGE, recipe.lightningTier());
        assertEquals(64, recipe.itemResults().get(0).getCount());
        assertEquals(output.getHoverName(),
                recipe.itemResults().get(0).getHoverName());
        assertEquals(3, OverloadProcessingRecipeService.maxLightningParallel(
                recipe.lightningTier(), recipe.lightningCost(), false, 3, 0));
        var input = new OverloadProcessingRecipeInput(
                List.of(new OverloadProcessingRecipeInput.SlotStack(0, new ItemStack(Items.IRON_INGOT, 96))),
                FluidStack.EMPTY);
        assertEquals(96, recipe.planMatch(input, 3).orElseThrow().getConsumptionForSlot(0));
        assertEquals(192, recipe.getScaledItemResults(3).get(0).getCount());
        // The view never replaced or changed the original recipe.
        assertEquals(123456, source.value().getEnergy());
        assertEquals(64, source.value().output.amount());
    }

    @Test
    void keepsItemTagsInsteadOfResolvingOneExampleStack() {
        var ingredient = Ingredient.of(ItemTags.LOGS);
        var source = itemRecipe(ingredient, 2, 1, 100);
        assertSame(ingredient, AdvancedAeReactionAdapter.convert(source).value().itemInputs().get(0).ingredient());
    }

    @Test
    void nativeFluidTypeAndParallelAmountAreBothRequired() {
        var source = new ReactionChamberRecipe(new ResourceLocation("test:fixture"), new GenericStack(AEItemKey.of(Items.DIAMOND), 1),
                List.of(new IngredientStack.Item(Ingredient.of(Items.IRON_INGOT), 1)),
                new IngredientStack.Fluid(Fluids.WATER, 500), 200);
        var recipe = AdvancedAeReactionAdapter.convert(holder("test:fluid", source)).value();
        assertEquals(500, recipe.inputFluidAmount());
        assertTrue(recipe.hasRequiredFluid(new FluidStack(Fluids.WATER, 1000), 2));
        assertFalse(recipe.hasRequiredFluid(new FluidStack(Fluids.LAVA, 1000), 2));
        assertFalse(recipe.hasRequiredFluid(new FluidStack(Fluids.LAVA, 999), 2));
        assertFalse(recipe.hasRequiredFluid(FluidStack.EMPTY, 1));
        assertFalse(recipe.hasRequiredFluid(new FluidStack(Fluids.WATER, Integer.MAX_VALUE), Integer.MAX_VALUE));
    }

    @Test
    void keepsFluidOutputAndAcceptsFluidOnlyInput() {
        var source = new ReactionChamberRecipe(new ResourceLocation("test:fixture"), new GenericStack(AEFluidKey.of(Fluids.LAVA), 1000),
                List.of(), new IngredientStack.Fluid(Fluids.WATER, 4000), 20000);
        var recipe = AdvancedAeReactionAdapter.convert(holder("test:fluid_output", source)).value();
        assertTrue(recipe.itemResults().isEmpty());
        assertFalse(recipe.isIncomplete());
        assertEquals(2000, recipe.getScaledFluidResult(2).getAmount());
        assertEquals(Fluids.LAVA, recipe.getScaledFluidResult(2).getFluid());
        assertTrue(recipe.matches(new OverloadProcessingRecipeInput(List.of(), new FluidStack(Fluids.WATER, 4000)), null));
    }

    @Test
    void reloadWithSameIdRebuildsViewAndRemovalLeavesNoFallbackCopy() {
        var manager = new TestRecipeManager();
        var original = itemRecipe(Ingredient.of(Items.IRON_INGOT), 1, 1, 100);
        manager.replaceRecipes(List.of(original.value()));
        var first = catalog(manager);
        assertSame(first, catalog(manager));

        var changed = itemRecipe(Ingredient.of(Items.GOLD_INGOT), 3, 7, 900);
        manager.replaceRecipes(List.of(changed.value()));
        var reloaded = catalog(manager);
        assertNotSame(first, reloaded);
        assertEquals(900, reloaded.get(0).value().totalEnergy());
        assertEquals(7, reloaded.get(0).value().itemResults().get(0).getCount());
        assertEquals(3, reloaded.get(0).value().itemInputs().get(0).count());
        assertFalse(reloaded.get(0).value().itemInputs().get(0).ingredient().test(new ItemStack(Items.IRON_INGOT)));
        assertSame(changed.value(), manager.byKey(changed.id()).orElseThrow());

        manager.replaceRecipes(List.of());
        assertTrue(catalog(manager).isEmpty());
    }

    @Test
    void managersStayIndependentAndNativeRecipesWorkWithoutReactionSources() {
        var one = new TestRecipeManager();
        var two = new TestRecipeManager();
        var recipe = itemRecipe(Ingredient.of(Items.IRON_INGOT), 1, 1, 100);
        one.replaceRecipes(List.of(recipe.value()));
        var first = catalog(one);
        assertTrue(catalog(two).isEmpty());
        assertSame(first, catalog(one));

        var nativeRecipe = new RecipeHolder<>(new ResourceLocation("ae2lt:test"),
                new OverloadProcessingRecipe(new ResourceLocation("test:fixture"), 0, List.of(new OverloadProcessingIngredient(Ingredient.of(Items.GOLD_INGOT), 1)),
                        FluidStack.EMPTY, List.of(new ItemStack(Items.DIAMOND)), FluidStack.EMPTY,
                        200, 8, LightningKey.Tier.EXTREME_HIGH_VOLTAGE));
        var nativeOnly = OverloadProcessingRecipeCatalog.recipes(two, List.of(nativeRecipe), List.of());
        assertEquals(List.of(nativeRecipe), nativeOnly);
        assertEquals(8, nativeOnly.get(0).value().lightningCost());
    }

    @Test
    void unsupportedRecipeIsSkippedWithoutHidingOtherRecipes() {
        var invalid = holder("test:too_many_inputs", new ReactionChamberRecipe(new ResourceLocation("test:fixture"),
                new GenericStack(AEItemKey.of(Items.DIAMOND), 1),
                Collections.nCopies(10, new IngredientStack.Item(Ingredient.of(Items.IRON_INGOT), 1)), null, 100));
        var valid = itemRecipe(Ingredient.of(Items.IRON_INGOT), 1, 1, 100);
        var manager = new TestRecipeManager();
        manager.replaceRecipes(List.of(invalid.value(), valid.value()));
        assertEquals(List.of(valid.id()), catalog(manager).stream().map(RecipeHolder::id).toList());
    }

    private static List<RecipeHolder<OverloadProcessingRecipe>> catalog(RecipeManager manager) {
        return OverloadProcessingRecipeCatalog.recipes(manager, List.of(), manager.getAllRecipesFor(ReactionChamberRecipe.TYPE).stream().map(RecipeHolder::of).toList());
    }

    private static RecipeHolder<ReactionChamberRecipe> itemRecipe(Ingredient ingredient, int count, int output, int energy) {
        return holder("test:recipe", new ReactionChamberRecipe(new ResourceLocation("test:fixture"), new GenericStack(AEItemKey.of(Items.DIAMOND), output),
                List.of(new IngredientStack.Item(ingredient, count)), null, energy));
    }

    private static RecipeHolder<ReactionChamberRecipe> holder(String id, ReactionChamberRecipe recipe) {
        recipe.id = new ResourceLocation(id);
        return new RecipeHolder<>(recipe.id, recipe);
    }
}
