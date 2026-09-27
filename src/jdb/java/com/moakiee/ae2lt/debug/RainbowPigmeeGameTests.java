package com.moakiee.ae2lt.debug;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.crafting.pattern.AECraftingPattern;
import com.moakiee.ae2lt.block.FumoBlock;
import com.moakiee.ae2lt.blockentity.FumoBlockEntity;
import com.moakiee.ae2lt.event.ArtificialLightningHandler;
import com.moakiee.ae2lt.event.LightningItemTransformationHandler;
import com.moakiee.ae2lt.lightning.ProtectedItemEntityHelper;
import com.moakiee.ae2lt.lightning.RainbowPigmeeTransformation;
import com.moakiee.ae2lt.recipe.RainbowPigmeeDyeRecipe;
import com.moakiee.ae2lt.registry.ModFumos;
import com.moakiee.ae2lt.registry.ModItems;
import com.moakiee.ae2lt.registry.ModRecipeTypes;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;

import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("ae2lt")
@PrefixGameTestTemplate(false)
public final class RainbowPigmeeGameTests {
    private static final BlockPos POS = new BlockPos(3, 2, 3);

    private static CraftingRecipe recipe(GameTestHelper h, DyeColor color) {
        var holder = h.getLevel().getRecipeManager().byKey(
                new ResourceLocation("ae2lt:rainbow_dye/" + color.getName())).orElseThrow();
        return (CraftingRecipe) holder;
    }

    private static ItemStack named(boolean rainbow, int count, String name) {
        var stack = new ItemStack(rainbow ? ModFumos.RAINBOW_PIGMEE_FUMO_ITEM.get() : ModFumos.PIGMEE_FUMO_ITEM.get(), count);
        stack.setHoverName(Component.literal(name));
        stack.setRepairCost(7);
        return stack;
    }

    private static List<ItemStack> ingredients(CraftingRecipe recipe) {
        return recipe.getIngredients().stream().map(i -> i.isEmpty() ? ItemStack.EMPTY : i.getItems()[0].copy()).toList();
    }

    @GameTest(template = "pigmee_station_empty")
    public static void recipeUnlockAdvancementsLoadOnForge(GameTestHelper h) {
        var manager = h.getLevel().getServer().getAdvancements();
        var ids = new ArrayList<String>();
        ids.add("recipes/pigmee_building_block");
        ids.add("recipes/pigmee_slabs/pigmee_building_slab");
        for (var color : DyeColor.values()) {
            ids.add("recipes/rainbow_dye/" + color.getName());
            ids.add("recipes/pigmee_slabs/" + color.getName() + "_pigmee_building_slab");
            ids.add("recipes/pigmee_slabs/" + color.getName() + "_pigmee_framed_building_slab");
        }
        for (String id : ids) h.assertTrue(manager.getAdvancement(new ResourceLocation("ae2lt", id)) != null,
                "Recipe unlock advancement did not load: " + id);
        h.succeed();
    }

