package tacz.longrangesniping;

import org.junit.jupiter.api.Test;
import tacz.longrangesniping.scoping.ScopeCone;

import static org.junit.jupiter.api.Assertions.*;

class ScopeConeTest {
    @Test void highPowerConeNarrows() {
        assertTrue(ScopeCone.halfAngle(25) < ScopeCone.halfAngle(6));
        assertTrue(ScopeCone.contains(100, 0, 0, 1, 0, 0, 0.5, 1024, 25));
        assertFalse(ScopeCone.contains(100, 0, 5, 1, 0, 0, 0.5, 1024, 25));
        assertTrue(ScopeCone.contains(100, 0, 5, 1, 0, 0, 0.5, 1024, 6));
    }
    @Test void behindAndBeyondAreRejected() {
        assertFalse(ScopeCone.contains(-10, 0, 0, 1, 0, 0, 1, 1024, 25));
        assertFalse(ScopeCone.contains(1025, 0, 0, 1, 0, 0, 1, 1024, 25));
    }
    @Test void overlappingMobBoundsAreIncluded() {
        assertTrue(ScopeCone.radius(0.6, 3, 0.6) >= 1.5, "Tall mobs need their full bounding radius");
        assertFalse(ScopeCone.contains(100, 0, 3, 1, 0, 0, 0, 1024, 25));
        assertTrue(ScopeCone.contains(100, 0, 3, 1, 0, 0, 1, 1024, 25));
    }
    @Test void invalidInputsFailClosed() {
        assertFalse(ScopeCone.contains(Double.NaN, 0, 0, 1, 0, 0, 1, 1024, 25));
        assertFalse(ScopeCone.contains(10, 0, 0, 1, 0, 0, 1, 1024, 0));
        assertFalse(ScopeCone.contains(10, 0, 0, 1, 0, 0, -1, 1024, 25));
    }
}
