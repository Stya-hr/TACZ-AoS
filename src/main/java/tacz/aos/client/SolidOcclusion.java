package tacz.aos.client;

import com.mojang.blaze3d.vertex.*;
import java.util.Collection;
import net.minecraft.client.renderer.*;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import tacz.aos.occlusion.OccluderBox;

/** At most six quads per server cover box; independent of entity model complexity and draw order. */
public final class SolidOcclusion extends RenderStateShard {
    private SolidOcclusion(){super("tacz_aos_solid_cover",()->{},()->{});}
    private static final RenderType DEPTH=RenderType.create("tacz_aos_solid_cover",DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS,65536,false,false,RenderType.CompositeState.builder()
                    .setShaderState(POSITION_COLOR_SHADER).setCullState(NO_CULL).setDepthTestState(LEQUAL_DEPTH_TEST)
                    .setWriteMaskState(DEPTH_WRITE).createCompositeState(false));
    private static final MultiBufferSource.BufferSource BUFFERS=MultiBufferSource.immediate(new BufferBuilder(65536));
    private static final int[][] FACES={{0,1,3,2},{4,6,7,5},{0,4,5,1},{2,3,7,6},{0,2,6,4},{1,5,7,3}};
    public static void render(Collection<OccluderBox> boxes,PoseStack pose,Vec3 eye) {
        if(boxes.isEmpty())return;
        var matrix=pose.last().pose();
        try {
            var sink=BUFFERS.getBuffer(DEPTH);
            for(var box:boxes)for(var face:FACES)for(int corner:face) {
                float x=(float)(((corner&1)==0?box.minX():box.maxX())-eye.x);
                float y=(float)(((corner&2)==0?box.minY():box.maxY())-eye.y);
                float z=(float)(((corner&4)==0?box.minZ():box.maxZ())-eye.z);
                sink.vertex(matrix,x,y,z).color(255,255,255,255).endVertex();
            }
        } finally {BUFFERS.endBatch();}
    }
}
