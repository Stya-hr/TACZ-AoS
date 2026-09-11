package tacz.aos.test;

import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import tacz.aos.TaczArtOfSniping;
import tacz.aos.client.ScopedRendering;
import tacz.aos.config.ServerConfig;


import java.util.Comparator;

/** Opt-in executable client integration test. It creates only an isolated build/ world and exits automatically. */
@EventBusSubscriber(modid = TaczArtOfSniping.MOD_ID, value = Dist.CLIENT)
public final class ScopeClientSmokeTest {
    private static final boolean ENABLED = Boolean.getBoolean("tacz_aos.clientScopeTest");
    private static final TicketType<ChunkPos> TARGET = TicketType.create("scope_smoke_target", Comparator.comparingLong(ChunkPos::toLong));
    private static final net.minecraft.core.BlockPos DEVICE = new net.minecraft.core.BlockPos(8, 80, 9);
    private static volatile boolean blockPreview, blockPrepared, blockFront, blockFrontReady;
    private static int blockFrames;
    private static volatile boolean observerFeedback, observerSound, distantSound;
    private static int observerFrames, observerServerFrames;
    private static boolean observerDrawn;
    @SubscribeEvent public static void observerSound(net.neoforged.neoforge.client.event.sound.PlaySoundSourceEvent event) {
        if (ENABLED && event.getSound().getLocation().equals(tacz.aos.trails.FeedbackSounds.FLYBY.getId())) observerSound = true;
        if (ENABLED && event.getSound().getLocation().equals(tacz.aos.trails.FeedbackSounds.DISTANT_GUNSHOT.getId())) distantSound = true;
    }
    private static boolean started;
    private static int clientTicks, serverTicks, drawnTicks;
    private static volatile int cowId = -1, itemId = -1, edgeCowId = -1;
    private static boolean edgeObserved;
    private static boolean itemObserved;
    private static int rangeDrawsAfterRelease = -1;
    private static volatile boolean release, failed;
    private static Cow cow;
    private static boolean navigating;
    private static double firstRenderedX;
    private static volatile boolean requestShot, shotFired;
    private static boolean trailAfterRelease;
    private static boolean trailStageCaptured;
    private static volatile boolean zoomTests;
    private static volatile int zoomStage, appliedZoomStage = -1;
    private static int zoomFrames, zoomBaseline;
    private static boolean zoomRangeObserved;
    private static com.mojang.blaze3d.platform.NativeImage beforeTrail;
    private static int irisQuery;

