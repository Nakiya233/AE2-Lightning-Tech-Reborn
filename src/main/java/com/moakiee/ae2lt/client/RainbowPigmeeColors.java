package com.moakiee.ae2lt.client;

import com.moakiee.ae2lt.AE2LightningTech;
import com.moakiee.ae2lt.registry.ModFumos;
import com.moakiee.ae2lt.blockentity.FumoBlockEntity;
import com.moakiee.ae2lt.lightning.RainbowPigmeeTransformation;
import net.minecraft.client.Minecraft;

import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.DyeColor;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.client.event.RegisterColorHandlersEvent;

/** Client-only colour animation; no server ticking or block updates are needed. */
@EventBusSubscriber(modid = AE2LightningTech.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class RainbowPigmeeColors {
    private RainbowPigmeeColors() {
    }

    public static int currentColor() {
        return 0xFF000000 | Mth.hsvToRgb((float) (animationTicks() % 240.0 / 240.0), 0.65F, 1.0F);
    }

    static double animationTicks() {
        var minecraft = Minecraft.getInstance();
        return minecraft.level == null ? 0 : minecraft.level.getGameTime() % 24000L
                + minecraft.getFrameTime();
    }

    /** Vanilla's dye sequence and 25-tick crossfade, lifted for the darker Pigmee texture. */
    public static int sheepColor() {
        double ticks = animationTicks();
        int index = (int) (ticks / 25.0);
        return FastColor.ARGB32.lerp((float) (ticks % 25.0 / 25.0),
                brighten(Sheep.getColorArray(DyeColor.byId(index % 16))),
                brighten(Sheep.getColorArray(DyeColor.byId((index + 1) % 16))));
    }

    private static int brighten(float[] color) {
        int r = Math.round(color[0] * 255), g = Math.round(color[1] * 255), b = Math.round(color[2] * 255);
        float scale = 255.0F / Math.max(1, Math.max(r, Math.max(g, b)));
        // Preserve each dye's hue; keep a light floor instead of cycling into near-black.
        return FastColor.ARGB32.color(255,
                Math.round(48 + r * scale * 207 / 255),
                Math.round(48 + g * scale * 207 / 255),
                Math.round(48 + b * scale * 207 / 255));
    }

    @SubscribeEvent
    public static void blockColors(RegisterColorHandlersEvent.Block event) {
        event.register((state, level, pos, index) -> currentColor(), ModFumos.RAINBOW_PIGMEE_FUMO.get());
        event.register((state, level, pos, index) -> level != null && pos != null
                && level.getBlockEntity(pos) instanceof FumoBlockEntity fumo
                && RainbowPigmeeTransformation.matchesName(fumo.getCustomName()) ? sheepColor() : -1,
                ModFumos.PIGMEE_FUMO.get());
    }

    @SubscribeEvent
    public static void itemColors(RegisterColorHandlersEvent.Item event) {
        event.register((stack, index) -> currentColor(), ModFumos.RAINBOW_PIGMEE_FUMO_ITEM.get());
        event.register((stack, index) -> RainbowPigmeeTransformation.matchesName(
                (stack.hasCustomHoverName() ? stack.getHoverName() : null)) ? sheepColor() : -1, ModFumos.PIGMEE_FUMO_ITEM.get());
    }
}
