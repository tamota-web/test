package dev.swordkingdom.horde.edit;

import dev.swordkingdom.horde.HordeMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 装备调色板 —— 把「一件具体的 {@link ItemStack}」压成一个 {@code short} 索引。
 *
 * <h2>为什么必须有这一层</h2>
 * 同步时**不能**每单位发物品 id：1000 个单位 × 6 槽 × 一个 {@code ResourceLocation}
 * 就是几百 KB/帧。而实际上「同一场战斗里用到的物品种类」只有个位数到几十种，
 * 所以走「调色板 + 索引」：整表只在**版本变化**时下发一次（见 {@code EquipPalettePacket}），
 * 逐单位的快照里每个槽只占 2 字节。
 *
 * <h2>为什么索引从 1 开始</h2>
 * {@code 0} 保留给「空槽」。这样 {@link dev.swordkingdom.horde.crowd.EquipData} 里的
 * {@code short[] slots} 默认值 0 天然表示「什么都没穿」，不需要额外一趟清零初始化，
 * 也不需要每单位一个「有没有装备」的位图。
 *
 * <h2>键里带什么</h2>
 * 只带**会改变外观或行为**的东西：物品本体、是否附魔光效、有没有自定义名。
 * 附魔的等级/词条不进调色板 —— 群集管线只渲染「物品的外形 + 光效开关」，
 * 具体附魔数值已经在服务端被 {@link EquipStats} 折算成属性加成了。
 *
 * <h2>线程与离线安全</h2>
 * 这个类在**离线 harness** 里也会被调用（注册表可能没就绪）。
 * 所有注册表查询都包了 try/catch 并退化成 {@code minecraft:air}，
 * 保证「没进游戏也能把整条调色板/拆包链路跑通」。
 */
public final class EquipPalette {

    /** 调色板索引类型：2 字节足够（65535 种同时存在的装备组合远超实际需要） */
    public static final short EMPTY = 0;

    /** 单个调色板的条数上限（超了返回 {@link #EMPTY} 并打日志，绝不静默丢装备） */
    public static final int MAX_ENTRIES = 4096;

    /** 6 个槽的顺序，全局唯一 —— 索引即 {@code EquipData} 里的槽位 */
    public static final String[] SLOT_NAMES = {"head", "chest", "legs", "feet", "mainhand", "offhand"};

    public static final int SLOT_HEAD = 0;
    public static final int SLOT_CHEST = 1;
    public static final int SLOT_LEGS = 2;
    public static final int SLOT_FEET = 3;
    public static final int SLOT_MAINHAND = 4;
    public static final int SLOT_OFFHAND = 5;
    public static final int SLOT_COUNT = 6;

    /**
     * 调色板里的一条。
     *
     * {@code item} 是**唯一**的身份：同一种物品 + 同样的光效/自定义名标记 = 同一条，
     * 所以「一千个单位都拿铁剑」在调色板里只占一条。
     */
    public static final class Entry {
        public final short index;
        public final ResourceLocation item;
        public final boolean glint;
        public final boolean customName;
        /**
         * 原始 {@link ItemStack} 的副本（**只在服务端内存里**，永远不上线缆）。
         *
         * 为什么留着它：玩家在 GUI 里按**索引**换装、以及「把单位身上那件换下来还给玩家」时，
         * 服务端需要把索引还原成一件真实的物品。离线 harness 里直构的 ItemStack
         * 可能因为注册表未就绪而拿不到 id，这条路径也会把它保留下来（HordeConfig 里的
         * 注册表守卫让 {@code itemIdOf} 返回 null 时索引是 0，不会进到这里）。
         */
        final ItemStack stack;

        Entry(short index, ResourceLocation item, boolean glint, boolean customName, ItemStack stack) {
            this.index = index;
            this.item = item;
            this.glint = glint;
            this.customName = customName;
            this.stack = stack == null ? null : stack.copy();
        }

