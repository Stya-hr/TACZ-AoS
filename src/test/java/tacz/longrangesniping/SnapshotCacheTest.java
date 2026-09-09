package tacz.longrangesniping;

import org.junit.jupiter.api.Test;
import tacz.longrangesniping.terrain.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotCacheTest {
    private static final SectionKey KEY = new SectionKey("minecraft:overworld", -1, -4, 3);
    private static final class Source implements SnapshotCollector.Source, SnapshotCollector.Reader {
        boolean loaded = true, air;
        CollisionCell cell = CollisionCell.FULL;
        int reads;
        @Override public SnapshotCollector.Reader open(SectionKey key) { return loaded ? this : null; }
        @Override public boolean allAir() { return air; }
        @Override public CollisionCell read(int index) { reads++; return cell; }
    }

    @Test void incompleteDataNeverAppearsInTheIndex() {
        var store = new SnapshotStore(1_000_000);
        var collector = new SnapshotCollector(store, 10);
        var source = new Source();
        collector.request(KEY, 0);
        for (int tick = 0; tick < 63; tick++) {
            assertEquals(64, collector.tick(source, tick, 64, 1, 0).cells());
            assertEquals(SnapshotStore.Coverage.UNKNOWN, store.coverage(KEY));
            assertTrue(store.find(KEY).isEmpty());
        }
        collector.tick(source, 63, 64, 1, 0);
        assertEquals(4096, source.reads);
        assertEquals(SnapshotStore.Coverage.KNOWN, store.coverage(KEY));
        assertEquals(CollisionCell.FULL, store.find(KEY).orElseThrow().cell(4095));
        assertEquals(0, collector.pendingCount());
    }

    @Test void editingDuringAcquisitionDiscardsTheWholePartialGeneration() {
        var store = new SnapshotStore(1_000_000);
        var collector = new SnapshotCollector(store, 10);
        var source = new Source();
        collector.request(KEY, 0);
        collector.tick(source, 0, 2048, 1, 0);
        source.cell = CollisionCell.WATER;
        collector.changed(KEY, 5);
        collector.tick(source, 4, 4096, 1, 0);
        assertEquals(SnapshotStore.Coverage.UNKNOWN, store.coverage(KEY));
        collector.tick(source, 5, 4096, 1, 0);
        var snapshot = store.find(KEY).orElseThrow();
        for (int i = 0; i < 4096; i++) assertEquals(CollisionCell.WATER, snapshot.cell(i));
        assertFalse(snapshot.allAir());
    }

    @Test void completedGenerationIsInvalidatedImmediatelyAndVersionsAdvance() {
        var store = new SnapshotStore(1_000_000);
        var collector = new SnapshotCollector(store, 10);
        var source = new Source();
        source.air = true;
        collector.request(KEY, 0);
        collector.tick(source, 0, 4096, 1, 0);
        var old = store.find(KEY).orElseThrow();
        assertEquals(SnapshotStore.Coverage.KNOWN_AIR, store.coverage(KEY));
        collector.changed(KEY, 5);
        collector.changed(KEY, 100);
        assertEquals(1, collector.pendingCount());
        assertEquals(SnapshotStore.Coverage.UNKNOWN, store.coverage(KEY));
        source.air = false;
        collector.tick(source, 5, 4096, 1, 0);
        assertTrue(store.find(KEY).orElseThrow().version() > old.version());
        assertTrue(old.allAir(), "Previously returned immutable snapshots must not change");
    }

    @Test void unloadingRetainsOnlyCompleteSnapshotsAndNeverReadsMissingChunks() {
        var store = new SnapshotStore(1_000_000);
        var collector = new SnapshotCollector(store, 10);
        var source = new Source();
        collector.request(KEY, 0);
        collector.tick(source, 0, 4096, 1, 0);
        source.loaded = false;
        collector.forgetWork(KEY);
        assertEquals(SnapshotStore.Coverage.KNOWN, store.coverage(KEY));
        SectionKey missing = new SectionKey(KEY.dimension(), 200, 0, 200);
        collector.request(missing, 0);
        int readsBefore = source.reads;
        collector.tick(source, 1, 4096, 1, 0);
        assertEquals(readsBefore, source.reads);
        assertEquals(SnapshotStore.Coverage.UNKNOWN, store.coverage(missing));
    }

    @Test void evictionAndResizeRemoveCoverageAlongWithPayload() {
        var first = SectionSnapshot.air(KEY, 1, 0);
        var second = SectionSnapshot.air(new SectionKey(KEY.dimension(), 2, 0, 0), 2, 0);
        var third = SectionSnapshot.air(new SectionKey(KEY.dimension(), 3, 0, 0), 3, 0);
        var store = new SnapshotStore(first.estimatedBytes() * 2);
        store.publish(first);
        store.publish(second);
        store.find(KEY); // First is recently used.
        store.publish(third);
        assertTrue(store.contains(KEY));
        assertEquals(SnapshotStore.Coverage.UNKNOWN, store.coverage(second.key()));
        assertEquals(1, store.evictions());
        store.resize(0);
        assertEquals(0, store.usedBytes());
        assertEquals(SnapshotStore.Coverage.UNKNOWN, store.coverage(KEY));
        assertFalse(store.publish(first));
    }

    @Test void dimensionsAndNegativeCoordinatesStaySeparate() {
        assertEquals(new SectionKey("a:b", -1, -5, -2), SectionKey.atBlock("a:b", -1, -65, -17));
        assertEquals(4095, SectionKey.cellIndex(-1, -65, -17));
        var store = new SnapshotStore(1_000_000);
        var other = new SectionKey("minecraft:the_nether", KEY.x(), KEY.y(), KEY.z());
        store.publish(SectionSnapshot.air(KEY, 1, 0));
        assertEquals(SnapshotStore.Coverage.UNKNOWN, store.coverage(other));
        store.publish(SectionSnapshot.air(other, 2, 0));
        store.removeDimension(KEY.dimension());
        assertTrue(store.contains(other));
        assertFalse(store.contains(KEY));
    }

    @Test void queueAndAllAirFastPathStillObeyTheirBudgets() {
        var store = new SnapshotStore(1_000_000);
        var collector = new SnapshotCollector(store, 4);
        var source = new Source();
        source.air = true;
        for (int i = 0; i < 20; i++) collector.request(new SectionKey("a:b", i, 0, 0), 0);
        assertEquals(4, collector.pendingCount());
        assertEquals(16, collector.droppedRequests());
        var work = collector.tick(source, 0, 10, 2, 0);
        assertEquals(2, work.sections());
        assertEquals(2, work.cells());
        assertEquals(0, source.reads);
        assertEquals(2, collector.pendingCount());
        collector.resizeQueue(1);
        assertEquals(1, collector.pendingCount());
    }

    @Test void builderRejectsIncompletePublicationAndGeometryIsImmutable() {
        var builder = new SectionSnapshot.Builder();
        builder.append(CollisionCell.AIR);
        assertThrows(IllegalStateException.class, () -> builder.finish(KEY, 1, 0));
        var boxes = new java.util.ArrayList<>(List.of(new CollisionCell.Box(0, 0, 0, 1, 0.5, 1)));
        var slab = new CollisionCell(CollisionCell.Kind.BOXES, boxes, false);
        boxes.clear();
        assertEquals(1, slab.boxes().size());
        assertThrows(UnsupportedOperationException.class, () -> slab.boxes().clear());
        assertThrows(IllegalArgumentException.class, () -> new CollisionCell.Box(0, 0, 0, Double.NaN, 1, 1));
    }
}
