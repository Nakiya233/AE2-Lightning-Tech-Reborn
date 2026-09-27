package com.moakiee.ae2lt.block;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.MapColor;

/** Vanilla slab placement, waterlogging and merging; the model provides decoration. */
public final class PigmeeBuildingSlabBlock extends SlabBlock {
    public PigmeeBuildingSlabBlock(MapColor color) {
        super(Properties.of().mapColor(color).strength(1.5F, 6.0F).sound(SoundType.STONE)
                .requiresCorrectToolForDrops());
    }

    @Override
    public void appendHoverText(ItemStack stack, net.minecraft.world.level.BlockGetter context, List<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack, context, lines, flag);
        lines.add(Component.translatable("tooltip.ae2lt.pigmee_building_slab").withStyle(ChatFormatting.GRAY));
    }
}
