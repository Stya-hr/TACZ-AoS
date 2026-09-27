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
import static org.lwjgl.opengl.GL33C.*;
import tacz.aos.TaczArtOfSniping;
import tacz.aos.client.VegetationOcclusion;

/** Exercises the actual model, atlas alpha, depth-only write mask, and GL state in a real client. */
final class VegetationDepthClientTest {
    static void run() {
        var mc=Minecraft.getInstance();
        int draw=org.lwjgl.opengl.GL30C.glGetInteger(org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        int read=org.lwjgl.opengl.GL30C.glGetInteger(org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER_BINDING);
        int[] viewport=new int[4];GL11C.glGetIntegerv(GL11C.GL_VIEWPORT,viewport);
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
            TaczArtOfSniping.LOGGER.info("VEGETATION_DEPTH_CLIENT PASS: opaque={}, grass={}, youngWheat={}, matureWheat={}; color untouched; transient depth restored, closer entity depth retained",stone,grass,young,mature);
        } finally {
            RenderSystem.restoreProjectionMatrix();modelView.popPose();RenderSystem.applyModelViewMatrix();
            framebuffer.destroyBuffers();
            org.lwjgl.opengl.GL30C.glBindFramebuffer(org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER,draw);
            org.lwjgl.opengl.GL30C.glBindFramebuffer(org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER,read);
            GL11C.glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);
        }
    }
    /** Exercise the active shader framebuffer, including all attached material/light outputs. */
    static void world(PoseStack pose,Vec3 eye) {
        var mc=Minecraft.getInstance();
        int draw=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING),read=glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int pack=glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING),row=glGetInteger(GL_PACK_ROW_LENGTH);
        int skipRows=glGetInteger(GL_PACK_SKIP_ROWS),skipPixels=glGetInteger(GL_PACK_SKIP_PIXELS);
        int[] viewport=new int[4];glGetIntegerv(GL_VIEWPORT,viewport);
        glBindFramebuffer(GL_READ_FRAMEBUFFER,draw);int readBuffer=glGetInteger(GL_READ_BUFFER);
        try {
            glBindBuffer(GL_PIXEL_PACK_BUFFER,0);glPixelStorei(GL_PACK_ROW_LENGTH,0);glPixelStorei(GL_PACK_SKIP_ROWS,0);glPixelStorei(GL_PACK_SKIP_PIXELS,0);
            int x=viewport[0]+viewport[2]/2-16,y=viewport[1]+viewport[3]/2-16;
            var depths=BufferUtils.createFloatBuffer(32*32);glReadPixels(x,y,32,32,GL_DEPTH_COMPONENT,GL_FLOAT,depths);
            var colors=new java.util.LinkedHashMap<Integer,java.nio.FloatBuffer>();
            for(int i=0;i<glGetInteger(GL_MAX_COLOR_ATTACHMENTS);i++) {
                int attachment=GL_COLOR_ATTACHMENT0+i;
                if(glGetFramebufferAttachmentParameteri(GL_READ_FRAMEBUFFER,attachment,GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE)==GL_NONE)continue;
                glReadBuffer(attachment);var color=BufferUtils.createFloatBuffer(32*32*4);glReadPixels(x,y,32,32,GL_RGBA,GL_FLOAT,color);colors.put(attachment,color);
            }
            var pos=BlockPos.containing(eye.add(mc.player.getViewVector(1).scale(4)));
            try(var mask=VegetationOcclusion.renderBlocks(Map.of(pos,Blocks.GRASS.defaultBlockState()),pose,eye)) {}
            var after=BufferUtils.createFloatBuffer(32*32);glReadPixels(x,y,32,32,GL_DEPTH_COMPONENT,GL_FLOAT,after);
            if(!depths.equals(after))throw new AssertionError("Plant mask changed persistent world depth");
            for(var entry:colors.entrySet()) {
                glReadBuffer(entry.getKey());var color=BufferUtils.createFloatBuffer(32*32*4);glReadPixels(x,y,32,32,GL_RGBA,GL_FLOAT,color);
                if(!entry.getValue().equals(color))throw new AssertionError("Plant mask changed shader attachment "+entry.getKey());
            }
            TaczArtOfSniping.LOGGER.info("WORLD_PLANT_MASK PASS: scene depth and {} shader color/material buffers unchanged",colors.size());
        } finally {
            glBindFramebuffer(GL_READ_FRAMEBUFFER,draw);glReadBuffer(readBuffer);glBindFramebuffer(GL_READ_FRAMEBUFFER,read);
            glBindBuffer(GL_PIXEL_PACK_BUFFER,pack);glPixelStorei(GL_PACK_ROW_LENGTH,row);glPixelStorei(GL_PACK_SKIP_ROWS,skipRows);glPixelStorei(GL_PACK_SKIP_PIXELS,skipPixels);
        }
    }

    private static int sample(TextureTarget target,BlockState state) {
        target.setClearColor(0,0,0,1);target.clear(Minecraft.ON_OSX);target.bindWrite(true);
        var mask=VegetationOcclusion.renderBlocks(Map.of(BlockPos.ZERO,state),new PoseStack(),Vec3.ZERO);
        try {
        var depth=BufferUtils.createFloatBuffer(128*128);
        GL11C.glReadPixels(0,0,128,128,GL11C.GL_DEPTH_COMPONENT,GL11C.GL_FLOAT,depth);
        int masked=0;while(depth.hasRemaining())if(depth.get()<.999f)masked++;
        try(var pixels=Screenshot.takeScreenshot(target)) {
            for(int y=0;y<128;y++)for(int x=0;x<128;x++)if((pixels.getPixelRGBA(x,y)&0xffffff)!=0)
                throw new AssertionError("Invisible plant mask wrote visible color");
        }
        // Simulate a closer entity fragment after the invisible plant pass.
        GL11C.glEnable(GL11C.GL_SCISSOR_TEST);GL11C.glScissor(0,0,1,1);
        GL11C.glClearDepth(.125);GL11C.glClear(GL11C.GL_DEPTH_BUFFER_BIT);
        GL11C.glDisable(GL11C.GL_SCISSOR_TEST);GL11C.glClearDepth(1);
        mask.close();
        depth.clear();GL11C.glReadPixels(0,0,128,128,GL11C.GL_DEPTH_COMPONENT,GL11C.GL_FLOAT,depth);
        if(Math.abs(depth.get()-.125f)>.00001)throw new AssertionError("Plant cleanup removed a closer entity fragment");
        while(depth.hasRemaining())if(depth.get()<.999f)throw new AssertionError("Invisible plant depth leaked into world lighting");
        return masked;
        } finally {mask.close();}
    }
}
