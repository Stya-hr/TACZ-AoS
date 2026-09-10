package tacz.longrangesniping.terrain;

import java.io.*;
import java.util.ArrayList;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Bounded, self-contained geometry format. No registry IDs or Java object deserialization. */
public final class SnapshotCodec {
    public static final int FORMAT = 1, MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAGIC = 0x4c525343;
    private SnapshotCodec() {}

    public static byte[] encode(SectionSnapshot snapshot) throws IOException {
        var raw = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(raw)) {
            out.writeUTF(snapshot.key().dimension());
            out.writeInt(snapshot.key().x()); out.writeInt(snapshot.key().y()); out.writeInt(snapshot.key().z());
            out.writeLong(snapshot.version()); out.writeLong(snapshot.capturedTick());
            out.writeShort(snapshot.palette().size());
            for (var cell : snapshot.palette()) {
                out.writeByte(cell.kind().ordinal()); out.writeBoolean(cell.water()); out.writeByte(cell.boxes().size());
                for (var box : cell.boxes()) {
                    out.writeDouble(box.minX()); out.writeDouble(box.minY()); out.writeDouble(box.minZ());
                    out.writeDouble(box.maxX()); out.writeDouble(box.maxY()); out.writeDouble(box.maxZ());
                }
            }
            if (snapshot.palette().size() > 1)
                for (int i = 0; i < SectionSnapshot.CELL_COUNT; i++) out.writeShort(snapshot.paletteIndex(i));
        }
        byte[] bytes = raw.toByteArray();
        if (bytes.length > MAX_BYTES) throw new IOException("Oversized snapshot");
        var checksum = new CRC32(); checksum.update(bytes);
        var result = new ByteArrayOutputStream();
        var header = new DataOutputStream(result);
        header.writeInt(MAGIC); header.writeInt(FORMAT); header.writeInt(bytes.length); header.writeLong(checksum.getValue());
        try (var gzip = new GZIPOutputStream(result)) { gzip.write(bytes); }
        if (result.size() > MAX_BYTES) throw new IOException("Oversized compressed snapshot");
        return result.toByteArray();
    }

    public static SectionSnapshot decode(byte[] file, SectionKey expected) throws IOException {
        if (file.length > MAX_BYTES) throw new IOException("Oversized file");
        try (var header = new DataInputStream(new ByteArrayInputStream(file))) {
            if (header.readInt() != MAGIC || header.readInt() != FORMAT) throw new IOException("Unknown snapshot format");
            int length = header.readInt(); long crc = header.readLong();
            if (length < 0 || length > MAX_BYTES) throw new IOException("Invalid decoded length");
            byte[] raw;
            try (var gzip = new GZIPInputStream(header)) { raw = gzip.readNBytes(length + 1); }
            var checksum = new CRC32(); checksum.update(raw);
            if (raw.length != length || checksum.getValue() != crc) throw new IOException("Snapshot checksum mismatch");
            try (var in = new DataInputStream(new ByteArrayInputStream(raw))) {
                var key = new SectionKey(in.readUTF(), in.readInt(), in.readInt(), in.readInt());
                if (!key.equals(expected)) throw new IOException("Snapshot address mismatch");
                long version = in.readLong(), tick = in.readLong();
                int count = in.readUnsignedShort();
                if (count < 1 || count > SectionSnapshot.CELL_COUNT) throw new IOException("Invalid palette length");
                var palette = new ArrayList<CollisionCell>(count);
                for (int i = 0; i < count; i++) {
                    int kind = in.readUnsignedByte(); boolean water = in.readBoolean(); int boxes = in.readUnsignedByte();
                    if (kind >= CollisionCell.Kind.values().length || boxes > 16) throw new IOException("Invalid collision cell");
                    var geometry = new ArrayList<CollisionCell.Box>(boxes);
                    for (int b = 0; b < boxes; b++) geometry.add(new CollisionCell.Box(in.readDouble(), in.readDouble(), in.readDouble(),
                            in.readDouble(), in.readDouble(), in.readDouble()));
                    palette.add(new CollisionCell(CollisionCell.Kind.values()[kind], geometry, water));
                }
                var builder = new SectionSnapshot.Builder();
                for (int i = 0; i < SectionSnapshot.CELL_COUNT; i++) {
                    int index = count == 1 ? 0 : in.readUnsignedShort();
                    if (index >= count) throw new IOException("Invalid palette index");
                    builder.append(palette.get(index));
                }
                if (in.read() != -1) throw new IOException("Trailing snapshot data");
                return builder.finish(key, version, tick);
            }
        } catch (IllegalArgumentException exception) { throw new IOException("Invalid snapshot geometry", exception); }
    }
}
