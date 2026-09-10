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

    /** TACZ view 2 is the tubular optical view; view 1 is the alternate/open sight, regardless of zoom value. */
    public static boolean usesOpticalView(Player player) {
        var decision = evaluateForDisplay(player);
        if (!decision.eligible()) return false;
        var index = TimelessAPI.getClientAttachmentIndex(ResourceLocation.parse(decision.scopeId())).orElse(null);
        return index != null && index.isScope() && index.getViews() != null && decision.slot() < index.getViews().length
                && index.getViews()[decision.slot()] == 2;
    }

    public static Decision evaluateForDisplay(Player player) {
        Decision decision = TaczBridge.evaluate(player);
        if (!decision.eligible()) return decision;
        var index = TimelessAPI.getClientAttachmentIndex(ResourceLocation.parse(decision.scopeId())).orElse(null);
        if (index == null || index.getZoom() == null || index.getZoom().length == 0) {
            return decision.withReason(Reason.CLIENT_LAYOUT_MISMATCH);
        }
        var gun = com.tacz.guns.api.item.IGun.getIGunOrNull(player.getMainHandItem());
        int number = com.tacz.guns.api.item.nbt.AttachmentItemDataAccessor.getZoomNumberFromTag(
                gun.getAttachmentTag(player.getMainHandItem(), com.tacz.guns.api.item.attachment.AttachmentType.SCOPE));
        int slot = Math.floorMod(number, index.getZoom().length);
        return new Decision(decision.reason(), decision.gunId(), decision.scopeId(), slot, index.getZoom()[slot]);
    }
}
