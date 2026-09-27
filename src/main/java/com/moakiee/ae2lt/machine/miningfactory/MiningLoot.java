package com.moakiee.ae2lt.machine.miningfactory;

import java.util.ArrayList;
import java.util.List;

import appeng.core.definitions.AEParts;
import com.moakiee.ae2lt.compat.mining.ApothicMiningLoot;
import com.moakiee.ae2lt.compat.mining.BloodMagicMiningTool;
import net.minecraft.core.BlockPos;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;


import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Loot-table evaluation only: no placed blocks, break events or tool mineBlock callbacks. */
public final class MiningLoot {
    private MiningLoot() {}

    public static boolean isPlane(ItemStack stack) {
        return stack.is(AEParts.ANNIHILATION_PLANE.asItem());
    }

    public static boolean isTool(ItemStack stack) {
        // Energy-only tools need an explicit cost adapter; accepting them here would permit free use.
        return isPlane(stack) || stack.getMaxDamage() > 0 && (stack.canPerformAction(net.minecraftforge.common.ToolActions.PICKAXE_DIG)
                || stack.canPerformAction(net.minecraftforge.common.ToolActions.AXE_DIG)
                || stack.canPerformAction(net.minecraftforge.common.ToolActions.SHOVEL_DIG)
                || stack.canPerformAction(net.minecraftforge.common.ToolActions.HOE_DIG)
                || stack.canPerformAction(net.minecraftforge.common.ToolActions.SWORD_DIG)
                || stack.canPerformAction(net.minecraftforge.common.ToolActions.SHEARS_DIG));
    }

    public static BlockState stateOf(ItemStack input) {
        if (!(input.getItem() instanceof BlockItem item) || BlockItem.getBlockEntityData(input) != null) {
            return null;
        }
        BlockState state = item.getBlock().defaultBlockState();
        var tag = input.getTagElement("BlockStateTag");
        if (tag != null) {
            for (String name : tag.getAllKeys()) {
                var property = state.getBlock().getStateDefinition().getProperty(name);
                if (property != null) state = applyProperty(state, property, tag.getString(name));
            }
        }
        // Container contents and other block-entity state cannot be reconstructed from a default state.
        return state.hasBlockEntity() || state.isAir() ? null : state;
    }

    private static <T extends Comparable<T>> BlockState applyProperty(BlockState state,
            net.minecraft.world.level.block.state.properties.Property<T> property, String value) {
        return property.getValue(value).map(parsed -> state.setValue(property, parsed)).orElse(state);
    }

    public static ItemStack lootTool(ItemStack installed, BlockState state) {
        if (!isPlane(installed)) {
            return copyTool(installed);
        }
        // Same diamond-tier tool selection as AE2's ItemPickupStrategy.
        var item = state.is(BlockTags.MINEABLE_WITH_PICKAXE) ? Items.DIAMOND_PICKAXE
                : state.is(BlockTags.MINEABLE_WITH_AXE) ? Items.DIAMOND_AXE
                : state.is(BlockTags.MINEABLE_WITH_SHOVEL) ? Items.DIAMOND_SHOVEL
                : state.is(BlockTags.MINEABLE_WITH_HOE) ? Items.DIAMOND_HOE : Items.DIAMOND_PICKAXE;
        ItemStack tool = new ItemStack(item);
        net.minecraft.world.item.enchantment.EnchantmentHelper.setEnchantments(
                net.minecraft.world.item.enchantment.EnchantmentHelper.getEnchantments(installed), tool);
        return tool;
    }

    public static boolean canHarvest(BlockState state, ItemStack installed) {
        return !state.requiresCorrectToolForDrops() || lootTool(installed, state).isCorrectToolForDrops(state);
    }

    public record Drop(ItemStack stack, long count) {}
    public record Result(int processed, int samples, ItemStack tool, List<Drop> drops) {}

    private static ItemStack copyTool(ItemStack original) {
        ItemStack copy = original.copy();
        return copy;
    }

    public static Result roll(ServerLevel level, BlockPos origin, BlockState state, ItemStack installed,
                              int count, int sampleBudget) {
        ItemStack remainingTool = copyTool(installed);
        boolean plane = isPlane(installed);
        var anointments = plane ? null : BloodMagicMiningTool.read(remainingTool);
        if (anointments != null) count = anointments.limit(count);
        int groups = Math.min(count, sampleBudget);
        int processed = 0;
        int sampled = 0;
        List<Drop> drops = new ArrayList<>();
        for (int group = 0; group < groups && !remainingTool.isEmpty(); group++) {
            int requested = count / groups + (group < count % groups ? 1 : 0);
            ItemStack sampleTool = lootTool(remainingTool, state);
            int actual = 0;
            for (; actual < requested && !remainingTool.isEmpty(); actual++) {
                if (!plane && state.getDestroySpeed(level, origin) != 0) {
                    // Cheap per-block durability evaluation preserves Unbreaking and stops at breakage.
                    int damage = remainingTool.getItem() instanceof net.minecraft.world.item.SwordItem ? 2 : 1;
                    if (remainingTool.hurt(damage, level.random, null)) {
                        remainingTool.shrink(1);
                        remainingTool.setDamageValue(0);
                    }
                }
            }
            if (actual == 0) {
                break;
            }
            for (ItemStack drop : Block.getDrops(state, level, origin, null, null, sampleTool)) {
                if (!drop.isEmpty()) {
                    add(drops, drop, (long) drop.getCount() * actual);
                }
            }
            int weight = actual;
            ApothicMiningLoot.addDrops(level, origin, state, sampleTool,
                    drop -> { if (!drop.isEmpty()) add(drops, drop, (long) drop.getCount() * weight); });
            processed += actual;
            sampled++;
        }
        if (anointments != null) anointments.consume(remainingTool, processed);
        return new Result(processed, sampled, remainingTool, List.copyOf(drops));
    }

    private static void add(List<Drop> drops, ItemStack stack, long count) {
        for (int i = 0; i < drops.size(); i++) {
            Drop previous = drops.get(i);
            if (ItemStack.isSameItemSameTags(previous.stack(), stack)) {
                drops.set(i, new Drop(previous.stack(), Math.addExact(previous.count(), count)));
                return;
            }
        }
        drops.add(new Drop(stack.copyWithCount(1), count));
    }
}
