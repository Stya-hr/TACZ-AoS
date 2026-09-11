package tacz.aos.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator;
import com.tacz.guns.client.model.BedrockAttachmentModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import tacz.aos.client.ScopeRangeHud;

/** Observe the center of the very circle TACZ draws. No changes to stencil, vertices or rendering state. */
@Mixin(value = BedrockAttachmentModel.class, remap = false)
public abstract class ScopeRangeLensMixin {
    @Shadow private float scopeViewRadiusModifier;
    @Shadow private java.util.List<java.util.List<com.tacz.guns.client.model.bedrock.BedrockPart>> ocularNodePaths;
    @Shadow private java.util.List<Boolean> isScopeOcular;
    @Unique private ScopeRangeHud.LensVertices tacz_aos$vertices;

    @Redirect(method = "renderTempPart", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;getBuffer(Lnet/minecraft/client/renderer/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"))
    private com.mojang.blaze3d.vertex.VertexConsumer tacz_aos$observeMesh(net.minecraft.client.renderer.MultiBufferSource.BufferSource buffers,
            RenderType requested, PoseStack stack, ItemDisplayContext context, RenderType type, int light, int overlay,
            java.util.List<com.tacz.guns.client.model.bedrock.BedrockPart> path) {
        var original = buffers.getBuffer(requested);
        tacz_aos$vertices = null;
        var mc = Minecraft.getInstance();
        int index = ocularNodePaths.indexOf(path);
        // Legacy single-view models (e.g. LPVO) call their lens "ocular", not "ocular_scope".
        // TACZ leaves that node's per-ocular scope flag false; the model-wide flag still identifies it.
        boolean opticalMesh = index >= 0 && (isScopeOcular.get(index)
                || ocularNodePaths.size() == 1 && ((BedrockAttachmentModel) (Object) this).isScope());
        if (mc.player == null || !context.firstPerson() || !opticalMesh
                || !tacz.aos.client.TaczClientBridge.usesOpticalView(mc.player)) return original;
        float progress = IClientPlayerGunOperator.fromLocalPlayer(mc.player)
                .getClientAimingProgress(mc.getTimer().getGameTimeDeltaPartialTick(false));
        if (progress < .98f) return original;
        return tacz_aos$vertices = new ScopeRangeHud.LensVertices(original);
    }

    @Inject(method = "renderTempPart", at = @At("RETURN"))
    private void tacz_aos$finishMesh(PoseStack stack, ItemDisplayContext context, RenderType type, int light, int overlay,
            java.util.List<com.tacz.guns.client.model.bedrock.BedrockPart> path, CallbackInfo ci) {
        if (tacz_aos$vertices != null) { tacz_aos$vertices.finish(); tacz_aos$vertices = null; }
    }

    @ModifyArgs(method = "renderOcularAndDivision", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/BufferBuilder;addVertex(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;FFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;",
            ordinal = 0))
    private void tacz_aos$captureLens(Args args, PoseStack stack, ItemDisplayContext context, RenderType type,
                                    int light, int overlay, boolean sightsOnly) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || !context.firstPerson()) return;
        float progress = IClientPlayerGunOperator.fromLocalPlayer(mc.player)
                .getClientAimingProgress(mc.getTimer().getGameTimeDeltaPartialTick(false));
        if (progress < .98f) return;
        ScopeRangeHud.capture(args.get(0), args.get(1), args.get(2), args.get(3), 80f * scopeViewRadiusModifier * progress);
    }
}
