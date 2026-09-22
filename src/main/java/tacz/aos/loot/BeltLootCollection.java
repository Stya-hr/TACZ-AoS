package tacz.aos.loot;

import com.tacz.guns.entity.EntityKineticBullet;
import com.tacz.guns.init.ModDamageTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import org.jetbrains.annotations.Nullable;
import tacz.aos.TaczArtOfSniping;
import tacz.aos.block.ModContent;
import tacz.aos.compat.curios.CuriosBeltCompat;

@EventBusSubscriber(modid = TaczArtOfSniping.MOD_ID)
public final class BeltLootCollection {
    private BeltLootCollection() {}

    public static boolean isEquipped(Player player) {
        return player.getItemBySlot(EquipmentSlot.LEGS).is(ModContent.TACTICAL_COLLECTION_BELT.get())
                || ModList.get().isLoaded("curios") && CuriosBeltCompat.isEquipped(player);
    }

    @Nullable
    public static ServerPlayer collector(LivingEntity victim, DamageSource source) {
        // Read the fatal damage source, not the held gun or the last player to damage the victim.
        // The actual projectile also covers TACZ's magic damage and explosive gun rounds.
        boolean gun = source.getDirectEntity() instanceof EntityKineticBullet || source.is(ModDamageTypes.BULLETS_TAG);
        if (gun && source.getEntity() instanceof ServerPlayer player && player != victim
                && player.isAlive() && player.level() == victim.level() && isEquipped(player)) return player;
        return null;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void drops(LivingDropsEvent event) {
        var player = collector(event.getEntity(), event.getSource());
        if (player == null) return;
        // Move the real drops after other loot modifiers. Keep counts, components and pickup rules.
        for (var drop : event.getDrops()) {
            drop.setPos(player.position());
            drop.setDeltaMovement(Vec3.ZERO);
        }
    }
}
