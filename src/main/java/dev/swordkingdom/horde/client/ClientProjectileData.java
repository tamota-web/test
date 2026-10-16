package dev.swordkingdom.horde.client;

import dev.swordkingdom.horde.network.ProjectileSnapshotPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 客户端虚拟投掷物数据（按维度），带**和群集单位一样的原版式插值**。
 *
 * 为什么投掷物更需要插值：单位一 tick 走 0.1 格，投掷物一 tick 走 3 格 ——
 * 不插值的话箭会以 10Hz 一格格「瞬移」，而插值后就是连续的一条轨迹。
 *
 * 与服务端一样，这里也全部是基本类型数组（按 id 索引），不做「每枚一个对象」。
 */
public final class ClientProjectileData {

    private static final Map<ResourceLocation, ClientProjectileData> BY_DIMENSION = new HashMap<>();

    public final ResourceLocation dimension;

    private int capacity;

    // ---- 当前帧 ----
    public float[] curX = new float[0];
    public float[] curY = new float[0];
    public float[] curZ = new float[0];
    public float[] curVx = new float[0];
    public float[] curVy = new float[0];
    public float[] curVz = new float[0];
    public float[] curYaw = new float[0];
    public float[] curPitch = new float[0];
    public float[] curLife = new float[0];
    public float[] curTeam = new float[0];
    public int[] curType = new int[0];
    /** 投掷物类型表（id -> 为了渲染要按这个 id 烘焙模型） */
    public ResourceLocation[] types = new ResourceLocation[0];

    // ---- 上一帧 ----
    private float[] prvX = new float[0];
    private float[] prvY = new float[0];
    private float[] prvZ = new float[0];
    private float[] prvYaw = new float[0];
    private float[] prvPitch = new float[0];
    private int[] prvStamp = new int[0];
    private int seq;

    /** 本帧在场的投掷物 id */
    public int[] ids = new int[0];
    public int count;

    private int[] pendingIds = new int[0];
    private int pendingN;
    private int pendingTotal = -1;

    private long lastNanos;
    private float intervalNanos = 50_000_000.0F;

    private ClientProjectileData(ResourceLocation dimension) {
        this.dimension = dimension;
    }

    public static ClientProjectileData get(ResourceLocation dimension) {
        return BY_DIMENSION.computeIfAbsent(dimension, ClientProjectileData::new);
    }

    public static ClientProjectileData forCurrentLevel() {
        ClientLevel level = Minecraft.getInstance().level;
        return level == null ? null : BY_DIMENSION.get(level.dimension().location());
    }

    /** 插值进度 [0,1]（与 {@link ClientCrowdData#alpha()} 各自独立计时，互不干扰） */
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

    /** 收到类型表（索引 -> 实体类型 id）：客户端据此为每个投掷物烘焙它自己的模型 */
    public static void acceptTypes(dev.swordkingdom.horde.network.ProjectileTypesPacket msg) {
        ClientProjectileData d = get(msg.dimension);
        d.types = msg.types == null ? new ResourceLocation[0] : msg.types;
        // 类型表变了 -> 已烘焙的投掷物网格作废，下一帧按新表重新烘
        ProjectileRenderer.invalidateAll();
    }

    /** 收一个包；同一帧被拆成多包时收齐后提交。 */
    public static void accept(ProjectileSnapshotPacket packet) {
        ClientProjectileData d = get(packet.dimension);
        if (packet.chunkIndex == 0 || d.pendingTotal < 0) {
            d.beginFrame(packet);
        }
        d.appendChunk(packet);
        if (packet.chunkIndex >= packet.chunkCount - 1 || d.pendingN >= d.pendingTotal) {
            d.commitFrame();
        }
    }

    private void beginFrame(ProjectileSnapshotPacket packet) {
        if (packet.types != null && packet.types.length > 0) {
            types = packet.types;
        }
        seq++;
        for (int k = 0; k < count; k++) {
            int id = ids[k];
            copyCurToPrv(id);
            prvStamp[id] = seq;
        }
        pendingN = 0;
        pendingTotal = packet.totalProjectiles > 0 ? packet.totalProjectiles : packet.count;
    }

    private void appendChunk(ProjectileSnapshotPacket packet) {
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
            curVx[id] = packet.vx[i] / 128.0F;
            curVy[id] = packet.vy[i] / 128.0F;
            curVz[id] = packet.vz[i] / 128.0F;
            int packed = packet.packed[i];
            curYaw[id] = ProjectileSnapshotPacket.unpackYaw(packed);
            curPitch[id] = ProjectileSnapshotPacket.unpackPitch(packed);
            curLife[id] = ProjectileSnapshotPacket.unpackLife(packed);
            curTeam[id] = ProjectileSnapshotPacket.unpackTeam(packed);
            curType[id] = ProjectileSnapshotPacket.unpackType(packed);
            if (prvStamp[id] != seq) {
                copyCurToPrv(id);
                prvStamp[id] = seq;
            }
        }
    }

    private void commitFrame() {
        if (ids.length < pendingN) {
            ids = Arrays.copyOf(ids, Math.max(pendingN, 16));
        }
        System.arraycopy(pendingIds, 0, ids, 0, pendingN);
        count = pendingN;
        pendingN = 0;
        pendingTotal = -1;

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
        curVx = Arrays.copyOf(curVx, capacity);
        curVy = Arrays.copyOf(curVy, capacity);
        curVz = Arrays.copyOf(curVz, capacity);
        curYaw = Arrays.copyOf(curYaw, capacity);
        curPitch = Arrays.copyOf(curPitch, capacity);
        curLife = Arrays.copyOf(curLife, capacity);
        curTeam = Arrays.copyOf(curTeam, capacity);
        curType = Arrays.copyOf(curType, capacity);
        prvX = Arrays.copyOf(prvX, capacity);
        prvY = Arrays.copyOf(prvY, capacity);
        prvZ = Arrays.copyOf(prvZ, capacity);
        prvYaw = Arrays.copyOf(prvYaw, capacity);
        prvPitch = Arrays.copyOf(prvPitch, capacity);
        prvStamp = Arrays.copyOf(prvStamp, capacity);
    }

    private void copyCurToPrv(int id) {
        prvX[id] = curX[id];
        prvY[id] = curY[id];
        prvZ[id] = curZ[id];
        prvYaw[id] = curYaw[id];
        prvPitch[id] = curPitch[id];
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

    public float prvPitch(int id) {
        return prvStamp[id] == seq ? prvPitch[id] : curPitch[id];
    }

    /** 当前帧朝向（度）；渲染器插值用 */
    public float curYawOf(int id) {
        return curYaw[id];
    }

    /** 当前帧俯仰（度）；渲染器插值用 */
    public float curPitchOf(int id) {
        return curPitch[id];
    }

    public static void clearAll() {
        BY_DIMENSION.clear();
    }
}