        /** 该条对应的物品（注册表未就绪时返回 null） */
        public Item resolve() {
            try {
                return ForgeRegistries.ITEMS.getValue(item);
            } catch (Throwable t) {
                return null;
            }
        }

        /**
         * 还原成一件可用的 {@link ItemStack}（数量 1）。
         *
         * 优先用建表时留下的副本（保留附魔/自定义名等 NBT），副本丢了再退回按 id 新建
         * —— 后者会成为「白板物品」，所以只当兜底。
         */
        public ItemStack toStack() {
            if (stack != null && !stack.isEmpty()) {
                return stack.copy();
            }
            Item it = resolve();
            return it == null ? ItemStack.EMPTY : new ItemStack(it);
        }

        @Override
        public String toString() {
            return index + "=" + item + (glint ? "+glint" : "") + (customName ? "+name" : "");
        }
    }

    /** 类型 -> 该类型用到的调色板（保持插入顺序，客户端必须按同一顺序建表） */
    private final Map<ResourceLocation, List<Entry>> byType = new HashMap<>();
    /** (类型, 物品+标记) -> 索引，避免线性查表 */
    private final Map<ResourceLocation, Map<String, Short>> lookup = new HashMap<>();

    /** 版本号：每次新增/清空 +1。客户端按它判断要不要收整表（参考 ProjectileTypesPacket 的做法） */
    private int version;

    public int version() {
        return version;
    }

    /**
     * 全局共享的一份调色板。
     *
     * <h2>为什么是全局的</h2>
     * 索引必须在「服务端发出去的快照」与「客户端收到的索引」之间**完全一致**，
     * 所以整局游戏只能有一张表。用全局单例而不是「每维度一份」也是这个原因：
     * 玩家跨维度传送时不该看到索引错位。
     */
    private static final EquipPalette SHARED = new EquipPalette();

    public static EquipPalette shared() {
        return SHARED;
    }

    /** 测试/重载用：清空全局表 */
    public static void resetShared() {
        SHARED.clearAll();
    }

