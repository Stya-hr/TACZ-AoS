package tacz.aos.loot;

import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/** Transient ownership on the dying entity, rather than a world-wide delayed loot search. */
public interface PowerPointLootOwner {
    @Nullable ServerPlayer tacz_aos$powerPointCollector();
}
