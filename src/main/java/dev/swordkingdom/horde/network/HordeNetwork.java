package dev.swordkingdom.horde.network;

import dev.swordkingdom.horde.HordeMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.DistExecutor;
import net.minecraft.network.Connection;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/** 网络通道注册与发送。 */
public final class HordeNetwork {

    private static final String PROTOCOL = "1";

    /**
     * 网络通道**懒创建**。
     *
     * 为什么要懒：{@code SimpleChannel} 的构造会在 Forge 事件总线上注册监听器，
     * 而事件总线的监听器扫描要求注册表与事件系统已经就绪。
     * 如果把它放在 {@code HordeNetwork} 的静态字段上，那么任何一次
     * 「因为别的原因加载了 HordeNetwork」都会连带构造通道 ——
     * 在离线 harness（我们会主动 {@code Bootstrap.bootStrap()} 来用注册表判断投掷物基类）
     * 这种场景下就会炸在 Forge 的 NetworkHooks.init 上。
     * 改成懒持有者之后，只有真正要发包时才构造通道，行为与线上完全一致。
     */
    private static final class Holder {
        static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
                new ResourceLocation(HordeMod.MODID, "main"),
                () -> PROTOCOL,
                PROTOCOL::equals,
                PROTOCOL::equals);
    }

    public static SimpleChannel channel() {
        return Holder.CHANNEL;
    }

    private HordeNetwork() {}

    public static void register() {
        SimpleChannel ch = channel();
        ch.messageBuilder(CrowdSnapshotPacket.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(CrowdSnapshotPacket::encode)
                .decoder(CrowdSnapshotPacket::new)
                .consumerMainThread((msg, ctx) -> {
                    DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT,
                            () -> () -> dev.swordkingdom.horde.client.ClientCrowdData.accept(msg));
                    ctx.get().setPacketHandled(true);
                })
                .add();

        // ---- 覆盖表（游戏内实时配置）----
        ch.messageBuilder(EditPackets.Request.class, 1, NetworkDirection.PLAY_TO_SERVER)
                .encoder(EditPackets.Request::encode)
                .decoder(EditPackets.Request::new)
                .consumerMainThread((msg, ctx) -> {
                    // 只有管理员能看/改；回整表给请求者
                    ServerPlayer player = ctx.get().getSender();
                    ctx.get().setPacketHandled(true);
                    if (player != null && player.hasPermissions(2)) {
                        sendToPlayer(new EditPackets.Sync(EditPackets.writeTable()), player);
                    }
                })
                .add();

        ch.messageBuilder(EditPackets.Set.class, 2, NetworkDirection.PLAY_TO_SERVER)
                .encoder(EditPackets.Set::encode)
                .decoder(EditPackets.Set::new)
                .consumerMainThread((msg, ctx) -> {
                    ServerPlayer player = ctx.get().getSender();
                    ctx.get().setPacketHandled(true);
                    if (player == null || !player.hasPermissions(2)) {
                        return;
                    }
                    dev.swordkingdom.horde.edit.EditService.apply(msg, player);
                })
                .add();

        ch.messageBuilder(EditPackets.Sync.class, 3, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(EditPackets.Sync::encode)
                .decoder(EditPackets.Sync::new)
                .consumerMainThread((msg, ctx) -> {
                    DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT,
                            () -> () -> dev.swordkingdom.horde.client.ClientOverrides.accept(msg));
                    ctx.get().setPacketHandled(true);
                })
                .add();

        ch.messageBuilder(EditPackets.OpenGui.class, 4, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(EditPackets.OpenGui::encode)
                .decoder(EditPackets.OpenGui::new)
                .consumerMainThread((msg, ctx) -> {
                    DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT,
                            () -> () -> dev.swordkingdom.horde.client.ClientOverrides.openScreen());
                    ctx.get().setPacketHandled(true);
                })
                .add();

        // ---- 虚拟投掷物（单独一条链路，不动上面已经稳定的群集快照）----
        ch.messageBuilder(ProjectileSnapshotPacket.class, 5, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(ProjectileSnapshotPacket::encode)
                .decoder(ProjectileSnapshotPacket::new)
                .consumerMainThread((msg, ctx) -> {
                    DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT,
                            () -> () -> dev.swordkingdom.horde.client.ClientProjectileData.accept(msg));
                    ctx.get().setPacketHandled(true);
                })
                .add();

        ch.messageBuilder(ProjectileTypesPacket.class, 6, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(ProjectileTypesPacket::encode)
                .decoder(ProjectileTypesPacket::new)
                .consumerMainThread((msg, ctx) -> {
                    DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT,
                            () -> () -> dev.swordkingdom.horde.client.ClientProjectileData.acceptTypes(msg));
                    ctx.get().setPacketHandled(true);
                })
                .add();
    }

    public static void sendToServer(Object packet) {
        channel().sendToServer(packet);
    }

    public static void sendToPlayer(Object packet, ServerPlayer player) {
        Connection connection = player.connection.connection;
        if (connection != null && connection.isConnected()) {
            channel().sendTo(packet, connection, NetworkDirection.PLAY_TO_CLIENT);
        }
    }

    public static void sendToPlayer(CrowdSnapshotPacket packet, ServerPlayer player) {
        Connection connection = player.connection.connection;
        if (connection != null && connection.isConnected()) {
            channel().sendTo(packet, connection, NetworkDirection.PLAY_TO_CLIENT);
        }
    }
}
