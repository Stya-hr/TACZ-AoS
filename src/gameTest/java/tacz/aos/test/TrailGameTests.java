package tacz.aos.test;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import tacz.aos.TaczArtOfSniping;

import tacz.aos.network.ShotTrailsPayload;
import tacz.aos.trails.TrailSegment;

import java.util.List;

@GameTestHolder(TaczArtOfSniping.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TrailGameTests {
    @GameTest(setupTicks = 20, template = "ballistics", batch = "trails")
    public static void descendingShotStopsAtGroundWhileClientSmokeFades(GameTestHelper helper) {
        var fixture = new LocalBallisticsGameTests.Fixture(helper);
        for (int x = 3; x <= 18; x++) for (int z = 3; z <= 6; z++)
            helper.setBlock(new BlockPos(x, 3, z), Blocks.STONE);
        var bullet = fixture.bullet(new Vec3(4, 9, 4.5), new Vec3(10, -8, 0));
        var start = bullet.position();
        LocalBallisticsGameTests.targetTick(helper, bullet, 128);
        var end = bullet.position();
        double ground = helper.absoluteVec(new Vec3(0, 4, 0)).y;
        helper.assertTrue(bullet.isRemoved() && Math.abs(end.y - ground) < .001,
                "Descending bullet went through the ground: " + end);
        var history = new tacz.aos.trails.TrailHistory();
        history.accept(new TrailSegment(42, 1, start.x, start.y, start.z, end.x, end.y, end.z,
                (float) new Vec3(10, -8, 0).length(), true), 10);
        helper.assertTrue(history.hasVisibleSamples(10.5), "Final approach was lost");
        history.expire(12);
        helper.assertTrue(history.hasVisibleSamples(12) && !history.hasMovingHead(42, 12),
                "Shooter lost smoke or kept the bullet head after impact");
        helper.assertTrue(history.visibleSamples(12).stream().allMatch(sample -> sample.segment().y1() >= ground - .001),
                "Retained shooter smoke crossed the ground");
        var observer = new tacz.aos.trails.TrailHistory();
        history.samples().forEach(sample -> tacz.aos.trails.TrailAudience.clip(sample.segment(), (x,z) -> true)
                .forEach(piece -> observer.accept(piece, 10)));
        observer.expire(12);
        helper.assertTrue(observer.hasVisibleSamples(12) && !observer.hasMovingHead(42, 12),
                "Observer lost the wake or kept a head after impact");
        helper.assertTrue(observer.visibleSamples(12).stream().allMatch(sample -> sample.segment().y1() >= ground - .001),
                "Retained observer smoke crossed the ground");
        history.expire(62);
        helper.assertTrue(!history.hasVisibleSamples(62), "Shooter wake did not expire");
        observer.expire(62);
        helper.assertTrue(!observer.hasVisibleSamples(62), "Observer wake did not expire");
        helper.succeed();
    }

    @GameTest(setupTicks = 20, template = "empty", batch = "trails")
    public static void trailPayloadBoundsAndRoundTrip(GameTestHelper helper) {
        var payload = new ShotTrailsPayload(helper.getLevel().dimension().location(), List.of(
                new TrailSegment(7, 25, -255.5, 81.5, 0, -240.5, 81.25, 0, 15, true)));
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ShotTrailsPayload.STREAM_CODEC.encode(buf, payload);
            helper.assertTrue(payload.equals(ShotTrailsPayload.STREAM_CODEC.decode(buf)), "Trail payload lost geometry or termination");
            buf.clear(); buf.writeResourceLocation(payload.dimension()); buf.writeBoolean(false); buf.writeVarInt(129);
            boolean rejected = false;
            try { ShotTrailsPayload.STREAM_CODEC.decode(buf); } catch (IllegalArgumentException expected) { rejected = true; }
            helper.assertTrue(rejected, "Trail payload accepted unbounded allocation");
        } finally { buf.release(); }
        helper.succeed();
    }

    @GameTest(setupTicks = 20, template = "ballistics", batch = "trails")
    public static void targetVisualEndpointStopsAtActualImpact(GameTestHelper helper) {
        var fixture = new LocalBallisticsGameTests.Fixture(helper);
        helper.setBlock(new BlockPos(10, 5, 4), Blocks.STONE);
        var bullet = fixture.bullet(new Vec3(4, 5.5, 4.5), new Vec3(12, 0, 0));
        LocalBallisticsGameTests.targetTick(helper,bullet,128); var impact=bullet.position();
        helper.assertTrue(impact != null && Math.abs(impact.x - helper.absoluteVec(new Vec3(10, 0, 0)).x) < 0.001,
                "Native trail did not end at wall");
        helper.assertTrue(bullet.isRemoved() && bullet.position().equals(impact), "Visual endpoint differs from actual impact");
        helper.setBlock(new BlockPos(10, 5, 4), Blocks.AIR);
        var cow = helper.spawn(EntityType.COW, new Vec3(10, 4.5, 4.5)); cow.setNoAi(true); cow.setNoGravity(true);
        bullet = fixture.bullet(new Vec3(4, 5.5, 4.5), new Vec3(12, 0, 0));
        LocalBallisticsGameTests.setField(bullet, "pierce", 1);
        LocalBallisticsGameTests.targetTick(helper,bullet,128); impact=bullet.position();
        helper.assertTrue(bullet.isRemoved() && impact != null && impact.x <= cow.getBoundingBox().maxX,
                "Native trail overshot entity impact");
        cow.discard(); helper.succeed();
    }
}
