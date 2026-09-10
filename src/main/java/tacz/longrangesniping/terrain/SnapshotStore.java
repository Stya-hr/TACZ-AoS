package tacz.longrangesniping.terrain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** The coverage index and payload have one lifetime: eviction immediately makes a section unknown. */
public final class SnapshotStore {
    public enum Coverage { UNKNOWN, KNOWN_AIR, KNOWN }
    private final Map<SectionKey, SectionSnapshot> snapshots = new LinkedHashMap<>(16, 0.75f, true);
    private long budgetBytes, usedBytes, evictions;
    private long pinBudget, pinnedBytes, nextPin;
    private int maxPins;
    private final Map<SectionKey, PinState> pins = new LinkedHashMap<>();
    private static final class PinState { final long id; int references = 1; PinState(long id) { this.id = id; } }
    private java.util.function.Consumer<SectionSnapshot> published = ignored -> {};
    private java.util.function.Consumer<SectionKey> invalidated = ignored -> {};
    public void listeners(java.util.function.Consumer<SectionSnapshot> published, java.util.function.Consumer<SectionKey> invalidated) {
        this.published = published; this.invalidated = invalidated;
    }
    public void pinLimits(long bytes, int count) {
        pinBudget = Math.min(budgetBytes, Math.max(0, bytes)); maxPins = Math.max(0, count);
        // Administrative budget reductions override leases immediately.
        while (!pins.isEmpty() && (pinnedBytes > pinBudget || pins.size() > maxPins)) {
            var key = pins.keySet().iterator().next(); pins.remove(key);
            var snapshot = snapshots.get(key); if (snapshot != null) pinnedBytes -= snapshot.estimatedBytes();
        }
    }
    public final class Pin implements AutoCloseable {
        private final SectionKey key; private final long id; private boolean closed;
        private Pin(SectionKey key, long id) { this.key = key; this.id = id; }
        public boolean valid() { var state = pins.get(key); return !closed && state != null && state.id == id; }
        @Override public void close() {
            if (closed) return; closed = true;
            var state = pins.get(key);
            if (state != null && state.id == id && --state.references == 0) {
                pins.remove(key); var snapshot = snapshots.get(key); if (snapshot != null) pinnedBytes -= snapshot.estimatedBytes();
            }
        }
    }
    public Pin pin(SectionKey key) {
        var snapshot = snapshots.get(key); if (snapshot == null) return null;
        var state = pins.get(key);
        if (state != null) state.references++;
        else {
            if (pins.size() >= maxPins || pinnedBytes + snapshot.estimatedBytes() > pinBudget) return null;
            state = new PinState(++nextPin); pins.put(key, state); pinnedBytes += snapshot.estimatedBytes();
        }
        return new Pin(key, state.id);
    }
    public long pinnedBytes() { return pinnedBytes; }
    public int pinnedSections() { return pins.size(); }

    public SnapshotStore(long budgetBytes) { resize(budgetBytes); }
    public Optional<SectionSnapshot> find(SectionKey key) { return Optional.ofNullable(snapshots.get(key)); }
    public Coverage coverage(SectionKey key) {
        var snapshot = snapshots.get(key);
        return snapshot == null ? Coverage.UNKNOWN : snapshot.allAir() ? Coverage.KNOWN_AIR : Coverage.KNOWN;
    }
    public boolean contains(SectionKey key) { return snapshots.containsKey(key); }
    public boolean publish(SectionSnapshot snapshot) {
        return install(snapshot, true);
    }
    public boolean restore(SectionSnapshot snapshot) { return !contains(snapshot.key()) && install(snapshot, false); }
    private boolean install(SectionSnapshot snapshot, boolean notify) {
        remove(snapshot.key());
        if (snapshot.estimatedBytes() > budgetBytes - pinnedBytes) return false;
        snapshots.put(snapshot.key(), snapshot);
        usedBytes += snapshot.estimatedBytes();
        evict();
        boolean retained = snapshots.containsKey(snapshot.key());
        if (retained && notify) published.accept(snapshot);
        return retained;
    }
    public void invalidate(SectionKey key) { remove(key); invalidated.accept(key); }
    private void remove(SectionKey key) {
        var existing = snapshots.get(key);
        if (pins.remove(key) != null && existing != null) pinnedBytes -= existing.estimatedBytes();
        var removed = snapshots.remove(key);
        if (removed != null) usedBytes -= removed.estimatedBytes();
    }
    public void removeDimension(String dimension) {
        var iterator = snapshots.values().iterator();
        while (iterator.hasNext()) {
            var snapshot = iterator.next();
            if (snapshot.key().dimension().equals(dimension)) {
                usedBytes -= snapshot.estimatedBytes();
                if (pins.remove(snapshot.key()) != null) pinnedBytes -= snapshot.estimatedBytes();
                iterator.remove();
            }
        }
    }
    public void resize(long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("Negative cache budget");
        budgetBytes = bytes;
        pinLimits(pinBudget, maxPins);
        evict();
    }
    private void evict() {
        var iterator = snapshots.values().iterator();
        while (usedBytes > budgetBytes && iterator.hasNext()) {
            var snapshot = iterator.next();
            if (pins.containsKey(snapshot.key())) continue;
            usedBytes -= snapshot.estimatedBytes();
            iterator.remove();
            evictions++;
        }
    }
    public void clear() { snapshots.clear(); pins.clear(); pinnedBytes = 0; usedBytes = 0; evictions = 0; }
    public int size() { return snapshots.size(); }
    public long usedBytes() { return usedBytes; }
    public long evictions() { return evictions; }
}
