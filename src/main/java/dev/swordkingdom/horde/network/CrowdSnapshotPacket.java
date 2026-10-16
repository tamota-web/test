package dev.swordkingdom.horde.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * 群集快照包（服务端 -> 客户端）。
 *
 * 每单位 21 字节：
 *   id(short) + 位置 3×short（1/32 格，相对玩家）+
 *   朝向/头部偏航/头部俯仰/挥动进度/受击/阵营 共 6×byte +
 *   行走相位/行走幅度 2×short + 年龄(short)。
 *
 * 精度按「与原版实体渲染输入等价」来定：
 *   - 朝向、头部偏航/俯仰用 byte（原版同步实体朝向同样是 1/256 圈）；
 *   - 行走相位用 short（65536 级覆盖整个行走周期，相位抖动 ≈ 1.4e-4 弧度）；
 *   - 行走幅度用 short；受击直接用 tick 数；年龄用 tick 数（年龄驱动原版的待机小动作）。
 *
 * 带 id 是为了让客户端能把「上一帧」和「当前帧」的同一个单位对上，
 * 从而按原版那样做插值（lerp），彻底消除快照频率带来的卡顿感。
 */
public class CrowdSnapshotPacket {

    /**
     * **单个包**最多携带的单位数。
     *
     * 这不是总数上限：一帧快照会按这个大小**拆成多个包**（{@link #chunkIndex}/{@link #chunkCount}），
     * 客户端收齐后合成同一帧，所以群集总数不受限制。
     * 8000 × 21 字节 ≈ 168 KB，远低于原版自定义负载 1 MB 的上限。
     */
    public static final int UNITS_PER_PACKET = 8000;

    public final ResourceLocation dimension;
    public final double originX;
    public final double originY;
    public final double originZ;
    /** 本包携带的单位数 */
    public final int count;
    /** 本包各单位在数组中的起始下标（同一帧的多个包共用同一批数组，避免拷贝） */
    public final int offset;
    /** 本包是这一帧的第几包 / 共几包 */
    public final int chunkIndex;
    public final int chunkCount;
    /** 这一帧的总单位数（跨所有包） */
    public final int totalUnits;
    /** 本次快照使用的实体类型表（teamFlags 里的 5 bit 类型索引指向它） */
    public final ResourceLocation[] types;

    public final short[] id;
    public final short[] x;
    public final short[] y;
    public final short[] z;
    public final byte[] yaw;
    public final short[] walkPos;
    public final short[] walkSpeed;
    public final byte[] headYaw;
    public final byte[] headPitch;
    public final byte[] attackAnim;
    public final byte[] hurt;
    public final byte[] teamFlags;
    /** 单位年龄（tick），对应原版实体的 tickCount */
    public final short[] age;
    /** 正在播放的动作/技能动画编号（0 = 无），对应实体 getAnimation/setAnimation */
    public final byte[] action;
    /** 该动作已播放的 tick 数（对应 getAnimationTick） */
    public final byte[] actionTick;

    /** 单包快照（chunkIndex = 0、chunkCount = 1、无动作数据） */
    public CrowdSnapshotPacket(ResourceLocation dimension, double originX, double originY, double originZ,
                               int count, ResourceLocation[] types, short[] id, short[] x, short[] y, short[] z,
                               byte[] yaw, short[] walkPos, short[] walkSpeed,
                               byte[] headYaw, byte[] headPitch, byte[] attackAnim,
                               byte[] hurt, byte[] teamFlags, short[] age) {
        this(dimension, originX, originY, originZ, 0, count, 0, 1, count, types, id, x, y, z,
                yaw, walkPos, walkSpeed, headYaw, headPitch, attackAnim, hurt, teamFlags, age,
                new byte[count], new byte[count]);
    }

