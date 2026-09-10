package tacz.longrangesniping.mixin;

import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Set;

@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public interface TrackedEntityAccessor {
    @Invoker("updatePlayer") void taczlrs$updatePlayer(ServerPlayer player);
    @Invoker("removePlayer") void taczlrs$removePlayer(ServerPlayer player);
    @Accessor("serverEntity") ServerEntity taczlrs$serverEntity();
    @Accessor("seenBy") Set<ServerPlayerConnection> taczlrs$seenBy();
    @Invoker("getEffectiveRange") int taczlrs$effectiveRange();
}
