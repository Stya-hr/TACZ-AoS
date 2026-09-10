package tacz.longrangesniping.test;

import net.minecraft.SharedConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import tacz.longrangesniping.TaczLongRangeSniping;
import tacz.longrangesniping.config.ServerConfig;
import tacz.longrangesniping.terrain.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;

@GameTestHolder(TaczLongRangeSniping.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SavedTerrainRecoveryGameTests {
    private static CompoundTag terrain(ChunkPos pos) {
        var tag = new CompoundTag();
        tag.putInt("DataVersion",SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        tag.putString("Status","minecraft:full"); tag.putInt("xPos",pos.x); tag.putInt("zPos",pos.z);
        var states = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES);
        states.set(8,1,8,Blocks.STONE.defaultBlockState());
        var section = new CompoundTag(); section.putByte("Y",(byte)5);
        section.put("block_states",PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY,BlockState.CODEC,
                PalettedContainer.Strategy.SECTION_STATES,Blocks.AIR.defaultBlockState()).encodeStart(NbtOps.INSTANCE,states).getOrThrow());
        var list = new ListTag(); list.add(section); tag.put("sections",list);
        return tag;
    }

    @GameTest(template = "ballistics", batch = "saved_terrain", timeoutTicks = 400)
    public static void missingSnapshotsRecoverWithoutChunkLoadsAndRejectUnconfirmedTerrain(GameTestHelper h) {
        var level=h.getLevel(); var map=level.getChunkSource().chunkMap;
        var ready=new HashSet<SectionKey>(); var recovery=new SavedTerrainRecovery(ready::add);
        var writes=new ArrayList<CompletableFuture<Void>>();
        int base=24100;
        for (int i=0;i<8;i++) {
            if (i==6) continue; // No file at this address.
            var pos=new ChunkPos(base+i,base); var tag=terrain(pos);
            if(i==1) tag.putString("Status","minecraft:features");
            if(i==2) tag.getList("sections",Tag.TAG_COMPOUND).getCompound(0).getCompound("block_states")
                    .getList("palette",Tag.TAG_COMPOUND).getCompound(0).putString("Name","missing_mod:unknown_solid");
            if(i==3) tag.putInt("xPos",base+100);
            if(i==4) tag.putInt("DataVersion",1);
            if(i==5) tag.getList("sections",Tag.TAG_COMPOUND).getCompound(0).putString("block_states","invalid");
            if(i==7) tag.getList("sections",Tag.TAG_COMPOUND).getCompound(0).put("block_states",new CompoundTag());
            writes.add(map.write(pos,tag));
        }
        int[] stage={0};
        h.onEachTick(() -> {
            // GameTest's accelerated loop must let the native I/O worker make progress.
            try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            recovery.tick();
        });
        h.succeedWhen(() -> {
            h.assertTrue(writes.stream().allMatch(CompletableFuture::isDone),"Waiting for fixture saves");
            if(stage[0]==0) {
                for(int i=0;i<8;i++) recovery.request(level,new SectionKey(TerrainSnapshots.dimension(level),base+i,5,base));
                // Several missing sections in the same column coalesce into one scan.
                recovery.request(level,new SectionKey(TerrainSnapshots.dimension(level),base,6,base));
                stage[0]=1;
            }
            h.assertTrue(recovery.status().reads()==8 && recovery.status().queued()==0,"Waiting for coalesced asynchronous scans");
            var key=new SectionKey(TerrainSnapshots.dimension(level),base,5,base);
            var reader=recovery.open(key,level);
            h.assertTrue(reader!=null && reader.read(SectionKey.cellIndex(8,81,8)).kind()==CollisionCell.Kind.FULL,
                    "Saved solid block was lost");
            h.assertTrue(reader.read(0).equals(CollisionCell.AIR),"Saved air was lost");
            h.assertTrue(ready.contains(key) && recovery.known(level,base,base),"Missing completion notification/evidence");
            for(int i=1;i<8;i++) {
                h.assertTrue(recovery.open(new SectionKey(TerrainSnapshots.dimension(level),base+i,5,base),level)==null,
                        "Malformed, ungenerated or absent terrain became usable");
            }
            for(int i=0;i<8;i++) h.assertTrue(level.getChunkSource().getChunkNow(base+i,base)==null,"Recovery loaded a real chunk");
            long reads=recovery.status().reads();
            for(int i=0;i<100;i++) recovery.request(level,new SectionKey(TerrainSnapshots.dimension(level),base+6,5,base));
            recovery.tick();
            h.assertTrue(recovery.status().reads()==reads,"Missing region was repeatedly read without cooldown");
            recovery.invalidate(level,base,base);
            h.assertTrue(recovery.open(key,level)==null,"Live incarnation retained stale saved geometry");
            recovery.clear();
        });
    }

    @GameTest(template = "ballistics", batch = "saved_terrain")
    public static void savedReadsRespectQueueAndPerTickConcurrency(GameTestHelper h) {
        var recovery = new SavedTerrainRecovery(ignored -> {});
        var level = h.getLevel(); var config = ServerConfig.recovery();
        for (int i=0;i<1000;i++) recovery.request(level,new SectionKey(TerrainSnapshots.dimension(level),26000+i,5,26000));
        h.assertTrue(recovery.status().queued()<=32,"Unbounded scan queue");
        for (int i=0;i<19;i++) {
            recovery.tick();
            h.assertTrue(recovery.status().inFlight()<=config.maxConcurrentReads(),"Unbounded concurrent scans");
            h.assertTrue(recovery.status().reads()<=config.readsPerSecond(),"Scan rate exceeded within one second");
        }
        recovery.clear(); h.succeed();
    }

    @GameTest(template = "ballistics", batch = "saved_terrain", timeoutTicks = 200)
    public static void liveReloadInvalidatesAnInFlightSavedRead(GameTestHelper h) {
        var level=h.getLevel(); var pos=new ChunkPos(24099,24099);
        var saved=level.getChunkSource().chunkMap.write(pos,terrain(pos));
        var ready=new HashSet<SectionKey>(); var recovery=new SavedTerrainRecovery(ready::add);
        var key=new SectionKey(TerrainSnapshots.dimension(level),pos.x,5,pos.z);
        boolean[] started={false};
        h.onEachTick(() -> { try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
        h.succeedWhen(() -> {
            h.assertTrue(saved.isDone(),"Waiting for fixture save");
            if(!started[0]) {
                recovery.request(level,key); recovery.tick();
                h.assertTrue(recovery.status().inFlight()==1,"Read did not start");
                recovery.invalidate(level,pos.x,pos.z); started[0]=true;
            }
            recovery.tick();
            h.assertTrue(recovery.status().queued()==0,"Waiting for invalidated read completion");
            h.assertTrue(ready.isEmpty() && recovery.open(key,level)==null,"Invalidated I/O installed old geometry");
            recovery.clear();
        });
    }
}
