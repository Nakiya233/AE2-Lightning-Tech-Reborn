package com.moakiee.ae2lt.compat.mining;

import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.Tags;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

/** Forge 1.20.1 Apotheosis Earth's Boon, without firing block events or spawning drops. */
public final class ApothicMiningLoot {
    private static final ResourceLocation BOON = new ResourceLocation("apotheosis", "earths_boon");
    private static final TagKey<Item> DROPS = TagKey.create(Registries.ITEM,
            new ResourceLocation("apotheosis", "boon_drops"));
    private ApothicMiningLoot() {}

    public static void addDrops(ServerLevel level, BlockPos origin, BlockState state, ItemStack tool,
                                Consumer<ItemStack> output) {
        if (!ModList.get().isLoaded("apotheosis") || !state.is(Tags.Blocks.STONE)) return;
        var enchantment = ForgeRegistries.ENCHANTMENTS.getValue(BOON);
        if (enchantment == null) return;
        int rank = tool.getEnchantmentLevel(enchantment);
        if (rank > 0 && level.random.nextFloat() <= 0.01F * rank) {
            var item = ForgeRegistries.ITEMS.tags().getTag(DROPS).getRandomElement(level.random).orElse(Items.AIR);
            if (item != Items.AIR) output.accept(new ItemStack(item));
        }
    }
}
