package tacz.longrangesniping;

import org.junit.jupiter.api.Test;
import tacz.longrangesniping.trails.TrailHistory;
import tacz.longrangesniping.trails.TrailSegment;
import tacz.longrangesniping.trails.TrailStyle;

import static org.junit.jupiter.api.Assertions.*;

class TrailHistoryTest {
    private static TrailSegment segment(int id, int step, boolean terminal) {
        return new TrailSegment(id, step, step, 80, 0, step + 1, 80, 0, 20, terminal);
    }
    @Test void verificationTrailHoldsDensityAndFadesAtOnePointEightSecondsEvenAtHighSpeed() {
        for (double speed : new double[]{0, 5, 28, 200}) {
            assertEquals(36, TrailStyle.lifetime(speed));
            assertEquals(1, TrailStyle.alpha(4, speed));
            assertTrue(TrailStyle.alpha(8, speed) > 0.7);
            assertTrue(TrailStyle.alpha(12, speed) < TrailStyle.alpha(8, speed));
            assertTrue(TrailStyle.alpha(32, speed) > 0);
            assertEquals(0, TrailStyle.alpha(36, speed));
        }
    }
    @Test void apparentWidthRemainsSmallAndVisibleAtEverySupportedDistanceAndZoom() {
        for (double distance : new double[]{0.75, 10, 128, 1024, 16384}) {
            for (double projection : new double[]{1.4, 10, 31.43, 100}) {
                double scale = 2 / (1080 * projection);
                double pixels = TrailStyle.halfWidth(distance, scale) / (distance * scale);
                assertTrue(pixels >= 2.27499 && pixels <= 2.73001, "Out-of-bounds apparent width: " + pixels);
            }
        }
    }
    @Test void duplicatesOutOfOrderAndPostTerminalUpdatesAreIgnored() {
        var h = new TrailHistory();
        h.accept(segment(1, 2, false), 100); h.accept(segment(1, 1, false), 101);
        h.accept(segment(1, 2, false), 101); h.accept(segment(1, 3, true), 102);
        h.accept(segment(1, 4, false), 103);
        assertEquals(2, h.samples().size());
        assertTrue(h.samples().getLast().segment().terminal());
    }
    @Test void missingUpdatesDoNotSynthesizeConnectingSegments() {
        var h = new TrailHistory(); h.accept(segment(1, 1, false), 0); h.accept(segment(1, 9, true), 9);
        assertEquals(2, h.samples().size());
        assertEquals(9, h.samples().getLast().segment().x0());
        h.expire(47); assertTrue(h.isEmpty());
    }
    @Test void memoryAndLostTerminalCleanupAreBounded() {
        var h = new TrailHistory();
        for (int id = 0; id < 500; id++) for (int step = 1; step <= TrailHistory.MAX_SAMPLES + 5; step++) h.accept(segment(id, step, false), step);
        assertEquals(TrailHistory.MAX_SHOTS * TrailHistory.MAX_SAMPLES, h.samples().size());
        h.expire(TrailHistory.MAX_SAMPLES + TrailStyle.LIFETIME_TICKS + 7); assertTrue(h.isEmpty());
        h.accept(segment(1, 1, false), 40); h.clear(); assertTrue(h.isEmpty());
    }
    @Test void lagCannotQueueAnUnboundedVisualFuture() {
        var h = new TrailHistory();
        for (int i = 1; i <= 100; i++) h.accept(segment(1, i, false), 10);
        assertTrue(h.samples().stream().allMatch(s -> s.start() <= 11));
        h.expire(50); assertTrue(h.isEmpty());
    }
    @Test void rollingHistoryDoesNotCutOffOnePointEightSecondTailAtOldTwentyEightSampleLimit() {
        var h = new TrailHistory();
        for (int step = 1; step <= 38; step++) {
            h.accept(segment(1, step, false), step); h.expire(step);
        }
        assertTrue(h.samples().stream().anyMatch(s -> s.segment().step() == 3), "Visible 35-tick-old sample was dropped");
        h.expire(76); assertTrue(h.isEmpty());
    }
    @Test void malformedAndTeleportSegmentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new TrailSegment(1, 1, Double.NaN, 0, 0, 0, 0, 0, 1, false));
        assertThrows(IllegalArgumentException.class, () -> new TrailSegment(1, 1, 0, 0, 0, 300, 0, 0, 1, false));
        assertThrows(IllegalArgumentException.class, () -> new TrailSegment(1, 0, 0, 0, 0, 1, 0, 0, 1, false));
    }
}
