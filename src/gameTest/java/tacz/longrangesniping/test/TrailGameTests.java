package tacz.longrangesniping.test;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import tacz.longrangesniping.TaczLongRangeSniping;
import tacz.longrangesniping.ballistics.BulletVisualAccess;
import tacz.longrangesniping.network.ShotTrailsPayload;
import tacz.longrangesniping.trails.TrailSegment;

import java.util.List;

@GameTestHolder(TaczLongRangeSniping.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TrailGameTests {
    @GameTest(template = "empty", batch = "trails")
    public static void trailPayloadBoundsAndRoundTrip(GameTestHelper helper) {
        var payload = new ShotTrailsPayload(helper.getLevel().dimension().location(), List.of(
                new TrailSegment(7, 25, -255.5, 81.5, 0, -240.5, 81.25, 0, 15, true)));
        var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
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

    @GameTest(template = "ballistics", batch = "trails")
    public static void nativeVisualEndpointStopsAtImpactWithoutChangingPhysics(GameTestHelper helper) {
        var fixture = new LocalBallisticsGameTests.Fixture(helper);
        helper.setBlock(new BlockPos(10, 5, 4), Blocks.STONE);
        var bullet = fixture.bullet(new Vec3(4, 5.5, 4.5), new Vec3(12, 0, 0));
        var capture = (BulletVisualAccess) bullet;
        capture.taczlrs$beginVisualStep(); bullet.tick(); var impact = capture.taczlrs$endVisualStep();
        helper.assertTrue(impact != null && Math.abs(impact.x - helper.absoluteVec(new Vec3(10, 0, 0)).x) < 0.001,
                "Native trail did not end at wall");
        helper.assertTrue(bullet.isRemoved() && bullet.position().x > impact.x, "Visual capture changed native tick movement");
        helper.setBlock(new BlockPos(10, 5, 4), Blocks.AIR);
        var cow = helper.spawn(EntityType.COW, new Vec3(10, 4.5, 4.5)); cow.setNoAi(true); cow.setNoGravity(true);
        bullet = fixture.bullet(new Vec3(4, 5.5, 4.5), new Vec3(12, 0, 0));
        LocalBallisticsGameTests.setField(bullet, "pierce", 1);
        capture = (BulletVisualAccess) bullet;
        capture.taczlrs$beginVisualStep(); bullet.tick(); impact = capture.taczlrs$endVisualStep();
        helper.assertTrue(bullet.isRemoved() && impact != null && impact.x <= cow.getBoundingBox().maxX,
                "Native trail overshot entity impact");
        cow.discard(); helper.succeed();
    }
}
