package tacz.aos.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import tacz.aos.loot.SpecialLootCollection;
import tacz.aos.loot.BeltLootCollection;
import tacz.aos.loot.PowerPointLootOwner;

/** Wraps the complete death, including other mods' injected handlers and Forge events. */
@Mixin(LivingEntity.class)
public abstract class BeltSpecialLootMixin implements PowerPointLootOwner {
    @Shadow public boolean dead;
    @Unique @Nullable private ServerPlayer tacz_aos$powerCollector;

    @Override
    public @Nullable ServerPlayer tacz_aos$powerPointCollector() { return tacz_aos$powerCollector; }

    @WrapMethod(method = "die")
    private void tacz_aos$specialDrops(DamageSource source, Operation<Void> original) {
        var victim = (LivingEntity)(Object)this;
        boolean wasDead = dead;
        try (var scope = SpecialLootCollection.begin(victim, source)) {
            original.call(source);
            if (scope != null) scope.complete(dead);
            // P points appear in tickDeath, 20 death ticks later. Do not rely on
            // getLastDamageSource(), which can expire while distant mobs stop ticking.
            if (!wasDead && dead && !victim.level().isClientSide()
                    && ModList.get().isLoaded("touhou_little_maid")
                    && BuiltInRegistries.ENTITY_TYPE.getKey(victim.getType()).getNamespace().equals("touhou_little_maid")) {
                tacz_aos$powerCollector = BeltLootCollection.collector(victim, source);
            }
        }
    }
}
