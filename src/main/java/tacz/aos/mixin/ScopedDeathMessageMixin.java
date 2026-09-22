package tacz.aos.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.world.damagesource.CombatEntry;
import net.minecraft.world.damagesource.CombatTracker;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import tacz.aos.ballistics.ScopedDeathMessages;

import java.util.List;

/** Reuse vanilla's single death broadcast, including showDeathMessages and team visibility rules. */
@Mixin(CombatTracker.class)
public abstract class ScopedDeathMessageMixin {
    @Shadow @Final private LivingEntity mob;
    @Shadow @Final private List<CombatEntry> entries;

    @Inject(method = "getDeathMessage", at = @At("HEAD"), cancellable = true)
    private void tacz_aos$sniperDeath(CallbackInfoReturnable<Component> cir) {
        if (entries.isEmpty()) return;
        var message = ScopedDeathMessages.replacement(mob, entries.get(entries.size() - 1).source());
        if (message != null) cir.setReturnValue(message);
    }
}
