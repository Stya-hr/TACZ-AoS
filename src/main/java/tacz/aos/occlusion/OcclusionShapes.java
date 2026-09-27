package tacz.aos.occlusion;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Remote visual cover: thin plants use atlas alpha; every other visible cell is a full cube. */
public final class OcclusionShapes {
    private static final List<AABB> FULL_BLOCK = List.of(new AABB(0, 0, 0, 1, 1, 1));
    private OcclusionShapes() {}

    public static List<AABB> boxes(BlockState state, BlockGetter level, BlockPos pos) {
        return solid(state) ? FULL_BLOCK : List.of();
    }

    public static boolean solid(BlockState state) {
        var block = state.getBlock();
        // Empty/invisible utility cells and fluids are not solid block cover.
        if (state.isAir() || textured(state) || block instanceof LiquidBlock || block instanceof BaseFireBlock
                || block == Blocks.LIGHT || block == Blocks.STRUCTURE_VOID || block == Blocks.BARRIER)
            return false;
        // Includes glass, leaves, slabs, stairs and open doors. Do not query selection/collision shapes.
        return true;
    }

    public static boolean textured(BlockState state) {
        var block = state.getBlock();
        if (block instanceof LeavesBlock || state.is(BlockTags.LEAVES)
                || block instanceof WaterlilyBlock || block instanceof AzaleaBlock) return false;
        // Explicitly exclude volumetric plants such as cocoa and spore blossoms.
        return block instanceof BushBlock || block instanceof GrowingPlantBlock || block instanceof VineBlock
                || block instanceof MultifaceBlock || block instanceof SugarCaneBlock || block instanceof HangingRootsBlock;
    }
}
