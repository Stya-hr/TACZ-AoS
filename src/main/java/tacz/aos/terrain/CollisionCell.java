package tacz.aos.terrain;

import java.util.List;

/** Immutable block-local geometry. No BlockState, entity, world or VoxelShape references. */
public record CollisionCell(Kind kind, List<Box> boxes, boolean water) {
    public enum Kind { EMPTY, FULL, BOXES, CONSERVATIVE }
    public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        public Box {
            if (!(minX >= -1 && minY >= -1 && minZ >= -1 && maxX <= 2 && maxY <= 2 && maxZ <= 2
                    && minX < maxX && minY < maxY && minZ < maxZ)) throw new IllegalArgumentException("Unsupported collision bounds");
        }
    }
    public static final Box UNIT = new Box(0, 0, 0, 1, 1, 1);
    public static final CollisionCell AIR = new CollisionCell(Kind.EMPTY, List.of(), false);
    public static final CollisionCell WATER = new CollisionCell(Kind.EMPTY, List.of(), true);
    public static final CollisionCell FULL = new CollisionCell(Kind.FULL, List.of(UNIT), false);
    public static final CollisionCell CONSERVATIVE = new CollisionCell(Kind.CONSERVATIVE, List.of(UNIT), false);

    public CollisionCell {
        boxes = List.copyOf(boxes);
        if (kind == null || boxes.size() > 16 || ((kind == Kind.EMPTY) != boxes.isEmpty())) {
            throw new IllegalArgumentException("Invalid collision cell");
        }
    }

    public long estimatedBytes() { return 96L + 72L * boxes.size(); }
}
