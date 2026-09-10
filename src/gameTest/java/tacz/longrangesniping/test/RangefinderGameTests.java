package tacz.longrangesniping.test;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import tacz.longrangesniping.TaczLongRangeSniping;
import tacz.longrangesniping.scoping.ScopeRangefinder;
import tacz.longrangesniping.terrain.CollisionCell;
import java.util.List;

@GameTestHolder(TaczLongRangeSniping.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RangefinderGameTests {
    @GameTest(template = "ballistics", batch = "rangefinder")
    public static void straightDistanceUsesFirstSurfaceAndUnknownStops(GameTestHelper helper) {
        var eye = new Vec3(.5, 10.5, .5);
        var direction = new Vec3(1, 0, 0);
        long deadline = System.nanoTime() + 10_000_000_000L;
        float wall = ScopeRangefinder.measure(eye, direction, (x,y,z) -> x == 300 ? CollisionCell.FULL : CollisionCell.AIR,
                List.of(), 1024, 65536, deadline);
        helper.assertTrue(Math.abs(wall - 299.5) < .001, "Straight block surface range incorrect: " + wall);
        var target = new AABB(201, 10, 0, 202, 12, 1);
        float entity = ScopeRangefinder.measure(eye, direction, (x,y,z) -> x == 300 ? CollisionCell.FULL : CollisionCell.AIR,
                List.of(target), 1024, 65536, deadline);
        helper.assertTrue(Math.abs(entity - 200.5) < .001, "Nearest entity range incorrect: " + entity);
        float blocked = ScopeRangefinder.measure(eye, direction, (x,y,z) -> x == 128 ? CollisionCell.FULL : CollisionCell.AIR,
                List.of(target), 1024, 65536, deadline);
        helper.assertTrue(Math.abs(blocked - 127.5) < .001, "Reported entity through wall");
        helper.assertTrue(ScopeRangefinder.measure(eye, direction, (x,y,z) -> x >= 128 ? null : CollisionCell.AIR,
                List.of(target), 1024, 65536, deadline) == -1, "Reported entity through unknown area");
        helper.assertTrue(ScopeRangefinder.measure(eye, direction, (x,y,z) -> CollisionCell.AIR,
                List.of(), 1024, 65536, deadline) == -1, "Air reported as max range");
        helper.assertTrue(ScopeRangefinder.measure(eye, direction, (x,y,z) -> CollisionCell.FULL,
                List.of(), 1024, 65536, System.nanoTime() - 1) == -1, "Time budget bypassed");
        helper.assertTrue(ScopeRangefinder.measure(eye, direction, (x,y,z) -> CollisionCell.AIR,
                List.of(target), 1024, 2, deadline) == -1, "Cell budget bypassed");
        helper.succeed();
    }
}
