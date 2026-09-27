package com.moakiee.ae2lt.lightning;

import com.moakiee.ae2lt.block.FumoBlock;
import com.moakiee.ae2lt.blockentity.FumoBlockEntity;
import com.moakiee.ae2lt.registry.ModFumos;
import net.minecraft.core.BlockPos;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

/** A named Pigmee becomes a reusable rainbow catalyst, through either form of placement. */
public final class RainbowPigmeeTransformation {
    public static final String REQUIRED_NAME = "jeb_";

    private RainbowPigmeeTransformation() {
    }

    public static boolean matchesName(@Nullable Component name) {
        return name != null && REQUIRED_NAME.equals(name.getString());
    }

    // Called by the existing once-per-bolt handler, before lightning can damage its outputs.
    public static void handleLightning(ServerLevel level, LightningBolt bolt) {
        var bounds = new AABB(bolt.position(), bolt.position()).inflate(
                LightningTransformRules.SEARCH_HORIZONTAL_RADIUS,
                LightningTransformRules.SEARCH_VERTICAL_RADIUS,
                LightningTransformRules.SEARCH_HORIZONTAL_RADIUS);
        long now = level.getGameTime();
        for (var entity : level.getEntitiesOfClass(ItemEntity.class, bounds,
                item -> ProtectedItemEntityHelper.canParticipateInTransform(item, now))) {
            var stack = entity.getItem();
            if (stack.is(ModFumos.PIGMEE_FUMO_ITEM.get())
                    && matchesName((stack.hasCustomHoverName() ? stack.getHoverName() : null))) {
                var result = new net.minecraft.world.item.ItemStack(ModFumos.RAINBOW_PIGMEE_FUMO_ITEM.get(), stack.getCount());
                if (stack.hasTag()) result.setTag(stack.getTag().copy());
                // The trigger has served its purpose; show the new item's translated name.
                result.resetHoverName();
                entity.setItem(result);
                ProtectedItemEntityHelper.applyOutputProtection(entity, now);
            }
        }

        var center = bolt.blockPosition();
        int horizontal = (int) LightningTransformRules.SEARCH_HORIZONTAL_RADIUS;
        int vertical = (int) LightningTransformRules.SEARCH_VERTICAL_RADIUS;
        for (var pos : BlockPos.betweenClosed(center.offset(-horizontal, -vertical, -horizontal),
                center.offset(horizontal, vertical, horizontal))) {
            // Never load a neighbouring chunk just for the easter egg.
            if (!level.hasChunkAt(pos)) continue;
            var state = level.getBlockState(pos);
            if (!state.is(ModFumos.PIGMEE_FUMO.get())
                    || !(level.getBlockEntity(pos) instanceof FumoBlockEntity old)
                    || !matchesName(old.getCustomName())) continue;
            boolean spinning = old.isSpinning();
            var result = ModFumos.RAINBOW_PIGMEE_FUMO.get().defaultBlockState()
                    .setValue(FumoBlock.FACING, state.getValue(FumoBlock.FACING))
                    .setValue(FumoBlock.WATERLOGGED, state.getValue(FumoBlock.WATERLOGGED));
            level.setBlock(pos, result, Block.UPDATE_ALL);
            if (level.getBlockEntity(pos) instanceof FumoBlockEntity converted) {
                converted.setCustomName(null);
                if (converted.isSpinning() != spinning) converted.toggleSpinning();
            }
        }
    }
}
