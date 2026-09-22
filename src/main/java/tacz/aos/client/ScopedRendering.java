package tacz.aos.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import tacz.aos.TaczArtOfSniping;
import tacz.aos.config.ServerConfig;
import tacz.aos.network.ScopeNetworking;
import tacz.aos.network.ScopeTargetsPayload;

import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/** Draws the very same tracked entity objects; no clone entities, client chunks, local AI or collision authority. */
@EventBusSubscriber(modid = TaczArtOfSniping.MOD_ID, value = Dist.CLIENT)
public final class ScopedRendering {
    private record Pose(ScopeTargetsPayload.Target target, Vec3 from, float fromYaw, float fromPitch, long received) {}
    private static final Map<Integer, Pose> targets = new HashMap<>();
    private static final Map<tacz.aos.network.ScopeEntityRepairPayload.Target, Long> missing = new HashMap<>();
    private static long nextRepair;
    private static ClientLevel level;
    private static long tick, received;
    private static int interval = 4;
    private static boolean active;
    private static double zoom;
    private static final Set<Integer> extraDrawn = new HashSet<>();
    private static BufferBuilder byteBuffer;
    private static MultiBufferSource.BufferSource buffers;

    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ScopeNetworking.setClientReceiver(ScopedRendering::receive);
        });
    }

    private static void receive(ScopeTargetsPayload payload) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !minecraft.level.dimension().location().equals(payload.dimension())) return;
        if (level != minecraft.level) { targets.clear(); missing.clear(); nextRepair=0; level = minecraft.level; }
        var next = new HashMap<Integer, Pose>();
        for (var target : payload.targets()) {
            Entity entity = level.getEntity(target.id());
            Vec3 from = entity != null && entity.getUUID().equals(target.uuid()) ? entity.position() : new Vec3(target.x(), target.y(), target.z());
            next.put(target.id(), new Pose(target, from, entity == null ? target.yaw() : entity.getYRot(),
                    entity == null ? target.pitch() : entity.getXRot(), tick));
        }
        targets.clear(); targets.putAll(next);
        interval = payload.interval();
        received = tick;
    }

    public static boolean active() { return active; }
    public static int targetCount() { return targets.size(); }
    public static int extraDraws() { return extraDrawn.size(); }
    public static boolean drewExtra(int id) { return extraDrawn.contains(id); }
    public static boolean hasRealClientChunk(Entity entity) {
        // ClientLevel.hasChunk/hasChunkAt are not a residency test; inspect the actual client storage.
        return entity.level() instanceof ClientLevel client
                && client.getChunkSource().getChunk(entity.chunkPosition().x, entity.chunkPosition().z, ChunkStatus.FULL, false) != null;
    }
    public static boolean suppressExtra(Entity entity) {
        var pose = targets.get(entity.getId());
        return !active && pose != null && pose.target.extra() && pose.target.uuid().equals(entity.getUUID());
    }

    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
        tick++;
        var minecraft = Minecraft.getInstance();
        if (minecraft.level != level) { targets.clear(); missing.clear(); nextRepair=0; level = minecraft.level; }
        active = false;
        if (minecraft.player != null && level != null && ServerConfig.current().scopeRendering().enabled()) {
            var decision = TaczClientBridge.evaluateForDisplay(minecraft.player);
            active = decision.eligible(); zoom = decision.zoom();
        }
        if (tick - received > Math.max(40, interval * 3)) targets.clear();
        if (!active || level == null) { missing.clear(); return; }
        repairMissing();
        for (var pose : targets.values()) {
            var entity = level.getEntity(pose.target.id());
            if (entity == null || !entity.getUUID().equals(pose.target.uuid())
                    || entity.isRemoved() || entity.isAlwaysTicking() || hasRealClientChunk(entity)) continue;
            // Client entities outside received chunks do not tick, so native lerpTo alone would leave them frozen.
            // Advance only visual pose/animation, never Mob.tick/baseTick/AI or world collision.
            double alpha = net.minecraft.util.Mth.clamp((double) (tick - pose.received) / interval, 0, 1);
            entity.setOldPosAndRot();
            entity.setPos(pose.from.lerp(new Vec3(pose.target.x(), pose.target.y(), pose.target.z()), alpha));
            entity.setYRot(Mth.rotLerp((float) alpha, pose.fromYaw, pose.target.yaw()));
            entity.setXRot(Mth.rotLerp((float) alpha, pose.fromPitch, pose.target.pitch()));
            if (entity instanceof LivingEntity living) {
                living.yHeadRotO = living.yHeadRot; living.yHeadRot = pose.target.headYaw();
                living.yBodyRotO = living.yBodyRot; living.yBodyRot = pose.target.bodyYaw();
                float moved = (float) Math.sqrt(Mth.square(entity.getX() - entity.xOld) + Mth.square(entity.getZ() - entity.zOld));
                living.walkAnimation.update(Math.min(moved * 4, 1), 0.4f);
                if (living.hurtTime > 0) living.hurtTime--;
            }
            entity.tickCount++;
        }
    }

    private static void repairMissing() {
        var absent = new HashSet<tacz.aos.network.ScopeEntityRepairPayload.Target>();
        var request = new java.util.ArrayList<tacz.aos.network.ScopeEntityRepairPayload.Target>();
        for (var pose:targets.values()) {
            var t=pose.target; var entity=level.getEntity(t.id());
            if (entity!=null && !entity.isRemoved() && entity.getUUID().equals(t.uuid())) continue;
            var key=new tacz.aos.network.ScopeEntityRepairPayload.Target(t.id(),t.uuid());
            absent.add(key);
            long since=missing.computeIfAbsent(key,ignored -> tick);
            if (tick>=nextRepair && tick-since>=tacz.aos.network.ScopeEntityRepairPayload.GRACE_TICKS
                    && request.size()<tacz.aos.network.ScopeEntityRepairPayload.MAX_TARGETS) request.add(key);
        }
        missing.keySet().retainAll(absent);
        if (!request.isEmpty()) {
            tacz.aos.network.PacketDelivery.sendToServer(new tacz.aos.network.ScopeEntityRepairPayload(level.dimension().location(),request));
            nextRepair=tick+tacz.aos.network.ScopeEntityRepairPayload.INTERVAL_TICKS;
            for (var key:request) missing.put(key,tick);
        }
    }

    @SubscribeEvent public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        extraDrawn.clear();
        ScopedOcclusion.beginFrame();
        if (!active || level == null) return;
        ScopeViewReporter.report(event, zoom, tick);
        if (targets.isEmpty()) return;
        var minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level != level) return;
        var camera = event.getCamera();
        Vec3 eye = camera.getPosition();
        var dispatcher = minecraft.getEntityRenderDispatcher();
        float partial = event.getPartialTick();
        float fogStart = RenderSystem.getShaderFogStart(), fogEnd = RenderSystem.getShaderFogEnd();
        if (buffers == null) { byteBuffer = new BufferBuilder(262144); buffers = MultiBufferSource.immediate(byteBuffer); }
        try {
            // Dedicated buffers prevent our fog range from changing already queued vanilla entity draws.
            RenderSystem.setShaderFogStart(ServerConfig.current().maxDistance());
            RenderSystem.setShaderFogEnd(ServerConfig.current().maxDistance() + 64);
            ScopedOcclusion.renderDepth(event.getPoseStack(),eye);
            for (var pose : targets.values()) {
                Entity entity = level.getEntity(pose.target.id());
                if (entity == null || !entity.getUUID().equals(pose.target.uuid()) || entity.isRemoved()
                        || entity.isInvisibleTo(minecraft.player) || !event.getFrustum().isVisible(entity.getBoundingBox())) continue;
                // The server already bounded the observation. Let the actual projection clip at the screen edge,
                // rather than hiding an authorized entity inside the lens with a second, narrower aim cone.
                if (entity.getBoundingBox().getCenter().distanceToSqr(eye) > Math.pow(ServerConfig.current().maxDistance(), 2)) continue;
                // Vanilla already drew this object if both its distance/frustum and section checks succeeded.
                if (dispatcher.shouldRender(entity, event.getFrustum(), eye.x, eye.y, eye.z)
                        && (level.isOutsideBuildHeight(entity.blockPosition()) || minecraft.levelRenderer.isChunkCompiled(entity.blockPosition()))) continue;
                double x = Mth.lerp(partial, entity.xOld, entity.getX()) - eye.x;
                double y = Mth.lerp(partial, entity.yOld, entity.getY()) - eye.y;
                double z = Mth.lerp(partial, entity.zOld, entity.getZ()) - eye.z;
                if (!ScopedOcclusion.ready(entity)) continue;
                dispatcher.render(entity, x, y, z, Mth.rotLerp(partial, entity.yRotO, entity.getYRot()), partial,
                        event.getPoseStack(), buffers, pose.target.light());
                extraDrawn.add(entity.getId());
            }
            buffers.endBatch();
        } finally {
            RenderSystem.setShaderFogStart(fogStart); RenderSystem.setShaderFogEnd(fogEnd);
        }
    }

    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        ScopeViewReporter.reset();
        extraDrawn.clear();
        targets.clear(); missing.clear(); nextRepair=0; level = null; active = false;
        if (byteBuffer != null) {  byteBuffer = null; buffers = null; }
    }
}
