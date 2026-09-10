package tacz.longrangesniping.mixin;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
    @Accessor("entityMap") Int2ObjectMap<?> taczlrs$trackers();
    @Invoker("isChunkTracked") boolean taczlrs$isChunkTracked(ServerPlayer player, int x, int z);
    @Invoker("getPlayerViewDistance") int taczlrs$viewDistance(ServerPlayer player);
}
