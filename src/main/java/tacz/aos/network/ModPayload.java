package tacz.aos.network;

import net.minecraft.resources.ResourceLocation;

/** Typed messages carried by the Forge channel; never used as vanilla custom payloads. */
public interface ModPayload {
    Type<? extends ModPayload> type();
    record Type<T extends ModPayload>(ResourceLocation id, Class<T> javaType) {}
}
