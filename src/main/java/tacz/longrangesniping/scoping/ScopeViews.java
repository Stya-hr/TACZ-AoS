package tacz.longrangesniping.scoping;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import tacz.longrangesniping.compat.tacz.TaczBridge;
import tacz.longrangesniping.config.ServerConfig;
import tacz.longrangesniping.network.ScopeViewPayload;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Bounded, short-lived projection hints; the server still decides equipment, targets, visibility and all budgets. */
public final class ScopeViews {
    public static final ScopeViews INSTANCE = new ScopeViews();
    private record View(ScopeViewPayload payload, long received) {}
    private final Map<UUID, View> views = new HashMap<>();
    private final Map<UUID, Long> attempts = new HashMap<>();
    private long tick;
    private ScopeViews() {}

    public void receive(ServerPlayer player, ScopeViewPayload payload) {
        var id = player.getUUID();
        var previous = attempts.get(id);
        if (previous != null && tick - previous < 4) return;
        attempts.put(id, tick);
        var decision = TaczBridge.evaluate(player);
        if (!ServerConfig.current().scopeRendering().enabled() || !decision.eligible()
                || !payload.dimension().equals(player.level().dimension().location())) return;
        views.put(id, new View(payload, tick));
    }
    public double halfAngle(ServerPlayer player, double zoom) {
        var view = views.get(player.getUUID());
        if (view == null || tick - view.received > 100 || !view.payload.dimension().equals(player.level().dimension().location())) return ScopeCone.halfAngle(zoom);
        double maximum = ScopeCone.maximumHalfAngle(1);
        return Math.clamp(view.payload.halfAngle(), Math.min(Math.toRadians(0.1), maximum), maximum);
    }
    @SubscribeEvent public void tick(ServerTickEvent.Pre event) {
        tick++;
        views.values().removeIf(view -> tick - view.received > 100);
        attempts.values().removeIf(when -> tick - when > 100);
    }
    @SubscribeEvent public void logout(PlayerEvent.PlayerLoggedOutEvent event) { clear(event.getEntity().getUUID()); }
    @SubscribeEvent public void dimension(PlayerEvent.PlayerChangedDimensionEvent event) { clear(event.getEntity().getUUID()); }
    private void clear(UUID player) { views.remove(player); attempts.remove(player); }
    @SubscribeEvent public void stopped(ServerStoppedEvent event) { views.clear(); attempts.clear(); tick = 0; }
}
