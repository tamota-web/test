package dev.swordkingdom.horde.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * 虚拟投掷物快照包（服务端 -&gt; 客户端）。
 *
 * <h2>为什么单独一个包，而不是塞进 {@link CrowdSnapshotPacket}</h2>
 * 投掷物的**增删极快**（一秒飞 60 格、活几秒就没了），而群集单位是「稳定在场」的集合。
 * 两者混在一个数组流里，拆包序号（chunkIndex/chunkCount）就要同时适配两种长度，
 * 很容易在「某一帧投掷物特别多」时把已经稳定的群集拆包路径搞乱。
 * 分成两个包之后：群集那条链路一个字节没动（现有 syncHarness 继续全绿），
 * 投掷物这条链路自己拆自己的包，客户端各自拼帧、各自提交。
 *
 * <h2>每枚 15 字节</h2>
 * <pre>
 *   id(short) + 位置 3×short（1/32 格，相对玩家）+
 *   速度 3×byte（1/128 格/tick，±1.0 覆盖绝大多数投掷物）+
 *   朝向/俯仰/剩余寿命/状态 打包成 1 个 VarInt（yaw 9bit + pitch 8bit + life 8bit + 存活 1bit + 类型 5bit）
 * </pre>
 * 精度取舍：位置 1/32 格与原版实体同步同档；速度量化为 1/128，用途是「客户端据此推朝向」
 * （真实位置仍然逐帧同步并插值），所以 1/128 足够；朝向用 block-at-a-time 编码，
 * 投掷物本身很小，1/256 圈的朝向误差在箭/火球这种小模型上完全看不出。
 */
public class ProjectileSnapshotPacket {

    /**
     * 单个包最多携带的投掷物数。
     *
     * 与 {@link CrowdSnapshotPacket#UNITS_PER_PACKET} 一样：这不是总数上限，
     * 一帧快照会按这个大小**拆成多个包**，客户端收齐后合成同一帧。
     * 32000 × 15 字节 ≈ 480 KB，仍低于原版自定义负载 1 MB 上限（留了足够余量给别的字段）。
     */
    public static final int PROJECTILES_PER_PACKET = 32000;

    public final ResourceLocation dimension;
    public final double originX;
    public final double originY;
    public final double originZ;
    /** 本包投掷物数 */
    public final int count;
    /** 本包在整帧数组里的起始下标（同一帧的多个包共用同一批数组，避免拷贝） */
    public final int offset;
    public final int chunkIndex;
    public final int chunkCount;
    /** 这一帧的总投掷物数（跨所有包） */
    public final int totalProjectiles;
    /** 投掷物类型表（打包字段里的 5 bit 类型索引指向它） */
    public final ResourceLocation[] types;

    public final short[] id;
    public final short[] x;
    public final short[] y;
    public final short[] z;
    public final byte[] vx;
    public final byte[] vy;
    public final byte[] vz;
    /** yaw 9bit | pitch 8bit | life 8bit | alive 1bit | type 5bit */
    public final int[] packed;

    public ProjectileSnapshotPacket(ResourceLocation dimension, double originX, double originY, double originZ,
                                    int offset, int count, int chunkIndex, int chunkCount, int totalProjectiles,
                                    ResourceLocation[] types,
                                    short[] id, short[] x, short[] y, short[] z,
                                    byte[] vx, byte[] vy, byte[] vz, int[] packed) {
        this.dimension = dimension;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.offset = offset;
        this.count = count;
        this.chunkIndex = chunkIndex;
        this.chunkCount = chunkCount;
        this.totalProjectiles = totalProjectiles;
        this.types = types;
        this.id = id;
        this.x = x;
        this.y = y;
        this.z = z;
        this.vx = vx;
        this.vy = vy;
        this.vz = vz;
        this.packed = packed;
    }

