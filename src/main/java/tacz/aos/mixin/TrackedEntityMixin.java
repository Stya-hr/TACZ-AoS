package tacz.aos.mixin;

import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tacz.aos.scoping.ScopeSubscriptions;

import java.util.Set;

/** Add an authorized observer to the existing tracker; vanilla remains responsible for packets and removal. */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class TrackedEntityMixin {
    @Shadow @Final private Entity entity;
    @Shadow @Final private ServerEntity serverEntity;
    @Shadow @Final private Set<ServerPlayerConnection> seenBy;

    @Inject(method = "updatePlayer", at = @At("HEAD"), cancellable = true)
    private void tacz_aos$scopeObserver(ServerPlayer player, CallbackInfo callback) {
        if (ScopeSubscriptions.INSTANCE.authorized(player, entity)) {
            if (seenBy.add(player.connection)) serverEntity.addPairing(player);
            callback.cancel();
        }
    }
}
