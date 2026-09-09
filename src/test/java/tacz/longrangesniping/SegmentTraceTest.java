package tacz.longrangesniping;

import org.junit.jupiter.api.Test;
import tacz.longrangesniping.ballistics.SegmentTrace;
import tacz.longrangesniping.terrain.CollisionCell;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SegmentTraceTest {
    @Test void knownAirCrossesManySectionBoundaries() {
        var hit = trace((x, y, z) -> CollisionCell.AIR, 200, 0, 0);
        assertEquals(SegmentTrace.End.CLEAR, hit.end());
        assertEquals(1, hit.fraction());
    }
    @Test void unknownStopsBeforeEnteringItsRequiredHalo() {
        var hit = trace((x, y, z) -> x >= 16 ? null : CollisionCell.AIR, 64, 0, 0);
        assertEquals(SegmentTrace.End.UNKNOWN, hit.end());
        assertEquals((15 - 0.5) / 64, hit.fraction());
    }
    @Test void wallBeforeUnknownStillWins() {
        var hit = trace((x, y, z) -> x >= 16 ? null : x == 8 ? CollisionCell.FULL : CollisionCell.AIR, 64, 0, 0);
        assertEquals(SegmentTrace.End.BLOCK, hit.end());
        assertEquals(7.5 / 64, hit.fraction());
    }
    @Test void wallAfterUnknownCannotAuthorizeTheGap() {
        var hit = trace((x, y, z) -> x == 8 ? null : x == 16 ? CollisionCell.FULL : CollisionCell.AIR, 64, 0, 0);
        assertEquals(SegmentTrace.End.UNKNOWN, hit.end());
    }
    @Test void diagonalCannotSkipTouchedCellsOrTheirCoverage() {
        var hit = trace((x, y, z) -> x == 4 && z == 3 ? null : CollisionCell.AIR, 16, 0, 16);
        assertEquals(SegmentTrace.End.UNKNOWN, hit.end());
        assertTrue(hit.fraction() < 0.25);
    }
    @Test void extendedFenceAndThinSlabGeometryAreRespected() {
        var fence = new CollisionCell(CollisionCell.Kind.BOXES,
                List.of(new CollisionCell.Box(0.375, 0, 0.375, 0.625, 1.5, 0.625)), false);
        var hit = SegmentTrace.trace((x, y, z) -> x == 4 && y == 0 && z == 0 ? fence : CollisionCell.AIR,
                0.5, 1.25, 0.5, 8, 0, 0);
        assertEquals(SegmentTrace.End.BLOCK, hit.end());
        assertEquals((4.375 - 0.5) / 8, hit.fraction());
        var slab = new CollisionCell(CollisionCell.Kind.BOXES, List.of(new CollisionCell.Box(0, 0, 0, 1, 0.5, 1)), false);
        assertEquals(SegmentTrace.End.CLEAR, SegmentTrace.trace((x, y, z) -> y == 0 ? slab : CollisionCell.AIR,
                0.5, 0.75, 0.5, 8, 0, 0).end());
    }
    @Test void startingInsideSolidIsAnImmediateHit() {
        assertEquals(new SegmentTrace.Result(SegmentTrace.End.BLOCK, 0), trace((x, y, z) -> CollisionCell.FULL, 8, 0, 0));
    }
    @Test void conservativeGeometryBlocksItsNeighborhood() {
        var hit = trace((x, y, z) -> x == 4 ? CollisionCell.CONSERVATIVE : CollisionCell.AIR, 8, 0, 0);
        assertEquals(SegmentTrace.End.BLOCK, hit.end());
        assertEquals(2.5 / 8, hit.fraction());
    }
    @Test void negativeCoordinatesAndExactBoundaryMovingBackwards() {
        var hit = SegmentTrace.trace((x, y, z) -> x == -5 ? CollisionCell.FULL : CollisionCell.AIR,
                0, 0.5, 0.5, -8, 0, 0);
        assertEquals(SegmentTrace.End.BLOCK, hit.end());
        assertEquals(0.5, hit.fraction());
    }
    @Test void gravityDeflectionMustCheckNewVerticalCoverage() {
        assertEquals(SegmentTrace.End.CLEAR, trace((x, y, z) -> y < -2 ? null : CollisionCell.AIR, 8, 0, 0).end());
        assertEquals(SegmentTrace.End.UNKNOWN, trace((x, y, z) -> y < -2 ? null : CollisionCell.AIR, 8, -4, 0).end());
    }
    @Test void invalidAndOversizedSegmentsDoNoTerrainWork() {
        AtomicInteger reads = new AtomicInteger();
        SegmentTrace.Source source = (x, y, z) -> { reads.incrementAndGet(); return CollisionCell.AIR; };
        assertEquals(SegmentTrace.End.INVALID, trace(source, Double.NaN, 0, 0).end());
        assertEquals(SegmentTrace.End.INVALID, trace(source, 257, 0, 0).end());
        assertEquals(0, reads.get());
        assertEquals(SegmentTrace.End.CLEAR, trace(source, 0, 0, 0).end());
    }
    private static SegmentTrace.Result trace(SegmentTrace.Source source, double dx, double dy, double dz) {
        return SegmentTrace.trace(source, 0.5, 0.5, 0.5, dx, dy, dz);
    }
}
