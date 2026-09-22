package tacz.aos;

import org.junit.jupiter.api.Test;
import tacz.aos.trails.TrailHistory;
import tacz.aos.trails.TrailSegment;
import tacz.aos.trails.TrailStyle;

import static org.junit.jupiter.api.Assertions.*;

class TrailHistoryTest {
    @Test void impactStopsHeadButKeepsEachShotsSmokeOnItsOwnClock() {
        var h = new TrailHistory();
        h.accept(segment(1, 1, false), 10);
        h.accept(segment(2, 1, false), 20);
        h.accept(segment(1, 12, true), 21);
        assertTrue(h.hasMovingHead(1, 21.5));
        h.expire(22);
        assertFalse(h.hasMovingHead(1, 22));
        assertEquals(3, h.visibleSamples(22).size());
        var confirmed = h.samples();
        h.accept(segment(1, 13, false), 23);
        assertEquals(confirmed, h.visibleSamples(23), "Late update extended a stopped bullet");
        h.expire(32);
        assertEquals(2, h.visibleSamples(32).size(), "Old smoke was refreshed at impact");
        assertTrue(h.samples().stream().noneMatch(s -> s.segment().shot() == 1 && s.segment().step() == 1));
        h.expire(94);
        assertFalse(h.hasVisibleSamples(94));
    }
    @Test void zeroMovementTerminationStopsHeadWithoutClearingOrRefreshingSmoke() {
        var h = new TrailHistory(); h.accept(segment(1, 1, false), 10);
        h.accept(new TrailSegment(1, 2, 2, 80, 0, 2, 80, 0, 0, true), 10.5);
        assertTrue(h.hasVisibleSamples(10.5));
        assertFalse(h.hasMovingHead(1, 10.5));
        assertEquals(1, h.samples().size());
        assertEquals(10, h.samples().get(0).start());
        h.accept(segment(1, 3, false), 12);
        assertEquals(1, h.samples().size());
        h.expire(62); assertTrue(h.isEmpty());
    }
    @Test void laggedTerminalStopsInterpolationWhileSmokeStillFades() {
        var h = new TrailHistory();
        for (int step = 1; step <= 20; step++) h.accept(segment(1, step, false), 10);
        h.accept(segment(1, 21, true), 10);
        assertTrue(h.hasMovingHead(1, 10.5));
        assertFalse(h.hasMovingHead(1, 11));
        h.expire(20);
        assertTrue(h.hasVisibleSamples(20));
        assertTrue(h.samples().stream().allMatch(s -> s.segment().x1() <= 22));
        h.expire(63); assertTrue(h.isEmpty());
    }

    @Test void fastObserversKeepOnlyConfirmedSmokeAfterImpactWithoutAStationaryHead() {
        var h = new TrailHistory();
        // A 40-block/tick shot arrives from behind the viewer at x=740 and stops at the wall x=748.
        h.accept(new TrailSegment(9, 19, 680, 81.6, 9.4, 720, 81.3, 9.4, 40, false), 100);
        h.accept(new TrailSegment(9, 20, 720, 81.3, 9.4, 748, 81.0, 9.4, 39, true), 101);
        assertTrue(h.hasMovingHead(9, 101.5));
        h.expire(102);
        assertFalse(h.hasMovingHead(9, 102), "A bullet head remained at the wall");
        assertEquals(2, h.visibleSamples(102).size(), "Observer wake vanished in the impact tick");
        var confirmed = h.samples();
        h.accept(new TrailSegment(9, 21, 748, 81, 9.4, 785, 80.5, 9.4, 39, false), 103);
        h.expire(116);
        assertEquals(confirmed, h.visibleSamples(116), "Impact grew or relocated the wake");
        assertTrue(TrailStyle.alpha(15, 39) > 0);
        assertTrue(h.visibleSamples(116).stream().allMatch(sample -> sample.segment().x1() <= 748));
        h.expire(153);
        assertFalse(h.hasVisibleSamples(153));
    }

