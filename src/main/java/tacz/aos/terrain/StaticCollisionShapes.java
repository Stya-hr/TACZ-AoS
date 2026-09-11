package tacz.aos.terrain;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;


public final class StaticCollisionShapes {
    private StaticCollisionShapes() {}
    public static CollisionCell classify(BlockState state) {
        boolean water = state.getFluidState().is(FluidTags.WATER);
        // Our own rangefinder has state-only static geometry, just like the supported vanilla states.
        // Treating it as unknown geometry would obstruct every ray in its one-cell halo,
        // including a player's eye beside the instrument (false 0.0 / 1.x range readings).
        // The two-argument getter below returns Minecraft's precomputed state cache and never
        // asks a live world for neighbors. Never call the entity-context overload here.
        if ((!BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("minecraft")
                && !(state.getBlock() instanceof tacz.aos.block.RangefinderBlock))
                || state.getBlock().hasDynamicShape() || state.hasBlockEntity()) {
            return conservative(water);
        }
        var shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        return fromShape(shape,water);
    }
    public static CollisionCell fromShape(net.minecraft.world.phys.shapes.VoxelShape shape, boolean water) {
        if (shape.isEmpty()) return water ? CollisionCell.WATER : CollisionCell.AIR;
        var boxes = shape.toAabbs();
        if (boxes.size() > 16) return conservative(water);
        try {
            List<CollisionCell.Box> immutable = boxes.stream().map(box -> new CollisionCell.Box(
                    box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)).toList();
            if (immutable.equals(List.of(CollisionCell.UNIT))) {
                return water ? new CollisionCell(CollisionCell.Kind.FULL, immutable, true) : CollisionCell.FULL;
            }
            return new CollisionCell(CollisionCell.Kind.BOXES, immutable, water);
        } catch (IllegalArgumentException unsupportedBounds) {
            return conservative(water);
        }
    }

    private static CollisionCell conservative(boolean water) {
        return water ? new CollisionCell(CollisionCell.Kind.CONSERVATIVE, List.of(CollisionCell.UNIT), true)
                : CollisionCell.CONSERVATIVE;
    }
}
