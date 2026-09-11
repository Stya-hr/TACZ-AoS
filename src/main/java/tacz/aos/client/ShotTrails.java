package tacz.aos.client;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import tacz.aos.TaczArtOfSniping;
import tacz.aos.network.ScopeNetworking;
import tacz.aos.network.ShotTrailsPayload;
import tacz.aos.trails.TrailHistory;
import tacz.aos.trails.TrailStyle;

import java.util.LinkedHashMap;
import java.io.IOException;

import static net.minecraft.client.renderer.RenderStateShard.*;

/** Unlit, depth-tested particle ribbons. Independent of entity/particle chunk ticking and terrain fog. */
@EventBusSubscriber(modid = TaczArtOfSniping.MOD_ID, value = Dist.CLIENT)
public final class ShotTrails {
    private static ShaderInstance smokeShader;
    private static final RenderType SMOKE_TYPE = RenderType.create("tacz_aos_smoke_trail", DefaultVertexFormat.POSITION_TEX_COLOR,
            VertexFormat.Mode.QUADS, 65536, false, false, RenderType.CompositeState.builder()
                    .setShaderState(new ShaderStateShard(() -> smokeShader)).setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setCullState(NO_CULL).setDepthTestState(LEQUAL_DEPTH_TEST).setWriteMaskState(COLOR_WRITE)
                    .createCompositeState(false));
    private static final RenderType TYPE = RenderType.create("tacz_aos_shot_trail", DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS, 65536, false, false, RenderType.CompositeState.builder()
                    .setShaderState(POSITION_COLOR_SHADER).setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setCullState(NO_CULL).setDepthTestState(LEQUAL_DEPTH_TEST).setWriteMaskState(COLOR_WRITE)
                    .createCompositeState(false));
    private static final TrailHistory history = new TrailHistory();
    private static final TrailHistory localHistory = new TrailHistory(TrailHistory.ImpactMode.FADE_SMOKE);
    private static ClientLevel level;
    private static long tick;
    private static ByteBufferBuilder bytes;
    private static MultiBufferSource.BufferSource buffers;
    private static int lastPieces;
    private static double lastFarthest;
    private static boolean shaderDraw;

    @SubscribeEvent public static void shaders(RegisterShadersEvent event) throws IOException {
        event.registerShader(new ShaderInstance(event.getResourceProvider(),
                ResourceLocation.fromNamespaceAndPath(TaczArtOfSniping.MOD_ID, "smoke_trail"),
                DefaultVertexFormat.POSITION_TEX_COLOR), shader -> smokeShader = shader);
    }

