package tacz.aos.test;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import tacz.aos.TaczArtOfSniping;
import tacz.aos.scoping.DepthProjection;

@GameTestHolder(TaczArtOfSniping.MOD_ID) @PrefixGameTestTemplate(false)
public final class DepthProjectionGameTests {
    @GameTest(template="empty")
    public static void lodProjectionAndHardwareDepthAreIndependent(GameTestHelper h) {
        // Voxy uses a 0..1 projection even when Iris leaves the window mapping at -1..1.
        for (boolean matrixZeroToOne : new boolean[]{false,true}) for (boolean hardwareZeroToOne : new boolean[]{false,true}) {
            var matrix = new Matrix4f().perspective((float)Math.toRadians(3), 16f/9, 8, 48000, matrixZeroToOne)
                    .rotateY((float)Math.PI/2);
            var camera = new Vec3(-30000, 81.62, 40000);
            for (float distance : new float[]{32,900,2048}) {
                var relative = new Vector4f(distance, 1, .3f, 1);
                var clip = new Vector4f(relative).mul(matrix); clip.div(clip.w);
                float depth = hardwareZeroToOne ? clip.z : clip.z*.5f+.5f;
                Vec3 point = DepthProjection.unproject(depth,1,clip.x,clip.y,hardwareZeroToOne,new Matrix4f(matrix).invert(),camera);
                h.assertTrue(point != null && point.distanceTo(camera.add(relative.x,relative.y,relative.z)) < .15,
                        "LOD depth unprojection changed range: "+distance+" / "+matrixZeroToOne+" / "+hardwareZeroToOne);
            }
        }
        for (float depth : new float[]{0,1,Float.NaN,Float.POSITIVE_INFINITY})
            h.assertTrue(DepthProjection.unproject(depth,1,0,0,false,new Matrix4f(),Vec3.ZERO)==null,"Invalid/sky depth became a near target");
        h.succeed();
    }
}
