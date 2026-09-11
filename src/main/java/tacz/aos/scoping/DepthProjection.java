package tacz.aos.scoping;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

/** Decode using the matrix and hardware depth convention captured with the producing draw pass. */
public final class DepthProjection {
    private DepthProjection() {}
    public static Vec3 unproject(float depth, float clear, float x, float y, boolean zeroToOne,
                                 Matrix4fc inverse, Vec3 camera) {
        if (!Float.isFinite(depth) || depth <= 0 || depth >= 1 || depth == clear) return null;
        var p = new Vector4f(x, y, zeroToOne ? depth : depth * 2 - 1, 1).mul(inverse);
        if (!Float.isFinite(p.w) || Math.abs(p.w) < 1e-10) return null;
        var point = camera.add(p.x / p.w, p.y / p.w, p.z / p.w);
        return Double.isFinite(point.lengthSqr()) ? point : null;
    }
}
