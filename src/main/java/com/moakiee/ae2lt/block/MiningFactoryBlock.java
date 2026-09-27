package com.moakiee.ae2lt.block;

import appeng.api.orientation.IOrientationStrategy;
import appeng.api.orientation.OrientationStrategies;
import appeng.menu.locator.MenuLocators;
import com.moakiee.ae2lt.blockentity.MiningFactoryBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

public final class MiningFactoryBlock extends AE2LTBaseEntityBlock<MiningFactoryBlockEntity> {
    public static final BooleanProperty WORKING = BooleanProperty.create("working");
    public MiningFactoryBlock() {
        super(metalProps().noOcclusion().forceSolidOn());
        registerDefaultState(defaultBlockState().setValue(WORKING, false)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder); builder.add(WORKING);
    }
    @Override public IOrientationStrategy getOrientationStrategy() { return OrientationStrategies.horizontalFacing(); }
    @Override public void neighborChanged(BlockState state, Level level, BlockPos pos,
                                          Block block, BlockPos fromPos, boolean isMoving) {
        var factory = getBlockEntity(level, pos);
        if (factory != null) factory.onNeighborChanged(fromPos);
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                         Player player, BlockHitResult hit) {
        var factory = getBlockEntity(level, pos);
        if (factory == null) return InteractionResult.PASS;
        if (!level.isClientSide) factory.openMenu(player, MenuLocators.forBlockEntity(factory));
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
