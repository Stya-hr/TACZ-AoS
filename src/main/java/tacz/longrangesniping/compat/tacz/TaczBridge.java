package tacz.longrangesniping.compat.tacz;

import com.tacz.guns.api.DefaultAssets;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.api.item.nbt.AttachmentItemDataAccessor;
import com.tacz.guns.entity.EntityKineticBullet;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import tacz.longrangesniping.config.ServerConfig;
import tacz.longrangesniping.eligibility.Eligibility;
import tacz.longrangesniping.eligibility.Eligibility.Decision;
import tacz.longrangesniping.eligibility.Eligibility.Reason;
import tacz.longrangesniping.eligibility.Eligibility.WeaponState;

import java.util.Optional;

/** Common-side TACZ boundary. Never calls getAimingZoom or client gun-pack APIs. */
public final class TaczBridge {
    private TaczBridge() {}

    /** Stateless: changing gun, scope, zoom, life state or dimension leaves no cached permission. */
    public static Decision evaluate(Player player) {
        ServerConfig.Settings settings = ServerConfig.current();
        if (!settings.available()) return Decision.denied(Reason.CONFIG_UNAVAILABLE);
        if (!settings.policy().enabled()) return Decision.denied(Reason.DISABLED);
        if (!player.isAlive() || player.isRemoved() || player.isSpectator()) {
            return Decision.denied(Reason.INACTIVE_PLAYER);
        }
        ItemStack stack = player.getMainHandItem();
        IGun gun = IGun.getIGunOrNull(stack);
        if (gun == null) return Decision.denied(Reason.NOT_A_GUN);
        ResourceLocation gunId = gun.getGunId(stack);
        ResourceLocation scopeId = gun.getAttachmentId(stack, AttachmentType.SCOPE);
        CompoundTag scopeTag = gun.getAttachmentTag(stack, AttachmentType.SCOPE);
        String scope = DefaultAssets.isEmptyAttachmentId(scopeId) ? "" : scopeId.toString();
        // Only explicitly installed scopes qualify. Built-in optics have different zoom semantics.
        int zoomNumber = AttachmentItemDataAccessor.getZoomNumberFromTag(scopeTag);
        Decision base = new Decision(Reason.ELIGIBLE, gunId.toString(), scope, -1, 0);
        if (!settings.policy().gunIds().contains(gunId.toString())) return base.withReason(Reason.GUN_NOT_ALLOWED);
        if (TimelessAPI.getCommonGunIndex(gunId).isEmpty()) return base.withReason(Reason.GUN_DATA_UNAVAILABLE);
        if (scope.isEmpty() || scopeTag == null) return base.withReason(Reason.NO_SCOPE);
        if (!settings.policy().scopeRules().containsKey(scope)) return base.withReason(Reason.SCOPE_NOT_ALLOWED);
        var attachment = TimelessAPI.getCommonAttachmentIndex(scopeId).orElse(null);
        if (attachment == null || attachment.getType() != AttachmentType.SCOPE) {
            return base.withReason(Reason.SCOPE_DATA_UNAVAILABLE);
        }
        if (!(player instanceof IGunOperator operator)) return base.withReason(Reason.GUN_OPERATOR_UNAVAILABLE);
        // Synced flags can lag behind an aim/release packet until the next TACZ tick.
        // The authoritative server holder already contains the state used for this shot.
        boolean aiming;
        if (player.level().isClientSide()) {
            aiming = operator.getSynIsAiming();
        } else {
            var holder = operator.getDataHolder();
            if (holder == null) return base.withReason(Reason.GUN_OPERATOR_UNAVAILABLE);
            aiming = holder.isAiming;
        }
        Decision decision = Eligibility.evaluate(settings.policy(), new WeaponState(true, aiming,
                gunId.toString(), scope, zoomNumber));
        return decision.eligible() && !tacz.longrangesniping.block.RangefinderAccess.nearby(player)
                ? decision.withReason(Reason.NO_NEARBY_RANGEFINDER) : decision;
    }

    /** Called after TACZ initializes the real projectile; no guessed speed or second property evaluation. */
    public static Optional<ShotContext> inspectNewBullet(EntityKineticBullet bullet) {
        if (bullet.level().isClientSide() || bullet.isRemoved() || bullet.tickCount != 0
                || !(bullet.getOwner() instanceof Player player)) {
            return Optional.empty();
        }
        Decision decision = evaluate(player);
        if (!decision.eligible() || !decision.gunId().equals(String.valueOf(bullet.getGunId()))) {
            return Optional.empty();
        }
        return Optional.of(new ShotContext(player.getUUID(), player.level().dimension().location(),
                decision, BulletParameters.capture(bullet)));
    }

    public record ShotContext(java.util.UUID shooterId, ResourceLocation dimension,
                              Decision eligibilityAtFire, BulletParameters parameters) {}
}
