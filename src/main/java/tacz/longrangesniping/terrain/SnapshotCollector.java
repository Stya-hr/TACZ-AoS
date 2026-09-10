package tacz.longrangesniping.terrain;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded, coalesced server-thread acquisition. An unfinished job retains no live chunk reference. */
public final class SnapshotCollector {
    public interface Source { Reader open(SectionKey key); }
    public interface Reader {
        boolean allAir();
        default CollisionCell uniform() { return allAir() ? CollisionCell.AIR : null; }
        CollisionCell read(int index);
    }
    public record Work(int cells, int sections) {}
    private final SnapshotStore store;
    private final LinkedHashMap<SectionKey, Long> pending = new LinkedHashMap<>();
    private SectionKey building;
    private SectionSnapshot.Builder builder;
    private int maxPending;
    private long version, dropped;

    public SnapshotCollector(SnapshotStore store, int maxPending) { this.store = store; resizeQueue(maxPending); }
    public void resizeQueue(int maximum) {
        if (maximum < 1) throw new IllegalArgumentException("Queue must have capacity");
        maxPending = maximum;
        var iterator = pending.keySet().iterator();
        while (pending.size() > maximum) {
            var key = iterator.next();
            if (key.equals(building)) resetBuilder();
            iterator.remove();
            dropped++;
        }
    }
    public void request(SectionKey key, long dueTick) {
        if (store.contains(key) || pending.containsKey(key)) return;
        if (pending.size() >= maxPending) { dropped++; return; }
        pending.put(key, dueTick);
    }
    /** Missing geometry requested by a current query shares the same queue and work budget. */
    public void prioritize(SectionKey key, long dueTick) {
        request(key, dueTick);
        if (pending.containsKey(key)) pending.putFirst(key, dueTick);
    }
    public void changed(SectionKey key, long dueTick) {
        store.invalidate(key);
        if (key.equals(building)) {
            resetBuilder();
            pending.remove(key);
        }
        // Keep the first due time during a burst; repeated edits cannot postpone refresh forever.
        request(key, dueTick);
    }
    public void forgetWork(SectionKey key) {
        pending.remove(key);
        if (key.equals(building)) resetBuilder();
    }
    public void removeDimension(String dimension) {
        pending.keySet().removeIf(key -> key.dimension().equals(dimension));
        if (building != null && building.dimension().equals(dimension)) resetBuilder();
        store.removeDimension(dimension);
    }

    public Work tick(Source source, long tick, int maxCells, int maxSections, long maxNanos) {
        long start = System.nanoTime();
        int cells = 0, sections = 0, attempts = 0;
        while (cells < maxCells && sections < maxSections && attempts < maxSections * 4
                && (maxNanos <= 0 || System.nanoTime() - start < maxNanos)) {
            if (building == null) {
                // Rotate not-yet-due work; inspect only a bounded number of entries per tick.
                if (pending.isEmpty()) break;
                var entry = pending.entrySet().iterator().next();
                SectionKey key = entry.getKey();
                long due = entry.getValue();
                if (due > tick) {
                    pending.remove(key);
                    pending.put(key, due);
                    attempts++;
                    continue;
                }
                building = key;
                builder = new SectionSnapshot.Builder();
            }
            Reader reader = source.open(building);
            attempts++;
            if (reader == null) { forgetWork(building); continue; }
            var uniform = builder.size() == 0 ? reader.uniform() : null;
            if (uniform != null) {
                store.publish(SectionSnapshot.uniform(building, ++version, tick, uniform));
                cells++; // Palette-proven uniform sections still consume work and section budgets.
                sections++;
                forgetWork(building);
                continue;
            }
            while (builder.size() < SectionSnapshot.CELL_COUNT && cells < maxCells) {
                builder.append(reader.read(builder.size()));
                cells++;
                if ((cells & 63) == 0 && maxNanos > 0 && System.nanoTime() - start >= maxNanos) break;
            }
            if (builder.size() == SectionSnapshot.CELL_COUNT) {
                store.publish(builder.finish(building, ++version, tick));
                sections++;
                forgetWork(building);
            } else break;
        }
        return new Work(cells, sections);
    }
    private void resetBuilder() { building = null; builder = null; }
    public void clear() { pending.clear(); resetBuilder(); store.clear(); dropped = 0; }
    public int pendingCount() { return pending.size(); }
    public long droppedRequests() { return dropped; }
}
