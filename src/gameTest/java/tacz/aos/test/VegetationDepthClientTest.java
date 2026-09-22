package tacz.aos.test;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11C;
import tacz.aos.TaczArtOfSniping;
import tacz.aos.client.VegetationOcclusion;

/** Exercises the actual model, atlas alpha, depth-only write mask, and GL state in a real client. */
final class VegetationDepthClientTest {
    static void run() {
        var mc=Minecraft.getInstance();
        var framebuffer=new TextureTarget(128,128,true,Minecraft.ON_OSX);
        var modelView=RenderSystem.getModelViewStack();
        RenderSystem.backupProjectionMatrix();modelView.pushPose();modelView.setIdentity();RenderSystem.applyModelViewMatrix();
        try {
            RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(-.25f,1.25f,-.25f,1.25f,-10,10),VertexSorting.ORTHOGRAPHIC_Z);
            int stone=sample(framebuffer,Blocks.STONE.defaultBlockState());
            int grass=sample(framebuffer,Blocks.GRASS.defaultBlockState());
            int young=sample(framebuffer,Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,0));
            int mature=sample(framebuffer,Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,7));
            if(stone<=0 || grass<=0 || grass>=stone || young<=0 || mature<=young || mature>=stone)
                throw new AssertionError("Texture alpha/depth incorrect: stone="+stone+", grass="+grass+", wheat="+young+"/"+mature);
            TaczArtOfSniping.LOGGER.info("VEGETATION_DEPTH_CLIENT PASS: opaque={}, grass={}, youngWheat={}, matureWheat={}; color untouched",stone,grass,young,mature);
        } finally {
            RenderSystem.restoreProjectionMatrix();modelView.popPose();RenderSystem.applyModelViewMatrix();
            framebuffer.destroyBuffers();mc.getMainRenderTarget().bindWrite(true);
        }
    }
    private static int sample(TextureTarget target,BlockState state) {
        target.setClearColor(0,0,0,1);target.clear(Minecraft.ON_OSX);target.bindWrite(true);
        VegetationOcclusion.renderBlocks(Map.of(BlockPos.ZERO,state),new PoseStack(),Vec3.ZERO);
        var depth=BufferUtils.createFloatBuffer(128*128);
        GL11C.glReadPixels(0,0,128,128,GL11C.GL_DEPTH_COMPONENT,GL11C.GL_FLOAT,depth);
        int masked=0;while(depth.hasRemaining())if(depth.get()<.999f)masked++;
        try(var pixels=Screenshot.takeScreenshot(target)) {
            for(int y=0;y<128;y++)for(int x=0;x<128;x++)if((pixels.getPixelRGBA(x,y)&0xffffff)!=0)
                throw new AssertionError("Invisible plant mask wrote visible color");
        }
        return masked;
    }
}
