package tacz.aos.loot;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/** Captures only the P points emitted synchronously by the dying fairy. No scans or altered XP. */
@Mod.EventBusSubscriber(modid = "tacz_aos")
public final class MaidPowerPointCollection {
    private record Scope(ServerPlayer player, java.util.List<net.minecraft.world.entity.Entity> points) {}
    private static final ThreadLocal<Scope> ACTIVE = new ThreadLocal<>();
    private MaidPowerPointCollection() {}
    public static void duringDeathTick(LivingEntity victim, Runnable deathTick) {
        Scope previous = ACTIVE.get();
        var player = victim instanceof PowerPointLootOwner owner ? owner.tacz_aos$powerPointCollector() : null;
        Scope scope = player != null && player.isAlive() && !player.isRemoved() && player.level() == victim.level()
                ? new Scope(player, new java.util.ArrayList<>()) : null;
        if (scope == null) ACTIVE.remove(); else ACTIVE.set(scope);
        try {
            deathTick.run();
            // Move after insertion so the entity section index follows the new position too.
            if (scope != null) for (var point : scope.points()) {
                if (!point.isRemoved() && point.isAddedToWorld() && point.level() == player.level()) {
                    point.setPos(player.position()); point.setDeltaMovement(Vec3.ZERO);
                }
            }
        } finally { if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous); }
    }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void spawn(EntityJoinLevelEvent event) {
        var scope = ACTIVE.get();
        if (scope == null || event.loadedFromDisk() || event.getLevel() != scope.player().level()) return;
        var id = ForgeRegistries.ENTITY_TYPES.getKey(event.getEntity().getType());
        if (id != null && id.getNamespace().equals("touhou_little_maid") && id.getPath().equals("power_point")) {
            scope.points().add(event.getEntity());
        }
    }
}
