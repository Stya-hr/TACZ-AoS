package tacz.longrangesniping.client;

import com.tacz.guns.api.TimelessAPI;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import tacz.longrangesniping.compat.tacz.TaczBridge;
import tacz.longrangesniping.config.ServerConfig;
import tacz.longrangesniping.eligibility.Eligibility.Decision;
import tacz.longrangesniping.eligibility.Eligibility.Reason;

/** Client-only display guard. Never referenced from common or dedicated-server code. */
public final class TaczClientBridge {
    private TaczClientBridge() {}

    public static Decision evaluateForDisplay(Player player) {
        Decision decision = TaczBridge.evaluate(player);
        if (!decision.eligible()) return decision;
        var rule = ServerConfig.current().policy().scopeRules().get(decision.scopeId());
        var index = TimelessAPI.getClientAttachmentIndex(ResourceLocation.parse(decision.scopeId())).orElse(null);
        if (rule == null || index == null || !rule.matchesLayout(index.getZoom())) {
            return decision.withReason(Reason.CLIENT_LAYOUT_MISMATCH);
        }
        return decision;
    }
}
