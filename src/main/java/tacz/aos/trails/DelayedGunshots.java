package tacz.aos.trails;

import com.tacz.guns.network.message.ServerMessageSound;
import com.tacz.guns.sound.SoundManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import tacz.aos.network.PacketDelivery;
import tacz.aos.config.ServerConfig;
import tacz.aos.network.DistantGunshotPayload;

import java.util.*;

/** Server-only delayed sound notifications. No terrain access and no dependency on the bullet's lifetime. */
public final class DelayedGunshots {
    public static final DelayedGunshots INSTANCE = new DelayedGunshots();
    public static final int MAX_PENDING = 4096, MAX_DELIVERIES_PER_TICK = 64;
    private record Mark(long tick, ResourceLocation gun) {}
    private record Arrival(long tick, ServerPlayer listener, Vec3 origin, float volume, float pitch) {}
    private static final class State {
        final Map<UUID, Mark> accepted = new HashMap<>();
        final PriorityQueue<Arrival> pending = new PriorityQueue<>(Comparator.comparingLong(Arrival::tick));
    }
    private final Map<ServerLevel, State> levels = new IdentityHashMap<>();
    private DelayedGunshots() {}

    public void accepted(ServerLevel level, UUID shooter, ResourceLocation gun) {
        if (ServerConfig.distantGunshots().enabled())
            levels.computeIfAbsent(level, ignored -> new State()).accepted.put(shooter, new Mark(level.getGameTime(), gun));
    }

    /** Called after TACZ creates its bullets, at its normal third-person sound dispatch. */
    public boolean replace(LivingEntity shooter, int nativeDistance, ResourceLocation gun, ResourceLocation display,
                           String name, float volume, float pitch) {
        if (!(shooter instanceof ServerPlayer) || !(shooter.level() instanceof ServerLevel level)
                || !(SoundManager.SHOOT_3P_SOUND.equals(name) || SoundManager.SILENCE_3P_SOUND.equals(name))) return false;
        var config = ServerConfig.distantGunshots();
        var state = levels.get(level);
        var mark = state == null ? null : state.accepted.get(shooter.getUUID());
        if (!config.enabled() || mark == null || mark.tick != level.getGameTime() || !mark.gun.equals(gun)) return false;
        state.accepted.remove(shooter.getUUID());
        Vec3 origin = shooter.getEyePosition();
        // Preserve TACZ's native near audience, including its chunk-tracking and distance restrictions.
        var nativeMessage = new ServerMessageSound(shooter.getId(), gun, display, name, volume, pitch, nativeDistance);
        var pos = shooter.blockPosition();
        for (var listener : level.getChunkSource().chunkMap.getPlayers(new ChunkPos(pos), false)) {
            if (listener != shooter && listener.distanceToSqr(pos.getX(), pos.getY(), pos.getZ()) < (double) nativeDistance * nativeDistance
                    && listener.getEyePosition().distanceTo(origin) < config.minDistance())
                com.tacz.guns.network.NetworkHandler.CHANNEL.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> listener), nativeMessage);
        }
        int maxDistance = config.maxDistance() == 0 ? ServerConfig.current().maxDistance() : config.maxDistance();
        double gain = config.volume() * (SoundManager.SILENCE_3P_SOUND.equals(name) ? .2 : 1);
        for (var listener : level.players()) {
            if (state.pending.size() >= MAX_PENDING) break;
            if (listener == shooter || !listener.isAlive() || listener.isSpectator()) continue;
            double distance = listener.getEyePosition().distanceTo(origin);
            if (distance < config.minDistance() || distance > maxDistance) continue;
            state.pending.add(new Arrival(level.getGameTime() + GunshotAcoustics.delayTicks(distance, config.speed()),
                    listener, origin, GunshotAcoustics.volume(distance, gain), net.minecraft.util.Mth.clamp(pitch, .8f, 1.2f)));
        }
        return true;
    }

    @SubscribeEvent public void tick(TickEvent.LevelTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
        if (event.level instanceof ServerLevel level) deliver(level, level.getGameTime());
    }
    public void deliver(ServerLevel level, long now) {
        var state = levels.get(level);
        if (state == null) return;
        if (!ServerConfig.distantGunshots().enabled()) { levels.remove(level); return; }
        state.accepted.values().removeIf(mark -> mark.tick < now);
        var listeners = new HashSet<>(level.players());
        for (int i = 0; i < MAX_DELIVERIES_PER_TICK && !state.pending.isEmpty() && state.pending.peek().tick <= now; i++) {
            var arrival = state.pending.remove();
            var listener = arrival.listener;
            if (now - arrival.tick > 20 || !listeners.contains(listener) || !listener.isAlive()
                    || listener.isSpectator() || listener.serverLevel() != level) continue;
            PacketDelivery.sendToPlayer(listener, new DistantGunshotPayload(level.dimension().location(),
                    arrival.origin.x, arrival.origin.y, arrival.origin.z, arrival.volume, arrival.pitch));
        }
        if (state.accepted.isEmpty() && state.pending.isEmpty()) levels.remove(level);
    }
    private void cancel(UUID listener) {
        for (var state : levels.values()) state.pending.removeIf(a -> a.listener.getUUID().equals(listener));
    }
    @SubscribeEvent public void logout(PlayerEvent.PlayerLoggedOutEvent event) { cancel(event.getEntity().getUUID()); }
    @SubscribeEvent public void dimension(PlayerEvent.PlayerChangedDimensionEvent event) { cancel(event.getEntity().getUUID()); }
    @SubscribeEvent public void unload(LevelEvent.Unload event) { levels.remove(event.getLevel()); }
    @SubscribeEvent public void stop(ServerStoppedEvent event) { levels.clear(); }
}
