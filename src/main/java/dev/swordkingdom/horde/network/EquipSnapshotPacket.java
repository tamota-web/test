package dev.swordkingdom.horde.network;

import dev.swordkingdom.horde.crowd.EquipData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * 逐单位装备快照包（服务端 -&gt; 客户端）。
 *
 * <h2>只发索引，不发物品 id</h2>
 * 每个单位 6 个槽，每槽 1 个 {@code short}（调色板索引），外加 1 个 {@code byte flags}
 * —— 也就是**每单位 13 字节**。物品本身通过 {@link EquipPalettePacket} 单独下发整表
 * （只在调色板版本变化时发一次），所以这条链路的开销与「世界里有多少种装备」无关。
 *
 * <h2>带宽账（这是设计时算过的，不是事后估的）</h2>
 * <pre>
 *   1000 单位 × 13 字节 = 13000 字节 ≈ 12.7 KB / 帧
 *   10 Hz（默认快照间隔 2 tick）= 127 KB/s
 *   稳态：{@link dev.swordkingdom.horde.network.EquipSync} 只发**变化过的单位**
 *        （装备是低频事件），所以稳态通常只有包头几十字节
 * </pre>
 * 单包上限 4096 单位（4096 × 13 ≈ 53 KB），远超原版自定义负载 1 MB 的安全线；
 * 一帧超过上限时按 {@code chunkIndex/chunkCount} 拆包（与 {@code ProjectileSnapshotPacket} 同款），
 * 客户端**收齐同一帧的所有包**之后才提交。
 */
public class EquipSnapshotPacket {

    /**
     * 单个包最多携带的单位数。
     *
     * 和群集/投掷物一样：这**不是**总数上限，一帧会被拆成多个包。
     * 4096 × 13 ≈ 53 KB，给「物品自定义名」之类的未来扩展留足了余量。
     */
    public static final int UNITS_PER_PACKET = 4096;

    public final ResourceLocation dimension;
    /** 这台单位 id 是否**完整一帧**（true 时客户端会先清掉不在本帧里的单位的装备） */
    public final boolean fullFrame;
    /** 调色板版本（客户端发现对不上就回请整表） */
    public final int paletteVersion;
    /** 本包单位数 */
    public final int count;
    /** 本包在整帧数组里的起始下标（同一帧多个包共用同一批数组，避免拷贝） */
    public final int offset;
    public final int chunkIndex;
    public final int chunkCount;
    /** 这一帧的总单位数（跨所有包） */
    public final int totalUnits;

    /**
     * 单位槽位 id。用 {@code int[]} 而不是 {@code short[]}：
     * 服务端收集时拿到的是 {@code CrowdManager.outSlots[i]}（int），
     * 线缆上仍然只写 2 字节 —— 类型是给「同一帧多个包共用同一批数组」这个用法留的余量。
     */
    public final int[] id;
    public final short[] slots;   // 长度 = count * 6
    public final byte[] flags;

    public EquipSnapshotPacket(ResourceLocation dimension, boolean fullFrame, int paletteVersion,
                               int offset, int count, int chunkIndex, int chunkCount, int totalUnits,
                               int[] id, short[] slots, byte[] flags) {
        this.dimension = dimension;
        this.fullFrame = fullFrame;
        this.paletteVersion = paletteVersion;
        this.offset = offset;
        this.count = count;
        this.chunkIndex = chunkIndex;
        this.chunkCount = chunkCount;
        this.totalUnits = totalUnits;
        this.id = id;
        this.slots = slots;
        this.flags = flags;
    }

    public EquipSnapshotPacket(FriendlyByteBuf buf) {
        this.dimension = buf.readResourceLocation();
        this.fullFrame = buf.readBoolean();
        this.paletteVersion = buf.readVarInt();
        this.chunkIndex = Math.max(0, buf.readVarInt());
        this.chunkCount = Math.max(1, buf.readVarInt());
        this.totalUnits = Math.max(0, buf.readVarInt());
        int n = buf.readVarInt();
        if (n < 0) {
            n = 0;
        }
        if (n > UNITS_PER_PACKET) {
            n = UNITS_PER_PACKET;
        }
        this.count = n;
        this.offset = 0;
        this.id = new int[n];
        this.slots = new short[n * EquipData.SLOT_COUNT];
        this.flags = new byte[n];
        for (int i = 0; i < n; i++) {
            id[i] = buf.readShort() & 0xFFFF;
            for (int s = 0; s < EquipData.SLOT_COUNT; s++) {
                slots[i * EquipData.SLOT_COUNT + s] = buf.readShort();
            }
            flags[i] = buf.readByte();
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(dimension);
        buf.writeBoolean(fullFrame);
        buf.writeVarInt(paletteVersion);
        buf.writeVarInt(chunkIndex);
        buf.writeVarInt(chunkCount);
        buf.writeVarInt(totalUnits);
        buf.writeVarInt(count);
        for (int k = 0; k < count; k++) {
            int i = offset + k;
            buf.writeShort(id[i] & 0xFFFF);
            int base = i * EquipData.SLOT_COUNT;
            for (int s = 0; s < EquipData.SLOT_COUNT; s++) {
                buf.writeShort(slots[base + s]);
            }
            buf.writeByte(flags[i]);
        }
    }

    /** 该包的线缆字节数（报告/自检用，不含 Netty 头） */
    public static int wireBytes(int units, int chunkCount) {
        // 头：dimension(资源路径，约 20B) + fullFrame(1) + version(约 2) + 3×VarInt(约 3)
        //     + chunkCount(约 1) + count(约 2)
        int header = 30 + chunkCount;
        return header + units * (2 + EquipData.SLOT_COUNT * 2 + 1);
    }
}
