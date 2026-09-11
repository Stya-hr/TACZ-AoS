package tacz.aos.ballistics;

import com.tacz.guns.entity.EntityKineticBullet;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

import java.util.Locale;
import java.util.function.Supplier;

/** Only synchronous damage from a currently managed flight may replace the native death message. */
public final class ScopedDeathMessages {
    public static final String KEY = "death.attack.tacz_aos.sniped";
    private static final ThreadLocal<EntityKineticBullet> CURRENT = new ThreadLocal<>();
    private ScopedDeathMessages() {}

    public static <T> T duringShot(EntityKineticBullet bullet, Supplier<T> action) {
        var previous = CURRENT.get();
        CURRENT.set(bullet);
        try { return action.get(); }
        finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    public static Component replacement(LivingEntity victim, DamageSource source) {
        var bullet = CURRENT.get();
        if (bullet == null || source.getDirectEntity() != bullet || !(victim instanceof ServerPlayer target)
                || !(source.getEntity() instanceof ServerPlayer shooter) || bullet.getOwner() != shooter
                || shooter == target || shooter.level() != target.level()) return null;
        double distance = shooter.getEyePosition().distanceTo(target.getEyePosition());
        if (!Double.isFinite(distance)) return null;
        return Component.translatableWithFallback(KEY, "%1$s was sniped by %2$s from %3$s blocks away.",
                target.getDisplayName(), shooter.getDisplayName(),
                String.format(Locale.ROOT, "%.1f", distance));
    }
}
