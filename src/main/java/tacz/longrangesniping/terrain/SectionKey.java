package tacz.longrangesniping.terrain;

/** A cache address includes the dimension; Y is a section coordinate, not an array index. */
public record SectionKey(String dimension, int x, int y, int z) {
    public static SectionKey atBlock(String dimension, int x, int y, int z) {
        return new SectionKey(dimension, x >> 4, y >> 4, z >> 4);
    }

    public static int cellIndex(int x, int y, int z) { return (x & 15) | ((z & 15) << 4) | ((y & 15) << 8); }
}
