package dev.swordkingdom.horde.network;

import dev.swordkingdom.horde.HordeConfig;
import dev.swordkingdom.horde.HordeMod;
import dev.swordkingdom.horde.crowd.CrowdData;
import dev.swordkingdom.horde.crowd.CrowdManager;
import dev.swordkingdom.horde.crowd.CrowdManagers;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 按固定频率，把每个玩家附近的群集量化成快照发回去。 */
public final class CrowdSync {

    private static final Map<UUID, Integer> LAST_COUNT = new HashMap<>();

    private CrowdSync() {}

    public static void tick(MinecraftServer server) {
        int interval = HordeConfig.COMMON.snapshotIntervalTicks.get();
        if (server.getTickCount() % interval != 0) {
            return;
        }
        float range = HordeConfig.COMMON.snapshotRange.get().floatValue();

        for (ServerLevel level : server.getAllLevels()) {
            CrowdManager m = CrowdManagers.getIfPresent(level.dimension());
            if (m == null) {
                continue;
            }
            List<ServerPlayer> players = level.players();
            for (int i = 0; i < players.size(); i++) {
                sendToPlayer(m, players.get(i), range);
            }
        }
    }

    public static void forget(UUID player) {
        LAST_COUNT.remove(player);
    }

    private static void sendToPlayer(CrowdManager m, ServerPlayer player, float range) {
        int budget = HordeConfig.COMMON.syncBudgetPerPlayer.get();
        int n = 0;
        if (m.aliveCount() > 0) {
            n = m.collectVisible((float) player.getX(), (float) player.getZ(), range, budget);
        }

        Integer last = LAST_COUNT.get(player.getUUID());
        int lastN = last == null ? -1 : last;
        if (n == 0 && lastN == 0) {
            return;
        }
        LAST_COUNT.put(player.getUUID(), n);

        // 快照按单包上限拆成多个包：群集总数不受单包大小限制
        int chunkCount = n <= 0 ? 1 : (n + CrowdSnapshotPacket.UNITS_PER_PACKET - 1)
                / CrowdSnapshotPacket.UNITS_PER_PACKET;

        double ox = Math.floor(player.getX());
        double oy = Math.floor(player.getY());
        double oz = Math.floor(player.getZ());

        if (n == 0) {
            HordeNetwork.sendToPlayer(new CrowdSnapshotPacket(m.dimension().location(), ox, oy, oz, 0,
                    new ResourceLocation[0], new short[0], new short[0], new short[0], new short[0],
                    new byte[0], new short[0], new short[0], new byte[0], new byte[0],
                    new byte[0], new byte[0], new byte[0], new short[0]), player);
            return;
        }

        CrowdData d = m.data();
        ResourceLocation[] types = m.typeIds().toArray(new ResourceLocation[0]);
        short[] ids = new short[n];
        short[] x = new short[n];
        short[] y = new short[n];
        short[] z = new short[n];
        byte[] yaw = new byte[n];
        short[] walkPos = new short[n];
        short[] walkSpeed = new short[n];
        byte[] headYaw = new byte[n];
        byte[] headPitch = new byte[n];
        byte[] attackAnim = new byte[n];
        byte[] hurt = new byte[n];
        byte[] teamFlags = new byte[n];
        short[] age = new short[n];
        byte[] action = new byte[n];
        byte[] actionTick = new byte[n];

        for (int i = 0; i < n; i++) {
            int s = m.outSlots[i];
            ids[i] = (short) (s & 0xFFFF);
            // 服务端存的是 AABB 最小角，这里按**该类型自己的宽度**换算回实体中心再下发
            int typeIdx = d.typeIndex[s] & 0x1F;
            CrowdManager.TypeStats stats = m.statsOf(typeIdx);
            float halfWidth = stats != null ? stats.halfWidth : CrowdManager.HALF_WIDTH;
            x[i] = (short) Math.round((d.posX[s] + halfWidth - ox) * 32.0D);
            y[i] = (short) Math.round((d.posY[s] - oy) * 32.0D);
            z[i] = (short) Math.round((d.posZ[s] + halfWidth - oz) * 32.0D);

            yaw[i] = quant360(d.yaw[s]);
            // 行走相位 [0, WALK_WRAP)
            float wp = d.walkPos[s] / CrowdManager.WALK_WRAP;
            walkPos[i] = quantUnit(clamp01(wp));
            walkSpeed[i] = quantUnit(clamp01(d.walkSpeed[s]));
            headYaw[i] = quantSigned(d.headYaw[s], 180.0F);
            headPitch[i] = quantSigned(d.headPitch[s], 90.0F);
            // 挥动进度：与原版 LivingEntity.updateSwingTime() 一致 ——
            // swinging 时 attackAnim = swingTime / 挥动时长（这里 6 tick），否则恒为 0
            float at = d.swinging[s] ? (d.swingTime[s] / (float) CrowdManager.SWING_DURATION) : 0.0F;
            attackAnim[i] = (byte) ((int) (clamp01(at) * 255.0F) & 0xFF);
            // 受击直接给 tick 数（原版 hurtTime 就是 tick）
            hurt[i] = (byte) Math.max(0, Math.min(255, d.hurtTime[s]));
            // 年龄 = 原版 tickCount，驱动 setupAnim 的 ageInTicks
            age[i] = (short) Math.max(0, Math.min(32767, d.age[s]));

            // 动作/技能动画状态：客户端据此播放与模拟完全相同的动作
            action[i] = d.action[s];
            actionTick[i] = (byte) Math.min(255, d.actionTick[s]);

            int team = d.team[s] & 0x03;
            int typeIndex = d.typeIndex[s] & 0x1F;
            // bit0-1 阵营 | bit2-6 实体类型索引(5 bit, 最多 32 种) | bit7 敌对状态
            teamFlags[i] = (byte) (team | (typeIndex << 2) | 0x80);
        }

        for (int c = 0; c < chunkCount; c++) {
            int offset = c * CrowdSnapshotPacket.UNITS_PER_PACKET;
            int len = Math.min(CrowdSnapshotPacket.UNITS_PER_PACKET, n - offset);
            HordeNetwork.sendToPlayer(new CrowdSnapshotPacket(m.dimension().location(), ox, oy, oz,
                    offset, len, c, chunkCount, n, types, ids, x, y, z, yaw, walkPos, walkSpeed,
                    headYaw, headPitch, attackAnim, hurt, teamFlags, age, action, actionTick), player);
        }

        if (n < m.aliveCount()) {
            logLimited(m, n, budget, range);
        }
    }

