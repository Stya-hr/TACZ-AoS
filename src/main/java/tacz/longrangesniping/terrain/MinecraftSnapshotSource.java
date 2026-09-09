package tacz.longrangesniping.terrain;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.Function;

public final class MinecraftSnapshotSource implements SnapshotCollector.Source {
    private final Function<String, ServerLevel> levels;
    public MinecraftSnapshotSource(Function<String, ServerLevel> levels) { this.levels = levels; }

    @Override public SnapshotCollector.Reader open(SectionKey key) {
        ServerLevel level = levels.apply(key.dimension());
        if (level == null || !level.getServer().isSameThread()) return null;
        var chunk = level.getChunkSource().getChunkNow(key.x(), key.z());
        if (chunk == null || key.y() < level.getMinSection() || key.y() >= level.getMaxSection()) return null;
        var section = chunk.getSection(level.getSectionIndexFromSectionY(key.y()));
        return new SnapshotCollector.Reader() {
            private final IdentityHashMap<BlockState, CollisionCell> palette = new IdentityHashMap<>();
            @Override public boolean allAir() { return section.hasOnlyAir(); }
            @Override public CollisionCell read(int index) {
                var state = section.getBlockState(index & 15, (index >> 8) & 15, (index >> 4) & 15);
                return palette.computeIfAbsent(state, MinecraftSnapshotSource::classify);
            }
        };
    }

    public static CollisionCell classify(BlockState state) {
        boolean water = state.getFluidState().is(FluidTags.WATER);
        // Only vanilla, non-dynamic, non-BE states are interpreted in this first implementation.
        // The two-argument getter below returns Minecraft's precomputed state cache and never
        // asks a live world for neighbors. Never call the entity-context overload here.
        if (!BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("minecraft")
                || state.getBlock().hasDynamicShape() || state.hasBlockEntity()) {
            return conservative(water);
        }
        var shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
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
