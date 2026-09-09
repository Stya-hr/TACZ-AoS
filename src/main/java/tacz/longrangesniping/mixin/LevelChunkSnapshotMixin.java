package tacz.longrangesniping.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import tacz.longrangesniping.terrain.TerrainSnapshots;

@Mixin(LevelChunk.class)
public abstract class LevelChunkSnapshotMixin {
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void taczlrs$invalidateSnapshot(BlockPos pos, BlockState state, boolean moving,
                                          CallbackInfoReturnable<BlockState> result) {
        if (result.getReturnValue() != null && result.getReturnValue() != state) {
            TerrainSnapshots.INSTANCE.changed((LevelChunk) (Object) this, pos);
        }
    }
}