    /** 分块快照（不带动作数据，动作数据为空） */
    public CrowdSnapshotPacket(ResourceLocation dimension, double originX, double originY, double originZ,
                               int offset, int count, int chunkIndex, int chunkCount, int totalUnits,
                               ResourceLocation[] types, short[] id, short[] x, short[] y, short[] z,
                               byte[] yaw, short[] walkPos, short[] walkSpeed,
                               byte[] headYaw, byte[] headPitch, byte[] attackAnim,
                               byte[] hurt, byte[] teamFlags, short[] age) {
        this(dimension, originX, originY, originZ, offset, count, chunkIndex, chunkCount, totalUnits,
                types, id, x, y, z, yaw, walkPos, walkSpeed, headYaw, headPitch, attackAnim,
                hurt, teamFlags, age, new byte[offset + count], new byte[offset + count]);
    }

    public CrowdSnapshotPacket(ResourceLocation dimension, double originX, double originY, double originZ,
                               int offset, int count, int chunkIndex, int chunkCount, int totalUnits,
                               ResourceLocation[] types, short[] id, short[] x, short[] y, short[] z,
                               byte[] yaw, short[] walkPos, short[] walkSpeed,
                               byte[] headYaw, byte[] headPitch, byte[] attackAnim,
                               byte[] hurt, byte[] teamFlags, short[] age,
                               byte[] action, byte[] actionTick) {
        this.dimension = dimension;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.offset = offset;
        this.count = count;
        this.chunkIndex = chunkIndex;
        this.chunkCount = chunkCount;
        this.totalUnits = totalUnits;
        this.types = types;
        this.id = id;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.walkPos = walkPos;
        this.walkSpeed = walkSpeed;
        this.headYaw = headYaw;
        this.headPitch = headPitch;
        this.attackAnim = attackAnim;
        this.hurt = hurt;
        this.teamFlags = teamFlags;
        this.age = age;
        this.action = action;
        this.actionTick = actionTick;
    }

    public CrowdSnapshotPacket(FriendlyByteBuf buf) {
        this.dimension = buf.readResourceLocation();
        this.originX = buf.readDouble();
        this.originY = buf.readDouble();
        this.originZ = buf.readDouble();
        this.chunkIndex = Math.max(0, buf.readVarInt());
        this.chunkCount = Math.max(1, buf.readVarInt());
        this.totalUnits = Math.max(0, buf.readVarInt());
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
        if (n > UNITS_PER_PACKET) {
            n = UNITS_PER_PACKET;
        }
        this.count = n;
        this.offset = 0;
        this.id = new short[n];
        this.x = new short[n];
        this.y = new short[n];
        this.z = new short[n];
        this.yaw = new byte[n];
        this.walkPos = new short[n];
        this.walkSpeed = new short[n];
        this.headYaw = new byte[n];
        this.headPitch = new byte[n];
        this.attackAnim = new byte[n];
        this.hurt = new byte[n];
        this.teamFlags = new byte[n];
        this.age = new short[n];
        this.action = new byte[n];
        this.actionTick = new byte[n];
        for (int i = 0; i < n; i++) {
            id[i] = buf.readShort();
            x[i] = buf.readShort();
            y[i] = buf.readShort();
            z[i] = buf.readShort();
            yaw[i] = buf.readByte();
            walkPos[i] = buf.readShort();
            walkSpeed[i] = buf.readShort();
            headYaw[i] = buf.readByte();
            headPitch[i] = buf.readByte();
            attackAnim[i] = buf.readByte();
            hurt[i] = buf.readByte();
            teamFlags[i] = buf.readByte();
            age[i] = buf.readShort();
            action[i] = buf.readByte();
            actionTick[i] = buf.readByte();
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(dimension);
        buf.writeDouble(originX);
        buf.writeDouble(originY);
        buf.writeDouble(originZ);
        buf.writeVarInt(chunkIndex);
        buf.writeVarInt(chunkCount);
        buf.writeVarInt(totalUnits);
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
            buf.writeByte(yaw[i]);
            buf.writeShort(walkPos[i]);
            buf.writeShort(walkSpeed[i]);
            buf.writeByte(headYaw[i]);
            buf.writeByte(headPitch[i]);
            buf.writeByte(attackAnim[i]);
            buf.writeByte(hurt[i]);
            buf.writeByte(teamFlags[i]);
            buf.writeShort(age[i]);
            buf.writeByte(action == null ? 0 : action[i]);
            buf.writeByte(actionTick == null ? 0 : actionTick[i]);
        }
    }
}
