package tacz.longrangesniping.compat.tacz;

import net.minecraft.world.entity.player.Player;
import tacz.longrangesniping.config.ServerConfig;

import java.util.Map;

/** Selected factory-ammunition baselines, in blocks and ticks. Never edits TACZ's shared gun data/cache. */
public record SnipingPhysics(float metersPerSecond, float friction) {
    public static final float GRAVITY = (float) (9.80665 / 400.0);
    // Constant drag fits: 0–300 m, except .30-06 at 0–300 yd (274.32 m).
    // .50 BMG uses Hornady's 36-inch test barrel baseline, not individual Barrett measurements.
    private static final Map<String, SnipingPhysics> PROFILES = Map.of(
            "tacz:ai_awp", new SnipingPhysics(905f, (905f - 762f) / 6000f),
            "tacz:m95", new SnipingPhysics(858f, (858f - 772f) / 6000f),
            "tacz:m107", new SnipingPhysics(858f, (858f - 772f) / 6000f),
            "tacz:m700", new SnipingPhysics(822.96f, (float) ((2700 - 2148) * .3048 / (20 * 274.32))),
            "tacz:kar98", new SnipingPhysics(780f, (780f - 615f) / 6000f));

    public float speedPerTick() { return metersPerSecond / 20f; }

    public static SnipingPhysics forPlayer(Player player, String gunId) {
        if (player.level().isClientSide() || !ServerConfig.current().ballistics().enabled()) return null;
        var profile = PROFILES.get(gunId);
        if (profile == null) return null; // Springfield and user-added guns keep native parameters.
        var decision = TaczBridge.evaluate(player);
        return decision.eligible() && gunId.equals(decision.gunId()) ? profile : null;
    }
}
