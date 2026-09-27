package com.moakiee.ae2lt.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.moakiee.ae2lt.registry.ModFumos;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Shared Pigmee silhouette renderer: portal or rainbow surface, then the face markings.
 */
final class HyperdimensionalPigmeeItemRenderer extends BlockEntityWithoutLevelRenderer {
    HyperdimensionalPigmeeItemRenderer(
            BlockEntityRenderDispatcher dispatcher, EntityModelSet entityModels) {
        super(dispatcher, entityModels);
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext displayContext, PoseStack poseStack,
            MultiBufferSource buffers, int packedLight, int packedOverlay) {
        Minecraft minecraft = Minecraft.getInstance();
        BakedModel wrappedModel =
                minecraft.getItemRenderer().getModel(stack, null, null, 0);
        if (!(wrappedModel instanceof HyperdimensionalPigmeeBakedModel hyperModel)) {
            return;
        }

        BakedModel model = hyperModel.baseModel();
        if (stack.is(ModFumos.RAINBOW_PIGMEE_FUMO_ITEM.get())) {
            RainbowPigmeeSurfaceLayer.renderItem(model, poseStack, buffers);
        } else {
            HyperdimensionalPigmeePortalLayer.renderItem(model, poseStack, buffers);
        }
        HyperdimensionalPigmeeTextureLayer.renderItem(
                poseStack, buffers, packedOverlay);
    }
}
