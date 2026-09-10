package tacz.longrangesniping;

import org.junit.jupiter.api.Test;
import tacz.longrangesniping.scoping.ScopeCone;

import static org.junit.jupiter.api.Assertions.*;

class ScopeConeTest {
    @Test void highPowerConeNarrows() {
        assertTrue(ScopeCone.halfAngle(25) < ScopeCone.halfAngle(6));
        assertTrue(ScopeCone.contains(100, 0, 0, 1, 0, 0, 0.5, 1024, 25));
        assertFalse(ScopeCone.contains(100, 0, 10, 1, 0, 0, 0.5, 1024, 25));
        assertTrue(ScopeCone.contains(100, 0, 10, 1, 0, 0, 0.5, 1024, 6));
    }
    @Test void behindAndBeyondAreRejected() {
        assertFalse(ScopeCone.contains(-10, 0, 0, 1, 0, 0, 1, 1024, 25));
        assertFalse(ScopeCone.contains(1025, 0, 0, 1, 0, 0, 1, 1024, 25));
    }
    @Test void overlappingMobBoundsAreIncluded() {
        assertTrue(ScopeCone.radius(0.6, 3, 0.6) >= 1.5, "Tall mobs need their full bounding radius");
        assertFalse(ScopeCone.containsAtAngle(100, 0, 4, 1, 0, 0, 0, 1024, Math.toRadians(2)));
        assertTrue(ScopeCone.containsAtAngle(100, 0, 4, 1, 0, 0, 1, 1024, Math.toRadians(2)));
    }
    @Test void invalidInputsFailClosed() {
        assertFalse(ScopeCone.contains(Double.NaN, 0, 0, 1, 0, 0, 1, 1024, 25));
        assertFalse(ScopeCone.contains(10, 0, 0, 1, 0, 0, 1, 1024, 0));
        assertFalse(ScopeCone.contains(10, 0, 0, 1, 0, 0, -1, 1024, 25));
    }
    @Test void actualProjectionCornersAreInsidePrefetchForEveryAspectRatio() {
        for (double aspect : new double[]{1, 16.0/9, 21.0/9, 32.0/9}) {
            double vertical = 31.43, horizontal = vertical / aspect;
            double view = ScopeCone.projectionHalfAngle(horizontal, vertical);
            double enter = ScopeCone.padded(view, ScopeCone.ACQUIRE_PADDING);
            assertTrue(ScopeCone.containsAtAngle(100, 100 / vertical, 100 / horizontal, 1, 0, 0, 0, 1024, enter));
            assertTrue(enter > view);
        }
    }
    @Test void existingTargetsHaveAWiderReleaseBoundaryThanNewTargets() {
        double view = Math.toRadians(3), offset = 100 * Math.tan(view) * 1.25;
        assertFalse(ScopeCone.containsAtAngle(100, 0, offset, 1, 0, 0, 0, 1024, ScopeCone.padded(view, ScopeCone.ACQUIRE_PADDING)));
        assertTrue(ScopeCone.containsAtAngle(100, 0, offset, 1, 0, 0, 0, 1024, ScopeCone.padded(view, ScopeCone.RELEASE_PADDING)));
        assertTrue(ScopeCone.maximumHalfAngle(25) < ScopeCone.maximumHalfAngle(6.25));
        assertTrue(ScopeCone.padded(Math.toRadians(70), 1.35) <= Math.toRadians(30));
        assertTrue(Double.isNaN(ScopeCone.projectionHalfAngle(0, 1)));
    }
}
