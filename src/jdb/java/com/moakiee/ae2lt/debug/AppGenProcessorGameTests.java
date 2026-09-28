package com.moakiee.ae2lt.debug;

import appeng.recipes.handlers.InscriberRecipe;
import com.google.gson.JsonParser;
import com.moakiee.ae2lt.machine.overloadfactory.OverloadProcessingFactoryInventory;
import com.moakiee.ae2lt.machine.overloadfactory.recipe.OverloadProcessingRecipe;
import com.moakiee.ae2lt.machine.overloadfactory.recipe.OverloadProcessingRecipeInput;
import com.moakiee.ae2lt.machine.overloadfactory.recipe.OverloadProcessingRecipeService;
import com.moakiee.ae2lt.me.key.LightningKey;
import io.netty.buffer.Unpooled;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Uses the actual optional mod's items and recipe manager, not mock registry entries. */
@GameTestHolder("ae2lt")
@PrefixGameTestTemplate(false)
public final class AppGenProcessorGameTests {
    private static final ResourceLocation BULK = new ResourceLocation("ae2lt:overload_processing/appgen_origination_processor");

    @GameTest(template = "pigmee_station_empty")
    public static void originationProcessorLoadsMatchesAndSyncsOnlyWithAppGen(GameTestHelper helper) {
        var level = helper.getLevel();
        var manager = level.getRecipeManager();
        var bulk = manager.byKey(BULK);
        if (!ModList.get().isLoaded("appgen")) {
            helper.assertTrue(bulk.isEmpty(), "AppGen recipe must be excluded when the optional mod is absent");
            helper.succeed();
            return;
        }
        var ember = item("appgen:ember_crystal");
        var emberBlock = item("appgen:ember_block");
        var printed = item("appgen:printed_origination_processor");
        var processor = item("appgen:origination_processor");
        var compression = (ShapedRecipe) manager.byKey(new ResourceLocation("appgen:crafting/ember_block"))
                .orElseThrow().value();
        helper.assertTrue(compression.getWidth() == 2 && compression.getHeight() == 2
                        && compression.getIngredients().stream().allMatch(i -> i.test(ember))
                        && compression.getResultItem(level.registryAccess()).is(emberBlock.getItem()),
                "actual AppGen compression is four crystals per ember block");
        var printing = (InscriberRecipe) manager.byKey(new ResourceLocation("appgen:inscriber/printed_origination_processor"))
                .orElseThrow().value();
        var finishing = (InscriberRecipe) manager.byKey(new ResourceLocation("appgen:inscriber/origination_processor"))
                .orElseThrow().value();
        helper.assertTrue(printing.getMiddleInput().test(ember) && printing.getResultItem(level.registryAccess()).is(printed.getItem())
                        && finishing.getTopOptional().test(printed) && finishing.getResultItem(level.registryAccess()).is(processor.getItem()),
                "bulk output follows the real inscriber processor chain");
        helper.assertTrue(bulk.isPresent() && bulk.get().value() instanceof OverloadProcessingRecipe, "conditional bulk recipe loaded");
        var recipe = (OverloadProcessingRecipe) bulk.orElseThrow().value();
        helper.assertTrue(recipe.itemInputs().size() == 3 && recipe.itemInputs().get(0).count() == 9
                        && recipe.itemInputs().get(1).count() == 4 && recipe.itemInputs().get(2).count() == 4,
                "36 processors require 9 ember blocks, 4 redstone blocks, and 4 silicon blocks");
        helper.assertTrue(recipe.totalEnergy() == 400_000 && recipe.lightningCost() == 1
                        && recipe.lightningTier() == LightningKey.Tier.HIGH_VOLTAGE,
                "400000 FE and 1 high-voltage lightning match other bulk processors");
        helper.assertTrue(recipe.itemResults().size() == 1 && recipe.itemResults().get(0).is(processor.getItem())
                        && recipe.itemResults().get(0).getCount() == 36, "exactly 36 actual AppGen processors");
        var siliconOptions = recipe.itemInputs().get(2).ingredient().getItems();
        helper.assertTrue(siliconOptions.length > 0, "silicon block tag resolves with Applied Flux present");
        var inventory = new OverloadProcessingFactoryInventory(null);
        inventory.setStackInSlot(0, emberBlock.copyWithCount(9));
        inventory.setStackInSlot(1, new ItemStack(Items.REDSTONE_BLOCK, 4));
        inventory.setStackInSlot(2, siliconOptions[0].copyWithCount(4));
        var input = OverloadProcessingRecipeInput.fromInventory(inventory, FluidStack.EMPTY);
        helper.assertTrue(recipe.matches(input, level), "real tagged inputs match");
        var candidate = OverloadProcessingRecipeService.findFirstProcessable(level, inventory,
                FluidStack.EMPTY, FluidStack.EMPTY, 1, 0);
        helper.assertTrue(candidate.isPresent() && candidate.get().recipe().id().equals(BULK)
                        && candidate.get().parallel() == 1, "factory recipe selection accepts the new processor");
        helper.assertTrue(OverloadProcessingRecipeService.findFirstProcessable(level, inventory,
                FluidStack.EMPTY, FluidStack.EMPTY, 0, 0).isEmpty(), "lightning requirement is retained");
        inventory.setStackInSlot(0, emberBlock.copyWithCount(8));
        helper.assertTrue(OverloadProcessingRecipeService.findFirstProcessable(level, inventory,
                FluidStack.EMPTY, FluidStack.EMPTY, 1, 0).isEmpty(), "eight ember blocks are insufficient");
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        try {
            var codec = new OverloadProcessingRecipe.Serializer().streamCodec();
            codec.encode(buffer, recipe);
            var copy = codec.decode(buffer);
            helper.assertTrue(copy.matches(input, level) && copy.totalEnergy() == 400_000
                            && copy.itemResults().get(0).is(processor.getItem())
                            && copy.itemResults().get(0).getCount() == 36,
                    "client synchronization preserves the optional recipe, ingredients, energy, and result");
        } finally {
            buffer.release();
        }
        helper.succeed();
    }

