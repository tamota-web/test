package dev.swordkingdom.horde.network;

import dev.swordkingdom.horde.crowd.EquipData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * 玩家与「虚拟实体的装备」之间的交互包。
 *
 * <h2>为什么要单独一组包，而不是复用 {@link EditPackets}</h2>
 * 覆盖表（{@code EditPackets}）是**管理员改配置**，要 JSON、要落盘、要广播给所有人；
 * 而这里做的是**玩家在战斗中给单位换装备**：高频、逐单位、立刻生效、不需要存盘。
 * 两者的生命周期完全不同，混在一起会让「一次右键顺手换装」背上整表 JSON 的开销。
 *
 * <h2>槽位怎么传</h2>
 * 客户端发的是**调色板索引**（{@code short}），不是 {@code ItemStack}
 * —— 因为客户端的 GUI 里显示的就是它自己那份调色板；这样客户端不需要「把物品序列化发过来」，
 * 也就没有「玩家伪造物品 NBT」这条路。唯一的例外是 {@link ApplyHeld}
 * （「把手上的东西装上去」），那种情况发的是**槽位号**，物品由服务端自己去读玩家手上那件
 * —— 服务端为准，客户端说了不算。
 */
public final class EquipPackets {

    private EquipPackets() {}

    /** 客户端 -&gt; 服务端：请求整表 + 当前各单位的装备（进 GUI / 发现版本不匹配时） */
    public static final class Request {
        /** 客户端手里的调色板版本；-1 表示「我什么都没有」 */
        public final int paletteVersion;

        public Request(int paletteVersion) {
            this.paletteVersion = paletteVersion;
        }

        public Request(FriendlyByteBuf buf) {
            this.paletteVersion = buf.readVarInt();
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(paletteVersion);
        }
    }

    /**
     * 客户端 -&gt; 服务端：给**一个**单位套装（6 个槽一次全给；0 = 清空该槽）。
     *
     * {@code unitId} 是群集单位的槽位 id（与快照里的 id 同一套编号）。
     */
    public static final class ApplyUnit {
        public final ResourceLocation dimension;
        public final int unitId;
        public final short[] slots;
        public final byte flags;

        public ApplyUnit(ResourceLocation dimension, int unitId, short[] slots, byte flags) {
            this.dimension = dimension;
            this.unitId = unitId;
            this.slots = slots;
            this.flags = flags;
        }

        public ApplyUnit(FriendlyByteBuf buf) {
            this.dimension = buf.readResourceLocation();
            this.unitId = buf.readVarInt();
            this.slots = new short[EquipData.SLOT_COUNT];
            for (int s = 0; s < EquipData.SLOT_COUNT; s++) {
                slots[s] = buf.readShort();
            }
            this.flags = buf.readByte();
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeResourceLocation(dimension);
            buf.writeVarInt(unitId);
            for (int s = 0; s < EquipData.SLOT_COUNT; s++) {
                buf.writeShort(s < slots.length ? slots[s] : 0);
            }
            buf.writeByte(flags);
        }
    }

    /**
     * 客户端 -&gt; 服务端：**批量**装（一次应用，不是 N 次单发）。
     *
     * <h2>为什么批量要合成一个包</h2>
     * 「给当前选中的类型全部换装」在 1000 只的时候就是 1000 次单发包 ——
     * 既费带宽又给了「只成功了一半」的机会。这里把**过滤条件**发过来，服务端一次扫完：
     * <pre>
     *   mode = 0  按实体类型   （filterType 有效）
     *   mode = 1  按阵营       （filterTeam 有效）
     *   mode = 2  全部在场单位
     *   mode = 3  圆形范围     （centerX/Y/Z + radius）
     * </pre>
     * 返回值里带上「实际改了多少个」，GUI 直接显示，不用猜。
     */
    public static final class ApplyBatch {
        public static final int MODE_TYPE = 0;
        public static final int MODE_TEAM = 1;
        public static final int MODE_ALL = 2;
        public static final int MODE_RADIUS = 3;

        public final ResourceLocation dimension;
        public final int mode;
        public final ResourceLocation filterType;
        public final int filterTeam;
        public final double centerX;
        public final double centerY;
        public final double centerZ;
        public final float radius;
        public final short[] slots;
        public final byte flags;

