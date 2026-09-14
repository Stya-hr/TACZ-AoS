package tacz.aos.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import tacz.aos.loot.SpecialLootCollection;

/** Wraps the complete death, including other mods' injected handlers and NeoForge events. */
@Mixin(LivingEntity.class)
public abstract class BeltSpecialLootMixin {
    @Shadow public boolean dead;

    @WrapMethod(method = "die")
    private void tacz_aos$specialDrops(DamageSource source, Operation<Void> original) {
        try (var scope = SpecialLootCollection.begin((LivingEntity)(Object)this, source)) {
            original.call(source);
            if (scope != null) scope.complete(dead);
        }
    }
}
