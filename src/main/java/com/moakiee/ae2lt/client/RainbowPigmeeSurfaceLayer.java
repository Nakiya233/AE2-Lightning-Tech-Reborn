package com.moakiee.ae2lt.client;

import java.util.List;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;

/** Reuses the portal Pigmee's silhouette with drifting, locally coloured light bands. */
final class RainbowPigmeeSurfaceLayer {
    private static final float SURFACE_OFFSET = 0.002F;
    private static final RenderType SURFACE = RenderType.create("ae2lt_rainbow_pigmee",
            DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS, 4096, false, false,
            RenderType.CompositeState.builder().setShaderState(
                    new RenderStateShard.ShaderStateShard(RainbowPigmeeShader::get))
                    .createCompositeState(false));

    private RainbowPigmeeSurfaceLayer() {
    }

    static void renderBlock(BakedModel model, BlockState state, ModelData data,
            PoseStack poses, MultiBufferSource buffers) {
        var consumer = buffers.getBuffer(SURFACE);
        var random = RandomSource.create();
        for (var type : model.getRenderTypes(state, random, data)) {
            for (var side : Direction.values()) {
                random.setSeed(42L);
                draw(poses.last(), consumer, model.getQuads(state, side, random, data, type));
            }
            random.setSeed(42L);
            draw(poses.last(), consumer, model.getQuads(state, null, random, data, type));
        }
    }

    static void renderItem(BakedModel model, PoseStack poses, MultiBufferSource buffers) {
        var consumer = buffers.getBuffer(SURFACE);
        var random = RandomSource.create();
        for (var side : Direction.values()) {
            random.setSeed(42L);
            draw(poses.last(), consumer, model.getQuads(null, side, random));
        }
        random.setSeed(42L);
        draw(poses.last(), consumer, model.getQuads(null, null, random));
    }

    private static void draw(PoseStack.Pose pose, VertexConsumer consumer, List<BakedQuad> quads) {
        for (var quad : quads) {
            int[] data = quad.getVertices();
            int stride = data.length / 4;
            for (int vertex = 0; vertex < 4; vertex++) {
                int offset = vertex * stride;
                vertex(pose, consumer, quad.getDirection(), Float.intBitsToFloat(data[offset]),
                        Float.intBitsToFloat(data[offset + 1]), Float.intBitsToFloat(data[offset + 2]));
            }
        }
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer consumer, Direction side,
            float x, float y, float z) {
        // Linear local offsets interpolate continuously, including across neighbouring faces.
        float tickOffset = x * 48.0F + y * 128.0F + z * 80.0F;
        float shade = switch (side) {
            case UP -> 1.0F;
            case DOWN -> 0.94F;
            case NORTH, SOUTH -> 0.98F;
            case EAST, WEST -> 0.96F;
        };
        consumer.vertex(pose.pose(), x + side.getStepX() * SURFACE_OFFSET,
                y + side.getStepY() * SURFACE_OFFSET, z + side.getStepZ() * SURFACE_OFFSET)
                .uv(tickOffset, 0.0F).color(shade, shade, shade, 1.0F).endVertex();
    }
}