        public ApplyBatch(ResourceLocation dimension, int mode, ResourceLocation filterType,
                          int filterTeam, double centerX, double centerY, double centerZ,
                          float radius, short[] slots, byte flags) {
            this.dimension = dimension;
            this.mode = mode;
            this.filterType = filterType;
            this.filterTeam = filterTeam;
            this.centerX = centerX;
            this.centerY = centerY;
            this.centerZ = centerZ;
            this.radius = radius;
            this.slots = slots;
            this.flags = flags;
        }

        public ApplyBatch(FriendlyByteBuf buf) {
            this.dimension = buf.readResourceLocation();
            this.mode = buf.readVarInt();
            this.filterType = buf.readBoolean() ? buf.readResourceLocation() : null;
            this.filterTeam = buf.readVarInt();
            this.centerX = buf.readDouble();
            this.centerY = buf.readDouble();
            this.centerZ = buf.readDouble();
            this.radius = buf.readFloat();
            this.slots = new short[EquipData.SLOT_COUNT];
            for (int s = 0; s < EquipData.SLOT_COUNT; s++) {
                slots[s] = buf.readShort();
            }
            this.flags = buf.readByte();
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeResourceLocation(dimension);
            buf.writeVarInt(mode);
            buf.writeBoolean(filterType != null);
            if (filterType != null) {
                buf.writeResourceLocation(filterType);
            }
            buf.writeVarInt(filterTeam);
            buf.writeDouble(centerX);
            buf.writeDouble(centerY);
            buf.writeDouble(centerZ);
            buf.writeFloat(radius);
            for (int s = 0; s < EquipData.SLOT_COUNT; s++) {
                buf.writeShort(s < slots.length ? slots[s] : 0);
            }
            buf.writeByte(flags);
        }
    }

    /**
     * 客户端 -&gt; 服务端：**把手上的物品装到某个单位身上**。
     *
     * 两种来源：
     * <pre>
     *   unitId &gt;= 0  右键射线命中的那个单位（单个）
     *   unitId == -1 潜行右键：批量装到「同类型 / 同阵营」
     * </pre>
     * {@code batchMode} 只在 {@code unitId == -1} 时有意义（见 {@link ApplyBatch}）。
     * {@code swap} 为 true 时把单位原来那个槽的物品**回给玩家**（交换，而不是覆盖）。
     */
    public static final class ApplyHeld {
        public final ResourceLocation dimension;
        public final int unitId;
        public final int batchMode;
        public final int slot;
        public final boolean swap;
        /** 该单位「同类型/同阵营」的参考：批量时用它的类型与阵营做过滤 */
        public final ResourceLocation filterType;
        public final int filterTeam;

        public ApplyHeld(ResourceLocation dimension, int unitId, int batchMode, int slot,
                         boolean swap, ResourceLocation filterType, int filterTeam) {
            this.dimension = dimension;
            this.unitId = unitId;
            this.batchMode = batchMode;
            this.slot = slot;
            this.swap = swap;
            this.filterType = filterType;
            this.filterTeam = filterTeam;
        }

        public ApplyHeld(FriendlyByteBuf buf) {
            this.dimension = buf.readResourceLocation();
            this.unitId = buf.readVarInt() - 1;          // 编码时 +1，好让 -1 也能走 VarInt
            this.batchMode = buf.readVarInt();
            this.slot = buf.readVarInt();
            this.swap = buf.readBoolean();
            this.filterType = buf.readBoolean() ? buf.readResourceLocation() : null;
            this.filterTeam = buf.readVarInt();
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeResourceLocation(dimension);
            buf.writeVarInt(unitId + 1);
            buf.writeVarInt(batchMode);
            buf.writeVarInt(slot);
            buf.writeBoolean(swap);
            buf.writeBoolean(filterType != null);
            if (filterType != null) {
                buf.writeResourceLocation(filterType);
            }
            buf.writeVarInt(filterTeam);
        }
    }

