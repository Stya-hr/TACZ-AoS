package tacz.longrangesniping.test;

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
import tacz.longrangesniping.TaczLongRangeSniping;
import tacz.longrangesniping.client.ScopedRendering;
import tacz.longrangesniping.config.ServerConfig;
import tacz.longrangesniping.terrain.TerrainSnapshots;

import java.util.Comparator;

/** Opt-in executable client integration test. It creates only an isolated build/ world and exits automatically. */
@EventBusSubscriber(modid = TaczLongRangeSniping.MOD_ID, value = Dist.CLIENT)
public final class ScopeClientSmokeTest {
    private static final boolean ENABLED = Boolean.getBoolean("taczlrs.clientScopeTest");
    private static final TicketType<ChunkPos> TARGET = TicketType.create("scope_smoke_target", Comparator.comparingLong(ChunkPos::toLong));
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
    private static com.mojang.blaze3d.platform.NativeImage beforeTrail;

    @SubscribeEvent(priority = EventPriority.HIGHEST) public static void beforeTrailFrame(net.neoforged.neoforge.client.event.RenderLevelStageEvent event) {
        if (ENABLED && !trailStageCaptured && event.getStage() == net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_PARTICLES
                && tacz.longrangesniping.client.ShotTrails.farthestDrawn() > 140) {
            beforeTrail = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST) public static void trailFrame(net.neoforged.neoforge.client.event.RenderLevelStageEvent event) {
        if (!ENABLED || beforeTrail == null || event.getStage() != net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        try {
            if (tacz.longrangesniping.client.ShotTrails.farthestDrawn() <= 160) return;
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
                if (changed < 40 || strongest < 40) { fail("Trail did not visibly affect framebuffer: pixels=" + changed + ", contrast=" + strongest); return; }
                TaczLongRangeSniping.LOGGER.info("TRAIL_FRAME pixels={}, peak brightness change={}", changed, strongest);
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
            started = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.renderDistance().set(6); mc.options.simulationDistance().set(6);
            mc.createWorldOpenFlows().createFreshLevel("scope-test-" + System.currentTimeMillis(),
                    new LevelSettings("Scope integration test", GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                            new GameRules(), WorldDataConfiguration.DEFAULT), new WorldOptions(12345, false, false),
                    registry -> registry.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), mc.screen);
        }
        if (mc.player == null || mc.level == null || cowId < 0) return;
        mc.player.setYRot(-90); mc.player.setYHeadRot(-90); mc.player.setXRot(0);
        com.tacz.guns.client.input.AimKey.AIM_KEY.setDown(!release);
        IGunOperator.fromLivingEntity(mc.player).aim(!release);
        if (!release && ScopedRendering.active() && ScopedRendering.extraDraws() > 0 && mc.level.getEntity(cowId) != null) {
            if (ScopedRendering.drewExtra(edgeCowId)) edgeObserved = true;
            if (ScopedRendering.hasRealClientChunk(mc.level.getEntity(cowId))) { fail("Target client chunk unexpectedly loaded"); return; }
            if (mc.level.getEntity(itemId) instanceof net.minecraft.world.entity.item.ItemEntity && ScopedRendering.extraDraws() >= 2)
                itemObserved = true;
            if (drawnTicks == 0) firstRenderedX = mc.level.getEntity(cowId).getX();
            if (++drawnTicks == 60) {
                if (!navigating || Math.abs(mc.level.getEntity(cowId).getX() - firstRenderedX) < 0.1) { fail("Remote pose stayed frozen"); return; }
                if (!itemObserved) { fail("Non-living item did not render remotely"); return; }
                if (!edgeObserved) { fail("Entity inside lens but outside old aim cone was never drawn"); return; }
                if (tacz.longrangesniping.client.ScopeRangeHud.draws() == 0) { fail("Lens range HUD never rendered"); return; }
                float range = tacz.longrangesniping.client.ScopeRangeHud.displayedDistance();
                if (Math.abs(range - 238) > .1) { fail("Live range to center wall incorrect: " + range); return; }
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
                TaczLongRangeSniping.LOGGER.info("SCOPE_CLIENT_TEST rendered remote cow outside 6-chunk view; firing trace test");
            }
        }
        if (shotFired && trailStageCaptured && !release && tacz.longrangesniping.client.ShotTrails.farthestDrawn() > 160) {
            if (mc.level.getChunkSource().getChunk(10, 0, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false) != null) {
                fail("Trail test unexpectedly received corridor terrain"); return;
            }
            Screenshot.grab(mc.gameDirectory, "shot-trail.png", mc.getMainRenderTarget(), ignored -> {});
            release = true;
        }
        if (release && !ScopedRendering.active() && tacz.longrangesniping.client.ShotTrails.drawnPieces() > 0) trailAfterRelease = true;
        if (release && !ScopedRendering.active() && rangeDrawsAfterRelease < 0)
            rangeDrawsAfterRelease = tacz.longrangesniping.client.ScopeRangeHud.draws();
        if (release && !ScopedRendering.active() && ScopedRendering.targetCount() == 0 && mc.level.getEntity(cowId) == null && mc.level.getEntity(itemId) == null && mc.level.getEntity(edgeCowId) == null
                && !tacz.longrangesniping.client.ShotTrails.hasTrails()) {
            if (!trailAfterRelease) { fail("In-flight trail vanished on scope release"); return; }
            if (rangeDrawsAfterRelease != tacz.longrangesniping.client.ScopeRangeHud.draws()) {
                fail("Range HUD continued drawing after scope release"); return;
            }
            TaczLongRangeSniping.LOGGER.info("SCOPE_CLIENT_TEST PASSED: native AI, lens-edge entity beyond old cone, real trail packets/render beyond 6 chunks, scope release and trail expiry");
            try { java.nio.file.Files.writeString(mc.gameDirectory.toPath().resolve("scope-result.txt"), "PASS"); }
            catch (java.io.IOException failure) { throw new RuntimeException(failure); }
            mc.stop();
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST) public static void server(ServerTickEvent.Post event) {
        if (!ENABLED || event.getServer().getPlayerList().getPlayers().isEmpty()) return;
        ServerPlayer player = event.getServer().getPlayerList().getPlayers().getFirst();
        var level = player.serverLevel();
        serverTicks++;
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
            LocalBallisticsGameTests.setSettings(new ServerConfig.Settings(true, original.policy(), 1024, 32, 128, false,
                    original.ballistics(), new ServerConfig.Snapshots(true, 65536, 256, 10, 0, 8192, 8192)));
            // Fixture-only preparation of previously explored terrain. Activity must later restore native simulation.
            for (int x = 0; x <= 14; x++) level.getChunk(x, 0);
            for (int x = 10; x <= 18; x++) for (int z = -4; z <= 4; z++) level.getChunk(x, z);
            for (int x = 220; x <= 246; x++) for (int z = 0; z <= 15; z++)
                level.setBlock(new net.minecraft.core.BlockPos(x, 79, z), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 2);
            level.setBlock(new net.minecraft.core.BlockPos(246, 81, 8), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 2);
            // Complete the corridor before short-lived fixture loading tickets can expire.
            for (int attempt = 0; attempt < 2048 && !corridorKnown(level); attempt++) TerrainSnapshots.INSTANCE.tick();
            if (!corridorKnown(level)) { fail("Fixture corridor could not be captured"); return; }
            var endpoint = new ChunkPos(14, 0);
            level.getChunkSource().addRegionTicket(TARGET, endpoint, 0, endpoint);
        }
        if (serverTicks == 230) {
            cow = EntityType.COW.create(level); cow.setPos(232, 80, 8); cow.setOldPosAndRot();
            level.addFreshEntity(cow); cowId = cow.getId();
            // About 1.74 degrees off-axis: inside the 25x lens, beyond the old 1.4-degree cone plus cow radius.
            var edgeCow = EntityType.COW.create(level); edgeCow.setPos(232, 80, 14.8);
            edgeCow.setNoAi(true); edgeCow.setNoGravity(true); level.addFreshEntity(edgeCow); edgeCowId = edgeCow.getId();
            var item = new net.minecraft.world.entity.item.ItemEntity(level, 233, 80.2, 9,
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND));
            item.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO); level.addFreshEntity(item); itemId = item.getId();
            TaczLongRangeSniping.LOGGER.info("SCOPE_CLIENT_TEST prepared cow={}, snapshots={}", cowId, TerrainSnapshots.INSTANCE.status().sections());
        }
        if (serverTicks >= 230) {
            if (!navigating && cow.onGround() && tacz.longrangesniping.scoping.ScopeActivity.INSTANCE.active(cow)
                    && level.isPositionEntityTicking(cow.blockPosition())) {
                navigating = cow.getNavigation().moveTo(242, 80, 8, 1.0);
                if (!navigating) { fail("Native cow path could not start"); return; }
            }
            IGunOperator.fromLivingEntity(player).aim(!release);
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
                TaczLongRangeSniping.LOGGER.info("SCOPE_CLIENT_TEST state: serverEligible={}, clientActive={}, received={}, draws={}, eye={}, look={}, selectable={}, visible={}",
                        tacz.longrangesniping.compat.tacz.TaczBridge.evaluate(player), ScopedRendering.active(), ScopedRendering.targetCount(), ScopedRendering.extraDraws(),
                        player.getEyePosition(), player.getViewVector(1), tacz.longrangesniping.scoping.ScopeSubscriptions.INSTANCE.select(player, 25).size(),
                        new tacz.longrangesniping.scoping.ScopeVisibility(level, 65536).clear(player.getEyePosition(), cow.getEyePosition()));
            }
        }
    }

    private static void fail(String message) {
        if (!failed) TaczLongRangeSniping.LOGGER.error("SCOPE_CLIENT_TEST FAILED: {}", message);
        failed = true;
    }

    private static boolean corridorKnown(net.minecraft.server.level.ServerLevel level) {
        for (int x = 0; x <= 14; x++) {
            var key = new tacz.longrangesniping.terrain.SectionKey(TerrainSnapshots.dimension(level), x, 5, 0);
            if (TerrainSnapshots.INSTANCE.coverage(key) == tacz.longrangesniping.terrain.SnapshotStore.Coverage.UNKNOWN) return false;
        }
        return true;
    }
}
