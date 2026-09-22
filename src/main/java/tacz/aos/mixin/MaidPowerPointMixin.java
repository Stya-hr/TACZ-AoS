package tacz.aos.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.monster.EntityFairy;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import net.minecraft.world.entity.LivingEntity;
import tacz.aos.loot.MaidPowerPointCollection;

/** The Forge Mixin version cannot inject into interface default methods. Scope the fairy's death tick instead. */
@Pseudo
@Mixin(EntityFairy.class)
public abstract class MaidPowerPointMixin {
    @WrapMethod(method = "tickDeath")
    private void tacz_aos$powerPointDeathTick(Operation<Void> original) {
        MaidPowerPointCollection.duringDeathTick((LivingEntity)(Object)this, original::call);
    }
}
