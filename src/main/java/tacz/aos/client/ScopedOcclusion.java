package tacz.aos.client;

import java.util.*;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import tacz.aos.TaczArtOfSniping;
import tacz.aos.config.ServerConfig;
import tacz.aos.network.*;
import tacz.aos.occlusion.OccluderBox;

/** Real server terrain, rendered as invisible depth masks before extra entities. No LOD visibility decisions. */
@EventBusSubscriber(modid=TaczArtOfSniping.MOD_ID,value=Dist.CLIENT)
public final class ScopedOcclusion {
    private static ClientLevel level;
    private static long received;
    private static final Map<Integer,ScopeOcclusionPayload.Target> targets=new HashMap<>();
    private ScopedOcclusion(){}
    public static int boxCount(int id){var t=targets.get(id);return t==null?0:t.boxes().size();}
    public static int texturedCount(int id){var t=targets.get(id);return t==null?0:t.textured().size();}
    public static void setup(FMLClientSetupEvent event){event.enqueueWork(()->ScopeNetworking.setOcclusionReceiver(ScopedOcclusion::receive));}
    private static void receive(ScopeOcclusionPayload payload) {
        var mc=Minecraft.getInstance();if(mc.level==null || !mc.level.dimension().location().equals(payload.dimension()))return;
        level=mc.level;received=level.getGameTime();targets.clear();for(var target:payload.targets())targets.put(target.id(),target);
    }
    public static void beginFrame() {
        var mc=Minecraft.getInstance();
        if(level!=mc.level || level==null || level.getGameTime()-received>40){targets.clear();level=mc.level;}
    }
    public static boolean ready(Entity entity) {
        if(!ServerConfig.occlusion().enabled())return true;
        var data=targets.get(entity.getId());
        // Missing/expired/incomplete is NOT an empty, verified clear corridor.
        return level==entity.level() && data!=null && data.complete() && data.uuid().equals(entity.getUUID());
    }
    public static void renderDepth(PoseStack pose,Vec3 eye) {
        if(!ServerConfig.occlusion().enabled())return;
        var mc=Minecraft.getInstance();if(mc.level==null)return;
        var active=new ArrayList<ScopeOcclusionPayload.Target>();
        var boxes=new LinkedHashSet<OccluderBox>();
        for(var target:targets.values()) {
            var entity=mc.level.getEntity(target.id());
            if(entity==null || !ready(entity))continue;
            active.add(target);
            for(var box:target.boxes())boxes.add(box.move(target.origin().getX(),target.origin().getY(),target.origin().getZ()));
        }
        SolidOcclusion.render(boxes,pose,eye);
        VegetationOcclusion.render(active,pose,eye);
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event){targets.clear();level=null;received=0;}
}
