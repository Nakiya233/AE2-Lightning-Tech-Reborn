package com.moakiee.ae2lt.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;

/** Forge item renderer registration for the portal and rainbow baked-model wrappers. */
public final class FumoItemRenderExtensions implements IClientItemExtensions {
    public static final FumoItemRenderExtensions INSTANCE = new FumoItemRenderExtensions();
    private BlockEntityWithoutLevelRenderer renderer;

    private FumoItemRenderExtensions() {}

    @Override
    public BlockEntityWithoutLevelRenderer getCustomRenderer() {
        if (renderer == null) {
            var client = Minecraft.getInstance();
            renderer = new HyperdimensionalPigmeeItemRenderer(
                    client.getBlockEntityRenderDispatcher(), client.getEntityModels());
        }
        return renderer;
    }
}
