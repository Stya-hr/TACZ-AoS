package tacz.longrangesniping.test;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import tacz.longrangesniping.TaczLongRangeSniping;
import tacz.longrangesniping.config.ServerConfig;
import tacz.longrangesniping.terrain.*;

import java.lang.reflect.Field;

@GameTestHolder(TaczLongRangeSniping.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TerrainSnapshotGameTests {
    @GameTest(template = "empty", batch = "terrain_cache")
    public static void staticShapesAndConservativeFallbacks(GameTestHelper helper) {
        helper.assertTrue(MinecraftSnapshotSource.classify(Blocks.AIR.defaultBlockState()).equals(CollisionCell.AIR), "Air became an obstacle");
        helper.assertTrue(MinecraftSnapshotSource.classify(Blocks.STONE.defaultBlockState()).equals(CollisionCell.FULL), "Stone lost collision");
        helper.assertTrue(MinecraftSnapshotSource.classify(Blocks.WATER.defaultBlockState()).equals(CollisionCell.WATER), "Water information lost");
        for (var state : new net.minecraft.world.level.block.state.BlockState[] {
                Blocks.STONE_SLAB.defaultBlockState(), Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP),
                Blocks.OAK_STAIRS.defaultBlockState(), Blocks.OAK_FENCE.defaultBlockState() }) {
            var expected = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs();
            var actual = MinecraftSnapshotSource.classify(state);
            helper.assertTrue(actual.kind() == CollisionCell.Kind.BOXES, "Static shape was not preserved: " + state);
            helper.assertTrue(actual.boxes().size() == expected.size(), "Shape box count differs");
            for (int i = 0; i < expected.size(); i++) {
                var box = expected.get(i);
                helper.assertTrue(actual.boxes().get(i).equals(new CollisionCell.Box(box.minX, box.minY, box.minZ,
                        box.maxX, box.maxY, box.maxZ)), "Collision bounds differ");
            }
        }
        helper.assertTrue(MinecraftSnapshotSource.classify(Blocks.SCAFFOLDING.defaultBlockState()).kind() == CollisionCell.Kind.CONSERVATIVE,
                "Entity-dependent shape should be conservative");
        helper.assertTrue(MinecraftSnapshotSource.classify(Blocks.CHEST.defaultBlockState()).kind() == CollisionCell.Kind.CONSERVATIVE,
                "Block entity shape should be conservative");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "terrain_cache")
    public static void incrementalCaptureAndRealBlockChangeMixin(GameTestHelper helper) {
        var previous = configure(64);
        var cache = TerrainSnapshots.INSTANCE;
        try {
            BlockPos position = helper.absolutePos(new BlockPos(1, 2, 1));
            helper.getLevel().setBlockAndUpdate(position, Blocks.STONE.defaultBlockState());
            var key = key(helper, position);
            helper.assertTrue(cache.status().queued() > 0, "LevelChunk change mixin did not schedule acquisition");
            cache.tick();
            helper.assertTrue(cache.coverage(key) == SnapshotStore.Coverage.UNKNOWN, "Partial section became known");
            helper.getLevel().setBlockAndUpdate(position, Blocks.STONE_SLAB.defaultBlockState());
            complete(helper, key);
            var old = cache.find(key).orElseThrow();
            helper.assertTrue(cache.cell(helper.getLevel(), position).orElseThrow().kind() == CollisionCell.Kind.BOXES, "Slab update was lost");
            helper.getLevel().setBlockAndUpdate(position, Blocks.AIR.defaultBlockState());
            helper.assertTrue(cache.coverage(key) == SnapshotStore.Coverage.UNKNOWN, "Old snapshot stayed valid after edit");
            complete(helper, key);
            helper.assertTrue(cache.find(key).orElseThrow().version() > old.version(), "Version did not advance");
            helper.assertTrue(cache.cell(helper.getLevel(), position).orElseThrow().equals(CollisionCell.AIR), "Air update was lost");
            helper.assertTrue(old.cell(SectionKey.cellIndex(position.getX(), position.getY(), position.getZ())).kind() == CollisionCell.Kind.BOXES,
                    "Published snapshot changed in place");
        } finally { restore(previous); }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "terrain_cache")
    public static void unloadDropsPartialWorkButRetainsCompletedData(GameTestHelper helper) {
        var previous = configure(4096);
        var cache = TerrainSnapshots.INSTANCE;
        try {
            BlockPos position = helper.absolutePos(new BlockPos(1, 2, 1));
            helper.getLevel().setBlockAndUpdate(position, Blocks.STONE.defaultBlockState());
            var key = key(helper, position);
            complete(helper, key);
            var chunk = helper.getLevel().getChunkAt(position);
            NeoForge.EVENT_BUS.post(new ChunkEvent.Unload(chunk));
            helper.assertTrue(cache.coverage(key) == SnapshotStore.Coverage.KNOWN, "Unload removed completed snapshot");
            NeoForge.EVENT_BUS.post(new ChunkEvent.Load(chunk, false));
            helper.assertTrue(cache.coverage(key) == SnapshotStore.Coverage.UNKNOWN, "Reload reused an old incarnation");
            NeoForge.EVENT_BUS.post(new ChunkEvent.Unload(chunk));
            helper.assertTrue(cache.coverage(key) == SnapshotStore.Coverage.UNKNOWN, "Unfinished reload became known");
        } finally { restore(previous); }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "terrain_cache")
    public static void unknownQueriesDoNotLoadOrScheduleAnything(GameTestHelper helper) {
        var previous = configure(4096);
        try {
            var cache = TerrainSnapshots.INSTANCE;
            var key = new SectionKey(TerrainSnapshots.dimension(helper.getLevel()), 812345, 4, 812345);
            int queued = cache.status().queued();
            int chunks = helper.getLevel().getChunkSource().getLoadedChunksCount();
            for (int i = 0; i < 100; i++) {
                helper.assertTrue(cache.coverage(key) == SnapshotStore.Coverage.UNKNOWN && cache.find(key).isEmpty(), "Missing data was treated as air");
            }
            var source = new MinecraftSnapshotSource(dimension -> helper.getLevel());
            helper.assertTrue(source.open(key) == null, "Source opened an unloaded chunk");
            helper.assertTrue(cache.status().queued() == queued && helper.getLevel().getChunkSource().getLoadedChunksCount() == chunks,
                    "Read-only queries scheduled work or loaded chunks");
        } finally { restore(previous); }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "terrain_natural_unload", timeoutTicks = 420)
    public static void naturallyLoadedChunkSnapshotSurvivesActualUnload(GameTestHelper helper) {
        var previous = configure(4096);
        var configured = ServerConfig.current();
        setSettings(new ServerConfig.Settings(configured.available(), configured.policy(), configured.maxDistance(),
                configured.maxTrackedEntities(), configured.snapshotMemoryMiB(), configured.diagnostics(),
                configured.ballistics(), new ServerConfig.Snapshots(true, 4096, 16, 2, 5, 8192, 8192)));
        var cache = TerrainSnapshots.INSTANCE;
        int x = 92000, z = 92000;
        var visit = net.minecraft.server.level.TicketType.<net.minecraft.world.level.ChunkPos>create("snapshot_test_visit",
                java.util.Comparator.comparingLong(net.minecraft.world.level.ChunkPos::toLong));
        Runnable leave = () -> {
            for (int offset = 0; offset < 8; offset++) {
                var pos = new net.minecraft.world.level.ChunkPos(x + offset, z);
                helper.getLevel().getChunkSource().removeRegionTicket(visit, pos, 0, pos);
            }
        };
        var key = new SectionKey(TerrainSnapshots.dimension(helper.getLevel()), x, 4, z);
        try {
            // A short naturally visited corridor, using default production budgets and real server ticks.
            // Fixture tickets represent the explorer's visit; production caching adds no tickets.
            // No instrument, gun or accelerated cache loop is provided.
            for (int offset = 0; offset < 8; offset++) {
                var pos = new net.minecraft.world.level.ChunkPos(x + offset, z);
                helper.getLevel().getChunkSource().addRegionTicket(visit, pos, 0, pos);
                helper.getLevel().getChunk(x + offset, z);
            }
            helper.assertTrue(cache.status().trackedChunks() > 0, "Natural chunk load event was not observed");
            helper.runAfterDelay(200, leave);
            helper.runAfterDelay(360, () -> {
                try {
                    helper.assertTrue(helper.getLevel().getChunkSource().getChunkNow(x, z) == null, "Cache retained a real chunk");
                    helper.assertTrue(cache.coverage(key) == SnapshotStore.Coverage.KNOWN_AIR, "Snapshot disappeared with the real chunk");
                    for (int offset = 0; offset < 8; offset++) {
                        var explored = new SectionKey(key.dimension(), x + offset, key.y(), z);
                        helper.assertTrue(cache.coverage(explored) == SnapshotStore.Coverage.KNOWN_AIR,
                                "Default-budget passive exploration missed corridor section: " + explored);
                    }
                    helper.succeed();
                } finally { leave.run(); restore(previous); }
            });
        } catch (RuntimeException | AssertionError failure) {
            leave.run();
            restore(previous);
            throw failure;
        }
    }

    private static SectionKey key(GameTestHelper helper, BlockPos pos) {
        return SectionKey.atBlock(TerrainSnapshots.dimension(helper.getLevel()), pos.getX(), pos.getY(), pos.getZ());
    }
    private static void complete(GameTestHelper helper, SectionKey key) {
        for (int i = 0; i < 1024 && TerrainSnapshots.INSTANCE.coverage(key) == SnapshotStore.Coverage.UNKNOWN; i++) TerrainSnapshots.INSTANCE.tick();
        helper.assertTrue(TerrainSnapshots.INSTANCE.coverage(key) != SnapshotStore.Coverage.UNKNOWN, "Snapshot did not complete: " + key);
    }
    private static ServerConfig.Settings configure(int blocks) {
        var old = ServerConfig.current();
        TerrainSnapshots.INSTANCE.clear();
        setSettings(new ServerConfig.Settings(old.available(), old.policy(), old.maxDistance(), old.maxTrackedEntities(),
                old.snapshotMemoryMiB(), old.diagnostics(), old.ballistics(), new ServerConfig.Snapshots(true, blocks, 16, 10, 0, 8192, 8192)));
        return old;
    }
    private static void restore(ServerConfig.Settings old) { setSettings(old); TerrainSnapshots.INSTANCE.clear(); }
    private static void setSettings(ServerConfig.Settings settings) {
        try {
            Field field = ServerConfig.class.getDeclaredField("current");
            field.setAccessible(true);
            field.set(null, settings);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
}