    @GameTest(template = "pigmee_station_empty")
    public static void allLayoutsAreUniqueAndNetworkSerializable(GameTestHelper h) {
        var recipes = new ArrayList<RainbowPigmeeDyeRecipe>();
        for (var color : DyeColor.values()) {
            var recipe = (RainbowPigmeeDyeRecipe) recipe(h, color);
            recipes.add(recipe);
            var input = ForgeCraftingInput.of(3, 3, ingredients(recipe));
            h.assertTrue(recipe.matches(input, h.getLevel()) && !recipe.isSpecial(), "Visible exact shaped recipe " + color);
            var buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                ModRecipeTypes.RAINBOW_PIGMEE_DYE_SERIALIZER.get().toNetwork(buffer, recipe);
                var copy = ModRecipeTypes.RAINBOW_PIGMEE_DYE_SERIALIZER.get().fromNetwork(recipe.getId(), buffer);
                h.assertTrue(copy.matches(input, h.getLevel()) && copy.assemble(input, h.getLevel().registryAccess()).getCount() == 4,
                        "Network recipe changed " + color);
            } finally { buffer.release(); }
        }
        int layouts = 0;
        // Every possible arrangement, including 0..8 bases: no ambiguous, cheaper or shifted recipe.
        int[] outer = {0, 1, 2, 3, 5, 6, 7, 8};
        for (int mask = 0; mask < 256; mask++) {
            var items = new ArrayList<ItemStack>();
            for (int i = 0; i < 9; i++) items.add(ItemStack.EMPTY);
            items.set(4, named(true, 1, "Keep this name"));
            for (int bit = 0; bit < 8; bit++) if ((mask & (1 << bit)) != 0) items.set(outer[bit], new ItemStack(ModItems.DYE_BASE.get()));
            var input = ForgeCraftingInput.of(3, 3, items);
            long count = recipes.stream().filter(r -> r.matches(input, h.getLevel())).count();
            h.assertTrue(count <= 1, "Ambiguous dye layout " + mask);
            if (count == 1) {
                h.assertTrue(Integer.bitCount(mask) == 4, "Dye must cost exactly four bases");
                layouts++;
            }
        }
        h.assertTrue(layouts == 16, "Expected exactly 16 accepted layouts, got " + layouts);
        h.succeed();
    }

    @GameTest(template = "pigmee_station_empty")
    public static void allDyesCraftAndShiftCraftWithReusableNamedCatalyst(GameTestHelper h) {
        var player = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "RainbowQA"));
        h.setBlock(POS, Blocks.CRAFTING_TABLE);
        for (var color : DyeColor.values()) {
            player.getInventory().clearContent();
            var recipe = recipe(h, color);
            var menu = new CraftingMenu(31, player.getInventory(), ContainerLevelAccess.create(h.getLevel(), h.absolutePos(POS)));
            player.containerMenu = menu;
            var items = ingredients(recipe);
            for (int i = 0; i < 9; i++) menu.getSlot(i + 1).set(i == 4 ? named(true, 2, "My catalyst")
                    : items.get(i).isEmpty() ? ItemStack.EMPTY : items.get(i).copyWithCount(3));
            var output = recipe.getResultItem(h.getLevel().registryAccess());
            menu.clicked(0, 0, ClickType.PICKUP, player);
            h.assertTrue(ItemStack.matches(menu.getCarried(), output), "Native crafting result " + color);
            player.getInventory().add(menu.getCarried());
            menu.setCarried(ItemStack.EMPTY);
            menu.clicked(0, 0, ClickType.QUICK_MOVE, player);
            h.assertTrue(player.getInventory().items.stream().filter(s -> s.is(output.getItem())).mapToInt(ItemStack::getCount).sum() == 12,
                    "Three crafts must yield twelve dyes " + color);
            h.assertTrue(ItemStack.matches(menu.getSlot(5).getItem(), named(true, 2, "My catalyst")), "Catalyst count/components changed");
            h.assertTrue(menu.getSlot(0).getItem().isEmpty(), "Crafting must stop when bases run out");
        }
        h.succeed();
    }

    @GameTest(template = "pigmee_station_empty")
    public static void ae2PatternsPreserveEveryColourAndReturnTheCatalyst(GameTestHelper h) {
        for (var color : DyeColor.values()) {
            var holder = recipe(h, color);
            var items = new ArrayList<>(ingredients(holder));
            items.set(4, named(true, 1, "Encoded catalyst"));
            var input = ForgeCraftingInput.of(3, 3, items);
            var expected = holder.assemble(input, h.getLevel().registryAccess());
            var encoded = PatternDetailsHelper.encodeCraftingPattern(holder, items.toArray(ItemStack[]::new), expected, false, false);
            var decoded = PatternDetailsHelper.decodePattern(encoded, h.getLevel());
            h.assertTrue(decoded instanceof AECraftingPattern, "AE2 cannot decode " + color);
            var pattern = (AECraftingPattern) decoded;
            h.assertTrue(ItemStack.matches(pattern.assemble(input, h.getLevel()), expected), "AE2 changed dye " + color);
            h.assertTrue(ItemStack.matches(pattern.getRemainingItems(input).get(4), items.get(4)), "AE2 lost reusable Pigmee " + color);
        }
        h.succeed();
    }

    @GameTest(template = "pigmee_station_empty")
    public static void baseRecipeAndWrongCatalysts(GameTestHelper h) {
        var base = (CraftingRecipe) h.getLevel().getRecipeManager().byKey(new ResourceLocation("ae2lt:dye_base")).orElseThrow();
        var input = ForgeCraftingInput.of(2, 1, List.of(new ItemStack(Items.CLAY_BALL), appeng.core.definitions.AEItems.SILICON.stack()));
        h.assertTrue(base.matches(input, h.getLevel()) && base.assemble(input, h.getLevel().registryAccess()).getCount() == 4, "Clay and silicon yield four bases");
        for (var color : DyeColor.values()) {
            var recipe = recipe(h, color);
            for (var wrong : List.of(ModFumos.PIGMEE_FUMO_ITEM.get(), ModFumos.CREATIVE_PIGMEE_FUMO_ITEM.get(), ModFumos.HYPERDIMENSIONAL_PIGMEE_FUMO_ITEM.get())) {
                var items = new ArrayList<>(ingredients(recipe)); items.set(4, new ItemStack(wrong));
                h.assertTrue(!recipe.matches(ForgeCraftingInput.of(3, 3, items), h.getLevel()), "Wrong catalyst accepted");
            }
        }
        h.succeed();
    }

    private static ItemEntity drop(GameTestHelper h, ItemStack stack) {
        var pos = Vec3.atCenterOf(h.absolutePos(POS));
        var entity = new ItemEntity(h.getLevel(), pos.x, pos.y, pos.z, stack);
        entity.setNoGravity(true); entity.setDeltaMovement(Vec3.ZERO);
        h.getLevel().addFreshEntity(entity);
        return entity;
    }

    private static LightningBolt bolt(GameTestHelper h) {
        var bolt = EntityType.LIGHTNING_BOLT.create(h.getLevel());
        bolt.moveTo(Vec3.atCenterOf(h.absolutePos(POS)));
        return bolt;
    }

    @GameTest(template = "pigmee_station_empty")
    public static void vanillaNameIsExactAndBoltIsHandledOnlyOnce(GameTestHelper h) {
        var valid = drop(h, named(false, 12, "jeb_"));
        var invalid = new ArrayList<ItemEntity>();
        for (var name : List.of("Jeb_", "jeb", "jeb_ ", " jeb_", "JEB_")) invalid.add(drop(h, named(false, 1, name)));
        var creative = drop(h, new ItemStack(ModFumos.CREATIVE_PIGMEE_FUMO_ITEM.get()));
        creative.getItem().setHoverName(Component.literal("jeb_"));
        var bolt = bolt(h);
        LightningItemTransformationHandler.handleLightningTick(bolt);
        h.assertTrue(valid.getItem().is(ModFumos.RAINBOW_PIGMEE_FUMO_ITEM.get()) && valid.getItem().getCount() == 12, "Conversion changed quantity");
        h.assertTrue(valid.getItem().getBaseRepairCost() == 7 && !valid.getItem().hasCustomHoverName(), "Components/default display name lost");
        h.assertTrue(ProtectedItemEntityHelper.isProtectedItem(valid), "Output is vulnerable to the same bolt");
        for (var entity : invalid) h.assertTrue(entity.getItem().is(ModFumos.PIGMEE_FUMO_ITEM.get()), "Wrong name converted");
        h.assertTrue(creative.getItem().is(ModFumos.CREATIVE_PIGMEE_FUMO_ITEM.get()), "Creative Pigmee converted");
        var late = drop(h, named(false, 1, "jeb_"));
        LightningItemTransformationHandler.handleLightningTick(bolt);
        h.assertTrue(late.getItem().is(ModFumos.PIGMEE_FUMO_ITEM.get()), "One bolt converted a late arrival twice");
        h.succeed();
    }

    @GameTest(template = "pigmee_station_empty", timeoutTicks = 80)
    public static void actualLightningKeepsItsConvertedOutputAlive(GameTestHelper h) {
        var entity = drop(h, named(false, 4, "jeb_"));
        h.getLevel().addFreshEntity(bolt(h));
        h.runAfterDelay(20, () -> {
            h.assertTrue(entity.isAlive() && entity.getItem().is(ModFumos.RAINBOW_PIGMEE_FUMO_ITEM.get()) && entity.getItem().getCount() == 4,
                    "Vanilla lightning damaged or failed to convert the output");
            h.succeed();
        });
    }

    @GameTest(template = "pigmee_station_empty", timeoutTicks = 80)
    public static void artificialLightningConvertsTheNamedPigmee(GameTestHelper h) {
        var entity = drop(h, named(false, 3, "jeb_"));
        ArtificialLightningHandler.spawnArtificialLightning(h.getLevel(), Vec3.atCenterOf(h.absolutePos(POS)), null);
        h.runAfterDelay(20, () -> {
            h.assertTrue(entity.isAlive() && entity.getItem().is(ModFumos.RAINBOW_PIGMEE_FUMO_ITEM.get()) && entity.getItem().getCount() == 3,
                    "Artificial lightning failed conversion");
            h.succeed();
        });
    }

    @GameTest(template = "pigmee_station_empty")
    public static void placedPigmeeKeepsItsNameThroughSaveDropAndConversion(GameTestHelper h) {
        h.setBlock(POS.below(), Blocks.STONE);
        var player = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "PlacedPigmeeQA"));
        var stack = named(false, 1, "jeb_");
        var abs = h.absolutePos(POS);
        var context = new BlockPlaceContext(h.getLevel(), player, InteractionHand.MAIN_HAND, stack,
                new BlockHitResult(Vec3.atCenterOf(abs.below()).add(0, 0.5, 0), Direction.UP, abs.below(), false));
        h.assertTrue(((BlockItem) stack.getItem()).place(context).consumesAction(), "Native block placement failed");
        FumoBlockEntity be = (FumoBlockEntity) h.getBlockEntity(POS);
        h.assertTrue(RainbowPigmeeTransformation.matchesName(be.getCustomName()), "Placed block lost its item name");
        var saved = be.saveWithFullMetadata();
        be.setCustomName(null);
        be.load(saved);
        h.assertTrue(RainbowPigmeeTransformation.matchesName(be.getCustomName()), "Saved name did not reload");
        var drops = Block.getDrops(be.getBlockState(), h.getLevel(), abs, be);
        h.assertTrue(drops.size() == 1 && RainbowPigmeeTransformation.matchesName(drops.get(0).getHoverName()), "Breaking loses the name");
        var state = be.getBlockState().setValue(FumoBlock.FACING, Direction.EAST).setValue(FumoBlock.WATERLOGGED, true);
        h.getLevel().setBlockAndUpdate(abs, state);
        be.toggleSpinning();
        RainbowPigmeeTransformation.handleLightning(h.getLevel(), bolt(h));
        FumoBlockEntity result = (FumoBlockEntity) h.getBlockEntity(POS);
        h.assertTrue(result.getBlockState().is(ModFumos.RAINBOW_PIGMEE_FUMO.get()) && result.getCustomName() == null,
                "Named block conversion failed");
        h.assertTrue(result.isSpinning() && result.getBlockState().getValue(FumoBlock.FACING) == Direction.EAST
                && result.getBlockState().getValue(FumoBlock.WATERLOGGED), "Conversion changed facing, water or spin");
        h.succeed();
    }
    private static List<net.minecraft.world.level.block.Block> slabs() {
        var list = new ArrayList<net.minecraft.world.level.block.Block>();
        list.add(com.moakiee.ae2lt.registry.ModBlocks.PIGMEE_BUILDING_SLAB.get());
        for (var color : DyeColor.values()) {
            list.add(com.moakiee.ae2lt.registry.ModBlocks.PIGMEE_BUILDING_SLABS.get(color).get());
            list.add(com.moakiee.ae2lt.registry.ModBlocks.PIGMEE_FRAMED_BUILDING_SLABS.get(color).get());
        }
        return list;
    }

    @GameTest(template = "pigmee_station_empty")
    public static void allThirtyThreeSlabsConserveMaterialThroughEveryRecipe(GameTestHelper h) {
        var restore = (CraftingRecipe) h.getLevel().getRecipeManager().byKey(new ResourceLocation("ae2lt:pigmee_building_block_from_slabs")).orElseThrow();
        var cuts = h.getLevel().getRecipeManager().getAllRecipesFor(net.minecraft.world.item.crafting.RecipeType.STONECUTTING);
        for (var slab : slabs()) {
            var stack = new ItemStack(slab);
            var single = new net.minecraft.world.SimpleContainer(stack);
            var matched = cuts.stream().filter(r -> r.matches(single, h.getLevel())).toList();
            h.assertTrue(matched.size() == 33, "Slabs must only recut into 33 other slabs");
            for (var cut : matched) h.assertTrue(cut.getResultItem(h.getLevel().registryAccess()).getCount() == 1,
                    "Slab recutting creates extra material");
            h.assertTrue(!restore.matches(ForgeCraftingInput.of(1, 1, List.of(stack)), h.getLevel()), "One slab cannot return a full block");
            var input = ForgeCraftingInput.of(2, 1, List.of(stack, new ItemStack(slabs().get(0))));
            h.assertTrue(restore.matches(input, h.getLevel()) && restore.assemble(input, h.getLevel().registryAccess()).getCount() == 1,
                    "Two mixed slabs should return one base block");
            var id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(slab).getPath();
            var recipe = (CraftingRecipe) h.getLevel().getRecipeManager().byKey(new ResourceLocation("ae2lt:pigmee_slabs/" + id)).orElseThrow();
            var craft = ForgeCraftingInput.of(3, 1, ingredients(recipe));
            h.assertTrue(recipe.matches(craft, h.getLevel()) && recipe.assemble(craft, h.getLevel().registryAccess()).getCount() == 6,
                    "Three blocks must craft six slabs");
        }
        h.succeed();
    }

    @GameTest(template = "pigmee_station_empty")
    public static void slabsHaveNativeShapeWaterloggingAndDoubleDrops(GameTestHelper h) {
        for (var block : slabs()) {
            for (var type : net.minecraft.world.level.block.state.properties.SlabType.values()) {
                var state = block.defaultBlockState().setValue(net.minecraft.world.level.block.SlabBlock.TYPE, type);
                h.setBlock(POS, state);
                h.assertTrue(!state.hasBlockEntity() && new ItemStack(Items.WOODEN_PICKAXE).isCorrectToolForDrops(state), "Slab should be a simple mineable block");
                var shape = state.getShape(h.getLevel(), h.absolutePos(POS)).bounds();
                double min = type == net.minecraft.world.level.block.state.properties.SlabType.TOP ? 0.5 : 0;
                double max = type == net.minecraft.world.level.block.state.properties.SlabType.BOTTOM ? 0.5 : 1;
                h.assertTrue(shape.minY == min && shape.maxY == max, "Slab collision bounds incorrect");
                var drops = Block.getDrops(state, h.getLevel(), h.absolutePos(POS), null);
                int expected = type == net.minecraft.world.level.block.state.properties.SlabType.DOUBLE ? 2 : 1;
                h.assertTrue(drops.size() == 1 && drops.get(0).is(block.asItem()) && drops.get(0).getCount() == expected,
                        "Double slab must drop two slabs");
                if (type != net.minecraft.world.level.block.state.properties.SlabType.DOUBLE) {
                    var wet = state.setValue(net.minecraft.world.level.block.SlabBlock.WATERLOGGED, true);
                    h.assertTrue(wet.getFluidState().isSource(), "Half slab must retain its water");
                }
            }
        }
        h.succeed();
    }

    @GameTest(template = "pigmee_station_empty")
    public static void nativeSlabPlacementMergesTwoAndConsumesExactlyTwoItems(GameTestHelper h) {
        var player = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "SlabQA"));
        for (var block : slabs()) {
            h.setBlock(POS, Blocks.AIR); h.setBlock(POS.below(), Blocks.STONE);
            var stack = new ItemStack(block, 2);
            var abs = h.absolutePos(POS);
            var first = new BlockPlaceContext(h.getLevel(), player, InteractionHand.MAIN_HAND, stack,
                    new BlockHitResult(Vec3.atCenterOf(abs.below()).add(0, 0.5, 0), Direction.UP, abs.below(), false));
            h.assertTrue(((BlockItem)stack.getItem()).place(first).consumesAction() && stack.getCount() == 1, "First slab placement");
            var second = new BlockPlaceContext(h.getLevel(), player, InteractionHand.MAIN_HAND, stack,
                    new BlockHitResult(Vec3.atCenterOf(abs), Direction.UP, abs, false));
            h.assertTrue(((BlockItem)stack.getItem()).place(second).consumesAction() && stack.isEmpty(), "Second slab placement");
            h.assertTrue(h.getLevel().getBlockState(abs).getValue(net.minecraft.world.level.block.SlabBlock.TYPE)
                    == net.minecraft.world.level.block.state.properties.SlabType.DOUBLE, "Native slab merge failed");
        }
        h.succeed();
    }

}
