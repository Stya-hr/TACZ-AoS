package tacz.longrangesniping.terrain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** The coverage index and payload have one lifetime: eviction immediately makes a section unknown. */
public final class SnapshotStore {
    public enum Coverage { UNKNOWN, KNOWN_AIR, KNOWN }
    private final Map<SectionKey, SectionSnapshot> snapshots = new LinkedHashMap<>(16, 0.75f, true);
    private long budgetBytes, usedBytes, evictions;

    public SnapshotStore(long budgetBytes) { resize(budgetBytes); }
    public Optional<SectionSnapshot> find(SectionKey key) { return Optional.ofNullable(snapshots.get(key)); }
    public Coverage coverage(SectionKey key) {
        var snapshot = snapshots.get(key);
        return snapshot == null ? Coverage.UNKNOWN : snapshot.allAir() ? Coverage.KNOWN_AIR : Coverage.KNOWN;
    }
    public boolean contains(SectionKey key) { return snapshots.containsKey(key); }
    public boolean publish(SectionSnapshot snapshot) {
        invalidate(snapshot.key());
        if (snapshot.estimatedBytes() > budgetBytes) return false;
        snapshots.put(snapshot.key(), snapshot);
        usedBytes += snapshot.estimatedBytes();
        evict();
        return true;
    }
    public void invalidate(SectionKey key) {
        var removed = snapshots.remove(key);
        if (removed != null) usedBytes -= removed.estimatedBytes();
    }
    public void removeDimension(String dimension) {
        var iterator = snapshots.values().iterator();
        while (iterator.hasNext()) {
            var snapshot = iterator.next();
            if (snapshot.key().dimension().equals(dimension)) {
                usedBytes -= snapshot.estimatedBytes();
                iterator.remove();
            }
        }
    }
    public void resize(long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("Negative cache budget");
        budgetBytes = bytes;
        evict();
    }
    private void evict() {
        var iterator = snapshots.values().iterator();
        while (usedBytes > budgetBytes && iterator.hasNext()) {
            usedBytes -= iterator.next().estimatedBytes();
            iterator.remove();
            evictions++;
        }
    }
    public void clear() { snapshots.clear(); usedBytes = 0; evictions = 0; }
    public int size() { return snapshots.size(); }
    public long usedBytes() { return usedBytes; }
    public long evictions() { return evictions; }
}
