package tacz.aos.client;

import com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator;
import com.tacz.guns.api.item.IGun;
import net.minecraft.client.Minecraft;
import tacz.aos.compat.parcool.ParCoolCompat;
import tacz.aos.compat.crawlondemand.CrawlOnDemandCompat;
import tacz.aos.config.ServerConfig;

/** Local visual counterpart of prone zero spread; independent of scope ID, zoom and rangefinder. */
public final class ProneAimStability {
    private ProneAimStability() {}

    public static boolean active() {
        var mc = Minecraft.getInstance();
        var player = mc.player;
        var settings = ServerConfig.current();
        if (player == null || !player.isAlive() || player.isSpectator() || mc.screen != null
                || mc.getCameraEntity() != player || !mc.options.getCameraType().isFirstPerson()
                || !settings.available() || !settings.policy().enabled()) return false;
        var stack = player.getMainHandItem();
        var gun = IGun.getIGunOrNull(stack);
        if (gun == null || !settings.policy().gunIds().contains(gun.getGunId(stack).toString())) return false;
        var operator = IClientPlayerGunOperator.fromLocalPlayer(player);
        // Server-side ShooterDataHolder is not the local client's crawl input state.
        return operator.isAim() && (operator.isCrawl() || ParCoolCompat.isCrawling(player)
                || CrawlOnDemandCompat.isCrawling(player));
    }
}
