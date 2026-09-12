package tacz.aos.compat.tacz;

import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.entity.EntityKineticBullet;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import tacz.aos.config.ServerConfig;

/** Per-launch rule; never modifies shared gun/attachment data or script-owned vectors. */
public final class GunSpread {
    private GunSpread() {}

    public static boolean zeroWhileProne(Projectile projectile) {
        if (!(projectile instanceof EntityKineticBullet bullet)
                || bullet.tickCount != 0 || bullet.isRemoved()
                || !(bullet.getOwner() instanceof Player player)) return false;
        var settings = ServerConfig.current();
        return settings.available() && settings.policy().enabled()
                && settings.policy().gunIds().contains(String.valueOf(bullet.getGunId()))
                // Any supported mod's action flag qualifies; a swimming/forced low pose alone does not.
                && (IGunOperator.fromLivingEntity(player).getDataHolder().isCrawling
                    || tacz.aos.compat.parcool.ParCoolCompat.isCrawling(player)
                    || tacz.aos.compat.crawlondemand.CrawlOnDemandCompat.isCrawling(player));
    }
}
