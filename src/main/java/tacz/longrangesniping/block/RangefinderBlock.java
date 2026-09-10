package tacz.longrangesniping.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Static two-block instrument. Only the lower half owns loot, including when the upper half is removed. */
public final class RangefinderBlock extends Block {
    public static final MapCodec<RangefinderBlock> CODEC = simpleCodec(RangefinderBlock::new);
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    private static final VoxelShape[] LOWER = shapes(false), UPPER = shapes(true);
    public RangefinderBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(HALF, DoubleBlockHalf.LOWER).setValue(FACING, Direction.NORTH));
    }
    @Override public MapCodec<RangefinderBlock> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(HALF, FACING); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        var pos = context.getClickedPos();
        if (pos.getY() >= context.getLevel().getMaxBuildHeight() - 1
                || !context.getLevel().getBlockState(pos.above()).canBeReplaced(context)) return null;
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }
    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        level.setBlock(pos.above(), state.setValue(HALF, DoubleBlockHalf.UPPER), UPDATE_ALL);
    }
    @Override protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        var below = level.getBlockState(pos.below());
        return state.getValue(HALF) == DoubleBlockHalf.LOWER ? below.isFaceSturdy(level, pos.below(), Direction.UP)
                : below.is(this) && below.getValue(HALF) == DoubleBlockHalf.LOWER;
    }
    @Override protected BlockState updateShape(BlockState state, Direction side, BlockState neighbor, LevelAccessor level,
                                               BlockPos pos, BlockPos neighborPos) {
        boolean lower = state.getValue(HALF) == DoubleBlockHalf.LOWER;
        if (side == (lower ? Direction.UP : Direction.DOWN)) {
            return neighbor.is(this) && neighbor.getValue(HALF) != state.getValue(HALF)
                    ? state.setValue(FACING, neighbor.getValue(FACING)) : Blocks.AIR.defaultBlockState();
        }
        if (lower && side == Direction.DOWN && !canSurvive(state, level, pos)) return Blocks.AIR.defaultBlockState();
        return super.updateShape(state, side, neighbor, level, pos, neighborPos);
    }
    @Override public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        // As with doors, creative upper-half removal must suppress the lower half's normal neighbor-update loot.
        if (!level.isClientSide && player.isCreative() && state.getValue(HALF) == DoubleBlockHalf.UPPER) {
            var below = level.getBlockState(pos.below());
            if (below.is(this) && below.getValue(HALF) == DoubleBlockHalf.LOWER) {
                level.setBlock(pos.below(), Blocks.AIR.defaultBlockState(), UPDATE_ALL | UPDATE_SUPPRESS_DROPS);
                level.levelEvent(player, 2001, pos.below(), Block.getId(below));
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return (state.getValue(HALF) == DoubleBlockHalf.LOWER ? LOWER : UPPER)[state.getValue(FACING).get2DDataValue()];
    }
    @Override protected boolean isPathfindable(BlockState state, PathComputationType type) { return false; }
    @Override protected BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(FACING, rotation.rotate(state.getValue(FACING))); }
    @Override protected BlockState mirror(BlockState state, Mirror mirror) { return state.rotate(mirror.getRotation(state.getValue(FACING))); }

    private static VoxelShape[] shapes(boolean upper) {
        VoxelShape shape;
        if (upper) {
            shape = Shapes.or(box(5, 0, 5, 11, 6, 11), box(2.5, 6, 4, 13.5, 14, 13),
                    box(4, 8, 1, 8, 12, 4), box(9, 7.5, 13, 13, 12, 14));
        } else {
            shape = Shapes.empty();
            // Four conservative steps per sloped leg leave the tripod's open center empty.
            for (int i = 0; i < 4; i++) {
                double y = i * 4, spread = (19 - (y + 2)) * Math.tan(Math.PI / 8);
                shape = Shapes.or(shape, box(6.8 - spread, y, 6.2, 9.2 - spread, y + 4, 8.2),
                        box(6.8 + spread, y, 6.2, 9.2 + spread, y + 4, 8.2),
                        box(7, y, 6.8 + spread, 9, y + 4, 9.2 + spread));
            }
        }
        var result = new VoxelShape[4];
        for (var facing : Direction.Plane.HORIZONTAL) {
            int turns = switch (facing) { case EAST -> 1; case SOUTH -> 2; case WEST -> 3; default -> 0; };
            VoxelShape rotated = shape;
            for (int i = 0; i < turns; i++) {
                VoxelShape[] next = {Shapes.empty()};
                rotated.forAllBoxes((x0,y0,z0,x1,y1,z1) -> next[0] = Shapes.or(next[0], Shapes.box(1-z1,y0,x0,1-z0,y1,x1)));
                rotated = next[0];
            }
            result[facing.get2DDataValue()] = rotated;
        }
        return result;
    }
}
