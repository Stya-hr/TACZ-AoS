package tacz.aos.test;

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
import tacz.aos.TaczArtOfSniping;
import tacz.aos.ballistics.BlockImpactEffects;
import tacz.aos.ballistics.LocalBallistics;
import tacz.aos.config.ServerConfig;
import tacz.aos.network.NearMissPayload;
import tacz.aos.network.ShotTrailsPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@GameTestHolder(TaczArtOfSniping.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ObserverFeedbackGameTests {
    @GameTest(template = "ballistics", batch = "distant_gunshots")
    public static void managedShotAudioIsDelayedOnceAndSurvivesBulletRemoval(GameTestHelper h) throws ReflectiveOperationException {
        var shooter = viewer(h, new Vec3(4,5,4), true);
        var near = viewer(h, new Vec3(44,5,4), true);
        var far = viewer(h, new Vec3(1004,5,4), false);
        var fixture = new LocalBallisticsGameTests.Fixture(h, shooter.player);
        var scheduler = new LocalBallistics(h.getLevel());
        var gun = net.minecraft.resources.ResourceLocation.parse("tacz:ai_awp");
        int chunks = h.getLevel().getChunkSource().getLoadedChunksCount();
        long now = h.getLevel().getGameTime();
        var mapField = net.minecraft.server.level.ChunkMap.class.getDeclaredField("playerMap");
        mapField.setAccessible(true);
        var nativePlayers = (net.minecraft.server.level.PlayerMap) mapField.get(h.getLevel().getChunkSource().chunkMap);
        nativePlayers.addPlayer(near.player, false); nativePlayers.addPlayer(far.player, false);
        far.player.setChunkTrackingView(ChunkTrackingView.of(shooter.player.chunkPosition(), 4));
        try {
            var bullet = fixture.bullet(new Vec3(4,6,4), new Vec3(40,0,0));
            LocalBallisticsGameTests.accept(h, scheduler, bullet, new ServerConfig.Ballistics(true,2048,16,600));
            // Exercise the real TACZ mixin, with an intentionally large native audible radius.
            com.tacz.guns.sound.SoundManager.sendSoundToNearby(shooter.player, 2048, gun, gun,
                    com.tacz.guns.sound.SoundManager.SHOOT_3P_SOUND, .8f, 1);
            h.assertTrue(near.count(com.tacz.guns.network.message.ServerMessageSound.class) == 1, "Nearby native shot disappeared");
            h.assertTrue(far.count(com.tacz.guns.network.message.ServerMessageSound.class) == 0, "Far listener got immediate duplicate gunfire");
            scheduler.clear(); // A hit or shutdown of projectile simulation must not cancel the sound wave.
            var sound = tacz.aos.trails.DelayedGunshots.INSTANCE;
            sound.deliver(h.getLevel(), now + 58);
            h.assertTrue(far.count(tacz.aos.network.DistantGunshotPayload.class) == 0, "1000-block gunshot arrived too early");
            sound.deliver(h.getLevel(), now + 59);
            sound.deliver(h.getLevel(), now + 60);
            h.assertTrue(far.count(tacz.aos.network.DistantGunshotPayload.class) == 1, "Distant gunshot missing or repeated");
            h.assertTrue(shooter.count(tacz.aos.network.DistantGunshotPayload.class) == 0 && near.count(tacz.aos.network.DistantGunshotPayload.class) == 0,
                    "Shooter/near listener received a delayed duplicate");
            h.assertTrue(chunks == h.getLevel().getChunkSource().getLoadedChunksCount(), "Sound propagation loaded terrain");
            sound.accepted(h.getLevel(), shooter.player.getUUID(), gun);
            sound.replace(shooter.player, 2048, gun, gun, com.tacz.guns.sound.SoundManager.SHOOT_3P_SOUND, .8f, 1);
            sound.dimension(new net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent(
                    far.player, net.minecraft.world.level.Level.OVERWORLD, net.minecraft.world.level.Level.NETHER));
            sound.deliver(h.getLevel(), now + 59);
            h.assertTrue(far.count(tacz.aos.network.DistantGunshotPayload.class) == 1, "Dimension change retained an old gunshot");
            var configField = ServerConfig.class.getDeclaredField("distantGunshots");
            configField.setAccessible(true);
            var original = ServerConfig.distantGunshots();
            sound.accepted(h.getLevel(), shooter.player.getUUID(), gun);
            try {
                configField.set(null, new ServerConfig.DistantGunshots(false,343,128,0,.65));
                h.assertTrue(!sound.replace(shooter.player,2048,gun,gun,com.tacz.guns.sound.SoundManager.SHOOT_3P_SOUND,.8f,1),
                        "Disabled feature still intercepted native sound");
                sound.deliver(h.getLevel(),now+60);
            } finally { configField.set(null,original); }
        } finally {
            nativePlayers.removePlayer(near.player); nativePlayers.removePlayer(far.player);
            scheduler.clear();
            for (var v : List.of(shooter,near,far)) h.getLevel().players().remove(v.player);
        }
        h.succeed();
    }

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
        var subscriptions = tacz.aos.scoping.ScopeSubscriptions.INSTANCE;
        var cow = h.spawn(net.minecraft.world.entity.EntityType.COW, new Vec3(12,5,4));
        var other = h.spawn(net.minecraft.world.entity.EntityType.COW, new Vec3(13,5,4));
        var dimension = h.getLevel().dimension().location();
        try {
            subscriptions.replace(viewer.player, List.of(cow));
            var tracker = tacz.aos.scoping.ScopeSubscriptions.tracker(h.getLevel(), cow.getId());
            h.assertTrue(tracker.tacz_aos$seenBy().contains(viewer.player.connection), "Fixture did not start native observation");
            int observers = tracker.tacz_aos$seenBy().size();
            int chunks = h.getLevel().getChunkSource().getLoadedChunksCount();
            viewer.packets.clear();
            var requested = new tacz.aos.network.ScopeEntityRepairPayload.Target(cow.getId(), cow.getUUID());
            var request = new tacz.aos.network.ScopeEntityRepairPayload(dimension, List.of(
                    requested, requested,
                    new tacz.aos.network.ScopeEntityRepairPayload.Target(other.getId(), other.getUUID()),
                    new tacz.aos.network.ScopeEntityRepairPayload.Target(cow.getId(), UUID.randomUUID())));
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
            h.assertTrue(tracker.tacz_aos$seenBy().size() == observers, "Repair changed observation ownership");
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
        var outsideHearing = viewer(h, new Vec3(12, 5.38, 10), true);
        var scheduler = new LocalBallistics(h.getLevel());
        int loaded = h.getLevel().getChunkSource().getLoadedChunksCount();
        try {
            h.assertTrue(!tacz.aos.block.RangefinderAccess.nearby(visible.player), "Observer fixture is near a device");
            var bullet = f.bullet(new Vec3(4, 7, 4), new Vec3(10, 0, 0));
            LocalBallisticsGameTests.setField(bullet, "gravity", 0f);
            LocalBallisticsGameTests.setField(bullet, "friction", 0f);
            LocalBallisticsGameTests.accept(h, scheduler, bullet, new ServerConfig.Ballistics(true, 128, 16, 20));
            scheduler.tick(); scheduler.tick();
            h.assertTrue(visible.count(ShotTrailsPayload.class) >= 1, "Unscoped observer received no trace");
            h.assertTrue(visible.count(NearMissPayload.class) == 1, "Flyby was missing or repeated");
            h.assertTrue(outsideHearing.count(ShotTrailsPayload.class) >= 1 && outsideHearing.count(NearMissPayload.class) == 0,
                    "Observer six blocks from the path must see smoke but hear no flyby");
            for (var packet : visible.packets) if (packet instanceof ClientboundCustomPayloadPacket c && c.payload() instanceof ShotTrailsPayload p)
                h.assertTrue(p.localOnly(), "Observer was granted full-distance trails");
            h.assertTrue(untracked.count(ShotTrailsPayload.class) == 0 && untracked.count(NearMissPayload.class) == 0,
                    "Untracked chunks leaked effects");
            h.assertTrue(far.count(ShotTrailsPayload.class) == 0 && far.count(NearMissPayload.class) == 0, "Far player received irrelevant effects");
            h.assertTrue(h.getLevel().getChunkSource().getLoadedChunksCount() == loaded, "Feedback loaded terrain");
        } finally {
            scheduler.clear();
            for (var v : List.of(visible,untracked,far,outsideHearing)) h.getLevel().players().remove(v.player);
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
