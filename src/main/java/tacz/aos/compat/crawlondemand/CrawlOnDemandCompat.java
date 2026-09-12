package tacz.aos.compat.crawlondemand;

import java.lang.reflect.Method;
import net.minecraft.world.entity.player.Player;
import net.neoforged.fml.ModList;
import tacz.aos.TaczArtOfSniping;

/** Reads the mod's local/server action flag, not the swimming pose it uses for rendering. */
public final class CrawlOnDemandCompat {
    private CrawlOnDemandCompat() {}

    private static volatile boolean failed;

    public static boolean isCrawling(Player player) {
        var access = Holder.ACCESS;
        if (access == null || failed) return false;
        try {
            return (boolean) access.invoke(null, player);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            disable(e);
            return false;
        }
    }

    // Resolve once, only after mod initialization and only when a qualifying shot/animation needs it.
    private static final class Holder {
        private static final Method ACCESS = resolve();
    }

    private static Method resolve() {
        if (!ModList.get().isLoaded("crawlondemand")) return null;
        try {
            return Class.forName("me.talilon.minecraft.crawlondemand.platform.CrawlStateManager")
                    .getMethod("isCrawling", Player.class);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            disable(e);
            return null;
        }
    }

    private static synchronized void disable(Throwable reason) {
        if (failed) return;
        failed = true;
        TaczArtOfSniping.LOGGER.warn("Crawl on Demand API unavailable; disabling its prone integration. Other prone integrations remain supported.", reason);
    }
}
