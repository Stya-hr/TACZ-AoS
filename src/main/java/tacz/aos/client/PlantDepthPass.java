package tacz.aos.client;

import java.nio.FloatBuffer;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33C.*;

/** A transient alpha mask. Invisible plants never become terrain for shader post-processing.
 * Raw, isolated programs deliberately bypass Oculus material substitution and MRT lighting outputs. */
final class PlantDepthPass implements AutoCloseable {
    private static int plantProgram, restoreProgram, vao, vbo;
    private static final int[] textures = new int[3];
    private static int capacityWidth, capacityHeight;
    private final int width, height;
    private final int framebuffer;
    private final int[] viewport = new int[4];
    private boolean finished;

    private static final String VERTEX = """
            #version 330 core
            layout(location=0) in vec3 position;
            layout(location=1) in vec2 uv;
            uniform mat4 matrix;
            out vec2 texCoord;
            void main() { gl_Position=matrix*vec4(position,1.0); texCoord=uv; }
            """;
    private static final String ALPHA = """
            #version 330 core
            uniform sampler2D atlas;
            in vec2 texCoord;
            void main() { if(texture(atlas,texCoord).a<0.1) discard; }
            """;
    private static final String SCREEN = """
            #version 330 core
            void main() {
                vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);
                gl_Position=vec4(p*2.0-1.0,0.0,1.0);
            }
            """;
    private static final String RESTORE = """
            #version 330 core
            uniform sampler2D originalDepth;
            uniform sampler2D plantDepth;
            uniform sampler2D finalDepth;
            uniform ivec2 origin;
            void main() {
                ivec2 p=ivec2(gl_FragCoord.xy)-origin;
                float original=texelFetch(originalDepth,p,0).r;
                float plant=texelFetch(plantDepth,p,0).r;
                float finalValue=texelFetch(finalDepth,p,0).r;
                // Keep closer entity fragments. Remove only unchanged invisible plant fragments.
                if(finalValue!=plant || plant==original) discard;
                gl_FragDepth=original;
            }
            """;

