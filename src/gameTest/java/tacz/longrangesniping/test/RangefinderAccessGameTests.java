package tacz.longrangesniping.test;

import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import tacz.longrangesniping.TaczLongRangeSniping;
import tacz.longrangesniping.ballistics.LocalBallistics;
import tacz.longrangesniping.block.RangefinderAccess;
import tacz.longrangesniping.compat.tacz.TaczBridge;
import tacz.longrangesniping.config.ServerConfig;

@GameTestHolder(TaczLongRangeSniping.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RangefinderAccessGameTests {
    @GameTest(template = "ballistics", batch = "rangefinder_access")
    public static void distanceBoundaryBothHalvesAndImmediateRemoval(GameTestHelper helper) {
        var fixture = new LocalBallisticsGameTests.Fixture(helper);
        var player = fixture.player;
        var base = TestRangefinders.placeNear(player);
        var level = helper.getLevel();
        int loaded = level.getChunkSource().getLoadedChunksCount();
        player.setPos(base.getX() + 4, base.getY() + 1.5, base.getZ() + .5);
        helper.assertTrue(RangefinderAccess.nearby(player), "Exact 3-block boundary beside upper half was denied");
        player.setPos(player.position().add(.001, 0, 0));
        helper.assertTrue(!RangefinderAccess.nearby(player), "Movement within the same block reused a stale distance");
        player.setPos(base.getX() + 3.2, base.getY() + 1, base.getZ() + 3.2);
        helper.assertTrue(!RangefinderAccess.nearby(player), "Diagonal cube corners were treated as within a sphere");
        player.setPos(base.getX() + .5, base.getY() + 5, base.getZ() + .5);
        helper.assertTrue(RangefinderAccess.nearby(player), "Exact vertical boundary above upper half was denied");
        player.setPos(base.getX() + .5, base.getY() - 3, base.getZ() + .5);
        helper.assertTrue(RangefinderAccess.nearby(player), "Exact vertical boundary below lower half was denied");
        player.setPos(base.getX() + .5, base.getY() + 1, base.getZ() - 1);
        helper.assertTrue(RangefinderAccess.nearby(player), "Complete device missing");
        level.removeBlock(base.above(), false);
        helper.assertTrue(!RangefinderAccess.nearby(player), "Same-tick upper removal retained authorization");
        TestRangefinders.placeNear(player);
        helper.assertTrue(RangefinderAccess.nearby(player), "Same-tick placement did not restore access");
        helper.assertTrue(level.getChunkSource().getLoadedChunksCount() == loaded, "Proximity checks loaded chunks");
        helper.succeed();
    }

    @GameTest(template = "ballistics", batch = "rangefinder_access")
    public static void everyZoomAdmitsShotsAndAnyDeviceGrantsAccess(GameTestHelper helper) {
        var fixture = new LocalBallisticsGameTests.Fixture(helper);
        var player = fixture.player;
        var first = TestRangefinders.placeNear(player);
        var original = player.position();
        player.setPos(original.add(1, 0, 0));
        var second = TestRangefinders.placeNear(player);
        player.setPos(original);
        helper.getLevel().removeBlock(first, false);
        var gun = IGun.getIGunOrNull(fixture.stack);
        for (int slot = 0; slot < 3; slot++) {
            var tag = gun.getAttachmentTag(fixture.stack, AttachmentType.SCOPE).copy();
            tag.putInt("ZoomNumber", slot); gun.setAttachmentTag(fixture.stack, AttachmentType.SCOPE, tag);
            helper.assertTrue(TaczBridge.evaluate(player).eligible(), "Zoom slot rejected: " + slot);
            var bullet = fixture.bullet(new Vec3(4, 5, 4), new Vec3(1, 0, 0));
            helper.assertTrue(TaczBridge.inspectNewBullet(bullet).isPresent(), "Zoom slot could not create shot: " + slot);
            bullet.discard();
            // Keep the first location empty: access must come from the other device.
            helper.getLevel().removeBlock(first, false);
        }
        helper.getLevel().removeBlock(second.above(), false);
        helper.assertTrue(!TaczBridge.evaluate(player).eligible(), "Removing last device did not revoke access");
        helper.succeed();
    }

    @GameTest(template = "ballistics", batch = "rangefinder_access")
    public static void leavingOrRemovingDeviceStopsDetachedFlight(GameTestHelper helper) {
        var fixture = new LocalBallisticsGameTests.Fixture(helper);
        var scheduler = new LocalBallistics(helper.getLevel());
        var limits = new ServerConfig.Ballistics(true, 256, 4, 600, true);
        var bullet = fixture.bullet(new Vec3(4, 5, 4), new Vec3(.1, 0, 0));
        LocalBallisticsGameTests.accept(helper, scheduler, bullet, limits);
        scheduler.tick();
        helper.assertTrue(!bullet.isRemoved() && bullet.tickCount == 1, "Near-device flight did not advance");
        fixture.player.setPos(fixture.player.position().add(8, 0, 0));
        scheduler.tick();
        helper.assertTrue(bullet.isRemoved() && bullet.tickCount == 1 && scheduler.status().active() == 0
                && scheduler.status().lastTermination().equals("rangefinder_unavailable"), "Leaving device let a shot continue");
        var next = fixture.bullet(new Vec3(4, 5, 4), new Vec3(.1, 0, 0));
        LocalBallisticsGameTests.accept(helper, scheduler, next, limits);
        helper.getLevel().removeBlock(TestRangefinders.placeNear(fixture.player).above(), false);
        scheduler.tick();
        helper.assertTrue(next.isRemoved() && next.tickCount == 0 && scheduler.status().active() == 0,
                "Device removal retained a pending flight");
        helper.succeed();
    }
}
