package tacz.longrangesniping.scoping;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.Tags;
import tacz.longrangesniping.TaczLongRangeSniping;
import tacz.longrangesniping.config.ServerConfig;

import java.util.ArrayList;
import java.util.List;

/** Class-based inclusion; boss tags supplement inheritance because Minecraft has no universal Boss base class. */
public final class ScopedEntityPolicy {
    private static List<String> previous;
    private static List<Class<?>> excluded = List.of();
    private static boolean valid = true;
    private ScopedEntityPolicy() {}

    public static boolean allows(Entity entity, boolean players) {
        return !entity.isSpectator() && (entity instanceof Mob || players && entity instanceof Player)
                && !(entity instanceof EnderDragon || entity instanceof WitherBoss || entity instanceof Warden)
                && !entity.getType().is(Tags.EntityTypes.BOSSES);
    }

    public static boolean configured(Entity entity, ServerConfig.ScopeRendering config) {
        if (!config.excludedClasses().equals(previous)) {
            previous = config.excludedClasses();
            var classes = new ArrayList<Class<?>>();
            valid = true;
            for (String name : previous) {
                try { classes.add(Class.forName(name, false, ScopedEntityPolicy.class.getClassLoader())); }
                catch (ClassNotFoundException | LinkageError failure) {
                    valid = false;
                    TaczLongRangeSniping.LOGGER.error("Unknown scope-rendering excluded class {}; extra tracking disabled", name);
                }
            }
            excluded = List.copyOf(classes);
        }
        return valid && allows(entity, config.players()) && excluded.stream().noneMatch(type -> type.isInstance(entity));
    }
}
