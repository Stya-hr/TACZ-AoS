package tacz.aos.loot;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import org.jetbrains.annotations.Nullable;
import tacz.aos.TaczArtOfSniping;

import java.util.ArrayList;
import java.util.List;

/** Associates audited synchronous mod drops with the exact fatal damage source. */
@EventBusSubscriber(modid = TaczArtOfSniping.MOD_ID)
public final class SpecialLootCollection {
    private static final ThreadLocal<DeathScope> ACTIVE = new ThreadLocal<>();
    private static final StackWalker WALKER = StackWalker.getInstance();

    private SpecialLootCollection() {}

    private static final class Installed {
        static final boolean ANY = ModList.get().isLoaded("lightmanscurrency")
                || ModList.get().isLoaded("collectorsalbum");
    }

    @Nullable
    public static DeathScope begin(LivingEntity victim, DamageSource source) {
        if (victim.level().isClientSide() || !Installed.ANY) return null;
        var previous = ACTIVE.get();
        var collector = BeltLootCollection.collector(victim, source);
        if (collector == null && previous == null) return null;
        // Even an ineligible nested death must hide the outer victim's collector.
        var scope = new DeathScope(victim, collector, previous);
        ACTIVE.set(scope);
        return scope;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onJoin(EntityJoinLevelEvent event) {
        var scope = ACTIVE.get();
        if (scope == null || scope.collector == null || event.loadedFromDisk()
                || event.getLevel() != scope.victim.level() || !(event.getEntity() instanceof ItemEntity item)) return;
        // Only inspect new items during an eligible death. No spatial search, item-ID guesses,
        // delayed collection window, or stack walking on ordinary entity/chunk loading.
        if (WALKER.walk(frames -> frames.anyMatch(SpecialLootCollection::isKnownProducer))) {
            scope.drops.add(item);
        }
    }

    private static boolean isKnownProducer(StackWalker.StackFrame frame) {
        String owner = frame.getClassName();
        String method = frame.getMethodName();
        return owner.equals("io.github.lightman314.lightmanscurrency.common.loot.LootManager")
                && method.equals("DropEntityLoot")
                || owner.equals("io.github.lightman314.lightmanscurrency.common.EventHandler")
                && method.equals("playerDrops")
                // Mixin renames this injected handler; the original suffix remains in its name.
                || owner.equals("net.minecraft.world.entity.LivingEntity")
                && method.endsWith("collectorsAlbum$die");
    }

    public static final class DeathScope implements AutoCloseable {
        private final LivingEntity victim;
        @Nullable private final ServerPlayer collector;
        @Nullable private final DeathScope previous;
        private final List<ItemEntity> drops = new ArrayList<>();
        private boolean completed;

        private DeathScope(LivingEntity victim, @Nullable ServerPlayer collector, @Nullable DeathScope previous) {
            this.victim = victim;
            this.collector = collector;
            this.previous = previous;
        }

        public void complete(boolean dead) { completed = dead; }

        @Override
        public void close() {
            if (previous == null) ACTIVE.remove();
            else ACTIVE.set(previous);
            // Wait until insertion/death processing finishes: changing position inside the join
            // event can disagree with the entity section being inserted by the caller.
            if (completed && collector != null && collector.isAlive() && collector.level() == victim.level()) {
                for (var drop : drops) {
                    if (!drop.isRemoved() && drop.isAddedToWorld() && drop.level() == collector.level()) {
                        drop.setPos(collector.position());
                        drop.setDeltaMovement(Vec3.ZERO);
                    }
                }
            }
            drops.clear();
        }
    }
}
