package tacz.longrangesniping.terrain;

import java.util.LinkedHashMap;
import java.util.Map;

/** Shared pins count toward the existing memory budget. Invalidations always override a flight's lease. */
public final class SnapshotLease implements AutoCloseable {
    private final SnapshotStore store;
    private final Map<SectionKey, SnapshotStore.Pin> held = new LinkedHashMap<>();
    private boolean closed;
    public SnapshotLease(SnapshotStore store) { this.store = store; }
    public void retain(SectionKey key) {
        if (closed) return;
        var old = held.get(key);
        if (old != null && old.valid()) return;
        if (old != null) { old.close(); held.remove(key); }
        // Keep a bounded moving window, not the entire traveled corridor of every bullet.
        if (held.size() >= 256) {
            var iterator = held.values().iterator(); iterator.next().close(); iterator.remove();
        }
        var pin = store.pin(key);
        if (pin != null) held.put(key, pin);
    }
    @Override public void close() { closed = true; held.values().forEach(SnapshotStore.Pin::close); held.clear(); }
}
