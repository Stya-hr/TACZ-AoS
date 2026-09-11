package tacz.longrangesniping.scoping;

import com.tacz.guns.block.entity.TargetBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import tacz.longrangesniping.config.ServerConfig;
import tacz.longrangesniping.network.ScopeBlockTargetsPayload;
import java.util.*;

/** Only inspect resident target cores. Shares the entity observation count, with a bounded block-entity candidate scan. */
public final class ScopeBlockTargets {
    private ScopeBlockTargets() {}
    public static List<ScopeBlockTargetsPayload.Target> select(ServerPlayer player,double zoom,int entityCount) {
        var settings=ServerConfig.current(); int limit=Math.min(ScopeBlockTargetsPayload.MAX_TARGETS,settings.maxTrackedEntities()-entityCount);
        if(limit<=0)return List.of();
        var level=player.serverLevel();var eye=player.getEyePosition();var direction=player.getViewVector(1);
        double angle=ScopeCone.padded(ScopeViews.INSTANCE.halfAngle(player,zoom),ScopeCone.RELEASE_PADDING);
        var seen=new HashSet<Long>();var found=new ArrayList<TargetBlockEntity>();int visited=0;
        outer: for(var core:ScopeActivity.INSTANCE.observationBounds(player)) {
            for(int x=(int)Math.floor(core.minX)>>4;x<=((int)Math.ceil(core.maxX)-1)>>4;x++)
                for(int z=(int)Math.floor(core.minZ)>>4;z<=((int)Math.ceil(core.maxZ)-1)>>4;z++) {
                    if(!seen.add(net.minecraft.world.level.ChunkPos.asLong(x,z)))continue;
                    var chunk=level.getChunkSource().getChunkNow(x,z);if(chunk==null)continue;
                    for(var be:chunk.getBlockEntities().values()) {
                        if(++visited>settings.scopeRendering().maxCandidates())break outer;
                        if(!(be instanceof TargetBlockEntity target) || target.isRemoved())continue;
                        var offset=Vec3.atBottomCenterOf(be.getBlockPos()).add(0,1,0).subtract(eye);
                        if(ScopeCone.containsAtAngle(offset.x,offset.y,offset.z,direction.x,direction.y,direction.z,1.3,settings.maxDistance(),angle))found.add(target);
                    }
                }
        }
        found.sort(Comparator.<TargetBlockEntity>comparingDouble(t->1-Vec3.atCenterOf(t.getBlockPos()).subtract(eye).normalize().dot(direction))
                .thenComparingLong(t->t.getBlockPos().asLong()));
        var result=new ArrayList<ScopeBlockTargetsPayload.Target>();long bytes=0;
        for(var target:found) {
            if(result.size()>=limit)break;
            BlockPos pos=target.getBlockPos();var tag=target.getUpdateTag(level.registryAccess());
            if(tag.sizeInBytes()>ScopeBlockTargetsPayload.MAX_TAG_BYTES || bytes+tag.sizeInBytes()>ScopeBlockTargetsPayload.MAX_TOTAL_TAG_BYTES)tag=new CompoundTag();
            if(bytes+tag.sizeInBytes()>ScopeBlockTargetsPayload.MAX_TOTAL_TAG_BYTES)break;
            bytes+=tag.sizeInBytes();
            int light=level.getBrightness(LightLayer.BLOCK,pos)<<4 | level.getBrightness(LightLayer.SKY,pos)<<20;
            result.add(new ScopeBlockTargetsPayload.Target(pos,target.getBlockState(),light,tag));
        }
        return List.copyOf(result);
    }
    public static void send(ServerPlayer player,double zoom,int entityCount) {
        PacketDistributor.sendToPlayer(player,new ScopeBlockTargetsPayload(player.level().dimension().location(),select(player,zoom,entityCount)));
    }
    public static void clear(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player,new ScopeBlockTargetsPayload(player.level().dimension().location(),List.of()));
    }
}
