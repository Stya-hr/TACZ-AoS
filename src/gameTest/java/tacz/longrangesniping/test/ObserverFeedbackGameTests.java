package tacz.longrangesniping.test;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import tacz.longrangesniping.TaczLongRangeSniping;
import tacz.longrangesniping.ballistics.BlockImpactEffects;
import tacz.longrangesniping.ballistics.LocalBallistics;
import tacz.longrangesniping.config.ServerConfig;
import tacz.longrangesniping.network.NearMissPayload;
import tacz.longrangesniping.network.ShotTrailsPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@GameTestHolder(TaczLongRangeSniping.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ObserverFeedbackGameTests {
    private record Viewer(FakePlayer player, List<Packet<?>> packets) {
        long count(Class<?> type) {
            return packets.stream().filter(p -> p instanceof ClientboundCustomPayloadPacket c && type.isInstance(c.payload())).count();
        }
    }
    private static Viewer viewer(GameTestHelper helper, Vec3 position, boolean tracked) {
        var level = helper.getLevel();
        var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "feedback-viewer"));
        player.setPos(helper.absoluteVec(position));
        var packets = new ArrayList<Packet<?>>();
        player.connection = new ServerGamePacketListenerImpl(level.getServer(), new Connection(PacketFlow.SERVERBOUND), player,
                CommonListenerCookie.createInitial(player.getGameProfile(), false)) {
            @Override public void send(Packet<?> p) { packets.add(p); }
        };
        player.setChunkTrackingView(tracked ? ChunkTrackingView.of(player.chunkPosition(), 4) : ChunkTrackingView.EMPTY);
        level.players().add(player);
        return new Viewer(player, packets);
    }

    @GameTest(template = "ballistics", batch = "observer_feedback")
    public static void missingScopeEntityRepairIsAuthorizedBoundedAndUsesNativePairing(GameTestHelper h) {
        var viewer = viewer(h, new Vec3(4,5,4), false);
        new LocalBallisticsGameTests.Fixture(h, viewer.player);
        var subscriptions = tacz.longrangesniping.scoping.ScopeSubscriptions.INSTANCE;
        var cow = h.spawn(net.minecraft.world.entity.EntityType.COW, new Vec3(12,5,4));
        var other = h.spawn(net.minecraft.world.entity.EntityType.COW, new Vec3(13,5,4));
        var dimension = h.getLevel().dimension().location();
        try {
            subscriptions.replace(viewer.player, List.of(cow));
            var tracker = tacz.longrangesniping.scoping.ScopeSubscriptions.tracker(h.getLevel(), cow.getId());
            h.assertTrue(tracker.taczlrs$seenBy().contains(viewer.player.connection), "Fixture did not start native observation");
            int observers = tracker.taczlrs$seenBy().size();
            int chunks = h.getLevel().getChunkSource().getLoadedChunksCount();
            viewer.packets.clear();
            var requested = new tacz.longrangesniping.network.ScopeEntityRepairPayload.Target(cow.getId(), cow.getUUID());
            var request = new tacz.longrangesniping.network.ScopeEntityRepairPayload(dimension, List.of(
                    requested, requested,
                    new tacz.longrangesniping.network.ScopeEntityRepairPayload.Target(other.getId(), other.getUUID()),
                    new tacz.longrangesniping.network.ScopeEntityRepairPayload.Target(cow.getId(), UUID.randomUUID())));
            subscriptions.repair(viewer.player, request);
            h.assertTrue(viewer.packets.size() == 1 && viewer.packets.getFirst() instanceof net.minecraft.network.protocol.game.ClientboundBundlePacket,
                    "Repair duplicated or disclosed an unauthorized target");
            var bundle = (net.minecraft.network.protocol.game.ClientboundBundlePacket) viewer.packets.getFirst();
            int spawns = 0;
            for (var packet : bundle.subPackets()) if (packet instanceof net.minecraft.network.protocol.game.ClientboundAddEntityPacket spawn) {
                h.assertTrue(spawn.getId() == cow.getId() && spawn.getUUID().equals(cow.getUUID()), "Repair spawned the wrong identity");
                spawns++;
            }
            h.assertTrue(spawns == 1, "Missing native spawn packet");
            subscriptions.repair(viewer.player, request);
            h.assertTrue(viewer.packets.size() == 1, "Repair rate limit was bypassed");
            h.assertTrue(tracker.taczlrs$seenBy().size() == observers, "Repair changed observation ownership");
            h.assertTrue(h.getLevel().getChunkSource().getLoadedChunksCount() == chunks, "Repair loaded chunks");
        } finally {
            subscriptions.replace(viewer.player, List.of());
            h.getLevel().players().remove(viewer.player);
            cow.discard(); other.discard();
        }
        h.succeed();
    }

    @GameTest(template = "ballistics", batch = "observer_feedback")
    public static void bystanderReceivesLocalTrailAndOneFlybyWithoutRangefinder(GameTestHelper h) {
        var f = new LocalBallisticsGameTests.Fixture(h);
        var visible = viewer(h, new Vec3(12, 5.38, 7), true);
        var untracked = viewer(h, new Vec3(12, 5.38, 7), false);
        var far = viewer(h, new Vec3(500, 5.38, 7), true);
        var scheduler = new LocalBallistics(h.getLevel());
        int loaded = h.getLevel().getChunkSource().getLoadedChunksCount();
        try {
            h.assertTrue(!tacz.longrangesniping.block.RangefinderAccess.nearby(visible.player), "Observer fixture is near a device");
            var bullet = f.bullet(new Vec3(4, 7, 4), new Vec3(10, 0, 0));
            LocalBallisticsGameTests.setField(bullet, "gravity", 0f);
            LocalBallisticsGameTests.setField(bullet, "friction", 0f);
            LocalBallisticsGameTests.accept(h, scheduler, bullet, new ServerConfig.Ballistics(true, 128, 16, 20));
            scheduler.tick(); scheduler.tick();
            h.assertTrue(visible.count(ShotTrailsPayload.class) >= 1, "Unscoped observer received no trace");
            h.assertTrue(visible.count(NearMissPayload.class) == 1, "Flyby was missing or repeated");
            for (var packet : visible.packets) if (packet instanceof ClientboundCustomPayloadPacket c && c.payload() instanceof ShotTrailsPayload p)
                h.assertTrue(p.localOnly(), "Observer was granted full-distance trails");
            h.assertTrue(untracked.count(ShotTrailsPayload.class) == 0 && untracked.count(NearMissPayload.class) == 0,
                    "Untracked chunks leaked effects");
            h.assertTrue(far.count(ShotTrailsPayload.class) == 0 && far.count(NearMissPayload.class) == 0, "Far player received irrelevant effects");
            h.assertTrue(h.getLevel().getChunkSource().getLoadedChunksCount() == loaded, "Feedback loaded terrain");
        } finally {
            scheduler.clear();
            for (var v : List.of(visible,untracked,far)) h.getLevel().players().remove(v.player);
        }
        h.succeed();
    }

    @GameTest(template = "ballistics", batch = "observer_feedback")
    public static void targetImpactUsesNativeDecalOnlyOnConfirmedLoadedSurface(GameTestHelper h) {
        var f = new LocalBallisticsGameTests.Fixture(h);
        var visible = viewer(h, new Vec3(12, 5, 7), true);
        var untracked = viewer(h, new Vec3(12, 5, 7), false);
        h.setBlock(new BlockPos(10,7,4), Blocks.STONE);
        var start = h.absoluteVec(new Vec3(4,7.5,4.5));
        int loaded = h.getLevel().getChunkSource().getLoadedChunksCount();
        try {
            var hit = BlockImpactEffects.liveHit(h.getLevel(), start, new Vec3(12,0,0), .5);
            h.assertTrue(hit != null && hit.getBlockPos().equals(h.absolutePos(new BlockPos(10,7,4)))
                    && hit.getDirection() == net.minecraft.core.Direction.WEST, "Wrong surface or decal face");
            var bullet = f.bullet(new Vec3(4,7.5,4.5), new Vec3(12,0,0));
            BlockImpactEffects.targetHit(h.getLevel(), bullet, start, new Vec3(12,0,0), .5);
            h.assertTrue(visible.packets.stream().filter(p -> p instanceof ClientboundLevelParticlesPacket q
                    && q.getParticle() instanceof com.tacz.guns.particles.BulletHoleOption).count() == 1, "Missing native decal packet");
            h.assertTrue(untracked.packets.stream().noneMatch(p -> p instanceof ClientboundLevelParticlesPacket), "Untracked player received decal");
            h.setBlock(new BlockPos(10,7,4), Blocks.AIR);
            h.assertTrue(BlockImpactEffects.liveHit(h.getLevel(), start, new Vec3(12,0,0), .5) == null, "Invented decal in air");
            h.assertTrue(BlockImpactEffects.liveHit(h.getLevel(), new Vec3(13000000,90,13000000), new Vec3(12,0,0), .5) == null,
                    "Unloaded surface was treated as live");
            h.assertTrue(h.getLevel().getChunkSource().getLoadedChunksCount() == loaded, "Decal lookup loaded terrain");
        } finally {
            h.getLevel().players().remove(visible.player); h.getLevel().players().remove(untracked.player);
        }
        h.succeed();
    }
}
