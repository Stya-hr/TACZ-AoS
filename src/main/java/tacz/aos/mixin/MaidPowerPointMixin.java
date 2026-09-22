package tacz.aos.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.item.EntityPowerPoint;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import tacz.aos.loot.PowerPointLootOwner;

/** Optional: keep the original P-point amount, timing and pickup implementation. */
@Pseudo
@Mixin(targets = "com.github.tartaricacid.touhoulittlemaid.entity.monster.IHasPowerPoint", remap = false)
public interface MaidPowerPointMixin {
    @WrapOperation(method = "dropPowerPoint", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean tacz_aos$movePowerPoint(Level level, Entity entity, Operation<Boolean> original,
                                           @Local(argsOnly = true) LivingEntity victim) {
        if (entity instanceof EntityPowerPoint && victim instanceof PowerPointLootOwner owner) {
            var player = owner.tacz_aos$powerPointCollector();
            if (player != null && player.isAlive() && !player.isRemoved() && player.level() == level) {
                entity.setPos(player.position());
                entity.setDeltaMovement(Vec3.ZERO);
            }
        }
        return original.call(level, entity);
    }
}
