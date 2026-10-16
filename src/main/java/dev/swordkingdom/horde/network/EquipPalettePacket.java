package dev.swordkingdom.horde.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * 装备调色板整表下发（服务端 -&gt; 客户端）。
 *
 * <h2>与 {@code ProjectileTypesPacket} 同款做法</h2>
 * 「类型表 + 版本号」：客户端只有拿到「索引 → 物品 id」这张表，才能知道某个单位
 * 身上那 6 个 short 到底代表什么装备，进而去烘焙对应的变体网格。
 * 表只在**版本变化**时发（玩家进服时也发一次），所以正常情况下每个玩家每局只收一两次。
 *
 * <h2>线缆格式</h2>
 * <pre>
 *   version(Int) | 条数(VarInt) | 每条：单位类型(ResourceLocation) + 索引(VarInt) +
 *   物品 id(ResourceLocation) + 标记字节(bit0 光效 / bit1 自定义名)
 * </pre>
 * 按**单位类型**分组下发（而不是全局一张表），因为「同一件物品在不同体型单位上」
 * 可能走不同的挂接骨骼/缩放，分开之后客户端缓存键天然是
 * {@code (单位类型, 装备组合)}，与 {@link dev.swordkingdom.horde.client.EquipBaker} 的
 * 缓存维度完全一致。
 */
public class EquipPalettePacket {

    /** 单包最多多少条（超过就说明有人在刷装备种类；截断并打日志，不静默丢） */
    public static final int MAX_ENTRIES = 4096;

    public final int version;
    public final ResourceLocation[] unitTypes;
    public final int[] indices;
    public final ResourceLocation[] items;
    public final byte[] marks;

    public EquipPalettePacket(int version, ResourceLocation[] unitTypes, int[] indices,
                              ResourceLocation[] items, byte[] marks) {
        this.version = version;
        this.unitTypes = unitTypes;
        this.indices = indices;
        this.items = items;
        this.marks = marks;
    }

    public EquipPalettePacket(FriendlyByteBuf buf) {
        this.version = buf.readVarInt();
        int n = buf.readVarInt();
        if (n < 0) {
            n = 0;
        }
        if (n > MAX_ENTRIES) {
            n = MAX_ENTRIES;
        }
        this.unitTypes = new ResourceLocation[n];
        this.indices = new int[n];
        this.items = new ResourceLocation[n];
        this.marks = new byte[n];
        for (int i = 0; i < n; i++) {
            unitTypes[i] = buf.readResourceLocation();
            indices[i] = buf.readVarInt();
            items[i] = buf.readResourceLocation();
            marks[i] = buf.readByte();
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(version);
        buf.writeVarInt(unitTypes.length);
        for (int i = 0; i < unitTypes.length; i++) {
            buf.writeResourceLocation(unitTypes[i]);
            buf.writeVarInt(indices[i]);
            buf.writeResourceLocation(items[i]);
            buf.writeByte(marks[i]);
        }
    }

    /** 标记位 */
    public static final int MARK_GLINT = 1;
    public static final int MARK_CUSTOM_NAME = 1 << 1;
}
