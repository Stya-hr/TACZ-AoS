package tacz.aos.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import tacz.aos.loot.BeltLootCollection;

/** Change only the XP spawn position, after the normal gamerules and XP events have run. */
@Mixin(LivingEntity.class)
public abstract class BeltExperienceMixin {
    @Unique private ServerPlayer tacz_aos$lootCollector;

    @WrapMethod(method = "dropAllDeathLoot")
    private void tacz_aos$deathLoot(ServerLevel level, DamageSource source, Operation<Void> original) {
        var previous = tacz_aos$lootCollector;
        tacz_aos$lootCollector = BeltLootCollection.collector((LivingEntity)(Object)this, source);
        try { original.call(level, source); }
        finally { tacz_aos$lootCollector = previous; }
    }

    @WrapOperation(method = "dropExperience", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ExperienceOrb;award(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/phys/Vec3;I)V"))
    private void tacz_aos$experienceAtWearer(ServerLevel level, Vec3 position, int amount, Operation<Void> original) {
        var player = tacz_aos$lootCollector;
        original.call(level, player != null && player.level() == level ? player.position() : position, amount);
    }
}