    private static final java.util.Map<ResourceLocation, Long> LAST_LIMIT_LOG = new HashMap<>();

    /** 只有真的被配置限制住时才提示（默认不限制，所以正常情况下不会打印） */
    private static void logLimited(CrowdManager m, int n, int budget, float range) {
        long now = System.currentTimeMillis();
        ResourceLocation key = m.dimension().location();
        Long last = LAST_LIMIT_LOG.get(key);
        if (last != null && now - last < 5000L) {
            return;
        }
        LAST_LIMIT_LOG.put(key, now);
        HordeMod.LOGGER.warn("[horde] {} 存活 {} 只，本次只同步了 {} 只"
                        + "（原因：{}）—— 把 config 里的 maxSyncDistance / maxSyncedUnitsPerPlayer 设为 0 即可不限制",
                key, m.aliveCount(), n,
                budget > 0 ? "maxSyncedUnitsPerPlayer=" + budget : "maxSyncDistance=" + (int) range);
    }

    /** 把 [0,1] 量化成 16 bit（65536 级） */
    private static short quantUnit(float v) {
        int q = Math.round(v * 65535.0F);
        if (q < 0) {
            q = 0;
        }
        if (q > 65535) {
            q = 65535;
        }
        return (short) q;
    }

    private static byte quant360(float degrees) {
        return (byte) (Math.round(degrees / 360.0F * 256.0F) & 0xFF);
    }

    /** 把 [-max, max] 映射到 [-127, 127]。 */
    private static byte quantSigned(float value, float max) {
        float t = value / max;
        if (t < -1f) t = -1f;
        if (t > 1f) t = 1f;
        return (byte) Math.round(t * 127.0F);
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }
}