    public ProjectileSnapshotPacket(FriendlyByteBuf buf) {
        this.dimension = buf.readResourceLocation();
        this.originX = buf.readDouble();
        this.originY = buf.readDouble();
        this.originZ = buf.readDouble();
        this.chunkIndex = Math.max(0, buf.readVarInt());
        this.chunkCount = Math.max(1, buf.readVarInt());
        this.totalProjectiles = Math.max(0, buf.readVarInt());
        int typeCount = buf.readVarInt();
        if (typeCount < 0) {
            typeCount = 0;
        }
        if (typeCount > 32) {
            typeCount = 32;
        }
        this.types = new ResourceLocation[typeCount];
        for (int i = 0; i < typeCount; i++) {
            types[i] = buf.readResourceLocation();
        }
        int n = buf.readVarInt();
        if (n < 0) {
            n = 0;
        }
        if (n > PROJECTILES_PER_PACKET) {
            n = PROJECTILES_PER_PACKET;
        }
        this.count = n;
        this.offset = 0;
        this.id = new short[n];
        this.x = new short[n];
        this.y = new short[n];
        this.z = new short[n];
        this.vx = new byte[n];
        this.vy = new byte[n];
        this.vz = new byte[n];
        this.packed = new int[n];
        for (int i = 0; i < n; i++) {
            id[i] = buf.readShort();
            x[i] = buf.readShort();
            y[i] = buf.readShort();
            z[i] = buf.readShort();
            vx[i] = buf.readByte();
            vy[i] = buf.readByte();
            vz[i] = buf.readByte();
            packed[i] = buf.readVarInt();
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(dimension);
        buf.writeDouble(originX);
        buf.writeDouble(originY);
        buf.writeDouble(originZ);
        buf.writeVarInt(chunkIndex);
        buf.writeVarInt(chunkCount);
        buf.writeVarInt(totalProjectiles);
        buf.writeVarInt(types.length);
        for (ResourceLocation t : types) {
            buf.writeResourceLocation(t);
        }
        buf.writeVarInt(count);
        for (int k = 0; k < count; k++) {
            int i = offset + k;
            buf.writeShort(id[i]);
            buf.writeShort(x[i]);
            buf.writeShort(y[i]);
            buf.writeShort(z[i]);
            buf.writeByte(vx[i]);
            buf.writeByte(vy[i]);
            buf.writeByte(vz[i]);
            buf.writeVarInt(packed[i]);
        }
    }

    // ------------------------------------------------------------------
    // 打包字段
    // ------------------------------------------------------------------

    /**
     * 把 (yaw, pitch, life, alive, type, team) 打包进**一个 int**（用 VarInt 传，通常 5 字节）。
     *
     * 位分配（共 32 bit，全部字段互不重叠 —— 这一点必须靠「位移量 = 前面所有字段的位宽之和」保证）：
     * <pre>
     *   yaw   9 bit @0..8     （0.703°，比原版实体同步的 1.4° 还细）
     *   pitch 7 bit @9..15    （-90°..+90°，有符号，分辨率 1.43°）
     *   life  8 bit @16..23
     *   alive 1 bit @24
     *   type  5 bit @25..29
     *   team  2 bit @30..31
     * </pre>
     * <b>一定要按累加的方式算位移量</b>：历史上就因为 pitch 的位移写成 6 而不是 9，
     * 把 yaw 的高位当成了自己的低位，结果 yaw 永远多 45° —— 这种「字段互相污染」
     * 在打包/解包两边都自洽（两边一起错），光看 round-trip 是发现不了的，
     * 所以 harness 里专门断言「已知输入的绝对值」而不只是往返一致。
     */
    public static int pack(float yawDegrees, float pitchDegrees, int lifeLeft, boolean alive,
                           int typeIndex, int team) {
        float yawNorm = yawDegrees % 360.0F;
        if (yawNorm < 0.0F) {
            yawNorm += 360.0F;
        }
        int yaw = Math.round(yawNorm / 360.0F * 512.0F) & 0x1FF;              // 9 bit
        int pitch = Math.round(clamp(pitchDegrees, -90.0F, 90.0F) / 90.0F * 63.0F) & 0x7F;  // 7 bit
        int life = lifeLeft & 0xFF;                                          // 8 bit
        int a = alive ? 1 : 0;                                               // 1 bit
        int t = typeIndex & 0x1F;                                            // 5 bit
        int teamBits = team & 0x03;                                          // 2 bit
        int result = yaw
                | (pitch << 9)
                | (life << 16)
                | (a << 24)
                | (t << 25)
                | (teamBits << 30);
        return result;
    }

    public static float unpackYaw(int packed) {
        return (packed & 0x1FF) / 512.0F * 360.0F;
    }

    public static float unpackPitch(int packed) {
        int raw = (packed >>> 9) & 0x7F;                                     // 7 bit
        // 7 bit 补码：>63 视为负数
        int signed = raw >= 64 ? raw - 128 : raw;
        return signed / 63.0F * 90.0F;
    }

    public static int unpackLife(int packed) {
        return (packed >>> 16) & 0xFF;
    }

    public static boolean unpackAlive(int packed) {
        return ((packed >>> 24) & 0x01) != 0;
    }

    public static int unpackType(int packed) {
        return (packed >>> 25) & 0x1F;
    }

    public static int unpackTeam(int packed) {
        return (packed >>> 30) & 0x03;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
