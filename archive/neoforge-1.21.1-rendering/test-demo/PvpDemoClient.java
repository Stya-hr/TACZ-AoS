package tacz.aos.test.demo;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.*;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.client.event.*;
import java.nio.file.*;
import java.util.Properties;
@EventBusSubscriber(modid="tacz_aos",value=Dist.CLIENT)
public final class PvpDemoClient {
 static final String ROLE=System.getProperty("tacz_aos.demo","");
 static boolean started,finished;static int shots,ticks;static Properties control=new Properties();
 static final Path ROOT=Path.of("..").toAbsolutePath().normalize();
 @SubscribeEvent public static void tick(ClientTickEvent.Post event)throws Exception {
  if(!ROLE.equals("shooter")&&!ROLE.equals("target"))return;
  var mc=Minecraft.getInstance();
  if(++ticks>10000){DemoCapture.finish();mc.stop();return;}
  if(!started&&mc.isGameLoadFinished()&&mc.getOverlay()==null){
   started=true;mc.options.pauseOnLostFocus=false;mc.options.renderDistance().set(8);
   mc.options.enableVsync().set(false);mc.options.framerateLimit().set(60);mc.options.guiScale().set(2);
   mc.options.tutorialStep=net.minecraft.client.tutorial.TutorialSteps.NONE;
   mc.options.bobView().set(false);mc.options.fov().set(70);
   mc.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MUSIC).set(0.0);
   mc.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER).set(1.0);
   mc.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.PLAYERS).set(1.0);
   mc.options.showSubtitles().set(true);
   ConnectScreen.startConnecting(new TitleScreen(),mc,ServerAddress.parseString("127.0.0.1:25579"),
    new ServerData("AoS recording","127.0.0.1:25579",ServerData.Type.OTHER),false,null);
  }
  Path state=ROOT.resolve("control.properties");
  if(Files.exists(state))try(var in=Files.newInputStream(state)){var latest=new Properties();latest.load(in);control=latest;}catch(java.io.IOException ignored){}
  if(control.getProperty("end","false").equals("true")&&!finished){
   finished=true;DemoCapture.finish();mc.stop();return;
  }
  if(mc.player==null||mc.level==null||finished)return;
  boolean ready=control.getProperty("stage","0").equals("2");
  if(ROLE.equals("shooter")){
   float pitch=Float.parseFloat(control.getProperty("pitch","0"));
   float yaw=Float.parseFloat(control.getProperty("yaw","-90"));
   mc.player.setXRot(pitch);mc.player.setYRot(yaw);mc.player.setYHeadRot(yaw);
   com.tacz.guns.client.input.AimKey.AIM_KEY.setDown(ready);
   IGunOperator.fromLivingEntity(mc.player).aim(ready);
   IClientPlayerGunOperator.fromLocalPlayer(mc.player).aim(ready);
   if(ready && tacz.aos.client.ScopedRendering.active() && mc.level.players().stream()
      .anyMatch(p->p!=mc.player&&p.getName().getString().equals("AoS_Target")))
    Files.writeString(ROOT.resolve("shooter-ready.txt"),"ready");
   int fire=Integer.parseInt(control.getProperty("fire","0"));
   if(fire>shots){
    var result=IClientPlayerGunOperator.fromLocalPlayer(mc.player).shoot();
    log("TRIGGER "+fire+" "+result);shots=fire;
   }
  }else{
   mc.player.setYRot(-80);mc.player.setYHeadRot(-80);mc.player.setXRot(12);
   if(ready)Files.writeString(ROOT.resolve("target-ready.txt"),"ready");
  }
  if(control.getProperty("record","false").equals("true"))DemoCapture.start();
 }
 @SubscribeEvent public static void frame(RenderFrameEvent.Post event)throws Exception{
  if(started&&!finished)DemoCapture.frame();
 }
 @SubscribeEvent public static void sound(net.minecraftforge.client.event.sound.PlaySoundSourceEvent event){
  if(started&&DemoCapture.startNanos>0)log("SOUND "+event.getSound().getLocation());
 }
 @SubscribeEvent public static void chat(ClientChatReceivedEvent.System event){
  if(started)log("CHAT "+event.getMessage().getString());
 }
 static synchronized void log(String line){try{
  Files.writeString(ROOT.resolve(ROLE).resolve("events.txt"),System.currentTimeMillis()+" "+line+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
 }catch(Exception e){throw new RuntimeException(e);}}
}
