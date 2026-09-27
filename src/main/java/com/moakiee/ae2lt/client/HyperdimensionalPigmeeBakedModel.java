package com.moakiee.ae2lt.client;

import net.minecraft.client.resources.model.BakedModel;

/**
 * Marks portal/rainbow Pigmee items for the shared custom renderer while retaining
 * all of the ordinary Pigmee model's transforms.
 */
final class HyperdimensionalPigmeeBakedModel extends SpinningFumoBakedModel {
    HyperdimensionalPigmeeBakedModel(BakedModel originalModel) {
        super(originalModel);
    }

    @Override
    public boolean isCustomRenderer() {
        return true;
    }

    BakedModel baseModel() {
        return originalModel;
    }
}
