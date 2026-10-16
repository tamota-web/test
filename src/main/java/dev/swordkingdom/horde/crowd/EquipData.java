package dev.swordkingdom.horde.crowd;

/**
 * 逐单位装备数据体 —— 与 {@link CrowdData} 一样是**结构数组（SoA）**，零对象分配。
 *
 * <h2>存什么</h2>
 * 每个单位 6 个槽（头/胸/腿/脚/主手/副手），每个槽存一个 {@code short}
 * —— 那是 {@link dev.swordkingdom.horde.edit.EquipPalette} 里的**调色板索引**，
 * 不是物品 id（原因见调色板类注释：整表单独下发，逐单位只发索引）。
 * 另有 1 个 {@code byte flags} 放「哪些槽亮着 / 附魔光效 / 自定义名 / 是否格挡」这类可选位。
 *
 * <h2>为什么单独一个类而不是塞进 CrowdData</h2>
 * 装备是**后加的独立关注点**：它只在「有人给单位发装备」时才会被写，
 * 绝大多数单位的这 13 个字节会一直是 0。独立成类之后
 *   - {@link CrowdData} 的热字段布局一点没动（物理/动画的缓存局部性不受影响）；
 *   - 服务端可以按需决定「要不要分配这张表」（容量 0 时它就是个零长度数组，不占内存）；
 *   - 与并行开发解耦：另一个子代理正在改 {@code CrowdData}，这里不碰它。
 *
 * <h2>flags 位分配</h2>
 * <pre>
 *   bit0..5     槽 0..5 是否非空（冗余但便宜：渲染器不必逐槽判 0）
 *   bit6        附魔光效（只要有任意一件带附魔就置位）
 *   bit7        自定义名（同上）
 * </pre>
 * 默认值 0 = 什么都没穿，于是 {@code data.alloc()} 的时候不需要任何初始化动作。
 */
public final class EquipData {

    public static final int SLOT_COUNT = 6;

    public static final int FLAG_HEAD = 1;
    public static final int FLAG_CHEST = 1 << 1;
    public static final int FLAG_LEGS = 1 << 2;
    public static final int FLAG_FEET = 1 << 3;
    public static final int FLAG_MAINHAND = 1 << 4;
    public static final int FLAG_OFFHAND = 1 << 5;
    /** 任一槽带附魔光效 */
    public static final int FLAG_GLINT = 1 << 6;
    /** 任一槽有自定义名 */
    public static final int FLAG_CUSTOM_NAME = 1 << 7;

    /** 该槽对应的 flag 位（顺序与 EquipPalette.SLOT_NAMES 一致） */
    public static final int[] SLOT_FLAG = {
            FLAG_HEAD, FLAG_CHEST, FLAG_LEGS, FLAG_FEET, FLAG_MAINHAND, FLAG_OFFHAND
    };

    public final short[] slots;
    public final byte[] flags;

    public EquipData(int capacity) {
        this.slots = new short[Math.max(0, capacity) * SLOT_COUNT];
        this.flags = new byte[Math.max(0, capacity)];
    }

    /** 该表能覆盖多少个单位（0 = 这张表被关掉了） */
    public int capacity() {
        return flags.length;
    }

    public boolean enabled() {
        return flags.length > 0;
    }

    public short slot(int unit, int slot) {
        int i = unit * SLOT_COUNT + slot;
        return i >= 0 && i < slots.length ? slots[i] : 0;
    }

    /** 取某单位的 6 个槽（写进调用方的数组，避免分配） */
    public void slots(int unit, short[] out) {
        int base = unit * SLOT_COUNT;
        for (int s = 0; s < SLOT_COUNT; s++) {
            out[s] = base + s < slots.length ? slots[base + s] : 0;
        }
    }

    public byte flags(int unit) {
        return unit >= 0 && unit < flags.length ? flags[unit] : 0;
    }

    /** 设置一个槽；同时维护 flags 里的「该槽非空」位 */
    public void setSlot(int unit, int slot, short paletteIndex) {
        int i = unit * SLOT_COUNT + slot;
        if (i < 0 || i >= slots.length) {
            return;
        }
        slots[i] = paletteIndex;
        if (paletteIndex == 0) {
            flags[unit] &= (byte) ~SLOT_FLAG[slot];
        } else {
            flags[unit] |= (byte) SLOT_FLAG[slot];
        }
    }

    public void setFlags(int unit, byte value) {
        if (unit >= 0 && unit < flags.length) {
            flags[unit] = value;
        }
    }

    public boolean anyEquipped(int unit) {
        return (flags(unit) & 0x3F) != 0;
    }

