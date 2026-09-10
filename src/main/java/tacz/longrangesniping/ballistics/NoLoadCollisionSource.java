package tacz.longrangesniping.ballistics;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import tacz.longrangesniping.terrain.*;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/** Tick-local readers only: live sections win over snapshots, without creating tickets or doing I/O. */
public final class NoLoadCollisionSource implements SegmentTrace.Source {
    private final ServerLevel level;
    private final String dimension;
    private final Map<SectionKey, SnapshotCollector.Reader> readers = new HashMap<>();
    private final IdentityHashMap<BlockState, CollisionCell> states = new IdentityHashMap<>();
    public NoLoadCollisionSource(ServerLevel level) {
        this.level = level;
        this.dimension = TerrainSnapshots.dimension(level);
    }

    @Override public CollisionCell cell(int x, int y, int z) {
        if (y < level.getMinBuildHeight() || y >= level.getMaxBuildHeight()
                || !level.getWorldBorder().isWithinBounds(new BlockPos(x, y, z))) return null;
        SectionKey key = SectionKey.atBlock(dimension, x, y, z);
        if (!readers.containsKey(key)) readers.put(key, open(key));
        var reader = readers.get(key);
        return reader == null ? null : reader.read(SectionKey.cellIndex(x, y, z));
    }

    private SnapshotCollector.Reader open(SectionKey key) {
        var chunk = level.getChunkSource().getChunkNow(key.x(), key.z());
        if (chunk != null) {
            var section = chunk.getSection(level.getSectionIndexFromSectionY(key.y()));
            return new SnapshotCollector.Reader() {
                public boolean allAir() { return section.hasOnlyAir(); }
                public CollisionCell read(int index) {
                    var state = section.getBlockState(index & 15, (index >> 8) & 15, (index >> 4) & 15);
                    return states.computeIfAbsent(state, MinecraftSnapshotSource::classify);
                }
            };
        }
        var snapshot = TerrainSnapshots.INSTANCE.find(key).orElse(null);
        if (snapshot == null) return null;
        return new SnapshotCollector.Reader() {
            public boolean allAir() { return snapshot.allAir(); }
            public CollisionCell read(int index) { return snapshot.cell(index); }
        };
    }
}
