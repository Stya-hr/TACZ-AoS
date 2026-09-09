package tacz.longrangesniping.test;

import com.tacz.guns.api.event.common.EntityHurtByGunEvent;
import com.tacz.guns.entity.EntityKineticBullet;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import tacz.longrangesniping.TaczLongRangeSniping;
import tacz.longrangesniping.ballistics.LocalBallistics;
import tacz.longrangesniping.config.ServerConfig;
import tacz.longrangesniping.terrain.SectionKey;
import tacz.longrangesniping.terrain.SnapshotStore;
import tacz.longrangesniping.terrain.TerrainSnapshots;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

import static tacz.longrangesniping.test.LocalBallisticsGameTests.*;

@GameTestHolder(TaczLongRangeSniping.MOD_ID)
@PrefixGameTestTemplate(false)
public final class UnloadedBallisticsGameTests {
    private static final ServerConfig.Prototype LIMITS = new ServerConfig.Prototype(true, 1024, 16, 600);
    private static final TicketType<ChunkPos> ENDPOINT = TicketType.create("taczlrs_test_endpoint", Comparator.comparingLong(ChunkPos::toLong));

    @GameTest(template = "ballistics", batch = "unloaded_ballistics", timeoutTicks = 240)
    public static void realUnloadedCorridorUsesSnapshotsWithoutLoading(GameTestHelper helper) {
        var level = helper.getLevel();
        var original = ServerConfig.current();
        var cache = TerrainSnapshots.INSTANCE;
        var fixture = new Fixture(helper);
        int cx = 94000, cz = 94000, wx = cx * 16, wz = cz * 16;
        var keys = new ArrayList<SectionKey>();
        try {
            cache.clear();
            setSettings(new ServerConfig.Settings(true, original.policy(), 1024, 32, 128, false, original.prototype(),
                    new ServerConfig.Snapshots(true, 65536, 256, 10, 0, 8192, 8192)));
            // Fixture preparation deliberately loads terrain. The firing phase below must not do so.
            for (int x = cx; x <= cx + 4; x++) {
                level.getChunk(x, cz);
                keys.add(new SectionKey(TerrainSnapshots.dimension(level), x, 5, cz));
            }
            // The GameTest world survives Gradle runs; reset the live wall used by the previous run.
            level.setBlockAndUpdate(new BlockPos(wx + 67, 85, wz + 8), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(wx + 40, 85, wz + 4), Blocks.STONE.defaultBlockState());
            for (int tick = 0; tick < 2048 && keys.stream().anyMatch(k -> cache.coverage(k) == SnapshotStore.Coverage.UNKNOWN); tick++) cache.tick();
            helper.assertTrue(keys.stream().allMatch(k -> cache.coverage(k) != SnapshotStore.Coverage.UNKNOWN), "Corridor snapshots incomplete");
        } catch (Throwable failure) {
            setSettings(original); cache.clear(); throw failure;
        }
        helper.runAfterDelay(160, () -> {
            var left = new ChunkPos(cx, cz);
            var right = new ChunkPos(cx + 4, cz);
            // FULL (radius zero), not an entity-ticking ticket: only each endpoint becomes accessible.
            level.getChunkSource().addRegionTicket(ENDPOINT, left, 0, left);
            level.getChunkSource().addRegionTicket(ENDPOINT, right, 0, right);
            level.getChunk(cx, cz);
            level.getChunk(cx + 4, cz);
            helper.runAfterDelay(5, () -> {
                List<Zombie> targets = new ArrayList<>();
                try {
                    for (int x = cx + 1; x < cx + 4; x++) {
                        helper.assertTrue(level.getChunkSource().getChunkNow(x, cz) == null, "Middle did not naturally unload");
                    }
                    helper.assertTrue(keys.subList(1, 4).stream().allMatch(k -> cache.coverage(k) != SnapshotStore.Coverage.UNKNOWN), "Unload lost a complete snapshot");
                    // Only the two endpoints return to the live world. Their invalidated snapshots must not prevent firing.
                    level.getChunk(cx, cz);
                    level.getChunk(cx + 4, cz);
                    targets.add(target(helper, new Vec3(wx + 72, 84.3, wz + 8)));
                    targets.add(target(helper, new Vec3(wx + 72, 84.3, wz + 4)));
                    targets.add(target(helper, new Vec3(wx + 72, 84.3, wz + 12)));
                    int loadedBefore = level.getChunkSource().getLoadedChunksCount();
                    long queuedBefore = cache.status().queued();

                    // A single movement segment crosses three unloaded chunks and reaches a real live target.
                    var direct = shot(fixture, new Vec3(wx + 8, 85, wz + 8), new Vec3(64, 0, 0));
                    var scheduler = new LocalBallistics(level);
                    accept(helper, scheduler, direct, LIMITS);
                    scheduler.tick();
                    helper.assertTrue(targets.get(0).getHealth() < 1000 && direct.isRemoved(), "Known unloaded corridor prevented damage: pos="
                            + direct.position() + ", status=" + scheduler.status() + ", health=" + targets.get(0).getHealth()
                            + ", accessible=" + (level.getEntity(targets.get(0).getUUID()) == targets.get(0)));

                    // Same geometry, but a cached stone block lies between the endpoints.
                    var wall = shot(fixture, new Vec3(wx + 8, 85, wz + 4), new Vec3(64, 0, 0));
                    accept(helper, scheduler, wall, LIMITS);
                    scheduler.tick();
                    helper.assertTrue(wall.isRemoved() && targets.get(1).getHealth() == 1000
                            && Math.abs(wall.getX() - (wx + 40)) < 0.0001, "Cached wall failed to block at its surface");

                    // Stay detached over multiple ticks; a native damage cancellation still applies at the far end.
                    var slow = shot(fixture, new Vec3(wx + 8, 85, wz + 12), new Vec3(8, 0, 0));
                    Consumer<EntityHurtByGunEvent.Pre> cancel = event -> { if (event.getBullet() == slow) event.setCanceled(true); };
                    NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, EntityHurtByGunEvent.Pre.class, cancel);
                    try {
                        accept(helper, scheduler, slow, LIMITS);
                        for (int tick = 0; tick < 8; tick++) scheduler.tick();
                        helper.assertTrue(slow.isRemoved() && slow.tickCount == 8 && targets.get(2).getHealth() == 1000,
                                "Multiple-tick crossing or native damage cancellation failed");
                    } finally { NeoForge.EVENT_BUS.unregister(cancel); }

                    // Compare real TACZ air physics against a detached bullet whose origin itself is unloaded.
                    var reference = fixture.bullet(new Vec3(4, 5, 4), new Vec3(0.4, 0.1, 0));
                    var flying = shot(fixture, new Vec3(wx + 20, 85, wz + 10), new Vec3(0.4, 0.1, 0));
                    for (var bullet : List.of(reference, flying)) {
                        setField(bullet, "gravity", 0.04f);
                        setField(bullet, "friction", 0.01f);
                        setField(bullet, "life", 5);
                    }
                    Vec3 referenceStart = reference.position(), flyingStart = flying.position();
                    accept(helper, scheduler, flying, LIMITS);
                    for (int tick = 0; tick < 4; tick++) {
                        level.tickNonPassenger(reference);
                        scheduler.tick();
                        helper.assertTrue(reference.position().subtract(referenceStart).distanceTo(flying.position().subtract(flyingStart)) < 1.0e-7
                                && reference.getDeltaMovement().distanceTo(flying.getDeltaMovement()) < 1.0e-12
                                && reference.isRemoved() == flying.isRemoved(), "Unloaded air gravity/drag/lifetime differs from native");
                    }
                    helper.assertTrue(flying.isRemoved(), "Detached lifetime did not expire");

                    // Live endpoint terrain must override its old air snapshot even before reacquisition finishes.
                    level.setBlockAndUpdate(new BlockPos(wx + 67, 85, wz + 8), Blocks.STONE.defaultBlockState());
                    float healthBefore = targets.get(0).getHealth();
                    var currentWall = shot(fixture, new Vec3(wx + 8, 85, wz + 8), new Vec3(64, 0, 0));
                    accept(helper, scheduler, currentWall, LIMITS);
                    scheduler.tick();
                    helper.assertTrue(currentWall.isRemoved() && targets.get(0).getHealth() == healthBefore
                            && Math.abs(currentWall.getX() - (wx + 67)) < 0.0001, "Live terrain did not override cached air");

                    // Eviction removes authorization for the middle, even though a live target exists beyond it.
                    invalidate(keys.get(2));
                    targets.add(target(helper, new Vec3(wx + 12, 84.3, wz + 12)));
                    var unknown = shot(fixture, new Vec3(wx + 8, 85, wz + 12), new Vec3(64, 0, 0));
                    setField(unknown, "pierce", 2);
                    accept(helper, scheduler, unknown, LIMITS);
                    scheduler.tick();
                    helper.assertTrue(unknown.isRemoved() && unknown.getX() < wx + 32 && scheduler.status().unknown() == 1
                            && targets.get(2).getHealth() == 1000 && targets.get(3).getHealth() < 1000,
                            "Known prefix hit or unknown gap termination failed");
                    helper.assertTrue(scheduler.status().active() == 0, "Completed shots remain active");
                    helper.assertTrue(level.getChunkSource().getLoadedChunksCount() == loadedBefore, "Firing loaded terrain");
                    // The live wall edit can enqueue its own section; tracing must not request the missing middle.
                    helper.assertTrue(cache.status().queued() <= queuedBefore + 1, "Firing scheduled snapshot acquisition");
                    for (int x = cx + 1; x < cx + 4; x++) {
                        helper.assertTrue(level.getChunkSource().getChunkNow(x, cz) == null, "Bullet loaded a middle chunk");
                    }
                    helper.succeed();
                } finally {
                    targets.forEach(Zombie::discard);
                    level.getChunkSource().removeRegionTicket(ENDPOINT, left, 0, left);
                    level.getChunkSource().removeRegionTicket(ENDPOINT, right, 0, right);
                    setSettings(original);
                    cache.clear();
                }
            });
        });
    }

    private static EntityKineticBullet shot(Fixture fixture, Vec3 position, Vec3 velocity) {
        var bullet = fixture.bullet(new Vec3(4, 5, 4), velocity);
        bullet.setPos(position);
        setField(bullet, "startPos", position);
        setField(bullet, "gravity", 0f);
        setField(bullet, "friction", 0f);
        setField(bullet, "life", 100);
        setField(bullet, "pierce", 1);
        return bullet;
    }

    private static Zombie target(GameTestHelper helper, Vec3 position) {
        var target = EntityType.ZOMBIE.create(helper.getLevel());
        target.setPos(position);
        target.setOldPosAndRot();
        target.setNoAi(true);
        target.setNoGravity(true);
        target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
        target.getAttribute(Attributes.ARMOR).setBaseValue(0);
        target.setHealth(1000);
        helper.assertTrue(helper.getLevel().addFreshEntity(target), "Target failed to spawn");
        return target;
    }

    private static void invalidate(SectionKey key) {
        try {
            Field field = TerrainSnapshots.class.getDeclaredField("store");
            field.setAccessible(true);
            ((SnapshotStore) field.get(TerrainSnapshots.INSTANCE)).invalidate(key);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
}
