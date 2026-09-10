package tacz.longrangesniping.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import tacz.longrangesniping.TaczLongRangeSniping;
import tacz.longrangesniping.network.RangePayload;
import tacz.longrangesniping.network.ScopeNetworking;

import java.util.Locale;

/** Lens-relative seven-segment range readout. Captures geometry already drawn by TACZ; never reads GPU pixels. */
@EventBusSubscriber(modid = TaczLongRangeSniping.MOD_ID, value = Dist.CLIENT)
public final class ScopeRangeHud {
    private static RangePayload sample;
    private static Object sampleLevel;
    private static long received, lensTime;
    private static double cx, cy, radius;
    private static double maskX, maskY, maskRadius;
    private static long maskTime;
    private static int draws;
    private static int shownTenths = Integer.MIN_VALUE;
    private static String shownNumber = "---";
    private static final int[] DIGITS = {0x3f, 0x06, 0x5b, 0x4f, 0x66, 0x6d, 0x7d, 0x07, 0x7f, 0x6f};
    private static final int[][] BARS = {{1,0,6,1},{6,1,7,6},{6,7,7,12},{1,12,6,13},{0,7,1,12},{0,1,1,6},{1,6,6,7}};
    private ScopeRangeHud() {}

    @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ScopeNetworking.setRangeReceiver(value -> {
            var mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null || !TaczClientBridge.evaluateForDisplay(mc.player).eligible()
                    || !mc.level.dimension().location().equals(value.dimension())) return;
            sample = value; sampleLevel = mc.level; received = mc.level.getGameTime();
        }));
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        sample = null; sampleLevel = null; lensTime = 0;
    }
    @SubscribeEvent public static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        var player = Minecraft.getInstance().player;
        if (player == null || !TaczClientBridge.evaluateForDisplay(player).eligible()) {
            sample = null; sampleLevel = null; lensTime = 0; maskTime = 0;
        }
    }
    public static int draws() { return draws; }
    public static float displayedDistance() {
        var mc = Minecraft.getInstance();
        if (sample == null || mc.level == null || mc.player == null || sampleLevel != mc.level
                || !TaczClientBridge.evaluateForDisplay(mc.player).eligible()
                || mc.level.getGameTime() - received > 10 || mc.level.getGameTime() < received
                || mc.player.getEyePosition().distanceToSqr(sample.eye()) > 1
                || mc.player.getViewVector(1).normalize().dot(sample.direction()) < .99999) return -1;
        return sample.distance();
    }

    /** Coordinates and radius of TACZ's actual ocular circle in its current first-person projection. */
    public static void capture(PoseStack.Pose pose, float x, float y, float z, float modelRadius) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || !mc.options.getCameraType().isFirstPerson()
                || !TaczClientBridge.usesOpticalView(mc.player)) return;
        var matrix = new Matrix4f(RenderSystem.getProjectionMatrix()).mul(RenderSystem.getModelViewMatrix()).mul(pose.pose());
        Vector4f center = matrix.transform(new Vector4f(x, y, z, 1));
        Vector4f right = matrix.transform(new Vector4f(x + modelRadius, y, z, 1));
        Vector4f top = matrix.transform(new Vector4f(x, y + modelRadius, z, 1));
        if (center.w <= 0 || right.w <= 0 || top.w <= 0) return;
        double w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
        double px = center.x / center.w, py = center.y / center.w;
        maskX = (px + 1) * w / 2; maskY = (1 - py) * h / 2;
        maskRadius = Math.min(Math.abs(right.x / right.w - px) * w / 2, Math.abs(top.y / top.w - py) * h / 2);
        maskTime = System.nanoTime();
    }

    /** Observe already-emitted lens mesh vertices, forwarding every attribute unchanged. */
    public static final class LensVertices implements VertexConsumer {
        private final VertexConsumer delegate;
        private final Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix()).mul(RenderSystem.getModelViewMatrix());
        private double minX = Double.POSITIVE_INFINITY, minY = minX, maxX = -minX, maxY = -minX;
        private int vertices;
        public LensVertices(VertexConsumer delegate) { this.delegate = delegate; }
        public VertexConsumer addVertex(float x, float y, float z) {
            delegate.addVertex(x, y, z);
            if (++vertices <= 2048) {
                var p = projection.transform(new Vector4f(x, y, z, 1));
                if (p.w > 0) {
                    var window = Minecraft.getInstance().getWindow();
                    double sx = (p.x / p.w + 1) * window.getGuiScaledWidth() / 2;
                    double sy = (1 - p.y / p.w) * window.getGuiScaledHeight() / 2;
                    minX = Math.min(minX, sx); maxX = Math.max(maxX, sx);
                    minY = Math.min(minY, sy); maxY = Math.max(maxY, sy);
                }
            }
            return this;
        }
        public VertexConsumer setColor(int r, int g, int b, int a) { delegate.setColor(r,g,b,a); return this; }
        public VertexConsumer setUv(float u, float v) { delegate.setUv(u,v); return this; }
        public VertexConsumer setUv1(int u, int v) { delegate.setUv1(u,v); return this; }
        public VertexConsumer setUv2(int u, int v) { delegate.setUv2(u,v); return this; }
        public VertexConsumer setNormal(float x, float y, float z) { delegate.setNormal(x,y,z); return this; }
        public void finish() {
            if (vertices < 3 || vertices > 2048 || !Double.isFinite(minX + minY + maxX + maxY)) return;
            cx = (minX + maxX) / 2; cy = (minY + maxY) / 2;
            radius = Math.min(maxX - minX, maxY - minY) / 2;
            lensTime = System.nanoTime();
        }
    }

    @SubscribeEvent public static void draw(RenderGuiEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui || mc.screen != null
                || !mc.options.getCameraType().isFirstPerson() || mc.getCameraEntity() != mc.player
                || !TaczClientBridge.usesOpticalView(mc.player)
                || System.nanoTime() - lensTime > 250_000_000L) return;
        float range = displayedDistance();
        int tenths = range < 0 ? -1 : Math.round(range * 10);
        if (tenths != shownTenths) {
            shownTenths = tenths;
            shownNumber = tenths < 0 ? "---" : String.format(Locale.ROOT, "%.1f", tenths / 10.0);
        }
        String number = shownNumber;
        double r = radius;
        if (System.nanoTime() - maskTime < 250_000_000L)
            r = Math.min(r, maskRadius - Math.hypot(maskX - cx, maskY - cy));
        if (!Double.isFinite(r) || r < 16) return;
        // All text corners lie comfortably within the circle, with room for the physical lens rim.
        double height = Math.clamp(r * .065, 6, 9);
        double units = number.length() * 8 + 4 + mc.font.width("blocks");
        float scale = (float) Math.min(height / 12, r * .9 / units);
        var gui = event.getGuiGraphics();
        gui.pose().pushPose();
        gui.pose().translate(cx - r * .55, cy + r * .50, 0);
        gui.pose().scale(scale, scale, 1);
        for (int i = 0; i < number.length(); i++) digit(gui, number.charAt(i), i * 8, 0xff72ff86);
        gui.drawString(mc.font, "blocks", number.length() * 8 + 4, 3, 0xff72ff86, true);
        gui.pose().popPose();
        draws++;
    }

    private static void digit(GuiGraphics gui, char c, int x, int color) {
        if (c == '.') { gui.fill(x + 2, 10, x + 4, 12, color); return; }
        int bits = c >= '0' && c <= '9' ? DIGITS[c - '0'] : 0x40;
        for (int i = 0; i < 7; i++) if ((bits & (1 << i)) != 0) {
            var b = BARS[i];
            gui.fill(x + b[0] + 1, b[1] + 1, x + b[2] + 1, b[3] + 1, 0x99001808);
            gui.fill(x + b[0], b[1], x + b[2], b[3], color);
        }
    }
}
