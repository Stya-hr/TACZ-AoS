package tacz.aos.compat.parcool;

import java.lang.reflect.Method;
import net.minecraft.world.entity.player.Player;
import net.neoforged.fml.ModList;
import tacz.aos.TaczArtOfSniping;

/** Optional read-only bridge to ParCool's synchronized action state, never its visual swimming pose. */
public final class ParCoolCompat {
    private ParCoolCompat() {}

    private static volatile boolean failed;

    public static boolean isCrawling(Player player) {
        var access = Holder.ACCESS;
        if (access == null || failed) return false;
        try {
            Object ability = access.ability().invoke(null, player);
            if (ability == null) return false;
            Object crawl = access.action().invoke(ability, access.crawlKey());
            return crawl != null && (boolean) access.isDoing().invoke(crawl);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            disable(e);
            return false;
        }
    }

    // Resolve once after mods have initialized, only if a qualifying non-TACZ crawl shot needs it.
    private static final class Holder {
        private static final Access ACCESS = resolve();
    }

    private record Access(Method ability, Method action, Object crawlKey, Method isDoing) {}

    private static Access resolve() {
        if (!ModList.get().isLoaded("parcool")) return null;
        try {
            Class<?> ability;
            try {
                ability = Class.forName("com.alrex.parcool.common.Parkourability");
            } catch (ClassNotFoundException legacy) {
                ability = Class.forName("com.alrex.parcool.common.attachment.common.Parkourability");
                Class<?> crawl = Class.forName("com.alrex.parcool.common.action.impl.Crawl");
                return new Access(ability.getMethod("get", Player.class), ability.getMethod("get", Class.class),
                        crawl, crawl.getMethod("isDoing"));
            }
            Class<?> entry = Class.forName("com.alrex.parcool.api.action.ActionEntry");
            Object crawl = Class.forName("com.alrex.parcool.common.action.ParCoolActions").getField("CRAWL").get(null);
            Method doing = Class.forName("com.alrex.parcool.api.action.ContinuableAction").getMethod("isDoing");
            return new Access(ability.getMethod("get", Player.class), ability.getMethod("get", entry), crawl, doing);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            disable(e);
            return null;
        }
    }

    private static synchronized void disable(Throwable reason) {
        if (failed) return;
        failed = true;
        TaczArtOfSniping.LOGGER.warn("ParCool crawl API unavailable; disabling ParCool zero-spread integration. TACZ crawling remains supported.", reason);
    }
}
