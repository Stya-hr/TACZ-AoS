package tacz.longrangesniping.test;

import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import tacz.longrangesniping.TaczLongRangeSniping;
import tacz.longrangesniping.client.ScopedRendering;
import tacz.longrangesniping.scoping.ScopeActivity;
import tacz.longrangesniping.terrain.*;

import java.util.*;

/** Opt-in real client: default production budgets, 2-chunk view, saved animal 880 blocks away. */
@EventBusSubscriber(modid = TaczLongRangeSniping.MOD_ID, value = Dist.CLIENT)
public final class LongRangeClientTest {
    private static final boolean ENABLED = Boolean.getBoolean("taczlrs.longRangeTest");
    private static final TicketType<ChunkPos> EXPLORE = TicketType.create("long_range_fixture", Comparator.comparingLong(ChunkPos::toLong));
    private static final List<ChunkPos> visited = new ArrayList<>();
    private static volatile int stage, animalId = -1;
    private static volatile String failure;
    private static volatile boolean movement;
    private static volatile boolean readyToFire, hitConfirmed;
    private static com.tacz.guns.entity.EntityKineticBullet shotBullet;
    private static Cow shotTarget;
    private static volatile boolean shotStarted;
    private static int ticks, stageTick, frames, draws;
    private static volatile boolean missedInitialSpawn;
    @SubscribeEvent public static void missingSpawn(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        // Reproduce an observer whose first pairing was not installed locally. Holding aim must repair it.
        if (ENABLED && stage >= 3 && event.getLevel().isClientSide() && uuid != null && event.getEntity().getUUID().equals(uuid) && !missedInitialSpawn) {
            missedInitialSpawn = true; event.setCanceled(true);
            TaczLongRangeSniping.LOGGER.info("LONG_RANGE_CLIENT deliberately skipped first remote pairing");
        }
    }
    private static boolean started;
    private static Cow original;
    private static UUID uuid;
    private static double initialX;
    private static int clientVisitTick;

