package com.moakiee.ae2lt.machine.overloadfactory.recipe;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import appeng.recipes.handlers.InscriberProcessType;
import appeng.recipes.handlers.InscriberRecipe;
import com.moakiee.ae2lt.me.key.LightningKey;
import net.minecraft.SharedConstants;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraftforge.fml.loading.LoadingModList;
import net.minecraftforge.common.crafting.StrictNBTIngredient;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class InscriberProcessorAdapterTest {
    @BeforeAll
    static void bootstrap() {
        if (LoadingModList.get() == null) LoadingModList.of(List.of(), List.of(), null);
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void unfoldsBothTemplatesAndUsesActualFourAndNineItemBlocks() {
        var sources = chain();
        compress(sources, Items.GOLD_INGOT, Items.GOLD_BLOCK, 9);
        compress(sources, Items.REDSTONE, Items.REDSTONE_BLOCK, 9);
        compress(sources, Items.BRICK, Items.BRICKS, 4);
        var derived = derive(sources).get(0);
        var recipe = derived.value();
        assertInputs(recipe, Items.GOLD_BLOCK, 4, Items.REDSTONE_BLOCK, 4, Items.BRICKS, 9);
        assertTrue(recipe.itemResults().get(0).is(Items.CLOCK)); // The source recipe ID opts in, regardless of its mod.
        assertEquals(36, recipe.itemResults().get(0).getCount());
        assertEquals(400_000, recipe.totalEnergy());
        assertEquals(1, recipe.lightningCost());
        assertEquals(LightningKey.Tier.HIGH_VOLTAGE, recipe.lightningTier());
        assertTrue(derived.id().getPath().startsWith("derived/inscriber/test/final_processor/"));
        assertTrue(sources.stream().noneMatch(h -> h.id().equals(derived.id())));
        var input = new OverloadProcessingRecipeInput(List.of(
                new OverloadProcessingRecipeInput.SlotStack(0, new ItemStack(Items.GOLD_BLOCK, 8)),
                new OverloadProcessingRecipeInput.SlotStack(1, new ItemStack(Items.REDSTONE_BLOCK, 8)),
                new OverloadProcessingRecipeInput.SlotStack(2, new ItemStack(Items.BRICKS, 18))), FluidStack.EMPTY);
        assertEquals(18, recipe.planMatch(input, 2).orElseThrow().getConsumptionForSlot(2));
        assertEquals(72, recipe.getScaledItemResults(2).get(0).getCount());
    }

    @Test
    void rejectsThreeInputRecipesWithoutProcessorInTheRecipePath() {
        var sources = chain();
        var finalRecipe = sources.get(0).value();
        sources.set(0, holder("unrelated_three_input", finalRecipe));
        assertTrue(derive(sources).isEmpty());
        // A namespace containing processor must not make all of that mod's recipes eligible.
        sources.set(0, new RecipeHolder<>(new ResourceLocation("processor_mod:unrelated_three_input"), finalRecipe));
        assertTrue(derive(sources).isEmpty());
        sources.set(0, holder("processors/unknown_kind", finalRecipe));
        assertEquals(1, derive(sources).size());
    }

    @Test
    void readsMultiplePrintingStepsAndKeepsRawItemsWithoutCompression() {
        var sources = chain();
        sources.add(print("second", Ingredient.of(Items.COPPER_INGOT), new ItemStack(Items.GOLD_INGOT), false));
        var recipe = derive(sources).get(0).value();
        assertInputs(recipe, Items.COPPER_INGOT, 36, Items.REDSTONE, 36, Items.BRICK, 36);
    }

    @Test
    void manualRecipeForSameOutputSuppressesAllGeneratedVariants() {
        var manual = new OverloadProcessingRecipe(new ResourceLocation("test:fixture"), 0, List.of(new OverloadProcessingIngredient(Ingredient.of(Items.DIAMOND), 1)),
                FluidStack.EMPTY, List.of(new ItemStack(Items.CLOCK, 64)), FluidStack.EMPTY, 800_000, 3, LightningKey.Tier.HIGH_VOLTAGE);
        assertTrue(InscriberProcessorAdapter.derive(chain(), List.of(holder("manual", manual))).isEmpty());
        // A distinct component-bearing output is not the same product.
        var named = new ItemStack(Items.CLOCK);
        named.setHoverName(Component.literal("Different product"));
        var different = new OverloadProcessingRecipe(new ResourceLocation("test:fixture"), 0, manual.itemInputs(), FluidStack.EMPTY, List.of(named),
                FluidStack.EMPTY, 800_000, 3, LightningKey.Tier.HIGH_VOLTAGE);
        assertEquals(1, InscriberProcessorAdapter.derive(chain(), List.of(holder("manual", different))).size());
    }

    @Test
    void consumedTemplatesAndTwoTemplatePrintsAreNotUnfolded() {
        var sources = chain();
        sources.set(1, holder("gold_processor", new InscriberRecipe(new ResourceLocation("test:fixture"), Ingredient.of(Items.GOLD_INGOT), new ItemStack(Items.GOLD_NUGGET),
                Ingredient.of(Items.PAPER), Ingredient.EMPTY, InscriberProcessType.PRESS)));
        sources.set(2, holder("silicon", new InscriberRecipe(new ResourceLocation("test:fixture"), Ingredient.of(Items.BRICK), new ItemStack(Items.CLAY_BALL),
                Ingredient.of(Items.PAPER), Ingredient.of(Items.PAPER), InscriberProcessType.INSCRIBE)));
        assertInputs(derive(sources).get(0).value(), Items.GOLD_NUGGET, 36, Items.REDSTONE, 36, Items.CLAY_BALL, 36);
        assertEquals(1, derive(sources).size()); // Two-slot PRESS isn't a three-input processor either.
    }

    @Test
    void preservesAlternativeMaterialsAndUnresolvedComponentPredicates() {
        var sources = chain();
        var alternatives = Ingredient.of(Items.GOLD_INGOT, Items.IRON_INGOT);
        sources.set(1, print("gold", alternatives, new ItemStack(Items.GOLD_NUGGET), false));
        var recipe = derive(sources).get(0).value();
        assertSame(alternatives, recipe.itemInputs().get(0).ingredient());
        assertTrue(recipe.itemInputs().get(0).ingredient().test(new ItemStack(Items.IRON_INGOT)));
        var named = new ItemStack(Items.DIAMOND);
        named.setHoverName(Component.literal("Required component"));
        var strict = StrictNBTIngredient.of( named);
        sources.set(1, print("gold", strict, new ItemStack(Items.GOLD_NUGGET), false));
        recipe = derive(sources).get(0).value();
        assertSame(strict, recipe.itemInputs().get(0).ingredient());
        assertFalse(recipe.itemInputs().get(0).ingredient().test(new ItemStack(Items.DIAMOND)));
        assertTrue(recipe.itemInputs().get(0).ingredient().test(named));
    }

    @Test
    void multipleValidPrintRoutesAreRetainedWithoutDuplicatingFinalRecipe() {
        var sources = chain();
        sources.add(print("alternate", Ingredient.of(Items.IRON_INGOT), new ItemStack(Items.GOLD_NUGGET), true));
        var result = derive(sources);
        assertEquals(1, result.size());
        var input = result.get(0).value().itemInputs().get(0);
        assertEquals(36, input.count());
        assertTrue(input.ingredient().test(new ItemStack(Items.IRON_INGOT)));
        assertTrue(input.ingredient().test(new ItemStack(Items.GOLD_INGOT)));
    }

    @Test
    void exactOutputCountsExpandBatchAndScaleEnergyAndLightningTogether() {
        var sources = chain();
        sources.set(1, print("gold", Ingredient.of(Items.GOLD_INGOT), new ItemStack(Items.GOLD_NUGGET, 2), false));
        compress(sources, Items.GOLD_INGOT, Items.GOLD_BLOCK, 4);
        var recipe = derive(sources).get(0).value();
        // One block -> four raw items -> eight prints; 72 is the first multiple of both 36 and 8.
        assertInputs(recipe, Items.GOLD_BLOCK, 9, Items.REDSTONE, 72, Items.BRICK, 72);
        assertEquals(72, recipe.itemResults().get(0).getCount());
        assertEquals(800_000, recipe.totalEnergy());
        assertEquals(2, recipe.lightningCost());
    }

    @Test
    void requiresARealInverseAndRejectsMixedCompression() {
        var sources = chain();
        sources.add(crafting("fake_block", new ItemStack(Items.GOLD_BLOCK),
                List.of(Ingredient.of(Items.GOLD_INGOT), Ingredient.of(Items.GOLD_INGOT),
                        Ingredient.of(Items.GOLD_INGOT), Ingredient.of(Items.DIAMOND))));
        sources.add(crafting("fake_unpack", new ItemStack(Items.GOLD_INGOT, 4), List.of(Ingredient.of(Items.GOLD_BLOCK))));
        assertInputs(derive(sources).get(0).value(), Items.GOLD_INGOT, 36, Items.REDSTONE, 36, Items.BRICK, 36);
        sources.remove(sources.size() - 1);
        sources.add(crafting("no_unpack", new ItemStack(Items.REDSTONE_BLOCK), java.util.Collections.nCopies(9, Ingredient.of(Items.REDSTONE))));
        assertInputs(derive(sources).get(0).value(), Items.GOLD_INGOT, 36, Items.REDSTONE, 36, Items.BRICK, 36);
    }

    @Test
    void stopsCyclesWithoutDroppingTheRequiredInput() {
        var sources = chain();
        sources.add(print("cycle", Ingredient.of(Items.GOLD_NUGGET), new ItemStack(Items.GOLD_INGOT), false));
        var recipe = derive(sources).get(0).value();
        assertEquals(36, recipe.itemInputs().get(0).count());
        assertTrue(recipe.itemInputs().get(0).ingredient().test(new ItemStack(Items.GOLD_NUGGET)));
    }

    @Test
    void finalCatalogNeverRegeneratesRecipesDeletedByScripts() {
        var manager = new TestRecipeManager();
        var sources = chain();
        manager.replaceRecipes(sources.stream().<Recipe<?>>map(RecipeHolder::value).toList());
        var generated = derive(sources);
        assertSame(OverloadProcessingRecipeCatalog.snapshot(manager), OverloadProcessingRecipeCatalog.snapshot(manager));
        var first = OverloadProcessingRecipeCatalog.recipes(manager, generated, List.of());
        assertSame(first, OverloadProcessingRecipeCatalog.recipes(manager, generated, List.of()));
        var id = generated.get(0).id();
        assertSame(first.get(0).value(), OverloadProcessingRecipeCatalog.find(manager, id).orElseThrow());
        // The script removes the generated entry, while every original inscriber still exists.
        assertTrue(OverloadProcessingRecipeCatalog.recipes(manager, List.of(), List.of()).isEmpty());
        assertTrue(OverloadProcessingRecipeCatalog.find(manager, id).isEmpty());
        assertTrue(OverloadProcessingRecipeCatalog.displayRecipes(manager).isEmpty());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void usesPendingReloadTagsAndAcceptsEquivalentTagAndItemCompressionSlots() {
        var tagId = new ResourceLocation("test:pending_gold");
        var tag = net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, tagId);
        var context = new net.minecraftforge.common.crafting.conditions.ICondition.IContext() {
            @Override
            public <T> Map<ResourceLocation, java.util.Collection<net.minecraft.core.Holder<T>>> getAllTags(
                    net.minecraft.resources.ResourceKey<? extends net.minecraft.core.Registry<T>> registry) {
                return registry.equals(net.minecraft.core.registries.Registries.ITEM)
                        ? (Map) Map.of(tagId, List.of(Items.GOLD_INGOT.builtInRegistryHolder())) : Map.of();
            }
        };
        var sources = chain();
        var tagged = Ingredient.of(tag);
        sources.set(1, print("gold", tagged, new ItemStack(Items.GOLD_NUGGET), false));
        var slots = new ArrayList<Ingredient>(java.util.Collections.nCopies(9, tagged));
        slots.set(4, Ingredient.of(Items.GOLD_INGOT));
        sources.add(crafting("pack", new ItemStack(Items.GOLD_BLOCK), slots));
        sources.add(crafting("unpack", new ItemStack(Items.GOLD_INGOT, 9), List.of(Ingredient.of(Items.GOLD_BLOCK))));
        var lookup = new ReloadIngredientLookup(context, com.mojang.serialization.JsonOps.INSTANCE);
        var result = InscriberProcessorAdapter.derive(sources, List.of(), lookup);
        assertInputs(result.get(0).value(), Items.GOLD_BLOCK, 4, Items.REDSTONE, 36, Items.BRICK, 36);
        // A second reload with that tag removed must not reuse the prior lookup/bound global tags.
        assertTrue(InscriberProcessorAdapter.derive(sources, List.of(), new ReloadIngredientLookup(
                net.minecraftforge.common.crafting.conditions.ICondition.IContext.EMPTY,
                com.mojang.serialization.JsonOps.INSTANCE)).get(0).value().itemInputs().get(0)
                .ingredient().test(new ItemStack(Items.GOLD_NUGGET)));
    }

    private static List<RecipeHolder<OverloadProcessingRecipe>> derive(List<RecipeHolder<?>> sources) {
        return InscriberProcessorAdapter.derive(sources, List.of());
    }

    private static List<RecipeHolder<?>> chain() {
        var sources = new ArrayList<RecipeHolder<?>>();
        sources.add(holder("final_processor", new InscriberRecipe(new ResourceLocation("test:final_processor"), Ingredient.of(Items.REDSTONE), new ItemStack(Items.CLOCK),
                Ingredient.of(Items.GOLD_NUGGET), Ingredient.of(Items.CLAY_BALL), InscriberProcessType.PRESS)));
        sources.add(print("gold", Ingredient.of(Items.GOLD_INGOT), new ItemStack(Items.GOLD_NUGGET), false));
        sources.add(print("silicon", Ingredient.of(Items.BRICK), new ItemStack(Items.CLAY_BALL), true));
        return sources;
    }

    private static RecipeHolder<InscriberRecipe> print(String id, Ingredient input, ItemStack result, boolean bottom) {
        return holder(id, new InscriberRecipe(new ResourceLocation("test", id.replace(':', '_')), input, result, bottom ? Ingredient.EMPTY : Ingredient.of(Items.PAPER),
                bottom ? Ingredient.of(Items.PAPER) : Ingredient.EMPTY, InscriberProcessType.INSCRIBE));
    }

    private static void compress(List<RecipeHolder<?>> sources, Item item, Item block, int count) {
        sources.add(crafting("pack_" + item, new ItemStack(block), java.util.Collections.nCopies(count, Ingredient.of(item))));
        sources.add(crafting("unpack_" + item, new ItemStack(item, count), List.of(Ingredient.of(block))));
    }

    private static RecipeHolder<ShapelessRecipe> crafting(String id, ItemStack result, List<Ingredient> inputs) {
        var ingredients = NonNullList.<Ingredient>create();
        ingredients.addAll(inputs);
        return holder(id, new ShapelessRecipe(new ResourceLocation("test", id.replace(':', '_')), "", CraftingBookCategory.MISC, result, ingredients));
    }

    private static <R extends Recipe<?>> RecipeHolder<R> holder(String id, R recipe) {
        return new RecipeHolder<>(new ResourceLocation("test", id.replace(':', '_')), recipe);
    }

    private static void assertInputs(OverloadProcessingRecipe recipe, Item a, int ac, Item b, int bc, Item c, int cc) {
        var inputs = recipe.itemInputs();
        assertEquals(3, inputs.size());
        assertTrue(inputs.get(0).ingredient().test(new ItemStack(a)), "first input");
        assertEquals(ac, inputs.get(0).count());
        assertTrue(inputs.get(1).ingredient().test(new ItemStack(b)), "second input");
        assertEquals(bc, inputs.get(1).count());
        assertTrue(inputs.get(2).ingredient().test(new ItemStack(c)), "third input");
        assertEquals(cc, inputs.get(2).count());
    }
}
