package tacz.aos.test.demo;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.TickEvent;
import tacz.aos.compat.tacz.BallisticPreview;
import java.nio.file.*;
import java.util.Properties;
/** Two real network clients. Fixture arranges the range and aims, but uses production gun packets and damage. */
@EventBusSubscriber(modid="tacz_aos")
public final class PvpDemoServer {
 static final boolean ENABLED=System.getProperty("tacz_aos.demo","").equals("server");
 static final Path ROOT=Path.of("..").toAbsolutePath().normalize();
 static int stage,ticks,stageTick,fire;static long record,death;static float pitch,yaw=-90;static boolean done;
 @SubscribeEvent public static void tick(ServerTickEvent.Post event)throws Exception{
  if(!ENABLED)return;
  if(done){if(System.currentTimeMillis()-death>10000)net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer().halt(false);return;}
  var server=net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();var level=server.overworld();ticks++;
  if(ticks>20000){write(true);done=true;server.halt(false);return;}
  var shooter=server.getPlayerList().getPlayerByName("AoS_Shooter");
  var target=server.getPlayerList().getPlayerByName("AoS_Target");
  if(shooter==null||target==null)return;
  if(stage==0){
   level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,server);
   level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false,server);
   level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,server);
   level.setDayTime(6000);level.setWeatherParameters(100000,0,false,false);
   // Explore the full corridor before the shooting scene, exactly as required by the generated-chunk guard.
   for(int x=0;x<=48;x++)for(int z=-1;z<=1;z++)level.getChunk(x,z);
   for(int x=0;x<=765;x++)for(int z=0;z<16;z++)
    level.setBlock(new BlockPos(x,79,z),(x%100<2?Blocks.SMOOTH_STONE:Blocks.GRASS_BLOCK).defaultBlockState(),3);
   for(int y=80;y<96;y++)for(int z=-3;z<20;z++)level.setBlock(new BlockPos(748,y,z),Blocks.STONE_BRICKS.defaultBlockState(),3);
   shooter.setGameMode(GameType.SURVIVAL);target.setGameMode(GameType.SURVIVAL);
   shooter.teleportTo(level,736,80,12,-90,0);target.teleportTo(level,740,80,8,-80,12);
   var stack=GunItemBuilder.create().setId(new ResourceLocation("tacz:m107")).setAmmoCount(10)
    .putAttachment(AttachmentType.SCOPE,new ResourceLocation("tacz:scope_mk5hd")).build(level.registryAccess());
   var gun=IGun.getIGunOrNull(stack);gun.setBulletInBarrel(stack,true);
   var tag=gun.getAttachmentTag(stack,AttachmentType.SCOPE);tag.putInt("ZoomNumber",1);gun.setAttachmentTag(stack,AttachmentType.SCOPE,tag);
   shooter.setItemInHand(InteractionHand.MAIN_HAND,stack);
   IGunOperator.fromLivingEntity(shooter).initialData();IGunOperator.fromLivingEntity(shooter).draw(shooter::getMainHandItem);
   stage=1;stageTick=ticks;log("SETUP distance=732 blocks (800.52 yards), production M107/mk5hd, native damage");write(false);return;
  }
  if(stage==1&&ticks-stageTick>160){
   shooter.teleportTo(level,8,80,8,-90,0);
   var device=new BlockPos(8,80,10);var block=tacz.aos.block.ModContent.RANGEFINDER.get();
   level.setBlockAndUpdate(device,block.defaultBlockState());block.setPlacedBy(level,device,block.defaultBlockState(),shooter,new net.minecraft.world.item.ItemStack(block));
   stage=2;stageTick=ticks;
  }
  if(stage==2){
   IGunOperator.fromLivingEntity(shooter).aim(true);
   if(ticks-stageTick>40){
    // Solve mean live gun trajectory, including the configured wind. Random TACZ spread remains enabled.
    aim(shooter,740,81.0,record==0||System.currentTimeMillis()-record<6500?9.4:8.0);
   }
   shooter.setXRot(pitch);shooter.setYRot(yaw);shooter.setYHeadRot(yaw);
   long now=System.currentTimeMillis();
   if(record==0&&Files.exists(ROOT.resolve("shooter-ready.txt"))&&Files.exists(ROOT.resolve("target-ready.txt"))&&ticks-stageTick>100){
    record=now;log("RECORD_START");
   }
   if(record>0&&target.isAlive()){
    long elapsed=now-record;
    if(fire==0&&elapsed>4000||fire>=1&&elapsed>10000+(fire-1)*5000L){
     fire++;log("REQUEST_SHOT "+fire+" targetHealth="+target.getHealth()+" yaw="+yaw+" pitch="+pitch);
    }
   }
   if(!target.isAlive()&&death==0){death=now;log("KILLED shots="+fire+" health="+target.getHealth());}
   if(ticks%20==0)log("STATE stage="+stage+" health="+target.getHealth()+" region="+tacz.aos.scoping.ScopeActivity.INSTANCE.status()+" prediction="+tacz.aos.scoping.ScopeActivity.INSTANCE.predictionStatus(shooter.getUUID()));
   boolean end=death>0&&now-death>6000||record>0&&now-record>38000;
   write(end);
   if(end){if(death==0)death=now;done=true;log("END success="+(death>0));}
  }else write(false);
 }
 static void aim(ServerPlayer p,double x,double y,double z){
  float a=-90,b=0;
  for(int k=0;k<5;k++){
   p.setYRot(a);p.setXRot(b);var shot=BallisticPreview.read(p).orElse(null);if(shot==null)return;
   Vec3 pos=shot.start(),v=shot.velocity();
   for(int t=0;t<200;t++){
    var end=pos.add(v);
    if(end.x>=x){
     double f=(x-pos.x)/v.x;var hit=pos.add(v.scale(f));
     b+=(float)Math.toDegrees(Math.atan2(hit.y-y,x-p.getX()));
     a-=(float)Math.toDegrees(Math.atan2(hit.z-z,x-p.getX()));break;
    }
    pos=end;v=tacz.aos.wind.WindPhysics.afterDrag(v.scale((double)(1f-shot.friction())).add(0,-shot.gravity(),0),shot.crosswind(),shot.friction(),false);
   }
  }
  yaw=a;pitch=b;
 }
 static void write(boolean end)throws Exception{
  var props=new Properties();props.setProperty("stage",""+stage);props.setProperty("fire",""+fire);
  props.setProperty("pitch",""+pitch);props.setProperty("yaw",""+yaw);props.setProperty("record",""+(record>0));props.setProperty("end",""+end);
  Path temp=ROOT.resolve("control.tmp");try(var out=Files.newOutputStream(temp)){props.store(out,"Automated real multiplayer recording");}
  Files.move(temp,ROOT.resolve("control.properties"),StandardCopyOption.REPLACE_EXISTING);
 }
 static void log(String line)throws Exception{Files.writeString(ROOT.resolve("server-events.txt"),System.currentTimeMillis()+" "+line+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
}