    PlantDepthPass(FloatBuffer vertices, Matrix4f matrix) {
        framebuffer=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        glGetIntegerv(GL_VIEWPORT,viewport);
        // Copy only the on-screen plant footprint, not three full-resolution scene buffers.
        double minX=1,minY=1,maxX=-1,maxY=-1;
        var clip=new org.joml.Vector4f();
        for(int i=vertices.position();i<vertices.limit();i+=5) {
            matrix.transform(clip.set(vertices.get(i),vertices.get(i+1),vertices.get(i+2),1));
            if(clip.w<=0){minX=minY=-1;maxX=maxY=1;break;}
            double x=clip.x/clip.w,y=clip.y/clip.w;
            minX=Math.min(minX,x);minY=Math.min(minY,y);maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);
        }
        int left=bounded((int)Math.floor((minX+1)*.5*viewport[2])-2,viewport[2]-1);
        int bottom=bounded((int)Math.floor((minY+1)*.5*viewport[3])-2,viewport[3]-1);
        int right=Math.max(left+1,bounded((int)Math.ceil((maxX+1)*.5*viewport[2])+2,viewport[2]));
        int top=Math.max(bottom+1,bounded((int)Math.ceil((maxY+1)*.5*viewport[3])+2,viewport[3]));
        viewport[0]+=left;viewport[1]+=bottom;width=right-left;height=top-bottom;
        try(var state=new State()) {
            if(plantProgram==0) {
                plantProgram=program(VERTEX,ALPHA);restoreProgram=program(SCREEN,RESTORE);
                vao=glGenVertexArrays();vbo=glGenBuffers();
            }
            if(capacityWidth<width || capacityHeight<height) {
                capacityWidth=Math.max(capacityWidth,(width+63)/64*64);
                capacityHeight=Math.max(capacityHeight,(height+63)/64*64);
                glActiveTexture(GL_TEXTURE0);glBindBuffer(GL_PIXEL_UNPACK_BUFFER,0);
                for(int i=0;i<3;i++) {
                    if(textures[i]==0)textures[i]=glGenTextures();
                    glBindTexture(GL_TEXTURE_2D,textures[i]);
                    glTexImage2D(GL_TEXTURE_2D,0,GL_DEPTH_COMPONENT32F,capacityWidth,capacityHeight,0,GL_DEPTH_COMPONENT,GL_FLOAT,(java.nio.ByteBuffer)null);
                    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);
                    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
                    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_COMPARE_MODE,GL_NONE);
                }
            }
            copy(0);
        }
    }

    void draw(FloatBuffer vertices, Matrix4f matrix, int atlas) {
        try(var state=new State()) {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER,framebuffer);
            glUseProgram(plantProgram);glBindVertexArray(vao);glBindBuffer(GL_ARRAY_BUFFER,vbo);
            glBufferData(GL_ARRAY_BUFFER,vertices,GL_STREAM_DRAW);
            glEnableVertexAttribArray(0);glVertexAttribPointer(0,3,GL_FLOAT,false,20,0L);
            glEnableVertexAttribArray(1);glVertexAttribPointer(1,2,GL_FLOAT,false,20,12L);
            glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,atlas);glBindSampler(0,0);
            glUniform1i(glGetUniformLocation(plantProgram,"atlas"),0);
            glUniformMatrix4fv(glGetUniformLocation(plantProgram,"matrix"),false,matrix.get(new float[16]));
            glColorMask(false,false,false,false);glDepthMask(true);glEnable(GL_DEPTH_TEST);glDepthFunc(GL_LEQUAL);
            glDisable(GL_BLEND);glDisable(GL_CULL_FACE);glDisable(GL_STENCIL_TEST);
            glDrawArrays(GL_TRIANGLES,0,vertices.remaining()/5);
            copy(1);
        }
    }

    @Override public void close() {
        if(finished)return;finished=true;
        try(var state=new State()) {
            copy(2);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER,framebuffer);
            glViewport(viewport[0],viewport[1],width,height);
            glUseProgram(restoreProgram);glBindVertexArray(vao);
            for(int i=0;i<3;i++) {
                glActiveTexture(GL_TEXTURE0+i);glBindTexture(GL_TEXTURE_2D,textures[i]);glBindSampler(i,0);
            }
            glUniform1i(glGetUniformLocation(restoreProgram,"originalDepth"),0);
            glUniform1i(glGetUniformLocation(restoreProgram,"plantDepth"),1);
            glUniform1i(glGetUniformLocation(restoreProgram,"finalDepth"),2);
            glUniform2i(glGetUniformLocation(restoreProgram,"origin"),viewport[0],viewport[1]);
            glColorMask(false,false,false,false);glDepthMask(true);glEnable(GL_DEPTH_TEST);glDepthFunc(GL_ALWAYS);
            glDisable(GL_BLEND);glDisable(GL_CULL_FACE);glDisable(GL_STENCIL_TEST);glDisable(GL_SCISSOR_TEST);
            glDrawArrays(GL_TRIANGLES,0,3);
        }
    }

    private static int bounded(int value,int max){return Math.max(0,Math.min(value,max));}

    private void copy(int index) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER,framebuffer);
        glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,textures[index]);
        glCopyTexSubImage2D(GL_TEXTURE_2D,0,0,0,viewport[0],viewport[1],width,height);
    }

    private static int program(String vertex,String fragment) {
        int vs=shader(GL_VERTEX_SHADER,vertex),fs=shader(GL_FRAGMENT_SHADER,fragment),p=glCreateProgram();
        glAttachShader(p,vs);glAttachShader(p,fs);glLinkProgram(p);glDeleteShader(vs);glDeleteShader(fs);
        if(glGetProgrami(p,GL_LINK_STATUS)==GL_FALSE)throw new IllegalStateException(glGetProgramInfoLog(p));
        return p;
    }
    private static int shader(int type,String source) {
        int shader=glCreateShader(type);glShaderSource(shader,source);glCompileShader(shader);
        if(glGetShaderi(shader,GL_COMPILE_STATUS)==GL_FALSE)throw new IllegalStateException(glGetShaderInfoLog(shader));
        return shader;
    }
    static void destroy() {
        for(int i=0;i<textures.length;i++){if(textures[i]!=0)glDeleteTextures(textures[i]);textures[i]=0;}
        if(plantProgram!=0)glDeleteProgram(plantProgram);if(restoreProgram!=0)glDeleteProgram(restoreProgram);
        if(vao!=0)glDeleteVertexArrays(vao);if(vbo!=0)glDeleteBuffers(vbo);
        capacityWidth=capacityHeight=plantProgram=restoreProgram=vao=vbo=0;
    }

    /** Restore actual GL state without changing Minecraft/Oculus caches. */
    private static final class State implements AutoCloseable {
        final int draw=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING),read=glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        final int program=glGetInteger(GL_CURRENT_PROGRAM),array=glGetInteger(GL_VERTEX_ARRAY_BINDING),buffer=glGetInteger(GL_ARRAY_BUFFER_BINDING);
        final int unpack=glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING),active=glGetInteger(GL_ACTIVE_TEXTURE),depthFunc=glGetInteger(GL_DEPTH_FUNC);
        final boolean depth=glIsEnabled(GL_DEPTH_TEST),blend=glIsEnabled(GL_BLEND),cull=glIsEnabled(GL_CULL_FACE),stencil=glIsEnabled(GL_STENCIL_TEST),scissor=glIsEnabled(GL_SCISSOR_TEST);
        final boolean depthWrite=glGetBoolean(GL_DEPTH_WRITEMASK);
        final int[] viewport=new int[4],binding=new int[3],samplers=new int[3];
        final java.nio.ByteBuffer colors=BufferUtils.createByteBuffer(4*glGetInteger(GL_MAX_DRAW_BUFFERS));
        State() {
            glGetIntegerv(GL_VIEWPORT,viewport);
            for(int i=0;i<colors.capacity()/4;i++){colors.position(i*4);glGetBooleani_v(GL_COLOR_WRITEMASK,i,colors);}colors.clear();
            for(int i=0;i<3;i++){glActiveTexture(GL_TEXTURE0+i);binding[i]=glGetInteger(GL_TEXTURE_BINDING_2D);samplers[i]=glGetInteger(GL_SAMPLER_BINDING);}
        }
        public void close() {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER,draw);glBindFramebuffer(GL_READ_FRAMEBUFFER,read);
            glUseProgram(program);glBindVertexArray(array);glBindBuffer(GL_ARRAY_BUFFER,buffer);glBindBuffer(GL_PIXEL_UNPACK_BUFFER,unpack);
            for(int i=0;i<3;i++){glActiveTexture(GL_TEXTURE0+i);glBindTexture(GL_TEXTURE_2D,binding[i]);glBindSampler(i,samplers[i]);}glActiveTexture(active);
            for(int i=0;i<colors.capacity()/4;i++)glColorMaski(i,colors.get(i*4)!=0,colors.get(i*4+1)!=0,colors.get(i*4+2)!=0,colors.get(i*4+3)!=0);
            glDepthMask(depthWrite);glDepthFunc(depthFunc);
            enable(GL_DEPTH_TEST,depth);enable(GL_BLEND,blend);enable(GL_CULL_FACE,cull);enable(GL_STENCIL_TEST,stencil);enable(GL_SCISSOR_TEST,scissor);
            glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);
        }
        private static void enable(int flag,boolean enabled){if(enabled)glEnable(flag);else glDisable(flag);}
    }
}
