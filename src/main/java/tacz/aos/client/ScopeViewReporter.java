package tacz.aos.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import tacz.aos.network.ScopeViewPayload;
import tacz.aos.scoping.ScopeCone;

/** Send changes at most twice a second, with a two-second heartbeat. No per-frame networking. */
final class ScopeViewReporter {
    private static ClientLevel level;
    private static long sent = Long.MIN_VALUE / 2;
    private static double lastZoom, lastAngle;
    private ScopeViewReporter() {}
    static void report(RenderLevelStageEvent event, double zoom, long tick) {
        var mc = Minecraft.getInstance();
        if (level != mc.level) { reset(); level = mc.level; }
        if (level == null || tick - sent < 10 || zoom > Float.MAX_VALUE) return;
        var projection = event.getProjectionMatrix();
        double angle = ScopeCone.projectionHalfAngle(Math.abs(projection.m00()), Math.abs(projection.m11()));
        if (!Double.isFinite(angle) || angle <= 0) return;
        if (zoom == lastZoom && Math.abs(angle - lastAngle) < lastAngle * 0.02 && tick - sent < 40) return;
        PacketDistributor.sendToServer(new ScopeViewPayload(level.dimension().location(), (float) zoom, (float) angle));
        sent = tick; lastZoom = zoom; lastAngle = angle;
    }
    static void reset() { level = null; sent = Long.MIN_VALUE / 2; lastZoom = lastAngle = 0; }
}