    /**
     * 客户端 -&gt; 服务端：右键射线拾取（「我指的是哪一个单位」）。
     *
     * 客户端只发**自己的视线**（眼位 + 方向 + 最大距离），服务端用群集自己的
     * 空间哈希 + 逐单位 AABB 做一次拾取 —— 不信任客户端给的「我打中了第几号」。
     */
    public static final class Pick {
        public final ResourceLocation dimension;
        public final double originX;
        public final double originY;
        public final double originZ;
        public final float dirX;
        public final float dirY;
        public final float dirZ;
        public final float maxDistance;
        /** 潜行时置位：服务端按「批量」语义处理，并把命中单位的类型/阵营回给客户端备用 */
        public final boolean batch;

        public Pick(ResourceLocation dimension, double originX, double originY, double originZ,
                    float dirX, float dirY, float dirZ, float maxDistance, boolean batch) {
            this.dimension = dimension;
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            this.dirX = dirX;
            this.dirY = dirY;
            this.dirZ = dirZ;
            this.maxDistance = maxDistance;
            this.batch = batch;
        }

        public Pick(FriendlyByteBuf buf) {
            this.dimension = buf.readResourceLocation();
            this.originX = buf.readDouble();
            this.originY = buf.readDouble();
            this.originZ = buf.readDouble();
            this.dirX = buf.readFloat();
            this.dirY = buf.readFloat();
            this.dirZ = buf.readFloat();
            this.maxDistance = buf.readFloat();
            this.batch = buf.readBoolean();
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeResourceLocation(dimension);
            buf.writeDouble(originX);
            buf.writeDouble(originY);
            buf.writeDouble(originZ);
            buf.writeFloat(dirX);
            buf.writeFloat(dirY);
            buf.writeFloat(dirZ);
            buf.writeFloat(maxDistance);
            buf.writeBoolean(batch);
        }
    }

    /** 服务端 -&gt; 客户端：拾取结果（命中哪个单位 / 没命中） */
    public static final class PickResult {
        public final ResourceLocation dimension;
        public final int unitId;
        public final ResourceLocation unitType;
        public final int team;
        public final float distance;
        /** 该单位当前 6 个槽（GUI 直接用，省一次往返） */
        public final short[] slots;
        public final byte flags;
        /** 没命中时的原因（中文，直接显示给玩家看） */
        public final String reason;

        public PickResult(ResourceLocation dimension, int unitId, ResourceLocation unitType, int team,
                          float distance, short[] slots, byte flags, String reason) {
            this.dimension = dimension;
            this.unitId = unitId;
            this.unitType = unitType;
            this.team = team;
            this.distance = distance;
            this.slots = slots;
            this.flags = flags;
            this.reason = reason == null ? "" : reason;
        }

        public PickResult(FriendlyByteBuf buf) {
            this.dimension = buf.readResourceLocation();
            this.unitId = buf.readVarInt() - 1;
            this.unitType = buf.readBoolean() ? buf.readResourceLocation() : null;
            this.team = buf.readVarInt();
            this.distance = buf.readFloat();
            this.slots = new short[EquipData.SLOT_COUNT];
            for (int s = 0; s < EquipData.SLOT_COUNT; s++) {
                slots[s] = buf.readShort();
            }
            this.flags = buf.readByte();
            this.reason = buf.readUtf(200);
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeResourceLocation(dimension);
            buf.writeVarInt(unitId + 1);
            buf.writeBoolean(unitType != null);
            if (unitType != null) {
                buf.writeResourceLocation(unitType);
            }
            buf.writeVarInt(team);
            buf.writeFloat(distance);
            for (int s = 0; s < EquipData.SLOT_COUNT; s++) {
                buf.writeShort(s < slots.length ? slots[s] : 0);
            }
            buf.writeByte(flags);
            buf.writeUtf(reason == null ? "" : reason);
        }

        public boolean hit() {
            return unitId >= 0;
        }
    }

    /** 服务端 -&gt; 客户端：操作结果回执（GUI 显示「改了多少个」） */
    public static final class Result {
        public final int changed;
        public final String message;

        public Result(int changed, String message) {
            this.changed = changed;
            this.message = message == null ? "" : message;
        }

        public Result(FriendlyByteBuf buf) {
            this.changed = buf.readVarInt();
            this.message = buf.readUtf(300);
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(changed);
            buf.writeUtf(message == null ? "" : message);
        }
    }
}