    /**
     * 该单位当前装备的**确定性键**。
     *
     * 用途：客户端按它去缓存「变体网格」。必须是确定性的（同一套装备永远同一个键），
     * 否则缓存永远命不中；也不能只取 hash（冲突会让两个不同装备共用一份网格），
     * 所以这里拼的是「6 个索引 + flags」的字面值 —— 只在**缓存未命中**时才会被调用一次。
     */
    public String key(int unit) {
        int base = unit * SLOT_COUNT;
        StringBuilder sb = new StringBuilder(24);
        for (int s = 0; s < SLOT_COUNT; s++) {
            if (s > 0) {
                sb.append(',');
            }
            sb.append(base + s < slots.length ? (slots[base + s] & 0xFFFF) : 0);
        }
        sb.append(';').append(flags(unit) & 0xFF);
        return sb.toString();
    }

    /** 同上，但直接由数组拼（打包/测试用） */
    public static String key(short[] sixSlots, int flags) {
        StringBuilder sb = new StringBuilder(24);
        for (int s = 0; s < SLOT_COUNT; s++) {
            if (s > 0) {
                sb.append(',');
            }
            sb.append(sixSlots != null && s < sixSlots.length ? (sixSlots[s] & 0xFFFF) : 0);
        }
        sb.append(';').append(flags & 0xFF);
        return sb.toString();
    }

    /** 把一个单位重置成「什么都没穿」 */
    public void clear(int unit) {
        int base = unit * SLOT_COUNT;
        for (int s = 0; s < SLOT_COUNT; s++) {
            if (base + s < slots.length) {
                slots[base + s] = 0;
            }
        }
        if (unit >= 0 && unit < flags.length) {
            flags[unit] = 0;
        }
    }

    public void clearAll() {
        java.util.Arrays.fill(slots, (short) 0);
        java.util.Arrays.fill(flags, (byte) 0);
    }

    // ------------------------------------------------------------------
    // 每维度的注册表（与 CrowdManagers / ProjectileManagers 同样的组织方式）
    // ------------------------------------------------------------------

    /** 维度 -> 该维度的装备表 */
    private static final java.util.Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, EquipData>
            BY_DIMENSION = new java.util.HashMap<>();

    /**
     * 取（必要时按容量新建）某维度的装备表。
     *
     * <b>懒建 + 可扩容</b>：{@code CrowdManager} 的容量是配置里定的，第一次访问时就按它建表；
     * 之后如果容量变了（玩家改了配置），{@link #ensureCapacity} 会重建并**清空**
     * —— 清空比「猜新号段对应谁」安全：装备丢了可以重新发，错位了会串到别的单位身上。
     */
    public static EquipData of(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
                               int capacity) {
        EquipData d = BY_DIMENSION.get(dimension);
        if (d == null) {
            d = new EquipData(capacity);
            BY_DIMENSION.put(dimension, d);
            return d;
        }
        if (d.capacity() < capacity) {
            EquipData bigger = new EquipData(capacity);
            BY_DIMENSION.put(dimension, bigger);
            return bigger;
        }
        return d;
    }

    /** 只取不建（同步层用：没有表说明这个维度从没人发过装备） */
    public static EquipData getIfPresent(
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) {
        return BY_DIMENSION.get(dimension);
    }

    public static void remove(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) {
        BY_DIMENSION.remove(dimension);
    }

    public static void clearAllDimensions() {
        BY_DIMENSION.clear();
    }

    /**
     * 复制另一个单位（<b>批量操作</b>的实现基础：一次算好、写 N 份，不用 N 次查表）。
     */
    public void copy(int from, int to) {
        int f = from * SLOT_COUNT;
        int t = to * SLOT_COUNT;
        for (int s = 0; s < SLOT_COUNT; s++) {
            if (t + s < slots.length && f + s < slots.length) {
                slots[t + s] = slots[f + s];
            }
        }
        if (to >= 0 && to < flags.length && from >= 0 && from < flags.length) {
            flags[to] = flags[from];
        }
    }

    // ------------------------------------------------------------------
    // flags 拼接（由调色板条目推导）
    // ------------------------------------------------------------------

    /** 由 6 个调色板条目算出 flags（槽位非空位 + 光效 + 自定义名） */
    public static byte flagsOf(short[] sixSlots, boolean glint, boolean customName) {
        int f = 0;
        for (int s = 0; s < SLOT_COUNT; s++) {
            if (sixSlots != null && s < sixSlots.length && sixSlots[s] != 0) {
                f |= SLOT_FLAG[s];
            }
        }
        if (glint) {
            f |= FLAG_GLINT;
        }
        if (customName) {
            f |= FLAG_CUSTOM_NAME;
        }
        return (byte) f;
    }
}
