package tacz.aos.test;

import com.mojang.authlib.GameProfile;
import com.tacz.guns.entity.EntityKineticBullet;
import com.tacz.guns.init.ModDamageTypes;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import tacz.aos.TaczArtOfSniping;
import tacz.aos.ballistics.ScopedDeathMessages;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

@GameTestHolder(TaczArtOfSniping.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ScopedDeathMessageGameTests {
    @GameTest(setupTicks = 20, template = "ballistics", batch = "scoped_death_messages")
    public static void managedFatalShotReplacesSinglePublicMessageWithStraightDistance(GameTestHelper h) throws Exception {
        try (var b = new Battle(h)) {
            ScopedDeathMessages.duringShot(b.bullet, () -> b.victim.player.hurt(b.source, 100));
            h.assertTrue(!b.victim.player.isAlive(), "Test projectile damage did not kill player");
            var messages = b.observer.chat();
            h.assertTrue(messages.size() == 1, "Expected one public death message, got " + messages);
            var message = (TranslatableContents) messages.get(0).getContents();
            h.assertTrue(message.getKey().equals(ScopedDeathMessages.KEY), "Native death message was not replaced");
            var args = message.getArgs();
            h.assertTrue(((Component)args[0]).getString().equals(b.victim.player.getDisplayName().getString())
                    && ((Component)args[1]).getString().equals(b.killer.player.getDisplayName().getString()), "Victim and killer reversed");
            h.assertTrue(args[2].equals("302.7"), "Expected 3D distance, not horizontal distance: " + args[2]);
            h.assertTrue(b.victim.packets.stream().filter(p -> p instanceof ClientboundPlayerCombatKillPacket).count() == 1,
                    "Native death screen packet was lost or duplicated");
            h.assertTrue(ScopedDeathMessages.replacement(b.victim.player,b.source) == null, "Shot context leaked");
        }
        h.succeed();
    }

    @GameTest(setupTicks = 20, template = "ballistics", batch = "scoped_death_messages")
    public static void ordinaryTaczAndLaterEnvironmentalDeathKeepTheirOwnMessages(GameTestHelper h) throws Exception {
        try (var b = new Battle(h)) {
            b.victim.player.hurt(b.source, 100);
            h.assertTrue(b.observer.chat().size() == 1 && !custom(b.observer.chat().get(0)), "Ordinary TACZ death was replaced");
        }
        try (var b = new Battle(h)) {
            ScopedDeathMessages.duringShot(b.bullet, () -> b.victim.player.hurt(b.source, 1));
            h.assertTrue(b.observer.chat().isEmpty(), "Nonfatal shot announced a kill");
            b.victim.player.invulnerableTime = 0;
            b.victim.player.hurt(h.getLevel().damageSources().generic(), 100);
            h.assertTrue(b.observer.chat().size() == 1 && !custom(b.observer.chat().get(0)), "Later death inherited sniper wording");
        }
        h.succeed();
    }

    @GameTest(setupTicks = 20, template = "ballistics", batch = "scoped_death_messages")
    public static void canceledDeathAndDisabledDeathMessagesDoNotBroadcast(GameTestHelper h) throws Exception {
        try (var b = new Battle(h)) {
            Consumer<LivingDeathEvent> cancel = event -> { if (event.getEntity() == b.victim.player) event.setCanceled(true); };
            MinecraftForge.EVENT_BUS.addListener(cancel);
            try { ScopedDeathMessages.duringShot(b.bullet, () -> b.victim.player.hurt(b.source,100)); }
            finally { MinecraftForge.EVENT_BUS.unregister(cancel); }
            h.assertTrue(b.observer.chat().isEmpty(), "Canceled death still announced a kill");
        }
        try (var b = new Battle(h)) {
            h.getLevel().getGameRules().getRule(GameRules.RULE_SHOWDEATHMESSAGES).set(false,h.getLevel().getServer());
            ScopedDeathMessages.duringShot(b.bullet, () -> b.victim.player.hurt(b.source,100));
            h.assertTrue(b.observer.chat().isEmpty(), "Death-message gamerule was bypassed");
        }
        h.succeed();
    }

    private static boolean custom(Component text) {
        return text.getContents() instanceof TranslatableContents t && t.getKey().equals(ScopedDeathMessages.KEY);
    }
    private record Viewer(ServerPlayer player, List<Packet<?>> packets) {
        List<Component> chat() {
            return packets.stream().filter(p -> p instanceof ClientboundSystemChatPacket)
                    .map(p -> ((ClientboundSystemChatPacket)p).content()).toList();
        }
    }
    private static Viewer player(GameTestHelper h, String name, Vec3 relative) throws Exception {
        var level = h.getLevel();
        var player = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(),name));
        player.setPos(h.absoluteVec(relative));
        var spawn = ServerPlayer.class.getDeclaredField("spawnInvulnerableTime");
        spawn.setAccessible(true); spawn.setInt(player,0);
        var packets = new ArrayList<Packet<?>>();
        player.connection = new ServerGamePacketListenerImpl(level.getServer(), new Connection(PacketFlow.SERVERBOUND), player) {
            @Override public void send(Packet<?> p) { packets.add(p); }
            @Override public void send(Packet<?> p, PacketSendListener listener) { packets.add(p); }
        };
        serverPlayers(h).add(player);
        level.players().add(player);
        return new Viewer(player,packets);
    }
    @SuppressWarnings("unchecked")
    private static List<ServerPlayer> serverPlayers(GameTestHelper h) {
        try {
            var field = net.minecraft.server.players.PlayerList.class.getDeclaredField("players");
            field.setAccessible(true);
            return (List<ServerPlayer>)field.get(h.getLevel().getServer().getPlayerList());
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static final class Battle implements AutoCloseable {
        final GameTestHelper h;
        final Viewer killer, victim, observer;
        final EntityKineticBullet bullet;
        final DamageSource source;
        final boolean pvp, messages;
        Battle(GameTestHelper h) throws Exception {
            this.h = h;
            var server = h.getLevel().getServer();
            pvp = server.isPvpAllowed(); server.setPvpAllowed(true);
            messages = h.getLevel().getGameRules().getBoolean(GameRules.RULE_SHOWDEATHMESSAGES);
            h.getLevel().getGameRules().getRule(GameRules.RULE_SHOWDEATHMESSAGES).set(true,server);
            killer = player(h,"aos-shooter",new Vec3(4,5,4));
            victim = player(h,"aos-victim",new Vec3(304,45,4));
            observer = player(h,"aos-observer",new Vec3(8,5,8));
            var fixture = new LocalBallisticsGameTests.Fixture(h,killer.player);
            bullet = fixture.bullet(new Vec3(4,5,4),new Vec3(20,0,0));
            source = ModDamageTypes.Sources.bullet(h.getLevel().registryAccess(),bullet,killer.player,false);
            for (var v : List.of(killer,victim,observer)) v.packets.clear();
        }
        @Override public void close() {
            bullet.discard();
            for (var v : List.of(killer,victim,observer)) {
                h.getLevel().players().remove(v.player);
                serverPlayers(h).remove(v.player);
            }
            h.getLevel().getServer().setPvpAllowed(pvp);
            h.getLevel().getGameRules().getRule(GameRules.RULE_SHOWDEATHMESSAGES).set(messages,h.getLevel().getServer());
        }
    }
}
