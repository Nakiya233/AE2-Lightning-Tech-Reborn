package com.moakiee.ae2lt.blockentity;

import net.minecraft.network.chat.Component;
import net.minecraft.world.Nameable;
import org.jetbrains.annotations.Nullable;
import com.moakiee.ae2lt.registry.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class FumoBlockEntity extends BlockEntity implements Nameable {

    public static final float SPIN_DEGREES_PER_TICK = 6.0F;

    private static final String TAG_SPINNING = "Spinning";

    @Nullable
    private Component customName;

    private boolean spinning;
    private float yRot;
    private float prevYRot;

    public FumoBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.FUMO.get(), pos, state);
    }

    @Override
    public Component getName() {
        return customName != null ? customName : getBlockState().getBlock().getName();
    }

    @Override
    @Nullable
    public Component getCustomName() {
        return customName;
    }

    public void setCustomName(@Nullable Component name) {
        customName = name;
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    public boolean isSpinning() {
        return spinning;
    }

    public float getRenderYRot(float partialTick) {
        return prevYRot + (yRot - prevYRot) * partialTick;
    }

    public void toggleSpinning() {
        spinning = !spinning;
        setChanged();
        if (level != null && !level.isClientSide()) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
        }
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, FumoBlockEntity be) {
        be.prevYRot = be.yRot;
        if (be.spinning) {
            be.yRot += SPIN_DEGREES_PER_TICK;
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (customName != null) tag.putString("CustomName", Component.Serializer.toJson(customName));
        tag.putBoolean(TAG_SPINNING, spinning);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        try { customName = tag.contains("CustomName", 8) ? Component.Serializer.fromJson(tag.getString("CustomName")) : null; }
        catch (RuntimeException ignored) { customName = null; }
        spinning = tag.getBoolean(TAG_SPINNING);
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        if (customName != null) tag.putString("CustomName", Component.Serializer.toJson(customName));
        tag.putBoolean(TAG_SPINNING, spinning);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
