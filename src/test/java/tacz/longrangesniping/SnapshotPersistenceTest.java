package tacz.longrangesniping;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tacz.longrangesniping.terrain.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotPersistenceTest {
    @TempDir Path root;
    private static final SectionKey KEY = new SectionKey("minecraft:overworld", -7, -4, 19);
    private static final SnapshotDiskCache.Limits LIMITS = new SnapshotDiskCache.Limits(16L << 20, 64, 16, 32L << 20, 128L << 20, 2048);
    private SnapshotDiskCache open(String fingerprint) { return new SnapshotDiskCache(root, fingerprint, LIMITS, ignored -> {}); }
    private static void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < until) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(5);
        }
        fail("Asynchronous operation did not finish");
    }
    private static SectionSnapshot geometry(SectionKey key) {
        var builder = new SectionSnapshot.Builder();
        for (int i = 0; i < 4096; i++) builder.append(switch (i % 5) {
            case 0 -> CollisionCell.AIR;
            case 1 -> CollisionCell.WATER;
            case 2 -> CollisionCell.FULL;
            case 3 -> CollisionCell.CONSERVATIVE;
            default -> new CollisionCell(CollisionCell.Kind.BOXES, List.of(new CollisionCell.Box(0, 0, 0, 1, 0.5, 1)), false);
        });
        return builder.finish(key, 42, 123);
    }
    @Test void geometryRoundTripChecksIdentityChecksumVersionAndTruncation() throws Exception {
        var snapshot = geometry(KEY); byte[] bytes = SnapshotCodec.encode(snapshot);
        var restored = SnapshotCodec.decode(bytes, KEY);
        assertEquals(snapshot.key(), restored.key()); assertEquals(42, restored.version());
        for (int i = 0; i < 4096; i++) assertEquals(snapshot.cell(i), restored.cell(i));
        assertEquals(snapshot.conservativeCells(), restored.conservativeCells());
        assertThrows(java.io.IOException.class, () -> SnapshotCodec.decode(bytes, new SectionKey("other:world", -7, -4, 19)));
        assertThrows(java.io.IOException.class, () -> SnapshotCodec.decode(Arrays.copyOf(bytes, bytes.length / 2), KEY));
        byte[] bad = bytes.clone(); bad[7]++;
        assertThrows(java.io.IOException.class, () -> SnapshotCodec.decode(bad, KEY));
        byte[] crc = bytes.clone(); crc[19] ^= 1;
        assertThrows(java.io.IOException.class, () -> SnapshotCodec.decode(crc, KEY));
    }
    @Test void orderlyRestartLoadsOnlyIndexThenRestoresOnDemand() throws Exception {
        var snapshot = geometry(KEY);
        try (var disk = open("same-format")) { await(() -> disk.status().ready()); disk.save(snapshot); }
        assertFalse(Files.exists(root.resolve("session.dirty")));
        try (var disk = open("same-format")) {
            await(() -> disk.status().ready()); assertTrue(disk.known(KEY));
            assertEquals(0, disk.status().reads()); assertTrue(disk.drain(8).isEmpty());
            disk.request(KEY); await(() -> disk.status().reads() == 1);
            List<SectionSnapshot> result = new ArrayList<>();
            await(() -> { result.addAll(disk.drain(8)); return !result.isEmpty(); });
            assertEquals(CollisionCell.FULL, result.getFirst().cell(2));
        }
    }
    @Test void missingAndCorruptFilesAffectOnlyTheirOwnAddresses() throws Exception {
        var second = new SectionKey("minecraft:the_nether", 2, 3, 4);
        Path firstFile;
        try (var disk = open("v")) { await(() -> disk.status().ready()); disk.save(geometry(KEY)); disk.save(SectionSnapshot.air(second, 1, 0)); firstFile = disk.path(KEY); }
        try (var disk = open("v")) {
            await(() -> disk.status().ready()); Files.write(firstFile, new byte[]{1, 2, 3});
            disk.request(KEY); await(() -> disk.status().corrupt() == 1);
            assertFalse(disk.known(KEY)); assertTrue(disk.known(second)); assertFalse(disk.status().failed());
            Files.delete(disk.path(second)); disk.request(second); await(() -> disk.status().corrupt() == 2);
            assertTrue(disk.drain(8).isEmpty());
        }
    }
    @Test void dirtyStopOrIncompatibleFormatCannotAuthorizeOldAir() throws Exception {
        try (var disk = open("v1")) { await(() -> disk.status().ready()); disk.save(SectionSnapshot.air(KEY, 1, 0)); }
        Files.writeString(root.resolve("session.dirty"), "simulated crash");
        try (var disk = open("v1")) { await(() -> disk.status().ready()); assertFalse(disk.known(KEY)); disk.save(geometry(KEY)); }
        try (var disk = open("v2")) { await(() -> disk.status().ready()); assertFalse(disk.known(KEY)); }
    }
    @Test void invalidationDiscardsCompletedReadsAndSurvivesRestart() throws Exception {
        try (var disk = open("v")) { await(() -> disk.status().ready()); disk.save(geometry(KEY)); }
        try (var disk = open("v")) {
            await(() -> disk.status().ready()); disk.request(KEY); await(() -> disk.status().reads() == 1);
            disk.invalidate(KEY); assertTrue(disk.drain(8).isEmpty()); assertFalse(disk.known(KEY));
        }
        try (var disk = open("v")) { await(() -> disk.status().ready()); assertFalse(disk.known(KEY)); }
    }
    @Test void queueAndDiskBudgetsStayBoundedUnderPressure() throws Exception {
        var small = new SnapshotDiskCache.Limits(16L << 20, 2, 2, 8L << 20, 128L << 20, 2048);
        try (var disk = new SnapshotDiskCache(root, "v", small, ignored -> {})) {
            await(() -> disk.status().ready());
            for (int i = 0; i < 20; i++) disk.save(SectionSnapshot.air(new SectionKey(KEY.dimension(), i, 0, 0), i, 0));
            assertTrue(disk.status().queued() <= 2); assertTrue(disk.status().queueBytes() <= 8L << 20);
        }
        try (var disk = new SnapshotDiskCache(root, "v", small, ignored -> {})) {
            await(() -> disk.status().ready()); assertTrue(disk.status().indexed() <= 2); assertTrue(disk.status().diskBytes() <= 16L << 20);
            for (int i = 0; i < 20; i++) disk.request(new SectionKey(KEY.dimension(), i, 0, 0));
            await(() -> disk.status().reads() >= 1);
            assertTrue(disk.status().queueBytes() <= 8L << 20); assertTrue(disk.status().queued() <= 1);
        }
    }
    @Test void fullWriteQueueRevokesOldAirAndAllowsLaterRetry() throws Exception {
        var blocker = new SectionKey(KEY.dimension(), 22, 0, 0);
        try (var disk = open("v")) {
            await(() -> disk.status().ready()); disk.save(SectionSnapshot.air(KEY, 1, 0)); disk.save(SectionSnapshot.air(blocker, 1, 0));
        }
        var small = new SnapshotDiskCache.Limits(16L << 20, 64, 1, 8L << 20, 128L << 20, 2048);
        try (var disk = new SnapshotDiskCache(root, "v", small, ignored -> {})) {
            await(() -> disk.status().ready()); disk.request(blocker); await(() -> disk.status().reads() == 1);
            disk.save(geometry(KEY)); assertFalse(disk.known(KEY)); assertTrue(disk.status().dropped() > 0);
            // The only queue slot is reserved until the main thread consumes the completed read.
            await(() -> !disk.drain(1).isEmpty());
            disk.retrySave(geometry(KEY)); await(() -> disk.known(KEY));
        }
        try (var disk = open("v")) {
            await(() -> disk.status().ready()); disk.request(KEY); await(() -> disk.status().reads() == 1);
            var restored = new ArrayList<SectionSnapshot>();
            await(() -> { restored.addAll(disk.drain(1)); return !restored.isEmpty(); });
            assertEquals(CollisionCell.FULL, restored.getFirst().cell(2));
        }
    }
    @Test void runningWorldWithoutCacheInvalidatesPriorSessionRecords() throws Exception {
        var world = root.resolve("level.dat"); Files.writeString(world, "initial world");
        try (var disk = new SnapshotDiskCache(root.resolve("cache"), "v", LIMITS, ignored -> {}, world)) {
            await(() -> disk.status().ready()); disk.save(geometry(KEY));
            Files.writeString(world, "world saved normally at shutdown");
        }
        try (var disk = new SnapshotDiskCache(root.resolve("cache"), "v", LIMITS, ignored -> {}, world)) {
            await(() -> disk.status().ready()); assertTrue(disk.known(KEY));
        }
        Files.writeString(world, "modified by a different session");
        try (var disk = new SnapshotDiskCache(root.resolve("cache"), "v", LIMITS, ignored -> {}, world)) {
            await(() -> disk.status().ready()); assertFalse(disk.known(KEY));
        }
    }
    @Test void leasesShareBudgetProtectFromEvictionAndNeverOverrideInvalidation() {
        var a = SectionSnapshot.air(KEY, 1, 0);
        var b = SectionSnapshot.air(new SectionKey(KEY.dimension(), 1, 0, 0), 1, 0);
        var c = SectionSnapshot.air(new SectionKey(KEY.dimension(), 2, 0, 0), 1, 0);
        var store = new SnapshotStore(a.estimatedBytes() * 2); store.pinLimits(a.estimatedBytes(), 1);
        store.publish(a);
        var first = store.pin(KEY); var second = store.pin(KEY);
        assertNotNull(first); assertNotNull(second); assertEquals(a.estimatedBytes(), store.pinnedBytes());
        store.publish(b); assertNull(store.pin(b.key())); store.publish(c);
        assertTrue(store.contains(KEY)); assertFalse(store.contains(b.key()));
        first.close(); assertEquals(1, store.pinnedSections());
        store.invalidate(KEY); assertFalse(second.valid()); assertEquals(0, store.pinnedSections());
        store.publish(a); var replacement = store.pin(KEY); second.close(); assertTrue(replacement.valid());
        replacement.close(); assertEquals(0, store.pinnedBytes());
    }
    @Test void snapshotLeaseReleasesOnCloseAndAdministrativeBudgetReductionWins() {
        var store = new SnapshotStore(10000); store.pinLimits(10000, 5); store.publish(SectionSnapshot.air(KEY, 1, 0));
        try (var lease = new SnapshotLease(store)) { lease.retain(KEY); assertEquals(1, store.pinnedSections()); }
        assertEquals(0, store.pinnedSections());
        var pin = store.pin(KEY); store.resize(0); assertFalse(pin.valid()); assertEquals(0, store.size());
    }
}
