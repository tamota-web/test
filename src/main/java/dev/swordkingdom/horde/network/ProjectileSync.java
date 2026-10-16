package dev.swordkingdom.horde.network;

import dev.swordkingdom.horde.HordeConfig;
import dev.swordkingdom.horde.HordeMod;
import dev.swordkingdom.horde.crowd.ProjectileData;
import dev.swordkingdom.horde.crowd.ProjectileManager;
import dev.swordkingdom.horde.crowd.ProjectileManagers;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 虚拟投掷物的同步：与 {@link CrowdSync} 同节奏、同拆包思路，
 * 但**单独一条链路**（理由见 {@link ProjectileSnapshotPacket} 的类注释）。
 *
 * 为什么不让客户端自己按速度外推：投掷物每 tick 都在被方块碰撞与命中结算改写，
 * 外推一旦和真实轨迹偏一点就会「穿墙/穿人」看起来很假。
 * 这里改成「服务端逐帧下发位置 + 客户端在两帧之间插值」——
 * 和原版实体同步 + lerpTo 是同一套做法，10Hz 也不会卡顿。
 */
public final class ProjectileSync {

    /** 上一次给该玩家发过的类型表版本（版本没变就不重复发类型表） */
    private static final Map<UUID, Integer> LAST_TYPE_VERSION = new HashMap<>();
    private static final Map<UUID, Integer> LAST_COUNT = new HashMap<>();

    private ProjectileSync() {}

    public static void forget(UUID player) {
        LAST_TYPE_VERSION.remove(player);
        LAST_COUNT.remove(player);
    }

    public static void tick(MinecraftServer server) {
        int interval = HordeConfig.COMMON.snapshotIntervalTicks.get();
        if (server.getTickCount() % interval != 0) {
            return;
        }
        float range = HordeConfig.COMMON.snapshotRange.get().floatValue();
        for (ServerLevel level : server.getAllLevels()) {
            ProjectileManager m = ProjectileManagers.getIfPresent(level.dimension());
            if (m == null) {
                continue;
            }
            List<ServerPlayer> players = level.players();
            for (int i = 0; i < players.size(); i++) {
                sendToPlayer(m, players.get(i), range);
            }
        }
    }

    private static void sendToPlayer(ProjectileManager m, ServerPlayer player, float range) {
        int budget = HordeConfig.COMMON.syncBudgetPerPlayer.get();
        int n = m.aliveCount() == 0 ? 0
                : m.collectVisible((float) player.getX(), (float) player.getZ(), range, budget);

        // 类型表只在变化时重发（客户端要按 id 烘焙每个投掷物的模型）
        Integer lastVersion = LAST_TYPE_VERSION.get(player.getUUID());
        boolean typesChanged = lastVersion == null || lastVersion != m.typeVersion();
        if (typesChanged) {
            HordeNetwork.sendToPlayer(new ProjectileTypesPacket(m.dimension(),
                    m.typeIds().toArray(new ResourceLocation[0])), player);
            LAST_TYPE_VERSION.put(player.getUUID(), m.typeVersion());
        }

        Integer last = LAST_COUNT.get(player.getUUID());
        int lastN = last == null ? -1 : last;
        if (n == 0 && lastN == 0 && !typesChanged) {
            return;
        }
        LAST_COUNT.put(player.getUUID(), n);

        double ox = Math.floor(player.getX());
        double oy = Math.floor(player.getY());
        double oz = Math.floor(player.getZ());

        if (n == 0) {
            HordeNetwork.sendToPlayer(new ProjectileSnapshotPacket(m.dimension(), ox, oy, oz,
                    0, 0, 0, 1, 0, new ResourceLocation[0],
                    new short[0], new short[0], new short[0], new short[0],
                    new byte[0], new byte[0], new byte[0], new int[0]), player);
            return;
        }

        ProjectileData d = m.data();
        ResourceLocation[] types = m.typeIds().toArray(new ResourceLocation[0]);
        short[] id = new short[n];
        short[] x = new short[n];
        short[] y = new short[n];
        short[] z = new short[n];
        byte[] vx = new byte[n];
        byte[] vy = new byte[n];
        byte[] vz = new byte[n];
        int[] packed = new int[n];

        for (int i = 0; i < n; i++) {
            int s = m.outSlots[i];
            id[i] = (short) (s & 0xFFFF);
            x[i] = (short) Math.round((d.posX[s] - ox) * 32.0D);
            y[i] = (short) Math.round((d.posY[s] - oy) * 32.0D);
            z[i] = (short) Math.round((d.posZ[s] - oz) * 32.0D);
            // 速度量化成 1/128：客户端用它推朝向/俯仰，位置仍然逐帧同步，所以这个精度足够
            vx[i] = quantSpeed(d.velX[s]);
            vy[i] = quantSpeed(d.velY[s]);
            vz[i] = quantSpeed(d.velZ[s]);
            packed[i] = ProjectileSnapshotPacket.pack(
                    d.yawOf(s), d.pitchOf(s), Math.min(255, d.lifeLeft[s]), true,
                    d.typeIndex[s] & 0x1F, d.team[s] & 0xFF);
        }

        int perPacket = ProjectileSnapshotPacket.PROJECTILES_PER_PACKET;
        int chunkCount = (n + perPacket - 1) / perPacket;
        for (int c = 0; c < chunkCount; c++) {
            int offset = c * perPacket;
            int len = Math.min(perPacket, n - offset);
            HordeNetwork.sendToPlayer(new ProjectileSnapshotPacket(m.dimension(), ox, oy, oz,
                    offset, len, c, chunkCount, n, types, id, x, y, z, vx, vy, vz, packed), player);
        }

        if ((player.tickCount % 200) == 0) {
            HordeMod.LOGGER.info("[horde] 投掷物同步：{} 枚（类型表 {} 项，版本 {}）",
                    n, types.length, m.typeVersion());
        }
    }

    /** 速度量化：1/128 格/tick，夹到 byte 范围 */
    private static byte quantSpeed(float v) {
        int q = Math.round(v * 128.0F);
        if (q > 127) {
            q = 127;
        }
        if (q < -128) {
            q = -128;
        }
        return (byte) q;
    }
}
