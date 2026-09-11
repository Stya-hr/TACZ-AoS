package tacz.aos.scoping;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import tacz.aos.config.ServerConfig;
import tacz.aos.network.ScopeAimPayload;
import tacz.aos.compat.tacz.TaczBridge;
import java.util.*;
public final class ScopeAim {
    private record Sample(ServerLevel level,Vec3 eye,Vec3 direction,double distance,long tick){}
    private static final Map<UUID,Sample> samples=new HashMap<>();
    private ScopeAim(){}
    public static void receive(ServerPlayer p, ScopeAimPayload value) {
        long tick=p.serverLevel().getGameTime();
        var old=samples.get(p.getUUID());
        if(old!=null && old.level==p.serverLevel() && tick-old.tick<2) return;
        if(!p.serverLevel().dimension().location().equals(value.dimension()) || !TaczBridge.evaluate(p).eligible()) return;
        Vec3 eye=value.eye(),dir=value.direction(); double distance=value.distance();
        if(!Double.isFinite(eye.lengthSqr()+dir.lengthSqr()+distance) || eye.distanceToSqr(p.getEyePosition())>4
                || Math.abs(dir.lengthSqr()-1)>.01 || dir.dot(p.getViewVector(1))<.999
                || distance < -1 || distance>ServerConfig.current().maxDistance()) return;
        samples.put(p.getUUID(),new Sample(p.serverLevel(),eye,dir,distance,tick));
    }
    public static Vec3 target(ServerPlayer p) {
        var s=samples.get(p.getUUID());
        if(s==null || s.level!=p.serverLevel() || p.serverLevel().getGameTime()-s.tick>12 || s.distance<0
                || s.eye.distanceToSqr(p.getEyePosition())>4 || s.direction.dot(p.getViewVector(1))<.999) return null;
        Vec3 target=s.eye.add(s.direction.scale(s.distance));
        return target.distanceToSqr(p.getEyePosition())<=Math.pow(ServerConfig.current().maxDistance(),2)?target:null;
    }
    public static void prune(net.minecraft.server.MinecraftServer server){samples.keySet().removeIf(id->server.getPlayerList().getPlayer(id)==null);}
    public static void remove(UUID id){samples.remove(id);}
    public static void clear(){samples.clear();}
}
