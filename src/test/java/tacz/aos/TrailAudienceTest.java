package tacz.aos;

import org.junit.jupiter.api.Test;
import tacz.aos.trails.*;
import static org.junit.jupiter.api.Assertions.*;

class TrailAudienceTest {
    private static TrailSegment line(double x0, double z0, double x1, double z1, boolean terminal) {
        return new TrailSegment(3, 1, x0, 80, z0, x1, 80, z1, 64, terminal);
    }
    @Test void onlyTrackedRunsSurviveAndHolesNeverBridge() {
        var s = line(-24, 8, 56, 8, true);
        var parts = TrailAudience.clip(s, (x,z) -> z == 0 && (x == -1 || x == 1));
        assertEquals(2, parts.size(), 1e-10);
        assertEquals(-16, parts.getFirst().x0(), 1e-10); assertEquals(0, parts.getFirst().x1(), 1e-10);
        assertEquals(16, parts.getLast().x0(), 1e-10); assertEquals(32, parts.getLast().x1(), 1e-10);
        assertTrue(parts.stream().noneMatch(TrailSegment::terminal));
        var history = new TrailHistory();
        parts.forEach(p -> history.accept(p, 0)); parts.forEach(p -> history.accept(p, 0));
        assertEquals(2, history.samples().size());
        assertTrue(TrailAudience.clip(s, (x,z) -> false).isEmpty());
        assertEquals(java.util.List.of(s), TrailAudience.clip(s, (x,z) -> true));
    }
    @Test void negativeBoundaryDiagonalAndVerticalAreClippedCorrectly() {
        var negative = TrailAudience.clip(line(16, 8, -32, 8, true), (x,z) -> x == 0);
        assertEquals(1, negative.size()); assertEquals(16, negative.getFirst().x0()); assertEquals(0, negative.getFirst().x1());
        var diagonal = TrailAudience.clip(line(-32, -32, 32, 32, true), (x,z) -> x == -1 && z == -1);
        assertEquals(1, diagonal.size()); assertEquals(-16, diagonal.getFirst().x0()); assertEquals(0, diagonal.getFirst().z1());
        var vertical = new TrailSegment(1, 1, 8, 70, 8, 8, 90, 8, 20, true);
        assertEquals(java.util.List.of(vertical), TrailAudience.clip(vertical, (x,z) -> x == 0 && z == 0));
    }
    @Test void closestApproachWaitsForPassAndAttenuatesWithDistance() {
        var approaching = line(0, 0, 16, 0, false);
        assertNull(TrailAudience.nearMiss(approaching, 20, 82, 0));
        var passing = line(16, 0, 32, 0, false);
        var near = TrailAudience.nearMiss(passing, 20, 81, 0);
        var far = TrailAudience.nearMiss(passing, 20, 89, 0);
        assertNotNull(near); assertNotNull(far);
        assertEquals(20, near.x()); assertEquals(1, near.distance());
        assertTrue(near.volume() > far.volume());
        assertNull(TrailAudience.nearMiss(passing, 20, 92, 0));
        assertNull(TrailAudience.nearMiss(line(0, 0, 4, 0, true), 20, 81, 0), "Wall stop must not project a flyby behind it");
    }
    @Test void clippingWorkIsBoundedEvenAtMaximumBulletSpeed() {
        int[] calls = {0};
        var s = line(-100, -100, 80, 80, false);
        TrailAudience.clip(s, (x,z) -> { calls[0]++; return (x & 1) == 0; });
        assertTrue(calls[0] <= 34);
    }
}