    @Test void observerStopDoesNotRefreshSmokeOrReviveFromLatePackets() {
        var h = new TrailHistory();
        h.accept(segment(1, 1, false), 10);
        h.accept(new TrailSegment(1, 2, 2, 80, 0, 2, 80, 0, 0, true), 15);
        assertEquals(1, h.samples().size(), "A stop spawned fresh smoke");
        assertEquals(10, h.samples().get(0).start());
        assertFalse(h.hasMovingHead(1, 15));
        h.expire(25); assertTrue(h.hasVisibleSamples(25));
        h.expire(32); assertFalse(h.hasVisibleSamples(32));
        h.accept(segment(1, 3, false), 33);
        assertFalse(h.hasVisibleSamples(33));
        h.expire(90); assertTrue(h.isEmpty());
    }

    @Test void observerSmokeNeverBridgesUnloadedChunksAndIsHiddenWhenTheyUnload() {
        var h = new TrailHistory();
        var s = new TrailSegment(1, 1, 8, 80, 8, 56, 80, 8, 48, true);
        tacz.aos.trails.TrailAudience.clip(s, (x,z) -> x == 0 || x == 3).forEach(p -> h.accept(p, 10));
        h.expire(12);
        var samples = h.visibleSamples(12);
        assertEquals(2, samples.size());
        assertEquals(16, samples.get(0).segment().x1());
        assertEquals(48, samples.get(samples.size() - 1).segment().x0());
        assertFalse(h.hasMovingHead(1, 12));
        assertTrue(samples.stream().flatMap(p -> tacz.aos.trails.TrailAudience.clip(p.segment(), (x,z) -> false).stream()).findAny().isEmpty());
        h.clear(); assertTrue(h.isEmpty());
    }

    private static TrailSegment segment(int id, int step, boolean terminal) {
        return new TrailSegment(id, step, step, 80, 0, step + 1, 80, 0, 20, terminal);
    }
    @Test void trailHoldsDensityAndFadesAtOneSecondEvenAtHighSpeed() {
        for (double speed : new double[]{0, 5, 28, 200}) {
            assertEquals(20, TrailStyle.lifetime(speed));
            assertEquals(1, TrailStyle.alpha(4, speed));
            assertTrue(TrailStyle.alpha(8, speed) > 0.7);
            assertTrue(TrailStyle.alpha(12, speed) < TrailStyle.alpha(8, speed));
            assertTrue(TrailStyle.alpha(19, speed) > 0);
            assertEquals(0, TrailStyle.alpha(20, speed));
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
        assertTrue(h.samples().get(h.samples().size() - 1).segment().terminal());
    }
    @Test void missingUpdatesDoNotSynthesizeConnectingSegments() {
        var h = new TrailHistory(); h.accept(segment(1, 1, false), 0); h.accept(segment(1, 9, true), 9);
        assertEquals(2, h.samples().size());
        assertEquals(9, h.samples().get(h.samples().size() - 1).segment().x0());
        h.expire(61); assertTrue(h.isEmpty());
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
        h.expire(63); assertTrue(h.isEmpty());
    }
    @Test void rollingHistoryKeepsEntireConfiguredTail() {
        var h = new TrailHistory();
        for (int step = 1; step <= 52; step++) {
            h.accept(segment(1, step, false), step); h.expire(step);
        }
        int oldestVisible = 52 - TrailStyle.LIFETIME_TICKS + 1;
        assertTrue(h.samples().stream().anyMatch(s -> s.segment().step() == oldestVisible), "Visible sample was dropped before its configured lifetime");
        h.expire(104); assertTrue(h.isEmpty());
    }
    @Test void malformedAndTeleportSegmentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new TrailSegment(1, 1, Double.NaN, 0, 0, 0, 0, 0, 1, false));
        assertThrows(IllegalArgumentException.class, () -> new TrailSegment(1, 1, 0, 0, 0, 300, 0, 0, 1, false));
        assertThrows(IllegalArgumentException.class, () -> new TrailSegment(1, 0, 0, 0, 0, 1, 0, 0, 1, false));
    }
}
