package tacz.longrangesniping.terrain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Published only when all 4096 cells have been acquired. Palette indices are never exposed. */
public final class SectionSnapshot {
    public static final int CELL_COUNT = 4096;
    private final SectionKey key;
    private final long version, capturedTick;
    private final List<CollisionCell> palette;
    private final short[] cells;
    private final int conservativeCells;
    private final long estimatedBytes;

    private SectionSnapshot(SectionKey key, long version, long capturedTick, List<CollisionCell> palette,
                            short[] cells, int conservativeCells) {
        this.key = key;
        this.version = version;
        this.capturedTick = capturedTick;
        this.palette = List.copyOf(palette);
        this.cells = cells == null ? null : cells.clone();
        this.conservativeCells = conservativeCells;
        this.estimatedBytes = 256L + (cells == null ? 0 : 16L + 2L * CELL_COUNT)
                + palette.stream().mapToLong(CollisionCell::estimatedBytes).sum();
    }

    public SectionKey key() { return key; }
    public long version() { return version; }
    public long capturedTick() { return capturedTick; }
    public int conservativeCells() { return conservativeCells; }
    public boolean allAir() { return palette.size() == 1 && palette.getFirst().equals(CollisionCell.AIR); }
    public CollisionCell cell(int index) {
        if (index < 0 || index >= CELL_COUNT) throw new IndexOutOfBoundsException(index);
        return palette.get(cells == null ? 0 : Short.toUnsignedInt(cells[index]));
    }
    public long estimatedBytes() { return estimatedBytes; }
    List<CollisionCell> palette() { return palette; }
    int paletteIndex(int index) { return cells == null ? 0 : Short.toUnsignedInt(cells[index]); }

    public static SectionSnapshot air(SectionKey key, long version, long tick) {
        return uniform(key, version, tick, CollisionCell.AIR);
    }

    public static SectionSnapshot uniform(SectionKey key, long version, long tick, CollisionCell cell) {
        return new SectionSnapshot(key, version, tick, List.of(cell), null,
                cell.kind() == CollisionCell.Kind.CONSERVATIVE ? CELL_COUNT : 0);
    }

    public static final class Builder {
        private final short[] cells = new short[CELL_COUNT];
        private final Map<CollisionCell, Short> indices = new HashMap<>();
        private final List<CollisionCell> palette = new ArrayList<>();
        private int size, conservativeCells;
        public int size() { return size; }
        public void append(CollisionCell cell) {
            if (size == CELL_COUNT) throw new IllegalStateException("Section already complete");
            short index = indices.computeIfAbsent(cell, value -> {
                short next = (short) palette.size();
                palette.add(value);
                return next;
            });
            cells[size++] = index;
            if (cell.kind() == CollisionCell.Kind.CONSERVATIVE) conservativeCells++;
        }
        public SectionSnapshot finish(SectionKey key, long version, long tick) {
            if (size != CELL_COUNT) throw new IllegalStateException("Incomplete section cannot be published");
            return new SectionSnapshot(key, version, tick, palette, palette.size() == 1 ? null : cells, conservativeCells);
        }
    }
}