    /** 把一个槽位名字转成下标；认不出来返回 -1 */
    public static int slotIndex(String name) {
        if (name == null) {
            return -1;
        }
        for (int i = 0; i < SLOT_NAMES.length; i++) {
            if (SLOT_NAMES[i].equalsIgnoreCase(name)) {
                return i;
            }
        }
        // 常见别名（GUI / 命令里手写时更宽容）
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "helmet", "hat", "头盔", "头" -> SLOT_HEAD;
            case "chestplate", "body", "胸甲", "胸" -> SLOT_CHEST;
            case "leggings", "legs", "护腿", "腿" -> SLOT_LEGS;
            case "boots", "feet", "靴子", "脚" -> SLOT_FEET;
            case "main", "mainhand", "hand", "主手" -> SLOT_MAINHAND;
            case "off", "offhand", "副手" -> SLOT_OFFHAND;
            default -> -1;
        };
    }

    /**
     * 取（必要时新增）某个物品在**该类型**调色板里的索引。
     *
     * @return 索引；空物品返回 {@link #EMPTY}；条数超上限返回 {@link #EMPTY} 并打一次警告
     */
    public short indexOf(ResourceLocation unitType, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return EMPTY;
        }
        ResourceLocation itemId = itemIdOf(stack);
        if (itemId == null) {
            return EMPTY;
        }
        boolean glint = glintOf(stack);
        boolean named = stack.hasCustomHoverName();
        return indexOf(unitType, itemId, glint, named, stack);
    }

    /** 同上，但直接按「物品 id + 标记」查表（拆包/回放时用得到） */
    public short indexOf(ResourceLocation unitType, ResourceLocation itemId, boolean glint, boolean named) {
        return indexOf(unitType, itemId, glint, named, null);
    }

    private short indexOf(ResourceLocation unitType, ResourceLocation itemId, boolean glint,
                          boolean named, ItemStack original) {
        if (unitType == null || itemId == null) {
            return EMPTY;
        }
        String key = key(itemId, glint, named);
        Map<String, Short> table = lookup.computeIfAbsent(unitType, k -> new HashMap<>());
        Short hit = table.get(key);
        if (hit != null) {
            return hit;
        }
        List<Entry> list = byType.computeIfAbsent(unitType, k -> new ArrayList<>());
        if (list.size() + 1 >= MAX_ENTRIES) {
            if (!warnedOverflow) {
                warnedOverflow = true;
                HordeMod.LOGGER.warn("[horde] 类型 {} 的装备调色板已达上限 {}，"
                        + "之后的装备会被忽略（这个上限远超实际需要，"
                        + "真遇到说明有人在刷装备种类）", unitType, MAX_ENTRIES);
            }
            return EMPTY;
        }
        short index = (short) (list.size() + 1);
        Entry entry = new Entry(index, itemId, glint, named, original);
        list.add(entry);
        table.put(key, index);
        version++;
        return index;
    }

    private boolean warnedOverflow;

    /** 按索引取条目（用于「反查这个索引是什么物品」，服务端/客户端都会用） */
    public Entry entry(ResourceLocation unitType, short index) {
        if (index == EMPTY) {
            return null;
        }
        List<Entry> list = byType.get(unitType);
        if (list == null) {
            return null;
        }
        int i = (index & 0xFFFF) - 1;
        return i >= 0 && i < list.size() ? list.get(i) : null;
    }

    /** 按索引还原成物品（服务端「交换/退还装备」用；空索引返回 {@link ItemStack#EMPTY}） */
    public ItemStack stack(ResourceLocation unitType, short index) {
        Entry e = entry(unitType, index);
        return e == null ? ItemStack.EMPTY : e.toStack();
    }

    /** 某类型当前有多少条 */
    public int size(ResourceLocation unitType) {
        List<Entry> list = byType.get(unitType);
        return list == null ? 0 : list.size();
    }

    /** 某类型的整表（客户端按它的顺序建索引；返回的是内部列表，不要改） */
    public List<Entry> entries(ResourceLocation unitType) {
        List<Entry> list = byType.get(unitType);
        return list == null ? List.of() : list;
    }

    public java.util.Set<ResourceLocation> types() {
        return byType.keySet();
    }

    /**
     * 清空某个类型的调色板。
     *
     * 什么时候用：所有该类型单位的装备都被重置之后。**不清空也不影响正确性**
     * （索引仍然自洽），只是白占内存，所以这是可选的整理动作，不做「按单位引用计数」
     * 那种容易算错的活儿。
     */
    public void clearType(ResourceLocation unitType) {
        byType.remove(unitType);
        lookup.remove(unitType);
        version++;
    }

    public void clearAll() {
        byType.clear();
        lookup.clear();
        version++;
    }

    /** 调色板占用估算（报告用）：条目数 × 每条约 40 字节（字符串 + 对象头，粗略值） */
    public int totalEntries() {
        int n = 0;
        for (List<Entry> l : byType.values()) {
            n += l.size();
        }
        return n;
    }

    // ------------------------------------------------------------------

    private static String key(ResourceLocation item, boolean glint, boolean named) {
        return item + (glint ? "|g" : "") + (named ? "|n" : "");
    }

    /** 物品 id；注册表没就绪时返回 null（离线 harness 里我们用直构的 ItemStack，通常没问题） */
    public static ResourceLocation itemIdOf(ItemStack stack) {
        try {
            return ForgeRegistries.ITEMS.getKey(stack.getItem());
        } catch (Throwable t) {
            return null;
        }
    }

    /** 是否附魔（决定要不要画光效）—— 原版 API，任何模组物品通用 */
    public static boolean glintOf(ItemStack stack) {
        try {
            return stack.isEnchanted();
        } catch (Throwable t) {
            return false;
        }
    }
}
