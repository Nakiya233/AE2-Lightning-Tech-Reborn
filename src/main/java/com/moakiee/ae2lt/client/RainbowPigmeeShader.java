package com.moakiee.ae2lt.client;

import java.io.IOException;

import com.moakiee.ae2lt.AE2LightningTech;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.client.event.RegisterShadersEvent;

/** The local tick offset is interpolated before colour evaluation, once per surface pixel. */
@EventBusSubscriber(modid = AE2LightningTech.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class RainbowPigmeeShader {
    private static ShaderInstance shader;

    private RainbowPigmeeShader() {
    }

    static ShaderInstance get() {
        return shader;
    }

    public static boolean isLoaded() {
        return shader != null;
    }

    @SubscribeEvent
    public static void registerShaders(RegisterShadersEvent event) throws IOException {
        event.registerShader(new TickShader(event.getResourceProvider()), loaded -> shader = loaded);
    }

    private static final class TickShader extends ShaderInstance {
        private final Uniform animationTicks;

        TickShader(ResourceProvider resources) throws IOException {
            super(resources, new ResourceLocation(AE2LightningTech.MODID, "rainbow_pigmee"),
                    DefaultVertexFormat.POSITION_TEX_COLOR);
            animationTicks = getUniform("AnimationTicks");
        }

        @Override
        public void apply() {
            if (animationTicks != null) {
                animationTicks.set((float) RainbowPigmeeColors.animationTicks());
            }
            super.apply();
        }
    }
}