    @SubscribeEvent(priority = EventPriority.HIGHEST) public static void beforeTrailFrame(net.neoforged.neoforge.client.event.RenderLevelStageEvent event) {
        if (ENABLED && !trailStageCaptured && event.getStage() == net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_PARTICLES
                && tacz.aos.client.ShotTrails.farthestDrawn() > 140) {
            if (tacz.aos.client.ShaderCompat.shadowPass()) return;
            if (tacz.aos.client.ShaderCompat.active()) {
                irisQuery = org.lwjgl.opengl.GL15C.glGenQueries();
                org.lwjgl.opengl.GL15C.glBeginQuery(org.lwjgl.opengl.GL15C.GL_SAMPLES_PASSED,irisQuery);
            } else beforeTrail = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST) public static void trailFrame(net.neoforged.neoforge.client.event.RenderLevelStageEvent event) {
        if (ENABLED && irisQuery != 0) {
            org.lwjgl.opengl.GL15C.glEndQuery(org.lwjgl.opengl.GL15C.GL_SAMPLES_PASSED);
            int samples = org.lwjgl.opengl.GL15C.glGetQueryObjecti(irisQuery,org.lwjgl.opengl.GL15C.GL_QUERY_RESULT);
            org.lwjgl.opengl.GL15C.glDeleteQueries(irisQuery); irisQuery=0;
            if (tacz.aos.client.ShotTrails.farthestDrawn()>160) {
                if(samples<40) throw new AssertionError("Iris smoke failed actual depth test: "+samples);
                trailStageCaptured=true;
                TaczArtOfSniping.LOGGER.info("IRIS_TRAIL_FRAME fragments={}",samples);
            }
            return;
        }
        if (!ENABLED || beforeTrail == null || event.getStage() != net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        try {
            if (tacz.aos.client.ShotTrails.farthestDrawn() <= 160) return;
            trailStageCaptured = true;
            var mc = Minecraft.getInstance();
            try (var after = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                int changed = 0, strongest = 0;
                for (int y = 0; y < after.getHeight(); y++) for (int x = 0; x < after.getWidth(); x++) {
                    int a = beforeTrail.getPixelRGBA(x, y), b = after.getPixelRGBA(x, y);
                    int difference = (b & 255) - (a & 255);
                    if (difference > 3) changed++;
                    strongest = Math.max(strongest, difference);
                }
                // A rendered terrain background has less contrast than the old empty-sky fixture.
                if (changed < 40 || strongest < 20) { fail("Trail did not visibly affect framebuffer: pixels=" + changed + ", contrast=" + strongest); return; }
                TaczArtOfSniping.LOGGER.info("TRAIL_FRAME pixels={}, peak brightness change={}", changed, strongest);
            }
            Screenshot.grab(mc.gameDirectory, "shot-trail-stage.png", mc.getMainRenderTarget(), ignored -> {});
        } finally { beforeTrail.close(); beforeTrail = null; }
    }

    @SubscribeEvent public static void client(ClientTickEvent.Post event) {
        if (!ENABLED) return;
        var mc = Minecraft.getInstance();
        clientTicks++;
        if (clientTicks > 2400 || failed) { fail("Client scope test timed out/failed"); mc.stop(); return; }
        if (!started && mc.isGameLoadFinished() && mc.getOverlay() == null) {
            var configFile = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get()
                    .resolve(tacz.aos.config.ConfigFileBootstrap.FILE_NAME);
            if (!java.nio.file.Files.isRegularFile(configFile)) {
                fail("Server config was not generated before opening the first world"); mc.stop(); return;
            }
            TaczArtOfSniping.LOGGER.info("CONFIG_BOOTSTRAP_CLIENT PASSED before opening a world: {}", configFile);
            started = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.renderDistance().set(16); mc.options.simulationDistance().set(6);
            mc.createWorldOpenFlows().createFreshLevel("scope-test-" + System.currentTimeMillis(),
                    new LevelSettings("Scope integration test", GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                            new GameRules(), WorldDataConfiguration.DEFAULT), new WorldOptions(12345, false, false),
                    registry -> registry.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), mc.screen);
        }
        if (mc.player == null || mc.level == null || cowId < 0) return;
        if (net.neoforged.fml.ModList.get().isLoaded("iris") && !tacz.aos.client.ShaderCompat.active()) {
            fail("Iris disabled shaders; this is not a shader compatibility pass"); mc.stop(); return;
        }
        if (observerFeedback) {
            com.tacz.guns.client.input.AimKey.AIM_KEY.setDown(false);
            IGunOperator.fromLivingEntity(mc.player).aim(false);
            mc.player.setYRot(-90); mc.player.setYHeadRot(-90); mc.player.setXRot(0);
            observerFrames++;
            if (tacz.aos.block.RangefinderAccess.nearby(mc.player)) { fail("Observer still near rangefinder"); return; }
            if (tacz.aos.client.ShotTrails.drawnPieces() > 0) {
                observerDrawn = true;
                if (tacz.aos.client.ShotTrails.farthestDrawn() > 100
                        || tacz.aos.client.ShotTrails.hasDistantTrails()) { fail("Observer drew an unloaded/full-distance trail"); return; }
            }
            if (observerFrames == 30) Screenshot.grab(mc.gameDirectory, "observer-trail.png", mc.getMainRenderTarget(), ignored -> {});
            if (observerFrames >= 45 && tacz.aos.client.ShotTrails.hasTrails()) {
                fail("Confirmed impact did not retire observer smoke before normal 50-tick expiry"); return;
            }
            if (observerFrames >= 100) {
                if (!observerDrawn || !observerSound || !distantSound || tacz.aos.client.ShotTrails.hasTrails()) {
                    fail("Observer feedback failed: rendered="+observerDrawn+", sound="+observerSound+", distantSound="+distantSound+", remaining="+tacz.aos.client.ShotTrails.hasTrails()); return;
                }
                TaczArtOfSniping.LOGGER.info("OBSERVER_CLIENT_TEST PASSED: unscoped/no device, local-only clipping, Ogg sound source and expiry");
                try { java.nio.file.Files.writeString(mc.gameDirectory.toPath().resolve("scope-result.txt"), "PASS"); }
                catch (java.io.IOException failure) { throw new RuntimeException(failure); }
                mc.stop();
            }
            return;
        }
        if (zoomTests && !blockPreview) {
            mc.player.setYRot(-90); mc.player.setYHeadRot(-90); mc.player.setXRot(0);
            com.tacz.guns.client.input.AimKey.AIM_KEY.setDown(true);
            IGunOperator.fromLivingEntity(mc.player).aim(true);
            if (appliedZoomStage != zoomStage) return;
            if (++zoomFrames == 50) zoomBaseline = tacz.aos.client.ScopeRangeHud.draws();
            if (zoomFrames >= 50 && Math.abs(tacz.aos.client.ScopeRangeHud.displayedDistance() - 238) < .1)
                zoomRangeObserved = true;
            if (zoomFrames >= 70 && (zoomRangeObserved || zoomStage == 3 || zoomFrames >= 180)) {
                var decision = tacz.aos.client.TaczClientBridge.evaluateForDisplay(mc.player);
                boolean optical = tacz.aos.client.TaczClientBridge.usesOpticalView(mc.player);
                int draws = tacz.aos.client.ScopeRangeHud.draws();
                float range = tacz.aos.client.ScopeRangeHud.displayedDistance();
                if (zoomStage == 0 || zoomStage == 2) {
                    double expected = zoomStage == 0 ? 5 : 1.25;
                    if (!decision.eligible() || decision.zoom() != expected || !optical || draws <= zoomBaseline
                            || !zoomRangeObserved) {
                        Screenshot.grab(mc.gameDirectory, "scope-low-failed.png", mc.getMainRenderTarget(), ignored -> {});
                        fail("Low optical zoom failed: " + decision + ", optical=" + optical + ", baseline=" + zoomBaseline + ", draws=" + draws + ", range=" + range + ", sample=" + rangeDebug()); return;
                    }
                    Screenshot.grab(mc.gameDirectory, "scope-low-" + zoomStage + ".png", mc.getMainRenderTarget(), ignored -> {});
                } else if (zoomStage == 1) {
                    if (!decision.eligible() || optical || draws != zoomBaseline || !zoomRangeObserved) {
                        fail("Backup sight must keep range processing without HUD: " + decision + ", range=" + range); return;
                    }
                } else {
                    if (decision.eligible() || optical || draws != zoomBaseline || range != -1 || ScopedRendering.active()
                            || Double.isFinite(tacz.aos.client.ScopeRangeHud.displayedCrosswind())) {
                        fail("Removing rangefinder left client functions active"); return;
                    }
                }
                TaczArtOfSniping.LOGGER.info("RANGEFINDER_ZOOM_CLIENT stage {} PASSED: {}", zoomStage, decision);
                if (++zoomStage == 4) { blockPreview = true; release = true; }
                zoomFrames = 0;
                zoomRangeObserved = false;
            }
            return;
        }
        if (blockPreview) {
            com.tacz.guns.client.input.AimKey.AIM_KEY.setDown(false);
            if (!blockPrepared) return;
            mc.options.fov().set(45);
            mc.options.hideGui = true;
            mc.player.setYRot(blockFront ? 31 : 149); mc.player.setYHeadRot(blockFront ? 31 : 149); mc.player.setXRot(13);
            if (++blockFrames == 60) {
                var pos = new net.minecraft.core.BlockPos(8, 79, 8);
                var state = mc.level.getBlockState(pos.above());
                if (!state.is(tacz.aos.block.ModContent.RANGEFINDER.get())) { fail("Rangefinder model block missing on client"); return; }
                var model = mc.getBlockRenderer().getBlockModel(state);
                var quads = model.getQuads(state, null, net.minecraft.util.RandomSource.create(1));
                if (quads.isEmpty() || quads.stream().anyMatch(q -> q.getSprite().contents().name().getPath().equals("missingno"))) {
                    fail("Rangefinder has empty/missing texture quads"); return;
                }
                var item = new net.minecraft.world.item.ItemStack(tacz.aos.block.ModContent.RANGEFINDER_ITEM.get());
                var itemModel = mc.getItemRenderer().getModel(item, mc.level, mc.player, 0);
                if (itemModel.getQuads(null, null, net.minecraft.util.RandomSource.create(1)).isEmpty()) { fail("Rangefinder item model missing"); return; }
                net.minecraft.world.item.CreativeModeTabs.tryRebuildTabContents(mc.player.connection.enabledFeatures(), true, mc.level.registryAccess());
                if (!com.tacz.guns.init.ModCreativeTabs.OTHER_TAB.get().contains(item)) { fail("Rangefinder missing from TACZ workbench tab"); return; }
                Screenshot.grab(mc.gameDirectory, "rangefinder-block.png", mc.getMainRenderTarget(), ignored -> {});
                blockFront = true;
            }
            if (blockFrontReady && blockFrames == 100) {
                Screenshot.grab(mc.gameDirectory, "rangefinder-front.png", mc.getMainRenderTarget(), ignored -> {});
                TaczArtOfSniping.LOGGER.info("RANGEFINDER_BLOCK_CLIENT_TEST PASSED: world/item models, textures, TACZ tab");
                mc.options.hideGui = false;
                mc.options.fov().set(70);
                mc.options.renderDistance().set(6);
                observerFeedback = true;
            }
            return;
        }
        mc.player.setYRot(-90); mc.player.setYHeadRot(-90); mc.player.setXRot(0);
        com.tacz.guns.client.input.AimKey.AIM_KEY.setDown(!release);
        IGunOperator.fromLivingEntity(mc.player).aim(!release);
        if (!release && ScopedRendering.active() && ScopedRendering.extraDraws() > 0 && mc.level.getEntity(cowId) != null) {
            if (ScopedRendering.drewExtra(edgeCowId)) edgeObserved = true;
            if (!ScopedRendering.hasRealClientChunk(mc.level.getEntity(cowId))) return;
            if (mc.level.getEntity(itemId) instanceof net.minecraft.world.entity.item.ItemEntity && ScopedRendering.extraDraws() >= 2)
                itemObserved = true;
            if (drawnTicks == 0) firstRenderedX = mc.level.getEntity(cowId).getX();
            if (++drawnTicks == 60) {
                if (!navigating || Math.abs(mc.level.getEntity(cowId).getX() - firstRenderedX) < 0.1) { fail("Remote pose stayed frozen: target="+tacz.aos.client.VisibleTargetReporter.lastDistance+", activity="+tacz.aos.scoping.ScopeActivity.INSTANCE.status()+", generation="+tacz.aos.terrain.GeneratedChunks.INSTANCE.status()); return; }
                if (!itemObserved) { fail("Non-living item did not render remotely"); return; }
                if (!edgeObserved) { fail("Entity inside lens but outside old aim cone was never drawn"); return; }
                if (tacz.aos.client.ScopeRangeHud.draws() == 0) { fail("Lens range HUD never rendered"); return; }
                float range = tacz.aos.client.ScopeRangeHud.displayedDistance();
                if (Math.abs(range - 238) > .1) { fail("Live range to center wall incorrect: " + range); return; }
                if (!Double.isFinite(tacz.aos.client.ScopeRangeHud.displayedCrosswind())) {
                    fail("Scoped crosswind sample was not received"); return;
                }
                try (var screenshot = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                    int green = 0, w = screenshot.getWidth(), h = screenshot.getHeight();
                    for (int y = h * 6 / 10; y < h * 9 / 10; y++) for (int x = w / 4; x < w / 2; x++) {
                        int pixel = screenshot.getPixelRGBA(x, y);
                        int r = pixel & 255, g = pixel >> 8 & 255, b = pixel >> 16 & 255;
                        if (g > 160 && g > r * 1.4 && g > b * 1.4) green++;
                    }
                    if (green < 20) { fail("Range text was not visible in lower-left lens pixels"); return; }
                }
                Screenshot.grab(mc.gameDirectory, "scope-render.png", mc.getMainRenderTarget(), ignored -> {});
                requestShot = true;
                TaczArtOfSniping.LOGGER.info("SCOPE_CLIENT_TEST rendered remote cow outside 6-chunk view; firing trace test");
            }
        }
        if (shotFired && trailStageCaptured && !release && tacz.aos.client.ShotTrails.farthestDrawn() > 160) {
            Screenshot.grab(mc.gameDirectory, "shot-trail.png", mc.getMainRenderTarget(), ignored -> {});
            release = true;
        }
        if (release && !ScopedRendering.active() && tacz.aos.client.ShotTrails.drawnPieces() > 0) trailAfterRelease = true;
        if (release && !ScopedRendering.active() && rangeDrawsAfterRelease < 0)
            rangeDrawsAfterRelease = tacz.aos.client.ScopeRangeHud.draws();
        if (release && !ScopedRendering.active() && ScopedRendering.targetCount() == 0 && mc.level.getEntity(cowId) == null && mc.level.getEntity(itemId) == null && mc.level.getEntity(edgeCowId) == null
                && !tacz.aos.client.ShotTrails.hasTrails()) {
            if (!trailAfterRelease) { fail("In-flight trail vanished on scope release"); return; }
            if (rangeDrawsAfterRelease != tacz.aos.client.ScopeRangeHud.draws()) {
                fail("Range HUD continued drawing after scope release"); return;
            }
            TaczArtOfSniping.LOGGER.info("SCOPE_CLIENT_TEST PASSED: native AI, lens-edge entity beyond old cone, real trail packets/render beyond 6 chunks, scope release and trail expiry");
            zoomTests = true;
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST) public static void server(ServerTickEvent.Post event) {
        if (!ENABLED || event.getServer().getPlayerList().getPlayers().isEmpty()) return;
        ServerPlayer player = event.getServer().getPlayerList().getPlayers().getFirst();
        var level = player.serverLevel();
        serverTicks++;
        if (observerFeedback) {
            if (++observerServerFrames == 10) {
                var eye = player.getEyePosition();
                var near = new tacz.aos.trails.TrailSegment(900001,1,eye.x+12,eye.y,eye.z-10,eye.x+12,eye.y,eye.z+10,20,false);
                var far = new tacz.aos.trails.TrailSegment(900002,1,eye.x+180,eye.y,eye.z-10,eye.x+180,eye.y,eye.z+10,20,true);
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                        new tacz.aos.network.ShotTrailsPayload(level.dimension().location(),true,java.util.List.of(near,far)));
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                        new tacz.aos.network.NearMissPayload(level.dimension().location(),eye.x+1,eye.y,eye.z+1,.8f));
            }
            if (observerServerFrames == 35) {
                var eye = player.getEyePosition();
                var stop = new tacz.aos.trails.TrailSegment(900001,2,eye.x+12,eye.y,eye.z+10,
                        eye.x+12,eye.y,eye.z+10,0,true);
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                        new tacz.aos.network.ShotTrailsPayload(level.dimension().location(),true,java.util.List.of(stop)));
            }
            if (observerServerFrames == 60) {
                var eye = player.getEyePosition();
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                        new tacz.aos.network.DistantGunshotPayload(level.dimension().location(),eye.x-1000,eye.y,eye.z,.2f,1));
            }
            return;
        }
        if (blockPreview) {
            if (!blockPrepared) {
                level.removeBlock(DEVICE, false);
                for (int x = 3; x <= 14; x++) for (int z = 3; z <= 14; z++)
                    level.setBlockAndUpdate(new net.minecraft.core.BlockPos(x, 78, z), net.minecraft.world.level.block.Blocks.SMOOTH_STONE.defaultBlockState());
                var pos = new net.minecraft.core.BlockPos(8, 79, 8);
                var block = tacz.aos.block.ModContent.RANGEFINDER.get();
                var state = block.defaultBlockState();
                level.setBlockAndUpdate(pos, state);
                block.setPlacedBy(level, pos, state, player, new net.minecraft.world.item.ItemStack(block));
                player.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
                player.getInventory().setItem(1, new net.minecraft.world.item.ItemStack(block));
                player.connection.teleport(11.5, 79.7, 13.5, 149, 13);
                level.setDayTime(6000);
                blockPrepared = true;
            }
            if (blockFront && !blockFrontReady) {
                player.connection.teleport(11.5, 79.7, 3.5, 31, 13);
                blockFrontReady = true;
            }
            return;
        }
        if (serverTicks == 30) {
            level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, event.getServer());
            player.connection.teleport(8, 80, 8, -90, 0);
            player.getAbilities().flying = true; player.onUpdateAbilities();
            var stack = GunItemBuilder.create().setId(ResourceLocation.parse("tacz:ai_awp"))
                    .putAttachment(AttachmentType.SCOPE, ResourceLocation.parse("tacz:scope_mk5hd")).build(level.registryAccess());
            var gun = IGun.getIGunOrNull(stack);
            var tag = gun.getAttachmentTag(stack, AttachmentType.SCOPE).copy(); tag.putInt("ZoomNumber", 1);
            gun.setAttachmentTag(stack, AttachmentType.SCOPE, tag);
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            var original = ServerConfig.current();
            LocalBallisticsGameTests.setSettings(new ServerConfig.Settings(true, original.policy(), 1024, 32, false, original.ballistics(), ServerConfig.DEFAULT_SCOPE_RENDERING, ServerConfig.DEFAULT_ACTIVITY, ServerConfig.DEFAULT_PREDICTION));
            // Fixture-only preparation of previously explored terrain. Activity must later restore native simulation.
            for (int x = 0; x <= 14; x++) level.getChunk(x, 0);
            for (int x = 10; x <= 20; x++) for (int z = -4; z <= 4; z++) level.getChunk(x, z);
            for (int x = 220; x <= 246; x++) for (int z = 0; z <= 15; z++)
                level.setBlock(new net.minecraft.core.BlockPos(x, 79, z), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 2);
            level.setBlock(new net.minecraft.core.BlockPos(246, 81, 8), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 2);
            var endpoint = new ChunkPos(14, 0);
            level.getChunkSource().addRegionTicket(TARGET, endpoint, 0, endpoint);
        }
        if (serverTicks == 230) {
            if (tacz.aos.scoping.ScopeActivity.INSTANCE.status().regions() != 0) {
                fail("Remote activity started before placing a rangefinder"); return;
            }
            // Put the device directly beside the eye ray, inside the old conservative one-cell halo.
            // The displayed 238-block wall must remain measurable at every optical magnification.
            var device = tacz.aos.block.ModContent.RANGEFINDER.get();
            level.setBlockAndUpdate(DEVICE.below(), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(DEVICE, device.defaultBlockState());
            device.setPlacedBy(level, DEVICE, device.defaultBlockState(), player, new net.minecraft.world.item.ItemStack(device));
            player.connection.teleport(4, 80, 8, -90, 0);
            cow = EntityType.COW.create(level); cow.setPos(232, 80, 8); cow.setOldPosAndRot();
            level.addFreshEntity(cow); cowId = cow.getId();
            // About 1.74 degrees off-axis: inside the 25x lens, beyond the old 1.4-degree cone plus cow radius.
            var edgeCow = EntityType.COW.create(level); edgeCow.setPos(232, 80, 14.8);
            edgeCow.setNoAi(true); edgeCow.setNoGravity(true); level.addFreshEntity(edgeCow); edgeCowId = edgeCow.getId();
            var item = new net.minecraft.world.entity.item.ItemEntity(level, 233, 80.2, 9,
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND));
            item.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO); level.addFreshEntity(item); itemId = item.getId();
            TaczArtOfSniping.LOGGER.info("SCOPE_CLIENT_TEST prepared cow={}", cowId);
        }
        if (serverTicks >= 230) {
            if (serverTicks == 270) {
                if (!IGunOperator.fromLivingEntity(player).getSynIsAiming()
                        || tacz.aos.scoping.ScopeActivity.INSTANCE.status().regions() != 0
                        || tacz.aos.client.ScopeRangeHud.displayedDistance() != -1) {
                    fail("Aimed player outside device range activated scoped functions"); return;
                }
                // Keep the same gun and held aim state while crossing the proximity boundary.
                player.connection.teleport(8, 80, 8, -90, 0);
                TaczArtOfSniping.LOGGER.info("SCOPE_CLIENT_TEST entered range while already aiming");
            }
            if (zoomTests && appliedZoomStage != zoomStage) {
                if (zoomStage == 0) {
                    var ray = new tacz.aos.compat.tacz.BallisticPreview(player.getEyePosition(),
                            new net.minecraft.world.phys.Vec3(128, 0, 0), 0, 0, 1);
                    TaczArtOfSniping.LOGGER.info("ZOOM_FIXTURE before endpoint retention: {}",
                            tacz.aos.scoping.ImpactPrediction.predict(ray,
                                    new tacz.aos.ballistics.TargetCollisionSource(level,tacz.aos.scoping.ScopeActivity.INSTANCE.observationBounds(player)), java.util.List.of(),
                                    1024, 8, 65536, 32768, Long.MAX_VALUE));
                    // The preceding release test deliberately retires the activity region. Keep this phase's
                    // measuring wall resident with a fixture-only ticket, independently of curved impact selection.
                    var wall = new ChunkPos(15, 0);
                    level.getChunkSource().addRegionTicket(TARGET, wall, 0, wall);
                    level.getChunk(15, 0);
                }
                String scope = zoomStage == 2 ? "tacz:scope_lpvo_1_6" : "tacz:scope_mk5hd";
                var stack = GunItemBuilder.create().setId(ResourceLocation.parse("tacz:ai_awp"))
                        .putAttachment(AttachmentType.SCOPE, ResourceLocation.parse(scope)).build(level.registryAccess());
                var gun = IGun.getIGunOrNull(stack);
                var tag = gun.getAttachmentTag(stack, AttachmentType.SCOPE).copy();
                tag.putInt("ZoomNumber", zoomStage == 1 ? 2 : zoomStage == 2 ? 1 : 0);
                gun.setAttachmentTag(stack, AttachmentType.SCOPE, tag);
                player.setItemInHand(InteractionHand.MAIN_HAND, stack);
                if (zoomStage == 3) level.removeBlock(DEVICE, false);
                appliedZoomStage = zoomStage;
            }
            if (!navigating && cow.onGround() && tacz.aos.scoping.ScopeActivity.INSTANCE.active(cow)
                    && level.isPositionEntityTicking(cow.blockPosition())) {
                navigating = cow.getNavigation().moveTo(242, 80, 8, 1.0);
                if (!navigating) { fail("Native cow path could not start"); return; }
            }
            IGunOperator.fromLivingEntity(player).aim(zoomTests || !release);
            if (requestShot && !shotFired) {
                var stack = player.getMainHandItem(); var gun = IGun.getIGunOrNull(stack);
                var data = com.tacz.guns.api.TimelessAPI.getCommonGunIndex(gun.getGunId(stack)).orElseThrow().getGunData();
                var attachmentCache = new com.tacz.guns.resource.modifier.AttachmentCacheProperty(); attachmentCache.eval(stack, data);
                IGunOperator.fromLivingEntity(player).updateCacheProperty(attachmentCache);
                var bullet = new com.tacz.guns.entity.EntityKineticBullet(level, player, stack, data.getAmmoId(), gun.getGunId(stack), false, data, data.getBulletData());
                bullet.setPos(player.getEyePosition().add(0, -0.05, -1.2));
                bullet.setDeltaMovement(new net.minecraft.world.phys.Vec3(8, 0, 0));
                LocalBallisticsGameTests.setField(bullet, "gravity", 0.002f);
                LocalBallisticsGameTests.setField(bullet, "friction", 0f);
                LocalBallisticsGameTests.setField(bullet, "pierce", 1);
                LocalBallisticsGameTests.setField(bullet, "startPos", bullet.position());
                if (level.addFreshEntity(bullet)) { fail("Smoke test bullet did not transfer ownership"); return; }
                shotFired = true;
            }
            if (serverTicks % 40 == 0) {
                TaczArtOfSniping.LOGGER.info("SCOPE_CLIENT_TEST state: serverEligible={}, clientActive={}, received={}, draws={}, eye={}, look={}, selectable={}, visible={}",
                        tacz.aos.compat.tacz.TaczBridge.evaluate(player), ScopedRendering.active(), ScopedRendering.targetCount(), ScopedRendering.extraDraws(),
                        player.getEyePosition(), player.getViewVector(1), tacz.aos.scoping.ScopeSubscriptions.INSTANCE.select(player, 25).size(),
                        true);
            }
        }
    }

    private static void fail(String message) {
        if (!failed) TaczArtOfSniping.LOGGER.error("SCOPE_CLIENT_TEST FAILED: {}", message);
        failed = true;
    }

    private static String rangeDebug() {
        try {
            var field = tacz.aos.client.ScopeRangeHud.class.getDeclaredField("sample");
            field.setAccessible(true);
            return String.valueOf(field.get(null));
        } catch (ReflectiveOperationException e) { return e.toString(); }
    }

}
