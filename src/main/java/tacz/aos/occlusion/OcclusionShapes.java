package tacz.aos.occlusion;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.common.Tags;

/** Server-side material policy and bounded geometry; no render-layer or shader introspection. */
public final class OcclusionShapes {
    public static final int MAX_PARTS = 8;
    private static final AABB FULL = new AABB(0, 0, 0, 1, 1, 1);
    private static final List<AABB> FULL_BLOCK = List.of(FULL);

    private OcclusionShapes() {}

    public static List<AABB> boxes(BlockState state, BlockGetter level, BlockPos pos) {
        var block = state.getBlock();
        if (state.isAir() || state.is(Tags.Blocks.GLASS_BLOCKS) || state.is(Tags.Blocks.GLASS_PANES)
                || block == Blocks.GLASS || block == Blocks.GLASS_PANE || block == Blocks.TINTED_GLASS
                || block instanceof StainedGlassBlock || block instanceof StainedGlassPaneBlock) return List.of();
        // Leaf texture holes must not reveal entities, including leaves supplied by other mods.
        if (block instanceof LeavesBlock || state.is(BlockTags.LEAVES)) return FULL_BLOCK;
        // Selection boxes of soft vegetation are not opaque surfaces. Inheritance/tags also cover mod crops.
        if (block instanceof BushBlock || block instanceof GrowingPlantBlock || block instanceof VineBlock || block instanceof MultifaceBlock
                || block instanceof SugarCaneBlock || block instanceof CocoaBlock
                || block instanceof HangingRootsBlock || block instanceof SporeBlossomBlock
                || state.is(BlockTags.CROPS) || state.is(BlockTags.FLOWERS) || state.is(BlockTags.SAPLINGS)
                || block instanceof HalfTransparentBlock || block instanceof BaseFireBlock
                || block instanceof LiquidBlock && state.getFluidState().is(FluidTags.WATER)
                || block == Blocks.LIGHT || block == Blocks.STRUCTURE_VOID || block == Blocks.BARRIER) return List.of();
        // canOcclude=false also describes opaque doors, fences and block-entity models: do not drop them.
        // Unrecognized mod materials conservatively participate instead of guessing transparency from noOcclusion.
        return parts(state.getShape(level, pos));
    }

    public static List<AABB> parts(VoxelShape shape) {
        if (shape.isEmpty() || Block.isShapeFullBlock(shape)) return FULL_BLOCK;
        var parts = shape.toAabbs();
        // Complex/special models cover at least their entire cell; keep protrusions such as tall posts too.
        return parts.size() <= MAX_PARTS ? parts : List.of(FULL.minmax(shape.bounds()));
    }
}