    private static ItemStack item(String id) {
        return new ItemStack(BuiltInRegistries.ITEM.getOptional(new ResourceLocation(id)).orElseThrow());
    }

    @GameTest(template = "pigmee_station_empty")
    public static void emberSynthesisPreservesUpstreamReaction(GameTestHelper helper) throws Exception {
        assertReactionRecipe(helper, "ember_crystal");
    }

    @GameTest(template = "pigmee_station_empty")
    public static void emberDuplicationPreservesUpstreamReaction(GameTestHelper helper) throws Exception {
        assertReactionRecipe(helper, "ember_crystal_duplicate");
    }

    @GameTest(template = "pigmee_station_empty")
    public static void emberChargingPreservesUpstreamReaction(GameTestHelper helper) throws Exception {
        assertReactionRecipe(helper, "charged_ember_crystal");
    }

    private static void assertReactionRecipe(GameTestHelper helper, String name) throws Exception {
        var level = helper.getLevel();
        var id = new ResourceLocation("appgen:reaction/" + name);
        var holder = OverloadProcessingRecipeService.findRecipeById(level, id);
        if (!ModList.get().isLoaded("appgen") || !ModList.get().isLoaded("advanced_ae")) {
            helper.assertTrue(holder.isEmpty(), name + " must be excluded without AppGen");
            helper.succeed();
            return;
        }
        helper.assertTrue(holder.isPresent(), name + " must be borrowed from the reaction catalog");
        var recipe = holder.orElseThrow().value();
        // Compare against the optional mod's resource, independently of the execution view.
        var source = level.getServer().getResourceManager().getResourceOrThrow(
                new ResourceLocation("appgen:recipe/reaction/" + name + ".json"));
        try (var reader = source.openAsReader()) {
            var upstream = JsonParser.parseReader(reader).getAsJsonObject();
            var inputs = upstream.getAsJsonArray("input_items");
            helper.assertTrue(recipe.itemInputs().size() == inputs.size(), "upstream ingredient count retained");
            var inventory = new OverloadProcessingFactoryInventory(null);
            for (int slot = 0; slot < inputs.size(); slot++) {
                var entry = inputs.get(slot).getAsJsonObject();
                var stack = item(entry.getAsJsonObject("ingredient").get("item").getAsString())
                        .copyWithCount(entry.get("amount").getAsInt());
                var converted = recipe.itemInputs().get(slot);
                helper.assertTrue(converted.count() == stack.getCount() && converted.ingredient().test(stack),
                        "upstream item and amount retained in slot " + slot);
                inventory.setStackInSlot(slot, stack);
            }
            var upstreamFluid = upstream.getAsJsonObject("input_fluid");
            var fluid = new FluidStack(BuiltInRegistries.FLUID.getOptional(new ResourceLocation(
                    upstreamFluid.getAsJsonObject("ingredient").get("fluid").getAsString())).orElseThrow(),
                    upstreamFluid.get("amount").getAsInt());
            helper.assertTrue(recipe.inputFluidAmount() == fluid.getAmount() && recipe.hasRequiredFluid(fluid, 1),
                    "upstream lava amount retained");
            var output = upstream.getAsJsonObject("output");
            var result = item(output.get("id").getAsString()).copyWithCount(output.get("#").getAsInt());
            helper.assertTrue(recipe.itemResults().size() == 1
                            && ItemStack.matches(recipe.itemResults().get(0), result) && recipe.fluidResult().isEmpty(),
                    "upstream output and yield retained");
            helper.assertTrue(recipe.totalEnergy() == upstream.get("input_energy").getAsLong()
                            && recipe.lightningCost() == 1 && recipe.lightningTier() == LightningKey.Tier.HIGH_VOLTAGE,
                    "upstream FE retained with the standard one high-voltage lightning cost");
            var candidate = OverloadProcessingRecipeService.findFirstProcessable(level, inventory,
                    fluid, FluidStack.EMPTY, 1, 0);
            helper.assertTrue(candidate.isPresent() && candidate.get().recipe().id().equals(id)
                            && candidate.get().parallel() == 1, "factory selects the reaction with actual registered inputs");
            helper.assertTrue(OverloadProcessingRecipeService.findFirstProcessable(level, inventory,
                    fluid, FluidStack.EMPTY, 0, 0).isEmpty(), "lightning remains required");
            helper.assertTrue(OverloadProcessingRecipeService.findFirstProcessable(level, inventory,
                    new FluidStack(Fluids.WATER, fluid.getAmount()), FluidStack.EMPTY, 1, 0).isEmpty(),
                    "water cannot replace lava");
            helper.assertTrue(OverloadProcessingRecipeService.findFirstProcessable(level, inventory,
                    fluid.copyWithAmount(fluid.getAmount() - 1), FluidStack.EMPTY, 1, 0).isEmpty(),
                    "insufficient lava cannot start a reaction");
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
            try {
                var sourceRecipe = (net.pedroksl.advanced_ae.recipes.ReactionChamberRecipe)
                        level.getRecipeManager().byKey(id).orElseThrow().value();
                var codec = net.pedroksl.advanced_ae.recipes.ReactionChamberRecipeSerializer.INSTANCE.streamCodec();
                codec.encode(buffer, sourceRecipe);
                var copy = codec.decode(buffer);
                helper.assertTrue(copy.getFluid().getIngredient().test(fluid)
                                && copy.getFluid().getAmount() == fluid.getAmount()
                                && ItemStack.matches(copy.getResultItem(), result)
                                && copy.getEnergy() == recipe.totalEnergy(),
                        "upstream client synchronization retains lava, yield and FE without an LT recipe copy");
            } finally {
                buffer.release();
            }
            var first = inventory.getStackInSlot(0);
            inventory.setStackInSlot(0, first.copyWithCount(first.getCount() - 1));
            helper.assertTrue(OverloadProcessingRecipeService.findFirstProcessable(level, inventory,
                    fluid, FluidStack.EMPTY, 1, 0).isEmpty(), "insufficient items cannot start a reaction");
        }
        helper.succeed();
    }
}
