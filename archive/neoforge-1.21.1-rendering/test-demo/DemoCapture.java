package tacz.aos.test.demo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.lwjgl.openal.SOFTLoopback;
import java.nio.*;
import java.nio.file.*;
import java.io.*;
import java.util.concurrent.locks.LockSupport;
/** Real-time framebuffer + independent stereo PCM capture; never synthesizes or replays game sounds. */
public final class DemoCapture {
 public static volatile long device, startNanos;
 private static volatile boolean audioRunning;
 private static Thread audioThread;
 private static Process encoder;
 private static OutputStream video;
 private static long frames;
 private static byte[] previous;
 public static final Path ROOT=Path.of("..").toAbsolutePath().normalize();
 public static final String ROLE=System.getProperty("tacz_aos.demo", "");
 private static final Path OUT=ROOT.resolve(ROLE);
 public static void startAudio(){
  audioRunning=true;
  audioThread=new Thread(()->{
   ByteBuffer samples=ByteBuffer.allocateDirect(4800*4).order(ByteOrder.nativeOrder());
   byte[] bytes=new byte[4800*4];long rendered=0, epoch=System.nanoTime(),written=0;
   try(var out=new BufferedOutputStream(Files.newOutputStream(OUT.resolve("audio.s16le")),192000)){
    while(audioRunning){
     long now=System.nanoTime();long due=(now-epoch)*48000/1000000000-rendered;
     if(due<=0){LockSupport.parkNanos(2000000);continue;}
     int count=(int)Math.min(due,4800);samples.clear();
     SOFTLoopback.alcRenderSamplesSOFT(device,samples,count); rendered+=count;
     long start=startNanos;
     if(start>0){
      long desired=Math.max(0,(now-start)*48000/1000000000);
      int keep=(int)Math.min(count,desired-written);
      if(keep>0){samples.position((count-keep)*4);samples.get(bytes,0,keep*4);out.write(bytes,0,keep*4);written+=keep;}
     }
    }
   }catch(Exception e){e.printStackTrace();}
  },"Isolated game audio capture");audioThread.setDaemon(true);audioThread.start();
 }
 public static void stopAudio(){audioRunning=false;if(audioThread!=null)try{audioThread.join(2000);}catch(InterruptedException ignored){}}
 public static void start() throws IOException {
  if(startNanos!=0)return;
  String exe=Files.readString(ROOT.resolve("ffmpeg-path.txt")).trim();
  var mc=Minecraft.getInstance();int w=mc.getMainRenderTarget().width,h=mc.getMainRenderTarget().height;
  encoder=new ProcessBuilder(exe,"-y","-loglevel","warning","-f","rawvideo","-pixel_format","rgba",
   "-video_size",w+"x"+h,"-framerate","30","-i","pipe:0","-an","-c:v","libx264","-preset","ultrafast",
   "-crf","19","-pix_fmt","yuv420p",OUT.resolve("silent.mp4").toString())
   .redirectError(OUT.resolve("encoder.log").toFile()).start();
  video=new BufferedOutputStream(encoder.getOutputStream(),w*h*4);
  startNanos=System.nanoTime();Files.writeString(OUT.resolve("capture-start.txt"),Long.toString(System.currentTimeMillis()));
 }
 public static void frame() throws IOException{
  if(startNanos==0||video==null)return;
  long want=(System.nanoTime()-startNanos)*30/1000000000+1;
  if(want<=frames)return;
  try(var pic=Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())){
   int[] pixels=pic.getPixelsRGBA();byte[] bytes=new byte[pixels.length*4];
   ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(pixels);
   while(frames<want){video.write(frames+1<want&&previous!=null?previous:bytes);frames++;}
   previous=bytes;
  }
 }
 public static void finish() throws Exception{
  if(video==null)return;frame();video.close();video=null;stopAudio();encoder.waitFor();
  Files.writeString(OUT.resolve("capture-done.txt"),"frames="+frames+" seconds="+frames/30.0);
 }
}
