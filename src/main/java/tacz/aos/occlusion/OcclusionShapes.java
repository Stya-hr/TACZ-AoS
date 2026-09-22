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
import net.minecraftforge.common.Tags;

/** Server-side material policy and bounded geometry; no render-layer or shader introspection. */
public final class OcclusionShapes {
    public static final int MAX_PARTS = 8;
    private static final AABB FULL = new AABB(0, 0, 0, 1, 1, 1);
    private static final List<AABB> FULL_BLOCK = List.of(FULL);

    private OcclusionShapes() {}

    public static List<AABB> boxes(BlockState state, BlockGetter level, BlockPos pos) {
        var block = state.getBlock();
        if (state.isAir() || state.is(Tags.Blocks.GLASS) || state.is(Tags.Blocks.GLASS_PANES)
                || block == Blocks.GLASS || block == Blocks.GLASS_PANE || block == Blocks.TINTED_GLASS
                || block instanceof StainedGlassBlock || block instanceof StainedGlassPaneBlock) return List.of();
        // Leaf texture holes must not reveal entities, including leaves supplied by other mods.
        if (block instanceof LeavesBlock || state.is(BlockTags.LEAVES)) return FULL_BLOCK;
        // Vegetation is sent separately: the client uses its baked model and texture alpha.
        if (textured(state) || block instanceof HalfTransparentBlock || block instanceof BaseFireBlock
                || block instanceof LiquidBlock && state.getFluidState().is(FluidTags.WATER)
                || block == Blocks.LIGHT || block == Blocks.STRUCTURE_VOID || block == Blocks.BARRIER) return List.of();
        var shape = state.getShape(level, pos);
        // A complex baked model can have a deceptively simple selection box (campfires, machinery, etc.).
        // Only known geometric block families can expose the openings in that box.
        if (block instanceof SlabBlock || block instanceof StairBlock || block instanceof DoorBlock
                || block instanceof TrapDoorBlock || block instanceof FenceBlock || block instanceof FenceGateBlock
                || block instanceof WallBlock || block instanceof IronBarsBlock || block instanceof SnowLayerBlock
                || block instanceof CarpetBlock || block instanceof DirtPathBlock || block instanceof FarmBlock)
            return parts(shape);
        return shape.isEmpty() ? FULL_BLOCK : List.of(FULL.minmax(shape.bounds()));
    }

    public static boolean textured(BlockState state) {
        var block = state.getBlock();
        if (block instanceof LeavesBlock || state.is(BlockTags.LEAVES)) return false;
        return block instanceof BushBlock || block instanceof GrowingPlantBlock || block instanceof VineBlock || block instanceof MultifaceBlock
                || block instanceof SugarCaneBlock || block instanceof CocoaBlock
                || block instanceof HangingRootsBlock || block instanceof SporeBlossomBlock
                || state.is(BlockTags.CROPS) || state.is(BlockTags.FLOWERS) || state.is(BlockTags.SAPLINGS);
    }

    public static List<AABB> parts(VoxelShape shape) {
        if (shape.isEmpty() || Block.isShapeFullBlock(shape)) return FULL_BLOCK;
        var parts = shape.toAabbs();
        // Complex/special models cover at least their entire cell; keep protrusions such as tall posts too.
        return parts.size() <= MAX_PARTS ? parts : List.of(FULL.minmax(shape.bounds()));
    }
}