    @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ScopeNetworking.setTrailReceiver(ShotTrails::receive);
            ScopeNetworking.setNearMissReceiver(NearMissSounds::receive);
            ScopeNetworking.setDistantGunshotReceiver(DistantGunshotSounds::receive);
        });
    }
    private static void receive(ShotTrailsPayload payload) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !payload.localOnly() && !tacz.aos.block.RangefinderAccess.nearby(mc.player)
                || !mc.level.dimension().location().equals(payload.dimension())) return;
        if (level != mc.level) { history.clear(); localHistory.clear(); level = mc.level; }
        double now = tick + mc.getTimer().getGameTimeDeltaPartialTick(false);
        for (var segment : payload.segments()) {
            if (payload.localOnly()) {
                for (var piece : tacz.aos.trails.TrailAudience.clip(segment, ShotTrails::hasLocalChunk)) localHistory.accept(piece, now);
            } else history.accept(segment, now);
        }
    }
    public static boolean hasTrails() {
        return level == Minecraft.getInstance().level && (localHistory.hasVisibleSamples(visualTime()) || hasDistantTrails());
    }
    public static boolean hasDistantTrails() {
        var mc = Minecraft.getInstance();
        return level == mc.level && history.hasVisibleSamples(visualTime()) && mc.player != null
                && tacz.aos.block.RangefinderAccess.nearby(mc.player);
    }
    public static int drawnPieces() { return lastPieces; }
    public static double farthestDrawn() { return lastFarthest; }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.level != level) { history.clear(); localHistory.clear(); level = mc.level; }
        if (!mc.isPaused()) tick++;
        if (mc.player == null || !history.isEmpty() && !tacz.aos.block.RangefinderAccess.nearby(mc.player)) history.clear();
        history.expire(tick); localHistory.expire(tick);
    }

    @SubscribeEvent public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || ShaderCompat.shadowPass()) return;
        lastPieces = 0; lastFarthest = 0;
        var mc = Minecraft.getInstance();
        if (!hasTrails() || mc.player == null || smokeShader == null) return;
        shaderDraw = ShaderCompat.active();
        if (shaderDraw) ShaderTrailMaterials.prepare();
        RenderType smokeType = shaderDraw ? ShaderTrailMaterials.SMOKE_TYPE : SMOKE_TYPE;
        if (buffers == null) { bytes = new ByteBufferBuilder(262144); buffers = MultiBufferSource.immediate(bytes); }
        double now = tick + event.getPartialTick().getGameTimeDeltaPartialTick(false);
        var camera = event.getCamera();
        Vec3 eye = camera.getPosition();
        var matrix = event.getPoseStack().last().pose();
        double pixelScale = 2 / (Math.max(1, mc.getWindow().getHeight()) * Math.max(0.01, Math.abs(event.getProjectionMatrix().m11())));
        var heads = new LinkedHashMap<Integer, TrailHistory.Sample>();
        smokeShader.safeGetUniform("SmokeTime").set((float) (now * 0.03));
        var vertices = buffers.getBuffer(smokeType);
        for (var sample : visibleSamples(now)) {
            var s = sample.segment();
            if (!shaderDraw && (history.hasMovingHead(s.shot(), now) || localHistory.hasMovingHead(s.shot(), now)))
                heads.put(s.shot(), sample);
            double progress = Math.clamp(now - sample.start(), 0, 1);
            if (progress <= 0 || lastPieces >= 2048) continue;
            Vec3 from = new Vec3(s.x0(), s.y0(), s.z0()), to = new Vec3(s.x1(), s.y1(), s.z1());
            if (!event.getFrustum().isVisible(new AABB(from, to).inflate(0.2))) continue;
            // Subdivision supplies a smooth spatial age gradient even for very fast bullets.
            for (int piece = 0; piece < 6 && lastPieces < 2048; piece++) {
                double u0 = piece / 6.0, u1 = Math.min((piece + 1) / 6.0, progress);
                if (u1 <= u0) break;
                float a0 = TrailStyle.alpha(now - sample.start() - u0, s.speed());
                float a1 = TrailStyle.alpha(now - sample.start() - u1, s.speed());
                if (Math.max(a0, a1) < 0.004) continue;
                Vec3 a = from.lerp(to, u0).subtract(eye), b = from.lerp(to, u1).subtract(eye);
                if (a.lengthSqr() < 0.75 * 0.75 || b.lengthSqr() < 0.75 * 0.75) continue;
                Vec3 axis = b.subtract(a), side = axis.cross(a.add(b).scale(0.5));
                if (axis.lengthSqr() < 1.0e-12) continue;
                if (side.lengthSqr() < 1.0e-12) side = axis.cross(new Vec3(0, 1, 0));
                if (side.lengthSqr() < 1.0e-12) side = axis.cross(new Vec3(1, 0, 0));
                side = side.normalize();
                double age0 = Math.max(0, now - sample.start() - u0), age1 = Math.max(0, now - sample.start() - u1);
                double phase0 = (s.shot() & 255) * 13.371 + (s.step() - 1 + u0) * 1.8;
                double phase1 = (s.shot() & 255) * 13.371 + (s.step() - 1 + u1) * 1.8;
                double width0 = width(a.length(), pixelScale), width1 = width(b.length(), pixelScale);
                // Older smoke gently spreads and curls; the bullet and its collision path remain untouched.
                Vec3 smokeA = a.add(side.scale(width0 * TrailStyle.smokeOffset(phase0, age0)));
                Vec3 smokeB = b.add(side.scale(width1 * TrailStyle.smokeOffset(phase1, age1)));
                smokeBand(vertices, matrix, smokeA, smokeB, side,
                        width0 * 1.9 * TrailStyle.smokeWidth(phase0, age0), width1 * 1.9 * TrailStyle.smokeWidth(phase1, age1),
                        (float) phase0, (float) phase1, a0, a1);
                lastPieces++; lastFarthest = Math.max(lastFarthest, b.length());
            }
        }
        buffers.endBatch(smokeType);
        // Shader packs render only the smoke ribbon; omit the separate bright bullet-head discs.
        if (shaderDraw) return;
        vertices = buffers.getBuffer(TYPE);
        Vec3 right = vector(new Vector3f(1, 0, 0).rotate(camera.rotation()));
        Vec3 up = vector(new Vector3f(0, 1, 0).rotate(camera.rotation()));
        for (var sample : heads.values()) {
            var s = sample.segment();
            double progress = Math.clamp(now - sample.start(), 0, 1);
            if (progress <= 0) continue;
            Vec3 point = new Vec3(s.x0(), s.y0(), s.z0()).lerp(new Vec3(s.x1(), s.y1(), s.z1()), progress);
            if (!event.getFrustum().isVisible(new AABB(point, point).inflate(0.2))) continue;
            point = point.subtract(eye);
            if (point.lengthSqr() < 0.75 * 0.75) continue;
            float alpha = TrailStyle.alpha(Math.max(0, now - sample.start() - 1), s.speed());
            double width = width(point.length(), pixelScale);
            disc(vertices, matrix, point, right, up, width * 3.5, alpha * 0.30f);
            disc(vertices, matrix, point, right, up, width * 2, Math.min(1, alpha * 1.3f));
        }
        buffers.endBatch(TYPE);
    }

    static boolean hasLocalChunk(int x, int z) {
        var world = Minecraft.getInstance().level;
        // ClientLevel.hasChunk() unconditionally returns true; query the real cache with creation disabled.
        return world != null && world.getChunkSource().getChunk(x, z,
                net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false) != null;
    }

    private static double visualTime() {
        return tick + Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
    }

    private static java.util.List<TrailHistory.Sample> visibleSamples(double now) {
        var result = new java.util.ArrayList<TrailHistory.Sample>();
        if (hasDistantTrails()) result.addAll(history.visibleSamples(now));
        var mc = Minecraft.getInstance();
        // Reclip retained smoke too: unloading/teleporting must not expose a previously received distant trail.
        for (var sample : localHistory.visibleSamples(now)) {
            for (var piece : tacz.aos.trails.TrailAudience.clip(sample.segment(), ShotTrails::hasLocalChunk))
                result.add(new TrailHistory.Sample(piece, sample.start()));
        }
        return result;
    }

    private static double width(double distance, double pixelScale) {
        // Wider verification setting: apparent half-width 2.275–2.73 pixels, independent of distance and zoom.
        return TrailStyle.halfWidth(distance, pixelScale);
    }
    private static Vec3 vector(Vector3f v) { return new Vec3(v.x, v.y, v.z); }
    private static void vertex(VertexConsumer out, Matrix4f matrix, Vec3 p, float alpha) {
        out.addVertex(matrix, (float) p.x, (float) p.y, (float) p.z).setColor(0.92f, 0.93f, 0.94f, alpha);
    }
    private static void smokeVertex(VertexConsumer out, Matrix4f matrix, Vec3 p, float u, float v, float alpha) {
        out.addVertex(matrix, (float) p.x, (float) p.y, (float) p.z)
                .setUv(shaderDraw ? u * .125f : u, shaderDraw ? (v + 1) * .5f : v).setColor(0.88f, 0.89f, 0.90f, alpha);
        if (shaderDraw) out.setLight(net.minecraft.client.renderer.LightTexture.FULL_BRIGHT);
    }
    private static void smokeBand(VertexConsumer out, Matrix4f matrix, Vec3 a, Vec3 b, Vec3 side,
                                  double width0, double width1, float u0, float u1, float alpha0, float alpha1) {
        smokeVertex(out, matrix, a.add(side.scale(-width0)), u0, -1, alpha0);
        smokeVertex(out, matrix, a.add(side.scale(width0)), u0, 1, alpha0);
        smokeVertex(out, matrix, b.add(side.scale(width1)), u1, 1, alpha1);
        smokeVertex(out, matrix, b.add(side.scale(-width1)), u1, -1, alpha1);
    }
    private static void disc(VertexConsumer out, Matrix4f matrix, Vec3 point, Vec3 right, Vec3 up, double radius, float alpha) {
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4, b = (i + 1) * Math.PI / 4;
            Vec3 ra = right.scale(Math.cos(a) * radius).add(up.scale(Math.sin(a) * radius));
            Vec3 rb = right.scale(Math.cos(b) * radius).add(up.scale(Math.sin(b) * radius));
            vertex(out, matrix, point, alpha);
            vertex(out, matrix, point.add(ra.scale(0.35)), alpha);
            vertex(out, matrix, point.add(rb.scale(0.35)), alpha);
            vertex(out, matrix, point, alpha);
            vertex(out, matrix, point.add(ra.scale(0.35)), alpha); vertex(out, matrix, point.add(ra), 0);
            vertex(out, matrix, point.add(rb), 0); vertex(out, matrix, point.add(rb.scale(0.35)), alpha);
        }
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        history.clear(); localHistory.clear(); level = null; tick = 0; lastPieces = 0;
        if (bytes != null) { bytes.close(); bytes = null; buffers = null; }
    }
}
