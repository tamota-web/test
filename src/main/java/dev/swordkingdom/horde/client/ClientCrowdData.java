package dev.swordkingdom.horde.client;

import dev.swordkingdom.horde.crowd.CrowdManager;
import dev.swordkingdom.horde.network.CrowdSnapshotPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 客户端群集数据（按维度），带**原版式插值**。
 *
 * 服务端以 20Hz 下发快照，客户端在两次快照之间用 {@link #alpha()} 做线性插值，
 * 于是 60+ FPS 下的移动与骨骼动画都是连续的 —— 这正是原版实体
 * （服务端 20Hz 同步 + 客户端 lerpTo）看起来顺滑的原因。
 *
 * 为了让「上一帧」和「当前帧」的同一个单位能对上，快照里带了单位 id。
 */
public final class ClientCrowdData {

    /**
     * 每单位在 GPU 侧的 float 数：位置(3) + (朝向, 阵营, 受击)(3)。
     * 骨骼姿态走另一张「姿态纹理」（每实例一行、每骨骼一个 texel），不占实例属性。
     */
    public static final int FLOATS_PER_UNIT = 6;

    private static final Map<ResourceLocation, ClientCrowdData> BY_DIMENSION = new HashMap<>();

    public final ResourceLocation dimension;

    private int capacity;

    // 当前帧
    public float[] curX = new float[0];
    public float[] curY = new float[0];
    public float[] curZ = new float[0];
    public float[] curYaw = new float[0];
    public float[] curWalkPos = new float[0];
    public float[] curWalkSpeed = new float[0];
    public float[] curHeadYaw = new float[0];
    public float[] curHeadPitch = new float[0];
    public float[] curAttack = new float[0];
    public float[] curHurt = new float[0];
    /** 单位年龄（tick，对应原版 tickCount） */
    public float[] curAge = new float[0];
    public float[] curTeam = new float[0];
    public float[] curAggro = new float[0];
    /** 正在播放的动作/技能动画（0 = 无）与其已播 tick 数 */
    public float[] curAction = new float[0];
    public float[] curActionTick = new float[0];
    /** 每单位使用的实体类型索引 */
    public int[] curType = new int[0];
    /** 当前快照的实体类型表 */
    public ResourceLocation[] types = new ResourceLocation[0];

    // 上一帧
    private float[] prvX = new float[0];
    private float[] prvY = new float[0];
    private float[] prvZ = new float[0];
    private float[] prvYaw = new float[0];
    private float[] prvWalkPos = new float[0];
    private float[] prvWalkSpeed = new float[0];
    private float[] prvHeadYaw = new float[0];
    private float[] prvHeadPitch = new float[0];
    private float[] prvAttack = new float[0];
    private float[] prvHurt = new float[0];
    private float[] prvAge = new float[0];

    /** 上一帧数据对应当前 seq 才有效（避免每次快照清空大数组） */
    private int[] prvStamp = new int[0];
    private int seq;

    /** 本帧在场的单位 id */
    public int[] ids = new int[0];
    public int count;

    /** 正在拼装的这一帧（一帧可能由多个包组成） */
    private int[] pendingIds = new int[0];
    private int pendingN;
    private int pendingChunk;
    private int pendingTotal = -1;

    private long lastNanos;
    private float intervalNanos = 50_000_000.0F;

    private ClientCrowdData(ResourceLocation dimension) {
        this.dimension = dimension;
    }

    public static ClientCrowdData get(ResourceLocation dimension) {
        return BY_DIMENSION.computeIfAbsent(dimension, ClientCrowdData::new);
    }

    public static ClientCrowdData forCurrentLevel() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        return BY_DIMENSION.get(level.dimension().location());
    }

    /** 插值进度 [0,1]：距上一帧快照经过时间 / 快照间隔。 */
    public float alpha() {
        if (lastNanos == 0L) {
            return 0.0F;
        }
        float a = (float) (System.nanoTime() - lastNanos) / intervalNanos;
        if (a < 0.0F) {
            return 0.0F;
        }
        return a > 1.0F ? 1.0F : a;
    }

    /**
     * 收一个快照包。
     *
     * 一帧快照可能被拆成多个包（{@code chunkIndex}/{@code chunkCount}），
     * 这里把同一帧的包拼起来，收齐后提交 —— 于是群集总数不受单包大小限制。
     */
    public static void accept(CrowdSnapshotPacket packet) {
        ClientCrowdData d = get(packet.dimension);
        if (packet.chunkIndex == 0 || d.pendingTotal < 0) {
            d.beginFrame(packet);
        }
        d.appendChunk(packet);
        if (packet.chunkIndex >= packet.chunkCount - 1
                || d.pendingN >= d.pendingTotal) {
            d.commitFrame();
        }
    }

    /** 开一帧：把上一帧在场的单位值搬到「上一帧」，并清空拼装缓冲。 */
    private void beginFrame(CrowdSnapshotPacket packet) {
        types = packet.types;
        seq++;
        for (int k = 0; k < count; k++) {
            int id = ids[k];
            copyCurToPrv(id);
            prvStamp[id] = seq;
        }
        pendingN = 0;
        pendingChunk = 0;
        pendingTotal = packet.totalUnits > 0 ? packet.totalUnits : packet.count;
    }

    private void appendChunk(CrowdSnapshotPacket packet) {
        int n = packet.count;
        if (n <= 0) {
            return;
        }
        int maxId = 0;
        for (int i = 0; i < n; i++) {
            int id = packet.id[i] & 0xFFFF;
            if (id > maxId) {
                maxId = id;
            }
        }
        ensure(maxId + 1);
        if (pendingIds.length < pendingN + n) {
            pendingIds = Arrays.copyOf(pendingIds, Math.max(pendingN + n, pendingIds.length * 2));
        }
        double ox = packet.originX;
        double oy = packet.originY;
        double oz = packet.originZ;
        for (int i = 0; i < n; i++) {
            int id = packet.id[i] & 0xFFFF;
            pendingIds[pendingN++] = id;

            curX[id] = (float) (ox + packet.x[i] / 32.0D);
            curY[id] = (float) (oy + packet.y[i] / 32.0D);
            curZ[id] = (float) (oz + packet.z[i] / 32.0D);
            curYaw[id] = (packet.yaw[i] & 0xFF) / 256.0F * 360.0F;
            curWalkPos[id] = (packet.walkPos[i] & 0xFFFF) / 65535.0F * CrowdManager.WALK_WRAP;
            curWalkSpeed[id] = (packet.walkSpeed[i] & 0xFFFF) / 65535.0F;
            curHeadYaw[id] = packet.headYaw[i] / 127.0F * 180.0F;
            curHeadPitch[id] = packet.headPitch[i] / 127.0F * 90.0F;
            curAttack[id] = (packet.attackAnim[i] & 0xFF) / 255.0F;
            curHurt[id] = Math.min(1.0F, (packet.hurt[i] & 0xFF) / 10.0F);
            curAge[id] = packet.age[i] & 0xFFFF;
            byte tf = packet.teamFlags[i];
            curTeam[id] = tf & 0x03;
            curAggro[id] = (tf & 0x80) != 0 ? 1.0F : 0.0F;
            curType[id] = (tf >> 2) & 0x1F;
            curAction[id] = packet.action == null ? 0.0F : (packet.action[i] & 0xFF);
            curActionTick[id] = packet.actionTick == null ? 0.0F : (packet.actionTick[i] & 0xFF);

            // 新出现的单位：上一帧 = 当前，避免从旧位置「滑」过来
            if (prvStamp[id] != seq) {
                copyCurToPrv(id);
                prvStamp[id] = seq;
            }
        }
    }

    /** 收齐一帧后提交：更新在场单位列表与插值计时。 */
    private void commitFrame() {
        if (ids.length < pendingN) {
            ids = Arrays.copyOf(ids, Math.max(pendingN, 16));
        }
        System.arraycopy(pendingIds, 0, ids, 0, pendingN);
        count = pendingN;
        pendingN = 0;
        pendingTotal = -1;
        pendingChunk = 0;

        long now = System.nanoTime();
        if (lastNanos != 0L) {
            float dt = (float) (now - lastNanos);
            if (dt > 1.0E6F && dt < 5.0E8F) {
                intervalNanos = intervalNanos * 0.75F + dt * 0.25F;
            }
        }
        lastNanos = now;
    }

    private void ensure(int needCapacity) {
        if (capacity >= needCapacity) {
            return;
        }
        capacity = needCapacity;
        curX = Arrays.copyOf(curX, capacity);
        curY = Arrays.copyOf(curY, capacity);
        curZ = Arrays.copyOf(curZ, capacity);
        curYaw = Arrays.copyOf(curYaw, capacity);
        curWalkPos = Arrays.copyOf(curWalkPos, capacity);
        curWalkSpeed = Arrays.copyOf(curWalkSpeed, capacity);
        curHeadYaw = Arrays.copyOf(curHeadYaw, capacity);
        curHeadPitch = Arrays.copyOf(curHeadPitch, capacity);
        curAttack = Arrays.copyOf(curAttack, capacity);
        curHurt = Arrays.copyOf(curHurt, capacity);
        curAge = Arrays.copyOf(curAge, capacity);
        curTeam = Arrays.copyOf(curTeam, capacity);
        curAggro = Arrays.copyOf(curAggro, capacity);
        curAction = Arrays.copyOf(curAction, capacity);
        curActionTick = Arrays.copyOf(curActionTick, capacity);
        curType = Arrays.copyOf(curType, capacity);

        prvX = Arrays.copyOf(prvX, capacity);
        prvY = Arrays.copyOf(prvY, capacity);
        prvZ = Arrays.copyOf(prvZ, capacity);
        prvYaw = Arrays.copyOf(prvYaw, capacity);
        prvWalkPos = Arrays.copyOf(prvWalkPos, capacity);
        prvWalkSpeed = Arrays.copyOf(prvWalkSpeed, capacity);
        prvHeadYaw = Arrays.copyOf(prvHeadYaw, capacity);
        prvHeadPitch = Arrays.copyOf(prvHeadPitch, capacity);
        prvAttack = Arrays.copyOf(prvAttack, capacity);
        prvHurt = Arrays.copyOf(prvHurt, capacity);
        prvAge = Arrays.copyOf(prvAge, capacity);
        prvStamp = Arrays.copyOf(prvStamp, capacity);
    }

    private void copyCurToPrv(int id) {
        prvX[id] = curX[id];
        prvY[id] = curY[id];
        prvZ[id] = curZ[id];
        prvYaw[id] = curYaw[id];
        prvWalkPos[id] = curWalkPos[id];
        prvWalkSpeed[id] = curWalkSpeed[id];
        prvHeadYaw[id] = curHeadYaw[id];
        prvHeadPitch[id] = curHeadPitch[id];
        prvAttack[id] = curAttack[id];
        prvHurt[id] = curHurt[id];
        prvAge[id] = curAge[id];
    }

    public float prvX(int id) {
        return prvStamp[id] == seq ? prvX[id] : curX[id];
    }

    public float prvY(int id) {
        return prvStamp[id] == seq ? prvY[id] : curY[id];
    }

    public float prvZ(int id) {
        return prvStamp[id] == seq ? prvZ[id] : curZ[id];
    }

    public float prvYaw(int id) {
        return prvStamp[id] == seq ? prvYaw[id] : curYaw[id];
    }

    public float prvWalkPos(int id) {
        return prvStamp[id] == seq ? prvWalkPos[id] : curWalkPos[id];
    }

    public float prvWalkSpeed(int id) {
        return prvStamp[id] == seq ? prvWalkSpeed[id] : curWalkSpeed[id];
    }

    public float prvHeadYaw(int id) {
        return prvStamp[id] == seq ? prvHeadYaw[id] : curHeadYaw[id];
    }

    public float prvHeadPitch(int id) {
        return prvStamp[id] == seq ? prvHeadPitch[id] : curHeadPitch[id];
    }

    public float prvAttack(int id) {
        return prvStamp[id] == seq ? prvAttack[id] : curAttack[id];
    }

    public float prvHurt(int id) {
        return prvStamp[id] == seq ? prvHurt[id] : curHurt[id];
    }

    public float prvAge(int id) {
        return prvStamp[id] == seq ? prvAge[id] : curAge[id];
    }

    public static void clearAll() {
        BY_DIMENSION.clear();
    }
}
