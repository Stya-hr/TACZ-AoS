package tacz.aos.scoping;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import tacz.aos.mixin.ChunkMapAccessor;

/** Mirrors the Forge 1.20.1 native chunk watch radius without loading or subscribing any chunk. */
public final class NativeChunkTracking {
    private NativeChunkTracking() {}
    public static int viewDistance(ChunkMapAccessor map, ServerPlayer player) { return map.tacz_aos$serverViewDistance(); }
    public static boolean isTracked(ChunkMapAccessor map, ServerPlayer player, int x, int z) {
        var section = player.getLastSectionPos();
        return ChunkMap.isChunkInRange(x, z, section.x(), section.z(), map.tacz_aos$serverViewDistance());
    }
}
