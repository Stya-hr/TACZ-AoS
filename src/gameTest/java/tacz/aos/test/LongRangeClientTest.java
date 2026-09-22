package tacz.aos.test;

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
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.TickEvent;
import tacz.aos.TaczArtOfSniping;
import tacz.aos.client.ScopedRendering;
import tacz.aos.scoping.ScopeActivity;
import tacz.aos.terrain.*;

import java.util.*;

/** Opt-in real client: default production budgets, 2-chunk view, saved animal 880 blocks away. */
@EventBusSubscriber(modid = TaczArtOfSniping.MOD_ID, value = Dist.CLIENT)
public final class LongRangeClientTest {
    private static final boolean ENABLED = Boolean.getBoolean("tacz_aos.longRangeTest");
    private static final TicketType<ChunkPos> EXPLORE = TicketType.create("long_range_fixture", Comparator.comparingLong(ChunkPos::toLong));
    private static final List<ChunkPos> visited = new ArrayList<>();
    private static volatile int stage, animalId = -1;
    private static volatile String failure;
    private static volatile boolean movement;
    private static volatile boolean occlusionRequested,occlusionFinished;
    private static volatile int coverPhase;
    private static int coverTick,baselineBoxes=-1;
    private static boolean coverBaselineCaptured;
    private static int coverBaselinePixels,coverPartialPixels,coverFullPixels=-1,coverRestoredPixels;
    private static boolean partialCoverSeen,fullCoverSeen,removedCoverSeen,logCoverSeen,offAxisLogCoverSeen,offAxisRestored;
    private static int movingVisibleFrames,movingHiddenFrames,movingDropouts,movingLeaks;
    private static int aimVisibleFrames,aimDropouts,retainedAnimalId=-1;
    private static tacz.aos.config.ServerConfig.Occlusion normalOcclusion;
    private static int budgetPressureTicks;
    private static double movingMinZ=Double.POSITIVE_INFINITY,movingMaxZ=Double.NEGATIVE_INFINITY;
    private static com.mojang.blaze3d.platform.NativeImage beforeEntities;
    private static volatile boolean readyToFire, hitConfirmed;
    private static com.tacz.guns.entity.EntityKineticBullet shotBullet;
    private static Cow shotTarget;
    private static final BlockPos BLOCK_TARGET = new BlockPos(890,80,6);
    private static UUID cartUuid;
    private static volatile int cartId=-1;
    private static volatile boolean targetsReady, targetsHit, sawFallen, sawCartHit, targetsRecovered;
    private static int targetHitTick, blockSamples;
    private static volatile boolean shotStarted;
    private static int ticks, stageTick, frames, draws;
    private static int entitySamples, trailSamples, query, queryKind, shotFrames, impactFrames;
    private static boolean vegetationDepthChecked;
    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.HIGHEST)
    public static void beforeDraw(net.minecraftforge.client.event.RenderLevelStageEvent event) {
        if (!ENABLED) return;
        if(!vegetationDepthChecked && Minecraft.getInstance().level!=null) {
            vegetationDepthChecked=true;
            try { VegetationDepthClientTest.run(); }
            catch(Throwable error) { failure="Vegetation depth test failed: "+error; }
        }
        var stage = event.getStage();
        if (stage != net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_ENTITIES
                && stage != net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_PARTICLES
                && stage != net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;
        queryKind = stage == net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_ENTITIES ? 1 : stage == net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES ? 3 : 2;
        if(queryKind==1 && occlusionRequested && !occlusionFinished)
            beforeEntities=Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget());
        query = org.lwjgl.opengl.GL15C.glGenQueries();
        org.lwjgl.opengl.GL15C.glBeginQuery(org.lwjgl.opengl.GL15C.GL_SAMPLES_PASSED, query);
    }
    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    public static void afterDraw(net.minecraftforge.client.event.RenderLevelStageEvent event) {
        if (!ENABLED || query == 0) return;
        org.lwjgl.opengl.GL15C.glEndQuery(org.lwjgl.opengl.GL15C.GL_SAMPLES_PASSED);
        int samples = org.lwjgl.opengl.GL15C.glGetQueryObjecti(query, org.lwjgl.opengl.GL15C.GL_QUERY_RESULT);
        org.lwjgl.opengl.GL15C.glDeleteQueries(query); query = 0;
        if (queryKind == 1 && animalId >= 0) entitySamples = Math.max(entitySamples, samples);
        if(beforeEntities!=null) {
            // Depth-only cover contributes to GL_SAMPLES_PASSED but never to visible entity pixels.
            // Compare actual color output, so drawing an invisible cover wall cannot make this test pass.
            try(var before=beforeEntities;var after=Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) {
                samples=0;
                for(int y=0;y<after.getHeight();y++)for(int x=0;x<after.getWidth();x++)
                    if(before.getPixelRGBA(x,y)!=after.getPixelRGBA(x,y))samples++;
            }
            beforeEntities=null;
            if(coverPhase==10 && ticks-coverTick>=12) {
                aimVisibleFrames++;
                if(!ScopedRendering.drewExtra(animalId) || samples==0)aimDropouts++;
            }
            if((coverPhase==8 || coverPhase==9) && ticks-coverTick>=12) {
                var animal=Minecraft.getInstance().level.getEntity(animalId);
                if(animal!=null){movingMinZ=Math.min(movingMinZ,animal.getZ());movingMaxZ=Math.max(movingMaxZ,animal.getZ());}
                if(!ScopedRendering.drewExtra(animalId))movingDropouts++;
                if(coverPhase==8){movingVisibleFrames++;if(samples==0)movingDropouts++;}
                else {movingHiddenFrames++;if(samples>0)movingLeaks++;}
            }
            if(ticks-coverTick>=12 && ScopedRendering.drewExtra(animalId)) {
                int boxes=tacz.aos.client.ScopedOcclusion.boxCount(animalId);
                if(coverPhase==1){coverBaselinePixels=Math.max(coverBaselinePixels,samples);baselineBoxes=boxes;}
                if(coverPhase==2 && boxes>baselineBoxes && samples>0 && samples<coverBaselinePixels){coverPartialPixels=Math.max(coverPartialPixels,samples);partialCoverSeen=true;}
                if(coverPhase==3 && boxes>baselineBoxes && samples==0){coverFullPixels=0;fullCoverSeen=true;}
                if(coverPhase==4 && boxes==baselineBoxes && samples>0){coverRestoredPixels=Math.max(coverRestoredPixels,samples);removedCoverSeen=true;}
                if(coverPhase==5 && boxes>baselineBoxes && samples==0)logCoverSeen=true;
                if(coverPhase==6 && boxes>baselineBoxes && samples==0)offAxisLogCoverSeen=true;
                if(coverPhase==7 && boxes==baselineBoxes && samples>0)offAxisRestored=true;
            }
        }
        if (queryKind == 3 && stage >= 3) blockSamples = Math.max(blockSamples, samples);
        if (queryKind == 2 && shotStarted) trailSamples = Math.max(trailSamples, samples);
    }
    private static volatile boolean missedInitialSpawn;
    @SubscribeEvent public static void missingSpawn(net.minecraftforge.event.entity.EntityJoinLevelEvent event) {
        // Reproduce an observer whose first pairing was not installed locally. Holding aim must repair it.
        if (ENABLED && stage >= 3 && event.getLevel().isClientSide() && uuid != null && event.getEntity().getUUID().equals(uuid) && !missedInitialSpawn) {
            missedInitialSpawn = true; event.setCanceled(true);
            TaczArtOfSniping.LOGGER.info("LONG_RANGE_CLIENT deliberately skipped first remote pairing");
        }
    }
    private static boolean started, rejoined;
    private static String worldName;
    private static Cow original;
    private static UUID uuid;
    private static double initialX;
    private static int clientVisitTick;

    @SubscribeEvent public static void client(TickEvent.ClientTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
        if (!ENABLED) return;
        var mc = Minecraft.getInstance();
        if (failure != null) { finish(false, failure); return; }
        if (!started && mc.screen instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen && mc.getOverlay() == null) {
            mc.options.onboardAccessibility = false; mc.options.save();
            mc.setScreen(new net.minecraft.client.gui.screens.TitleScreen());
        }
        if (!started && (mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen) && mc.getOverlay() == null) {
            started = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.renderDistance().set(2); mc.options.simulationDistance().set(5);
            worldName="range-"+System.currentTimeMillis();
            mc.createWorldOpenFlows().createFreshLevel(worldName,
                    new LevelSettings("Long range regression", GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                            new GameRules(), WorldDataConfiguration.DEFAULT), new WorldOptions(12345,false,false),
                    r -> r.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions());
        }
        if (mc.level == null || mc.player == null) return;

        if (stage>=3 && frames%40==0) TaczArtOfSniping.LOGGER.info("LONG_RANGE_CLIENT visual active={}, targets={}, entity={}, draws={}, eligibility={}, pos={}",ScopedRendering.active(),ScopedRendering.targetCount(),mc.level.getEntity(animalId),draws,
                tacz.aos.client.TaczClientBridge.evaluateForDisplay(mc.player),mc.player.position());
        if(stage>=3 && frames%40==0)TaczArtOfSniping.LOGGER.info("LONG_RANGE_RETICLE distance={}",tacz.aos.client.VisibleTargetReporter.lastDistance);
        mc.options.chatVisibility().set(net.minecraft.world.entity.player.ChatVisiblity.HIDDEN);
        if (++frames > 2400) { finish(false,"Client timed out, stage="+stage); return; }
        float aimYaw=coverPhase==6 || coverPhase==7 ? -89.4f : coverPhase==10 && (frames/8)%2==0 ? -89.4f : -90;
        mc.player.setYRot(aimYaw); mc.player.setYHeadRot(aimYaw); mc.player.setXRot(0);
        com.tacz.guns.client.input.AimKey.AIM_KEY.setDown(stage >= 3 && (!shotStarted));
        IGunOperator.fromLivingEntity(mc.player).aim(stage >= 3 && (!shotStarted));
        if (shotStarted) {
            shotFrames++;
        }
        if(stage>=3 && !net.minecraftforge.fml.ModList.get().isLoaded("voxy") && ticks-stageTick>60) {
            if(ScopedRendering.targetCount()!=0 || tacz.aos.client.VisibleTargetReporter.lastDistance>=0)
                finish(false,"No-Voxy client selected terrain beyond two-chunk view");
            else finish(true,"No-Voxy sky does not select or load remote terrain");
            return;
        }
        if(shotStarted && hitConfirmed) {
            // The bullet stops immediately, but confirmed smoke fades for its configured lifetime.
            if (++impactFrames < 65) return;
            if (tacz.aos.client.ShotTrails.hasTrails() || tacz.aos.client.ShotTrails.drawnPieces() != 0) {
                finish(false,"Trail continued rendering after confirmed remote impact"); return;
            }

            finish(true,"Actual world save/rejoin, cold generation index, Voxy depth, real animal AI/render, both TACZ targets with hit/recovery animation, and remote damage after unscoping verified");return;
        }
        if (stage>=3 && !shotStarted) {
            var visual=tacz.aos.client.ScopedBlockRendering.visual(BLOCK_TARGET);
            var cart=mc.level.getEntity(cartId);
            if(visual!=null && cart instanceof com.tacz.guns.entity.TargetMinecart targetCart
                    && tacz.aos.client.ScopedBlockRendering.draws()>0 && ScopedRendering.drewExtra(cartId)) {
                if(ScopedRendering.hasRealClientChunk(cart)) {finish(false,"Target client chunk should remain absent");return;}
                targetsReady=true;
                sawFallen |= targetsHit && !visual.getBlockState().getValue(com.tacz.guns.block.TargetBlock.STAND) && visual.rot>=89;
                sawCartHit |= targetCart.getHurtTime()>0;
                targetsRecovered |= sawFallen && sawCartHit && visual.getBlockState().getValue(com.tacz.guns.block.TargetBlock.STAND) && visual.rot==0 && targetCart.getHurtTime()==0;
            }
        }
        // After releasing aim the entity can legitimately be unpaired before the next render event.
        if (!shotStarted && stage >= 3 && animalId >= 0 && ScopedRendering.drewExtra(animalId)) {
            var animal = mc.level.getEntity(animalId);
            if (animal == null || !animal.getUUID().equals(uuid) || ScopedRendering.hasRealClientChunk(animal)) {
                finish(false,"Wrong animal or remote terrain sent to 2-chunk client"); return;
            }
            if (++draws >= 40 && targetsRecovered && movement && missedInitialSpawn && tacz.aos.client.ScopeRangeHud.displayedDistance() > 800) {
                if(!rejoined) {
                    // Exercise actual save, server shutdown, client/Voxy teardown and reopening the same world.
                    rejoined=true;
                    mc.level.disconnect();
                    mc.clearLevel(new net.minecraft.client.gui.screens.TitleScreen());
                    draws=0;animalId=cartId=-1;initialX=0;movement=false;missedInitialSpawn=false;
                    targetsReady=targetsHit=sawFallen=sawCartHit=targetsRecovered=false;
                    entitySamples=trailSamples=blockSamples=0;stageTick=ticks;
                    TaczArtOfSniping.LOGGER.info("LONG_RANGE_CLIENT reopening saved world {}",worldName);
                    mc.createWorldOpenFlows().loadLevel(new net.minecraft.client.gui.screens.TitleScreen(), worldName);
                    return;
                }
                if(!occlusionFinished) {occlusionRequested=true;return;}
                if(aimVisibleFrames<20 || aimDropouts!=0) {
                    finish(false,"Aim return caused invisible frames: "+aimDropouts+" / "+aimVisibleFrames);return;
                }
                if(movingVisibleFrames<20 || movingHiddenFrames<20 || movingDropouts!=0 || movingLeaks!=0 || movingMaxZ-movingMinZ<1) {
                    finish(false,"Moving cover: visibleFrames="+movingVisibleFrames+", hiddenFrames="+movingHiddenFrames+", dropouts="+movingDropouts+", leaks="+movingLeaks+", travel="+(movingMaxZ-movingMinZ));return;
                }
                if(!logCoverSeen || !offAxisLogCoverSeen || !offAxisRestored || !partialCoverSeen || !fullCoverSeen || !removedCoverSeen || coverBaselinePixels<=0 || coverPartialPixels<=0 || coverPartialPixels>=coverBaselinePixels || coverFullPixels!=0 || coverRestoredPixels<=0) {finish(false,"Remote cover failed: logs="+logCoverSeen+"/"+offAxisLogCoverSeen+"/"+offAxisRestored+", partial="+partialCoverSeen+", full="+fullCoverSeen+", removed="+removedCoverSeen+", GPU="+coverBaselinePixels+"/"+coverPartialPixels+"/"+coverFullPixels+"/"+coverRestoredPixels);return;}
                if(!readyToFire) TaczArtOfSniping.LOGGER.info("RENDER_ANIMAL_POSITION {} bbox={} camera={}", animal.position(),animal.getBoundingBox(),mc.gameRenderer.getMainCamera().getPosition());
                if(!readyToFire) Screenshot.grab(mc.gameDirectory,"long-range-entity.png",mc.getMainRenderTarget(),ignored -> {});
                readyToFire = true;
                if (!hitConfirmed) return;
                Screenshot.grab(mc.gameDirectory,"long-range-entity.png",mc.getMainRenderTarget(),ignored -> {});
                TaczArtOfSniping.LOGGER.info("LONG_RANGE_CLIENT PASS distance={}, view={}, uuid={}, extraDraws={}",
                        animal.distanceTo(mc.player),mc.options.renderDistance().get(),uuid,ScopedRendering.extraDraws());
                finish(true,"Visible-depth targeting, saved animal AI/render and target-only projectile damage verified");
            }
        }
    }

    @SubscribeEvent public static void server(TickEvent.ServerTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
        if (!ENABLED || failure != null || net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayers().isEmpty()) return;
        var player = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayers().get(0);
        var level = player.serverLevel(); ticks++;
        try {
            if (ticks == 30) {
                level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer());
                level.setDayTime(6000);
                player.connection.teleport(8,80,8,-90,0);
                player.getAbilities().flying = true; player.onUpdateAbilities();
                var stack = GunItemBuilder.create().setId(new ResourceLocation("tacz:ai_awp"))
                        .putAttachment(AttachmentType.SCOPE,new ResourceLocation("tacz:scope_mk5hd")).build();
                var gun = IGun.getIGunOrNull(stack);
                var tag = gun.getAttachmentTag(stack,AttachmentType.SCOPE).copy(); tag.putInt("ZoomNumber",1);
                gun.getAttachmentTag(stack,AttachmentType.SCOPE).merge(tag); player.setItemInHand(InteractionHand.MAIN_HAND,stack);
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
                TaczTargetGameTests.place(level,BLOCK_TARGET);
                var cart=new com.tacz.guns.entity.TargetMinecart(level,890,80,10);cart.setNoGravity(true);
                cartUuid=cart.getUUID();level.addFreshEntity(cart);
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
                TaczArtOfSniping.LOGGER.info("LONG_RANGE_CLIENT exploration saved");
            }
            if (stage==2 && ticks-stageTick>100 && level.getChunkSource().getChunkNow(55,0)==null
                    && level.getChunkSource().getChunkNow(56,0)==null && level.getEntity(uuid)==null) {
                if (level.getChunkSource().getChunkNow(30,0)!=null) throw new AssertionError("Corridor still resident");
                GeneratedChunks.INSTANCE.clear(); // Cold metadata cache must recover without terrain snapshots.
                var pos=new BlockPos(8,80,10); var device=tacz.aos.block.ModContent.RANGEFINDER.get();
                level.setBlockAndUpdate(pos.below(),Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(pos,device.defaultBlockState());
                device.setPlacedBy(level,pos,device.defaultBlockState(),player,new net.minecraft.world.item.ItemStack(device));
                stage=3; stageTick=ticks;
                TaczArtOfSniping.LOGGER.info("LONG_RANGE_CLIENT target and corridor unloaded; enabling scope");
            }
            if (stage==3) {
                var restored=level.getEntity(uuid);
                if(occlusionRequested && coverPhase>0 && !occlusionFinished
                        && (restored==null || restored.getId()!=retainedAnimalId))
                    throw new AssertionError("Moving aim unloaded the observed entity during ballistic retention");
                if (restored instanceof Cow cow && ScopeActivity.INSTANCE.active(cow) && level.isPositionEntityTicking(cow.blockPosition())) {
                    if (cow==original) throw new AssertionError("Animal was never deserialized");
                    animalId=cow.getId();
                    if (initialX==0 && cow.onGround()) {cow.setNoAi(false);if(cow.getNavigation().moveTo(892,80,8,1))initialX=cow.getX();}
                    movement |= initialX!=0 && Math.abs(cow.getX()-initialX)>.1;
                    // Keep the AI witness out of the separate live-fire target lanes after movement is proved.
                    if(movement){cow.setNoAi(true);cow.getNavigation().stop();cow.setPos(888,80,8);}
                }
                if(occlusionRequested && !occlusionFinished && restored instanceof Cow coverCow) {
                    coverCow.setNoAi(true);coverCow.setNoGravity(true);coverCow.getNavigation().stop();coverCow.setPos(888,80,8);
                    if(coverPhase==8 || coverPhase==9)coverCow.setPos(888,80,8+1.5*Math.sin((ticks-coverTick)*.15));
                    if(coverPhase==0){coverPhase=1;coverTick=ticks;retainedAnimalId=coverCow.getId();var other=level.getEntity(cartUuid);if(other!=null)other.setInvisible(true);}
                    if(coverPhase==10 && ticks-coverTick>=20) {
                        if(normalOcclusion==null) {
                            normalOcclusion=tacz.aos.config.ServerConfig.occlusion();
                            var field=tacz.aos.config.ServerConfig.class.getDeclaredField("occlusion");field.setAccessible(true);
                            field.set(null,new tacz.aos.config.ServerConfig.Occlusion(true,normalOcclusion.radius(),0,
                                    normalOcclusion.cellsPerTick(),normalOcclusion.boxesPerPlayer()));
                        }
                        budgetPressureTicks++;
                    }
                    if(ticks-coverTick>=(coverPhase==10?180:coverPhase>=6?90:30)) {
                        coverTick=ticks;coverPhase++;
                        for(int y=80;y<=82;y++)for(int z=5;z<=11;z++)
                            level.setBlockAndUpdate(new BlockPos(884,y,z),coverPhase==2 && y==80 ? Blocks.STONE.defaultBlockState()
                                    : coverPhase==3 ? Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT,true)
                                    : coverPhase==4 ? Blocks.GLASS.defaultBlockState() : coverPhase==5 || coverPhase==6 || coverPhase==9 ? Blocks.OAK_LOG.defaultBlockState() : Blocks.AIR.defaultBlockState());
                        if(coverPhase>=11){
                            var field=tacz.aos.config.ServerConfig.class.getDeclaredField("occlusion");field.setAccessible(true);field.set(null,normalOcclusion);
                            if(budgetPressureTicks<100)throw new AssertionError("Stationary budget-pressure phase did not run");
                            occlusionFinished=true;TaczArtOfSniping.LOGGER.info("OCCLUSION_CLIENT stonePartial={}, leavesHidden={}, glassVisible={}, logsHidden={}, offAxisLogsHidden={}, offAxisRestored={}, pixels={}/{}/{}/{}",partialCoverSeen,fullCoverSeen,removedCoverSeen,logCoverSeen,offAxisLogCoverSeen,offAxisRestored,coverBaselinePixels,coverPartialPixels,coverFullPixels,coverRestoredPixels);
                            TaczArtOfSniping.LOGGER.info("MOVING_OCCLUSION_CLIENT visibleFrames={}, hiddenFrames={}, dropouts={}, leaks={}, travel={}",movingVisibleFrames,movingHiddenFrames,movingDropouts,movingLeaks,movingMaxZ-movingMinZ);
                            TaczArtOfSniping.LOGGER.info("AIM_RETENTION_CLIENT visibleFrames={}, dropouts={}, retainedEntityId={}, zeroScanBudgetTicks={}",aimVisibleFrames,aimDropouts,retainedAnimalId,budgetPressureTicks);}
                    }
                }
                if (level.getChunkSource().getChunkNow(30,0)!=null) throw new AssertionError("Scoping loaded the flight corridor");
                var cart=level.getEntity(cartUuid);
                if(cart!=null)cartId=cart.getId();
                // Native TACZ hitbox compensation uses the previous server position. The instant
                // fixture shot must wait for deserialized minecarts to receive their first real ticks.
                if(targetsReady && !targetsHit && cart!=null && cart.tickCount>=3 && level.isPositionEntityTicking(cart.blockPosition())) {
                    if(!(cart instanceof com.tacz.guns.entity.TargetMinecart targetCart))throw new AssertionError("Saved target minecart missing");
                    var stack=player.getMainHandItem();var gun=IGun.getIGunOrNull(stack);
                    var data=com.tacz.guns.api.TimelessAPI.getCommonGunIndex(gun.getGunId(stack)).orElseThrow().getGunData();
                    for(var position:List.of(new net.minecraft.world.phys.Vec3(878,81.32,6.5),cart.getBoundingBox().getCenter().add(-10,0,0))) {
                        var bullet=new com.tacz.guns.entity.EntityKineticBullet(level,player,stack,data.getAmmoId(),gun.getGunId(stack),false,data,data.getBulletData());
                        bullet.setPos(position);bullet.setDeltaMovement(20,0,0);
                        tacz.aos.ballistics.TargetBallistics.tick(level,bullet,1024,new tacz.aos.ballistics.TargetCollisionSource(level,ScopeActivity.INSTANCE.observationBounds(player)));
                    }
                    if(level.getBlockState(BLOCK_TARGET).getValue(com.tacz.guns.block.TargetBlock.STAND) || targetCart.getHurtTime()==0)throw new AssertionError("Remote target hit feedback: standing="+level.getBlockState(BLOCK_TARGET).getValue(com.tacz.guns.block.TargetBlock.STAND)+", cartHurt="+targetCart.getHurtTime());
                    targetsHit=true;targetHitTick=ticks;
                }
                if(targetsHit && ticks-targetHitTick>120 && !targetsRecovered)throw new AssertionError("Target animation synchronization: fallen="+sawFallen+", cartHit="+sawCartHit+", recovered="+targetsRecovered);
                if (readyToFire && shotBullet == null) {
                    var shot=tacz.aos.compat.tacz.BallisticPreview.read(player).orElseThrow();
                    var result=tacz.aos.scoping.ImpactPrediction.predict(shot,
                            new tacz.aos.ballistics.TargetCollisionSource(level,ScopeActivity.INSTANCE.observationBounds(player)),List.of(),1024,600,65536,8192,Long.MAX_VALUE);
                    if(result.end()!=tacz.aos.scoping.ImpactPrediction.End.BLOCK) throw new AssertionError("No known ballistic endpoint");
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
                    if(shotBullet.isRemoved() && !hitConfirmed) throw new AssertionError("Remote shot missed: "+tacz.aos.ballistics.ScopedBallistics.INSTANCE.status(level));
                }
                if (ticks%40==0) TaczArtOfSniping.LOGGER.info("LONG_RANGE_CLIENT prediction={}, activity={}, entity={}, movement={}, generation={}",
                        ScopeActivity.INSTANCE.predictionStatus(player.getUUID()),ScopeActivity.INSTANCE.status(),restored,movement,GeneratedChunks.INSTANCE.status());
                if(ticks%40==0)TaczArtOfSniping.LOGGER.info("LONG_RANGE_CORES aim={}, bounds={}",tacz.aos.scoping.ScopeAim.target(player),ScopeActivity.INSTANCE.observationBounds(player));
                if (ticks-stageTick>1050) throw new AssertionError("Target not restored/rendered: "+ScopeActivity.INSTANCE.predictionStatus(player.getUUID())+" "+ScopeActivity.INSTANCE.status());
            }
            if (ticks>1800) throw new AssertionError("Preparation timed out at stage "+stage+" "+GeneratedChunks.INSTANCE.status());
        } catch (Throwable e) { failure=e.toString(); }
    }

    private static void finish(boolean success,String reason) {
        var mc=Minecraft.getInstance();
        TaczArtOfSniping.LOGGER.info("LONG_RANGE_CLIENT {}: {}",success?"PASS":"FAIL",reason);
        try { java.nio.file.Files.writeString(mc.gameDirectory.toPath().resolve("result.txt"),success?"PASS":reason); }
        catch(java.io.IOException e) { throw new RuntimeException(e); }
        if (!success && mc.level != null) Screenshot.grab(mc.gameDirectory,"failure.png",mc.getMainRenderTarget(),ignored -> {});
        mc.stop();
    }
}
