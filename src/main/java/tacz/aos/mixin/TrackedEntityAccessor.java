package tacz.aos.mixin;

import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Set;

@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public interface TrackedEntityAccessor {
    @Invoker("updatePlayer") void tacz_aos$updatePlayer(ServerPlayer player);
    @Invoker("removePlayer") void tacz_aos$removePlayer(ServerPlayer player);
    @Accessor("serverEntity") ServerEntity tacz_aos$serverEntity();
    @Accessor("seenBy") Set<ServerPlayerConnection> tacz_aos$seenBy();
    @Invoker("getEffectiveRange") int tacz_aos$effectiveRange();
}
