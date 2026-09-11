package tacz.aos.block;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** A bounded local read, never a chunk request. Candidate scans are shared by all checks in the same tick. */
public final class RangefinderAccess {
    private record Scan(WeakReference<Level> level, BlockPos cell, long tick, long revision, List<BlockPos> bases) {}
    private static final WeakHashMap<Player, Scan> SCANS = new WeakHashMap<>();
    private static final AtomicLong REVISION = new AtomicLong();
    private RangefinderAccess() {}
    public static void invalidate() { REVISION.incrementAndGet(); }

    public static synchronized boolean nearby(Player player) {
        var level = player.level();
        var cell = player.blockPosition();
        long tick = level.getGameTime(), revision = REVISION.get();
        var scan = SCANS.get(player);
        if (scan == null || scan.level.get() != level || !scan.cell.equals(cell) || scan.tick != tick || scan.revision != revision) {
            var bases = new ArrayList<BlockPos>();
            // Include the two-block height and both block faces on the exact three-block boundary.
            for (int x = cell.getX() - 4; x <= cell.getX() + 3; x++)
                for (int z = cell.getZ() - 4; z <= cell.getZ() + 3; z++) {
                    ChunkAccess chunk = level instanceof ServerLevel server ? server.getChunkSource().getChunkNow(x >> 4, z >> 4)
                            : level.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
                    if (chunk == null) continue;
                    for (int y = cell.getY() - 5; y <= cell.getY() + 3; y++) {
                        if (y < level.getMinBuildHeight() || y + 1 >= level.getMaxBuildHeight()) continue;
                        var pos = new BlockPos(x, y, z);
                        BlockState state = chunk.getBlockState(pos);
                        if (!state.is(ModContent.RANGEFINDER.get()) || state.getValue(RangefinderBlock.HALF) != DoubleBlockHalf.LOWER) continue;
                        var upper = chunk.getBlockState(pos.above());
                        if (upper.is(ModContent.RANGEFINDER.get()) && upper.getValue(RangefinderBlock.HALF) == DoubleBlockHalf.UPPER)
                            bases.add(pos);
                    }
                }
            scan = new Scan(new WeakReference<>(level), cell.immutable(), tick, revision, List.copyOf(bases));
            SCANS.put(player, scan);
        }
        for (var base : scan.bases) if (within(player.position(), base)) return true;
        return false;
    }

    public static boolean within(Vec3 point, BlockPos base) {
        double x = Math.max(Math.max(base.getX() - point.x, point.x - base.getX() - 1), 0);
        double y = Math.max(Math.max(base.getY() - point.y, point.y - base.getY() - 2), 0);
        double z = Math.max(Math.max(base.getZ() - point.z, point.z - base.getZ() - 1), 0);
        return x*x + y*y + z*z <= 9;
    }
}
