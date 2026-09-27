package tacz.aos.client;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import tacz.aos.TaczArtOfSniping;
import tacz.aos.config.ServerConfig;
import tacz.aos.network.*;

/** Complete shared section versions; incomplete initial coverage never exposes an extra entity. */
@EventBusSubscriber(modid=TaczArtOfSniping.MOD_ID,value=Dist.CLIENT)
public final class ScopedOcclusion {
    private static ClientLevel level;
    private static long received;
    private static final Map<Integer,ScopeOcclusionPayload.Target> targets=new HashMap<>();
    private static final Map<Long,Long> required=new HashMap<>();
    private static final Map<Long,OcclusionMesh.Section> sections=new HashMap<>();
    private static final Map<Integer,List<Long>> frameCoverage=new HashMap<>();
    private ScopedOcclusion(){}
    public static void setup(FMLClientSetupEvent event){event.enqueueWork(()->ScopeNetworking.setOcclusionReceiver(ScopedOcclusion::receive));}
    private static void reset(){targets.clear();required.clear();sections.clear();frameCoverage.clear();}
    private static void receive(ScopeOcclusionPayload payload) {
        var mc=Minecraft.getInstance();if(mc.level==null || !mc.level.dimension().location().equals(payload.dimension()))return;
        if(level!=mc.level)reset();
        level=mc.level;received=level.getGameTime();
        required.clear();for(var region:payload.regions())required.put(region.key(),region.revision());
        sections.keySet().retainAll(required.keySet());
        for(var update:payload.updates())sections.put(update.key(),new OcclusionMesh.Section(update));
        targets.clear();for(var target:payload.targets())targets.put(target.id(),target);
    }
    public static void beginFrame() {
        var mc=Minecraft.getInstance();
        if(level!=mc.level||level==null){reset();level=mc.level;}
        // Pause drawing on a stale roster, but keep acknowledged section versions. The next heartbeat
        // references those versions without resending unchanged geometry (TCP already delivered it).
        else if(level.getGameTime()-received>40)targets.clear();
    }
    private static boolean complete(List<Long> keys) {
        return keys.stream().allMatch(key->{
            var section=sections.get(key);return section!=null&&section.revision()==required.getOrDefault(key,0L)&&section.revision()>0;
        });
    }
    public static boolean ready(Entity entity) {
        if(!ServerConfig.occlusion().enabled())return true;
        var target=targets.get(entity.getId());
        return level==entity.level()&&target!=null&&target.admitted()&&target.uuid().equals(entity.getUUID())&&frameCoverage.containsKey(entity.getId());
    }
    public static OcclusionMesh.Mask render(com.mojang.blaze3d.vertex.PoseStack pose,net.minecraft.world.phys.Vec3 eye,net.minecraft.client.renderer.culling.Frustum frustum,float partial) {
        frameCoverage.clear();var needed=new HashSet<Long>();
        if(ServerConfig.occlusion().enabled()&&level!=null)for(var target:targets.values()) {
            var entity=level.getEntity(target.id());
            if(entity==null||!target.admitted()||!entity.getUUID().equals(target.uuid())||!frustum.isVisible(entity.getBoundingBox()))continue;
            // Remote players can turn, sprint or teleport independently of the last server mask roster.
            // Check the actual interpolated draw position every frame, not predicted motion or only the packet's old box.
            var position=new net.minecraft.world.phys.Vec3(
                    net.minecraft.util.Mth.lerp(partial,entity.xOld,entity.getX()),
                    net.minecraft.util.Mth.lerp(partial,entity.yOld,entity.getY()),
                    net.minecraft.util.Mth.lerp(partial,entity.zOld,entity.getZ()));
            var bounds=entity.getBoundingBox().move(position.subtract(entity.position()));
            var keys=tacz.aos.occlusion.OcclusionCoverage.required(eye,bounds,ServerConfig.occlusion().radius(),level.getMinSection(),level.getMaxSection());
            if(complete(keys)){frameCoverage.put(entity.getId(),keys);needed.addAll(keys);}
        }
        // Known preparation/retained cover must not disappear when the entity crosses a section edge.
        // It can add occlusion, but missing optional sections never gate the entity's actual coverage.
        if(!frameCoverage.isEmpty())for(var entry:sections.entrySet())
            if(entry.getValue().revision()>0&&entry.getValue().revision()==required.getOrDefault(entry.getKey(),0L))needed.add(entry.getKey());
        return OcclusionMesh.render(needed.stream().map(sections::get).toList(),pose,eye);
    }
    public static int solidCount(int id){var target=targets.get(id);if(target==null)return 0;return target.required().stream().map(sections::get).filter(Objects::nonNull).mapToInt(s->s.mask().solid().stream().mapToInt(Long::bitCount).sum()).sum();}
    public static int texturedCount(int id){var target=targets.get(id);if(target==null)return 0;return target.required().stream().map(sections::get).filter(Objects::nonNull).mapToInt(s->s.mask().plants().size()).sum();}
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event){reset();level=null;received=0;OcclusionMesh.clear();}
}
