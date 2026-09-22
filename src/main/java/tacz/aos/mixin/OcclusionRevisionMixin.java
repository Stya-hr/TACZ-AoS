package tacz.aos.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import tacz.aos.occlusion.OcclusionRevision;

@Mixin(LevelChunk.class)
public abstract class OcclusionRevisionMixin implements OcclusionRevision {
    @Unique private long[] tacz_aos$sectionRevisions;

    @Override public long tacz_aos$occlusionRevision(int section) {
        if(tacz_aos$sectionRevisions==null)
            tacz_aos$sectionRevisions=new long[((LevelChunk)(Object)this).getSections().length];
        return tacz_aos$sectionRevisions[section];
    }

    @Inject(method="setBlockState",at=@At("RETURN"))
    private void tacz_aos$changed(BlockPos pos,BlockState state,boolean moving,CallbackInfoReturnable<BlockState> result) {
        if(tacz_aos$sectionRevisions!=null && result.getReturnValue()!=null && result.getReturnValue()!=state)
            tacz_aos$sectionRevisions[((LevelChunk)(Object)this).getSectionIndex(pos.getY())]++;
    }
}
