package tacz.longrangesniping.test;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import tacz.longrangesniping.TaczLongRangeSniping;
import tacz.longrangesniping.ballistics.NoLoadCollisionSource;
import tacz.longrangesniping.ballistics.SegmentTrace;
import tacz.longrangesniping.terrain.*;

@GameTestHolder(TaczLongRangeSniping.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PersistenceGameTests {
    @GameTest(template = "empty", batch = "disk_restore", timeoutTicks = 2000000)
    public static void diskOnlyWallRestoresWithoutLoadingItsChunk(GameTestHelper helper) {
        var level = helper.getLevel(); var cache = TerrainSnapshots.INSTANCE;
        int cx = 94500, cz = 94500, x = cx * 16, z = cz * 16;
        level.getChunk(cx, cz);
        level.setBlockAndUpdate(new BlockPos(x + 8, 85, z + 8), Blocks.STONE.defaultBlockState());
        var key = new SectionKey(TerrainSnapshots.dimension(level), cx, 5, cz);
        for (int tick = 0; tick < 2048 && cache.coverage(key) == SnapshotStore.Coverage.UNKNOWN; tick++) cache.tick();
        var snapshot = cache.find(key).orElseThrow();
        SnapshotDiskCache disk = field(cache, "disk", SnapshotDiskCache.class);
        helper.assertTrue(disk != null, "Production persistence did not start");
        disk.save(snapshot);
        // GameTest runs ticks much faster than wall time without players. I/O rate limits use wall time.
        long deadline = System.nanoTime() + 20_000_000_000L;
        helper.runAfterDelay(160, () -> await(helper, deadline,
                () -> { disk.retrySave(snapshot); return disk.known(key) && level.getChunkSource().getChunkNow(cx, cz) == null; }, () -> {
            var store = field(cache, "store", SnapshotStore.class);
            // LRU eviction, not invalidation: remove resident payload while preserving its disk record.
            store.resize(0); store.resize(128L << 20);
            helper.assertTrue(cache.find(key).isEmpty(), "Disk read blocked until ready instead of returning unknown");
            helper.assertTrue(cache.coverage(key) == SnapshotStore.Coverage.UNKNOWN, "Disk-only data was authorized");
            helper.assertTrue(cache.knownChunk(level, cx, cz), "Disk-only cache did not preserve prior chunk residency");
            helper.assertTrue(!cache.knownChunk(level, cx + 10000, cz), "Missing cache granted exploration evidence");
            await(helper, deadline, () -> cache.coverage(key) == SnapshotStore.Coverage.KNOWN, () -> {
                var result = SegmentTrace.trace(new NoLoadCollisionSource(level), x + 4, 85.5, z + 8.5, 8, 0, 0);
                helper.assertTrue(result.end() == SegmentTrace.End.BLOCK, "Restored disk wall did not block ballistic trace");
                helper.assertTrue(level.getChunkSource().getChunkNow(cx, cz) == null, "Reading disk snapshot loaded real chunk");
                helper.succeed();
            });
        }));
    }
    private static void await(GameTestHelper helper, long deadline, java.util.function.BooleanSupplier ready, Runnable complete) {
        if (ready.getAsBoolean()) { complete.run(); return; }
        helper.assertTrue(System.nanoTime() < deadline, "Disk restore timed out: " + TerrainSnapshots.INSTANCE.diskStatus());
        helper.runAfterDelay(20, () -> await(helper, deadline, ready, complete));
    }
    private static <T> T field(Object object, String name, Class<T> type) {
        try { var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(object)); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