    @SubscribeEvent public static void client(ClientTickEvent.Post event) {
        if (!ENABLED) return;
        var mc = Minecraft.getInstance();
        if (failure != null) { finish(false, failure); return; }
        if (!started && mc.isGameLoadFinished() && mc.getOverlay() == null) {
            started = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.renderDistance().set(2); mc.options.simulationDistance().set(5);
            mc.createWorldOpenFlows().createFreshLevel("range-"+System.currentTimeMillis(),
                    new LevelSettings("Long range regression", GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                            new GameRules(), WorldDataConfiguration.DEFAULT), new WorldOptions(12345,false,false),
                    r -> r.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), mc.screen);
        }
        if (mc.level == null || mc.player == null) return;
        if (stage>=3 && frames%40==0) TaczLongRangeSniping.LOGGER.info("LONG_RANGE_CLIENT visual active={}, targets={}, entity={}, draws={}",ScopedRendering.active(),ScopedRendering.targetCount(),mc.level.getEntity(animalId),draws);
        if (++frames > 2400) { finish(false,"Client timed out, stage="+stage); return; }
        mc.player.setYRot(-90); mc.player.setYHeadRot(-90); mc.player.setXRot(0);
        com.tacz.guns.client.input.AimKey.AIM_KEY.setDown(stage >= 3 && !shotStarted);
        IGunOperator.fromLivingEntity(mc.player).aim(stage >= 3 && !shotStarted);
        if(stage>=3 && !net.neoforged.fml.ModList.get().isLoaded("voxy") && ticks-stageTick>60) {
            if(ScopedRendering.targetCount()!=0 || tacz.longrangesniping.client.VisibleTargetReporter.lastDistance>=0)
                finish(false,"No-Voxy client selected terrain beyond two-chunk view");
            else finish(true,"No-Voxy sky does not select or load remote terrain");
            return;
        }
        if(shotStarted && hitConfirmed) {
            finish(true,"Cold generation index, Voxy depth, real animal AI/render and remote damage after unscoping verified");return;
        }
        if (stage >= 3 && animalId >= 0 && ScopedRendering.drewExtra(animalId)) {
            var animal = mc.level.getEntity(animalId);
            if (animal == null || !animal.getUUID().equals(uuid) || ScopedRendering.hasRealClientChunk(animal)) {
                finish(false,"Wrong animal or remote terrain sent to 2-chunk client"); return;
            }
            if (++draws >= 40 && movement && missedInitialSpawn && tacz.longrangesniping.client.ScopeRangeHud.displayedDistance() > 800) {
                if(!readyToFire) Screenshot.grab(mc.gameDirectory,"long-range-entity.png",mc.getMainRenderTarget(),ignored -> {});
                readyToFire = true;
                if (!hitConfirmed) return;
                Screenshot.grab(mc.gameDirectory,"long-range-entity.png",mc.getMainRenderTarget(),ignored -> {});
                TaczLongRangeSniping.LOGGER.info("LONG_RANGE_CLIENT PASS distance={}, view={}, uuid={}, extraDraws={}",
                        animal.distanceTo(mc.player),mc.options.renderDistance().get(),uuid,ScopedRendering.extraDraws());
                finish(true,"Visible-depth targeting, saved animal AI/render and target-only projectile damage verified");
            }
        }
    }

    @SubscribeEvent public static void server(ServerTickEvent.Post event) {
        if (!ENABLED || failure != null || event.getServer().getPlayerList().getPlayers().isEmpty()) return;
        var player = event.getServer().getPlayerList().getPlayers().getFirst();
        var level = player.serverLevel(); ticks++;
        try {
            if (ticks == 30) {
                level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,event.getServer());
                level.setDayTime(6000);
                player.connection.teleport(8,80,8,-90,0);
                player.getAbilities().flying = true; player.onUpdateAbilities();
                var stack = GunItemBuilder.create().setId(ResourceLocation.parse("tacz:ai_awp"))
                        .putAttachment(AttachmentType.SCOPE,ResourceLocation.parse("tacz:scope_mk5hd")).build(level.registryAccess());
                var gun = IGun.getIGunOrNull(stack);
                var tag = gun.getAttachmentTag(stack,AttachmentType.SCOPE).copy(); tag.putInt("ZoomNumber",1);
                gun.setAttachmentTag(stack,AttachmentType.SCOPE,tag); player.setItemInHand(InteractionHand.MAIN_HAND,stack);
                var columns = new LinkedHashSet<ChunkPos>();
                for (int x=0;x<=56;x++) for (int z=-1;z<=1;z++) columns.add(new ChunkPos(x,z));
                for (int x=52;x<=60;x++) for (int z=-4;z<=4;z++) columns.add(new ChunkPos(x,z));
                visited.addAll(columns);
                // Fixture-only exploration holds terrain long enough for default-budget background collection.
                for (var pos : visited) { level.getChunkSource().addRegionTicket(EXPLORE,pos,0,pos); level.getChunk(pos.x,pos.z); }
                for (int x=875;x<=910;x++) for (int z=0;z<16;z++) level.setBlock(new BlockPos(x,79,z),Blocks.STONE.defaultBlockState(),2);
                for (int y=60;y<=84;y++) for (int z=0;z<16;z++) level.setBlock(new BlockPos(896,y,z),Blocks.STONE.defaultBlockState(),2);
                original = EntityType.COW.create(level); original.setPos(888,80,8); original.setPersistenceRequired();
                uuid=original.getUUID(); level.addFreshEntity(original);
                stage=1; stageTick=ticks;
            }
            if (stage==1 && ticks-stageTick>=100) {
                if(clientVisitTick==0) {
                    clientVisitTick=ticks; player.connection.teleport(888,85,8,-90,0);
                    return;
                }
                if(ticks-clientVisitTick<80) return;
                player.connection.teleport(8,80,8,-90,0);
                for (var pos:visited) level.getChunkSource().removeRegionTicket(EXPLORE,pos,0,pos);
                stage=2; stageTick=ticks;
                TaczLongRangeSniping.LOGGER.info("LONG_RANGE_CLIENT exploration saved");
            }
            if (stage==2 && ticks-stageTick>100 && level.getChunkSource().getChunkNow(55,0)==null
                    && level.getChunkSource().getChunkNow(56,0)==null && level.getEntity(uuid)==null) {
                if (level.getChunkSource().getChunkNow(30,0)!=null) throw new AssertionError("Corridor still resident");
                GeneratedChunks.INSTANCE.clear(); // Cold metadata cache must recover without terrain snapshots.
                var pos=new BlockPos(8,80,10); var device=tacz.longrangesniping.block.ModContent.RANGEFINDER.get();
                level.setBlockAndUpdate(pos.below(),Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(pos,device.defaultBlockState());
                device.setPlacedBy(level,pos,device.defaultBlockState(),player,new net.minecraft.world.item.ItemStack(device));
                stage=3; stageTick=ticks;
                TaczLongRangeSniping.LOGGER.info("LONG_RANGE_CLIENT target and corridor unloaded; enabling scope");
            }
            if (stage==3) {
                var restored=level.getEntity(uuid);
                if (restored instanceof Cow cow && ScopeActivity.INSTANCE.active(cow) && level.isPositionEntityTicking(cow.blockPosition())) {
                    if (cow==original) throw new AssertionError("Animal was never deserialized");
                    animalId=cow.getId();
                    if (initialX==0 && cow.onGround() && cow.getNavigation().moveTo(892,80,8,1)) initialX=cow.getX();
                    movement |= initialX!=0 && Math.abs(cow.getX()-initialX)>.1;
                }
                if (level.getChunkSource().getChunkNow(30,0)!=null) throw new AssertionError("Scoping loaded the flight corridor");
                if (readyToFire && shotBullet == null) {
                    var shot=tacz.longrangesniping.compat.tacz.BallisticPreview.read(player).orElseThrow();
                    var result=tacz.longrangesniping.scoping.ImpactPrediction.predict(shot,
                            new tacz.longrangesniping.ballistics.TargetCollisionSource(level,ScopeActivity.INSTANCE.observationBounds(player)),List.of(),1024,600,65536,8192,Long.MAX_VALUE);
                    if(result.end()!=tacz.longrangesniping.scoping.ImpactPrediction.End.BLOCK) throw new AssertionError("No known ballistic endpoint");
                    // A stationary hit fixture immediately before the predicted wall, using the real gravity/drag/wind.
                    shotTarget=EntityType.COW.create(level); shotTarget.setNoAi(true); shotTarget.setNoGravity(true);
                    shotTarget.setPos(result.position().add(-1,-.5,0));
                    shotTarget.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(1000);
                    shotTarget.setHealth(1000); level.addFreshEntity(shotTarget);
                    var stack=player.getMainHandItem(); var gun=IGun.getIGunOrNull(stack);
                    var data=com.tacz.guns.api.TimelessAPI.getCommonGunIndex(gun.getGunId(stack)).orElseThrow().getGunData();
                    shotBullet=new com.tacz.guns.entity.EntityKineticBullet(level,player,stack,data.getAmmoId(),gun.getGunId(stack),false,data,data.getBulletData());
                    shotBullet.setPos(shot.start()); var velocity=shot.velocity();
                    shotBullet.shoot(velocity.x,velocity.y,velocity.z,(float)velocity.length(),0);
                    if(level.addFreshEntity(shotBullet)) throw new AssertionError("Scoped shot was not taken over");
                    shotStarted=true;
                }
                if(shotBullet!=null) {
                    hitConfirmed=shotTarget.getHealth()<1000;
                    if(shotBullet.isRemoved() && !hitConfirmed) throw new AssertionError("Remote shot missed: "+tacz.longrangesniping.ballistics.ScopedBallistics.INSTANCE.status(level));
                }
                if (ticks%40==0) TaczLongRangeSniping.LOGGER.info("LONG_RANGE_CLIENT prediction={}, activity={}, entity={}, movement={}, generation={}",
                        ScopeActivity.INSTANCE.predictionStatus(player.getUUID()),ScopeActivity.INSTANCE.status(),restored,movement,GeneratedChunks.INSTANCE.status());
                if (ticks-stageTick>400) throw new AssertionError("Target not restored/rendered: "+ScopeActivity.INSTANCE.predictionStatus(player.getUUID())+" "+ScopeActivity.INSTANCE.status());
            }
            if (ticks>1800) throw new AssertionError("Preparation timed out at stage "+stage+" "+GeneratedChunks.INSTANCE.status());
        } catch (Throwable e) { failure=e.toString(); }
    }

    private static void finish(boolean success,String reason) {
        var mc=Minecraft.getInstance();
        TaczLongRangeSniping.LOGGER.info("LONG_RANGE_CLIENT {}: {}",success?"PASS":"FAIL",reason);
        try { java.nio.file.Files.writeString(mc.gameDirectory.toPath().resolve("result.txt"),success?"PASS":reason); }
        catch(java.io.IOException e) { throw new RuntimeException(e); }
        mc.stop();
    }
}
