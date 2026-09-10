package tacz.longrangesniping.client;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import tacz.longrangesniping.TaczLongRangeSniping;
import tacz.longrangesniping.network.ScopeNetworking;
import tacz.longrangesniping.network.ShotTrailsPayload;
import tacz.longrangesniping.trails.TrailHistory;
import tacz.longrangesniping.trails.TrailStyle;

import java.util.LinkedHashMap;

import static net.minecraft.client.renderer.RenderStateShard.*;

/** Unlit, depth-tested particle ribbons. Independent of entity/particle chunk ticking and terrain fog. */
@EventBusSubscriber(modid = TaczLongRangeSniping.MOD_ID, value = Dist.CLIENT)
public final class ShotTrails {
    private static final RenderType TYPE = RenderType.create("taczlrs_shot_trail", DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS, 65536, false, false, RenderType.CompositeState.builder()
                    .setShaderState(POSITION_COLOR_SHADER).setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setCullState(NO_CULL).setDepthTestState(LEQUAL_DEPTH_TEST).setWriteMaskState(COLOR_WRITE)
                    .createCompositeState(false));
    private static final TrailHistory history = new TrailHistory();
    private static ClientLevel level;
    private static long tick;
    private static ByteBufferBuilder bytes;
    private static MultiBufferSource.BufferSource buffers;
    private static int lastPieces;
    private static double lastFarthest;

    @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ScopeNetworking.setTrailReceiver(ShotTrails::receive));
    }
    private static void receive(ShotTrailsPayload payload) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().location().equals(payload.dimension())) return;
        if (level != mc.level) { history.clear(); level = mc.level; }
        double now = tick + mc.getTimer().getGameTimeDeltaPartialTick(false);
        for (var segment : payload.segments()) history.accept(segment, now);
    }
    public static boolean hasTrails() { return level == Minecraft.getInstance().level && !history.isEmpty(); }
    public static int drawnPieces() { return lastPieces; }
    public static double farthestDrawn() { return lastFarthest; }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.level != level) { history.clear(); level = mc.level; }
        if (!mc.isPaused()) tick++;
        history.expire(tick);
    }

    @SubscribeEvent public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        lastPieces = 0; lastFarthest = 0;
        var mc = Minecraft.getInstance();
        if (!hasTrails() || mc.player == null) return;
        if (buffers == null) { bytes = new ByteBufferBuilder(262144); buffers = MultiBufferSource.immediate(bytes); }
        double now = tick + event.getPartialTick().getGameTimeDeltaPartialTick(false);
        var camera = event.getCamera();
        Vec3 eye = camera.getPosition();
        var matrix = event.getPoseStack().last().pose();
        double pixelScale = 2 / (Math.max(1, mc.getWindow().getHeight()) * Math.max(0.01, Math.abs(event.getProjectionMatrix().m11())));
        var heads = new LinkedHashMap<Integer, TrailHistory.Sample>();
        var vertices = buffers.getBuffer(TYPE);
        for (var sample : history.samples()) {
            var s = sample.segment();
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
                double width = width((a.length() + b.length()) * 0.5, pixelScale);
                // A solid center survives subpixel sampling and a pale sky; only the outer edges taper to zero.
                band(vertices, matrix, a, b, side, -width * 1.8, -width * 0.6, 0, a0, 0, a1);
                band(vertices, matrix, a, b, side, -width * 0.6, width * 0.6, a0, a0, a1, a1);
                band(vertices, matrix, a, b, side, width * 0.6, width * 1.8, a0, 0, a1, 0);
                lastPieces++; lastFarthest = Math.max(lastFarthest, b.length());
            }
        }
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

    private static double width(double distance, double pixelScale) {
        // Wider verification setting: apparent half-width 1.25–1.5 pixels, independent of distance and zoom.
        return TrailStyle.halfWidth(distance, pixelScale);
    }
    private static Vec3 vector(Vector3f v) { return new Vec3(v.x, v.y, v.z); }
    private static void vertex(VertexConsumer out, Matrix4f matrix, Vec3 p, float alpha) {
        out.addVertex(matrix, (float) p.x, (float) p.y, (float) p.z).setColor(0.98f, 0.99f, 1f, alpha);
    }
    private static void band(VertexConsumer out, Matrix4f matrix, Vec3 a, Vec3 b, Vec3 side,
                             double lo, double hi, float aLo, float aHi, float bLo, float bHi) {
        vertex(out, matrix, a.add(side.scale(lo)), aLo); vertex(out, matrix, a.add(side.scale(hi)), aHi);
        vertex(out, matrix, b.add(side.scale(hi)), bHi); vertex(out, matrix, b.add(side.scale(lo)), bLo);
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
        history.clear(); level = null; tick = 0; lastPieces = 0;
        if (bytes != null) { bytes.close(); bytes = null; buffers = null; }
    }
}
