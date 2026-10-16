package dev.swordkingdom.horde.edit;

import dev.swordkingdom.horde.HordeMod;
import dev.swordkingdom.horde.crowd.CrowdData;
import dev.swordkingdom.horde.crowd.CrowdManager;
import dev.swordkingdom.horde.crowd.CrowdManagers;
import dev.swordkingdom.horde.crowd.EquipData;
import dev.swordkingdom.horde.network.EquipPackets;
import dev.swordkingdom.horde.network.HordeNetwork;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 装备的服务端应用入口（结构参考 {@link EditService}）。
 *
 * <h2>一次操作的完整链条</h2>
 * <pre>
 *   1. 把物品写进 {@link EquipPalette}（得到索引）—— 只有「装新东西」时才需要；
 *   2. 把索引写进该维度的 {@link EquipData}（纯数组写，零分配）；
 *   3. 给发起者回执（{@link EquipPackets.Result}）；
 *   4. 下一个同步 tick，{@link dev.swordkingdom.horde.network.EquipSync} 走**增量**把变化推给玩家，
 *      客户端据此取新的「变体网格」—— 所以「改完立刻生效」不需要额外通知
 *      （增量同步本身就是通知，延迟 ≤ 一个快照间隔 = 100 ms）。
 * </pre>
 *
 * <h2>为什么服务器要重算一遍属性</h2>
 * 装备会改 {@code TypeStats}（护甲/攻击力/攻速/击退抗性）与「怎么打」（远程/近战/举盾）。
 * 这些是**模拟层**的数据，所以由服务端在 {@link #applyTo} 里立刻重算，不等客户端。
 */
public final class EquipService {

    /** 批量操作一次最多影响多少个单位（玩家把整个战场换装也不该卡一帧） */
    public static final int MAX_BATCH = 4096;
    /** 右键拾取的最大距离（格） */
    public static final float PICK_RANGE = 8.0F;

    private EquipService() {}

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    /** 给一个单位套装（6 个调色板索引） */
    public static int applyUnit(ServerPlayer player, ResourceLocation dimension, int unitId,
                                short[] slots, byte flags) {
        CrowdManager m = manager(dimension);
        if (m == null) {
            return 0;
        }
        CrowdData d = m.data();
        if (unitId < 0 || unitId >= d.highWater || !d.aliveFlag[unitId]) {
            return 0;
        }
        EquipData eq = EquipData.of(m.dimension(), d.capacity);
        writeUnit(eq, unitId, slots, flags);
        reapplyUnit(m, unitId);
        return 1;
    }

    /** 批量装：按过滤条件一次扫完（不是一个单位一个包） */
    public static int applyBatch(ServerPlayer player, EquipPackets.ApplyBatch msg) {
        CrowdManager m = manager(msg.dimension);
        if (m == null) {
            return 0;
        }
        CrowdData d = m.data();
        EquipData eq = EquipData.of(m.dimension(), d.capacity);
        int mode = msg.mode;
        ResourceLocation filterType = msg.filterType;
        // 按类型过滤时先解析出类型索引（客户端给的是类型 id，不是索引 —— 更稳）
        int wantType = -1;
        if (mode == EquipPackets.ApplyBatch.MODE_TYPE && filterType != null) {
            wantType = m.typeIndexOf(filterType);
            if (wantType < 0) {
                return 0;
            }
        }

        int changed = 0;
        for (int s = 0; s < d.highWater && changed < MAX_BATCH; s++) {
            if (!d.aliveFlag[s]) {
                continue;
            }
            if (!matches(m, d, s, mode, wantType, msg.filterTeam,
                    msg.centerX, msg.centerY, msg.centerZ, msg.radius)) {
                continue;
            }
            writeUnit(eq, s, msg.slots, msg.flags);
            reapplyUnit(m, s);
            changed++;
        }
        if (changed >= MAX_BATCH) {
            HordeMod.LOGGER.info("[horde] 批量装备达到单次上限 {}，其余单位未改（再点一次即可）", MAX_BATCH);
        }
        return changed;
    }

    /**
     * 「把手上的东西装上去」。
     *
     * @param unitId &ge; 0 单个；-1 表示批量（按 {@code batchMode} 过滤）
     */
    public static int applyHeld(ServerPlayer player, EquipPackets.ApplyHeld msg) {
        CrowdManager m = manager(msg.dimension);
        if (m == null) {
            return 0;
        }
        CrowdData d = m.data();
        ItemStack held = player.getMainHandItem();
        if (held == null || held.isEmpty()) {
            return 0;
        }
        short index;
        if (msg.unitId >= 0) {
            ResourceLocation type = typeIdAt(m, msg.unitId);
            if (type == null) {
                return 0;
            }
            index = EquipPalette.shared().indexOf(type, held);
        } else {
            // 批量时物品要按**每个单位自己的类型**登记（同一件物品在不同类型的表里索引不同）
            index = 0;
        }

        int changed = 0;
        if (msg.unitId >= 0) {
            EquipData eq = EquipData.of(m.dimension(), d.capacity);
            if (msg.swap) {
                // 交换：把单位原来那件还给玩家（背包满就掉在地上由调用方处理，这里先塞回背包）
                short old = eq.slot(msg.unitId, msg.slot);
                ItemStack oldStack = EquipPalette.shared().stack(typeIdAt(m, msg.unitId), old);
                if (!oldStack.isEmpty()) {
                    give(player, oldStack);
                }
            }
            eq.setSlot(msg.unitId, msg.slot, index);
            reapplyUnit(m, msg.unitId);
            changed = 1;
        } else {
            int mode = msg.batchMode;
            int wantType = -1;
            if (mode == EquipPackets.ApplyBatch.MODE_TYPE && msg.filterType != null) {
                wantType = m.typeIndexOf(msg.filterType);
            }
            EquipData eq = EquipData.of(m.dimension(), d.capacity);
            for (int s = 0; s < d.highWater && changed < MAX_BATCH; s++) {
                if (!d.aliveFlag[s]) {
                    continue;
                }
                if (!matches(m, d, s, mode, wantType, msg.filterTeam,
                        player.getX(), player.getY(), player.getZ(), 32.0F)) {
                    continue;
                }
                // 每个单位按**自己的类型**登记一次（同一个物品在不同类型的调色板里索引可能不同）
                ResourceLocation type = typeIdAt(m, s);
                if (type == null) {
                    continue;
                }
                eq.setSlot(s, msg.slot, EquipPalette.shared().indexOf(type, held));
                reapplyUnit(m, s);
                changed++;
            }
        }
        return changed;
    }

    // ------------------------------------------------------------------
    // 拾取（右键射线）
    // ------------------------------------------------------------------

    /**
     * 服务端射线拾取：从玩家眼睛出发，沿视线找**最近**被命中的群集单位。
     *
     * 用单位自己的碰撞盒（宽 × 高）做**逐轴 slab 求交**，取最小 t。
     * 之所以在服务端做：客户端只发视线，命中判定不信任客户端。
     * 复杂度：先用 {@link CrowdManager#collectVisible} 把视线长度内的单位筛出来
     * （空间哈希），再逐个求交 —— 是「候选集 × 常数」，与场上单位总数无关。
     */
    public static EquipPackets.PickResult pick(ServerPlayer player, EquipPackets.Pick msg) {
        CrowdManager m = manager(msg.dimension);
        if (m == null) {
            return miss(msg.dimension, "该维度没有群集");
        }
        CrowdData d = m.data();
        if (d.alive == 0) {
            return miss(msg.dimension, "附近没有虚拟实体");
        }
        float range = Math.min(Math.max(1.0F, msg.maxDistance), 64.0F);
        // 候选集：以玩家为中心、半径 = 视线长度的圆（复用空间哈希，不用扫全场）
        int n = m.collectVisible((float) msg.originX, (float) msg.originZ, range,
                dev.swordkingdom.horde.HordeConfig.COMMON.syncBudgetPerPlayer.get());
        double dx = msg.dirX;
        double dy = msg.dirY;
        double dz = msg.dirZ;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0E-6D) {
            return miss(msg.dimension, "视线方向无效");
        }
        dx /= len;
        dy /= len;
        dz /= len;

        int best = -1;
        double bestT = range;
        for (int i = 0; i < n; i++) {
            int s = m.outSlots[i];
            if (!d.aliveFlag[s]) {
                continue;
            }
            CrowdManager.TypeStats st = m.statsOf(d.typeIndex[s] & 0x1F);
            float half = st != null ? st.halfWidth : 0.3F;
            float height = st != null ? st.height : 1.8F;
            double x0 = d.posX[s];
            double y0 = d.posY[s];
            double z0 = d.posZ[s];
            double t = rayBox(msg.originX, msg.originY, msg.originZ, dx, dy, dz,
                    x0 - half, y0, z0 - half, x0 + half, y0 + height, z0 + half, bestT);
            if (t >= 0.0D && t < bestT) {
                bestT = t;
                best = s;
            }
        }
        if (best < 0) {
            return miss(msg.dimension, "视线里没有虚拟实体（距离 " + fmt(range) + " 格内）");
        }
        EquipData eq = EquipData.getIfPresent(m.dimension());
        short[] slots = new short[EquipData.SLOT_COUNT];
        byte flags = 0;
        if (eq != null) {
            eq.slots(best, slots);
            flags = eq.flags(best);
        }
        return new EquipPackets.PickResult(msg.dimension, best, typeIdAt(m, best),
                d.team[best] & 0xFF, (float) bestT, slots, flags, "");
    }

    /**
     * 从射线原点沿方向求与 AABB 的最近交点（slab 法）。
     *
     * @return 命中时的 t（&gt;= 0）；不命中返回 -1；超过 {@code maxT} 也返回 -1
     */
    public static double rayBox(double ox, double oy, double oz, double dx, double dy, double dz,
                                double x0, double y0, double z0, double x1, double y1, double z1,
                                double maxT) {
        double tmin = 0.0D;
        double tmax = maxT;
        double[] o = {ox, oy, oz};
        double[] dir = {dx, dy, dz};
        double[] lo = {x0, y0, z0};
        double[] hi = {x1, y1, z1};
        for (int a = 0; a < 3; a++) {
            if (Math.abs(dir[a]) < 1.0E-9D) {
                if (o[a] < lo[a] || o[a] > hi[a]) {
                    return -1.0D;
                }
                continue;
            }
            double inv = 1.0D / dir[a];
            double t1 = (lo[a] - o[a]) * inv;
            double t2 = (hi[a] - o[a]) * inv;
            if (t1 > t2) {
                double tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            if (t1 > tmin) {
                tmin = t1;
            }
            if (t2 < tmax) {
                tmax = t2;
            }
            if (tmin > tmax) {
                return -1.0D;
            }
        }
        return tmin;
    }

    // ------------------------------------------------------------------
    // 客户端进服 / 进 GUI 时的整表下发
    // ------------------------------------------------------------------

    /** 把调色板整表发给某个玩家（进服、进 GUI、客户端说版本对不上时） */
    public static void sendPalette(ServerPlayer player) {
        dev.swordkingdom.horde.network.EquipSync.sendPalette(player);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private static void writeUnit(EquipData eq, int unit, short[] slots, byte flags) {
        for (int s = 0; s < EquipData.SLOT_COUNT; s++) {
            eq.setSlot(unit, s, slots != null && s < slots.length ? slots[s] : 0);
        }
        eq.setFlags(unit, flags);
    }

    /**
     * 装备变了以后立刻重算这个单位的有效属性。
     *
     * <h2>为什么这里只「算」不「写」</h2>
     * 把逐单位属性和 {@code CrowdManager} 的热循环接起来需要改 {@code CrowdManager.java}，
     * 而那不在本任务允许修改的文件清单里（并行开发正在改它）。
     * 所以这里通过 {@code CrowdManager} 上的一个**可见性挂钩**字段
     * （{@code CrowdManager.EQUIP_STATS_HOOK}，类型是 {@code Consumer<float[]>}）
     * 把「6 个槽的物品 + 算好的修正」交出去，由接线方决定怎么存。
     * 挂钩为 null（= 还没接线）时这个方法什么都不做，**日志里也不会谎报数值已生效**。
     *
     * 挂钩收到的数组布局（长度 = 10 + 6）：
     * <pre>
     *   [0] armor          [1] armorToughness  [2] knockbackResistance
     *   [3] attackDamage   [4] attackSpeed     [5] attackCooldownTicks(float)
     *   [6] moveSpeedScale [7] maxHealthBonus  [8] weaponKind(ordinal)  [9] anyEquipped(0/1)
     *   [10..15] 该单位 6 个槽的 ItemStack **调色板索引**（不是物品对象，避免在热路径上分配）
     * </pre>
     */
    /**
     * 逐单位属性写入的**挂钩**。
     *
     * <h2>为什么要有这个东西</h2>
     * 把逐单位属性和 {@code CrowdManager} 的热循环接起来必须改 {@code CrowdManager.java}，
     * 而那不在本任务允许修改的文件清单里（并行开发正在改它）。所以这里暴露一个
     * 三参数函数式接口，接线时在 {@code CrowdManager} 的构造函数里写一行：
     * <pre>
     *   dev.swordkingdom.horde.edit.EquipService.UNIT_STATS_HOOK = (mgr, unit, v) -&gt; {
     *       if (mgr.unitEquipStats == null || (unit + 1) * 16 &gt; mgr.unitEquipStats.length) return;
     *       int b = unit * 16;
     *       mgr.unitEquipStats[b]     = v[0];   // armor
     *       mgr.unitEquipStats[b + 1] = v[1];   // armorToughness
     *       mgr.unitEquipStats[b + 2] = v[2];   // knockbackResistance
     *       mgr.unitEquipStats[b + 3] = v[3];   // attackDamage
     *       mgr.unitEquipStats[b + 4] = v[5];   // attackCooldownTicks
     *       mgr.unitEquipStats[b + 5] = v[6];   // moveSpeedScale
     *       mgr.unitEquipStats[b + 6] = v[7];   // maxHealthBonus
     *       mgr.unitEquipStats[b + 7] = v[8];   // weaponKind.ordinal()
     *   };
     * </pre>
     * 挂钩为 null（= 还没接线）时 {@link #reapplyUnit} 什么都不做，
     * <b>日志里也不会谎报数值已生效</b>。
     *
     * <h2>载荷布局（长度 = 10 + 6）</h2>
     * <pre>
     *   [0] armor          [1] armorToughness  [2] knockbackResistance
     *   [3] attackDamage   [4] attackSpeed     [5] attackCooldownTicks(float)
     *   [6] moveSpeedScale [7] maxHealthBonus  [8] weaponKind(ordinal)  [9] anyEquipped(0/1)
     *   [10..15] 该单位 6 个槽的**调色板索引**（不是物品对象，避免热路径上分配）
     * </pre>
     * 约定：{@code values} 是**临时数组**，实现里必须当场消费完，不许留着引用。
     */
    @FunctionalInterface
    public interface UnitStatsSink {
        void accept(CrowdManager manager, int unit, float[] values);
    }

    public static volatile UnitStatsSink UNIT_STATS_HOOK;

    public static void reapplyUnit(CrowdManager m, int unit) {
        EquipData eq = EquipData.getIfPresent(m.dimension());
        if (eq == null) {
            return;
        }
        CrowdData d = m.data();
        if (unit < 0 || unit >= d.highWater || !d.aliveFlag[unit]) {
            return;
        }
        UnitStatsSink hook = UNIT_STATS_HOOK;
        if (hook == null) {
            return;   // 还没接线：数值层不生效（渲染层与行为判定不受影响）
        }
        ResourceLocation type = typeIdAt(m, unit);
        CrowdManager.TypeStats base = m.statsOf(d.typeIndex[unit] & 0x1F);
        EquipStats.Adjust adjust = adjustOf(type, eq, unit, base);
        if (adjust == null) {
            return;
        }
        hook.accept(m, unit, valuesOf(adjust, unit, m, eq, type));
    }

    /**
     * 把修正摊平成 {@code float[]}（挂钩的载荷）。
     *
     * 用数组而不是对象：这个方法在「批量给 1000 个单位换装」时会被调用 1000 次，
     * 每次 new 一个对象就是 1000 次分配 —— 群集引擎从第一天起就在避免这种事。
     */
    public static float[] valuesOf(EquipStats.Adjust adjust, int unit, CrowdManager m,
                                   EquipData eq, ResourceLocation type) {
        float[] v = new float[10 + EquipData.SLOT_COUNT];
        v[0] = adjust.armor;
        v[1] = adjust.armorToughness;
        v[2] = adjust.knockbackResistance;
        v[3] = adjust.attackDamage;
        v[4] = adjust.attackSpeed;
        v[5] = adjust.attackCooldownTicks;
        v[6] = adjust.moveSpeedScale;
        v[7] = adjust.maxHealthBonus;
        v[8] = adjust.weaponKind.ordinal();
        v[9] = adjust.anyEquipped ? 1.0F : 0.0F;
        for (int s = 0; s < EquipData.SLOT_COUNT; s++) {
            v[10 + s] = eq.slot(unit, s);
        }
        return v;
    }

    /** 纯计算：算出这个单位穿上装备后的修正（也供离线 harness 验证） */
    public static EquipStats.Adjust adjustOf(ResourceLocation type, EquipData eq, int unit,
                                             CrowdManager.TypeStats base) {
        if (eq == null || base == null) {
            return null;
        }
        short[] six = new short[EquipData.SLOT_COUNT];
        eq.slots(unit, six);
        ItemStack[] stacks = new ItemStack[EquipData.SLOT_COUNT];
        for (int s = 0; s < EquipData.SLOT_COUNT; s++) {
            stacks[s] = EquipPalette.shared().stack(type, six[s]);
        }
        float attackSpeed = 20.0F / Math.max(1, base.attackCooldownTicks);
        return EquipStats.fromStacks(stacks, base.damage, base.armor, base.armorToughness,
                base.knockbackResistance, base.moveSpeed, base.maxHp, attackSpeed, null);
    }

    /** 该单位的实体类型 id（越界返回 null） */
    public static ResourceLocation typeIdAt(CrowdManager m, int unit) {
        CrowdData d = m.data();
        if (unit < 0 || unit >= d.highWater) {
            return null;
        }
        int idx = d.typeIndex[unit] & 0x1F;
        List<ResourceLocation> ids = m.typeIds();
        return idx >= 0 && idx < ids.size() ? ids.get(idx) : null;
    }

    private static boolean matches(CrowdManager m, CrowdData d, int s, int mode, int wantType,
                                   int team, double cx, double cy, double cz, float radius) {
        switch (mode) {
            case EquipPackets.ApplyBatch.MODE_TYPE:
                return wantType < 0 || (d.typeIndex[s] & 0x1F) == wantType;
            case EquipPackets.ApplyBatch.MODE_TEAM:
                return (d.team[s] & 0xFF) == team;
            case EquipPackets.ApplyBatch.MODE_RADIUS:
                float ox = d.posX[s] - (float) cx;
                float oz = d.posZ[s] - (float) cz;
                float r = Math.max(1.0F, Math.min(radius, 256.0F));
                return ox * ox + oz * oz <= r * r;
            case EquipPackets.ApplyBatch.MODE_ALL:
            default:
                return true;
        }
    }

    private static CrowdManager manager(ResourceLocation dimension) {
        if (dimension == null || HordeMod.server == null) {
            return null;
        }
        for (ServerLevel level : HordeMod.server.getAllLevels()) {
            if (level.dimension().location().equals(dimension)) {
                return CrowdManagers.getIfPresent(level.dimension());
            }
        }
        return null;
    }

    private static EquipPackets.PickResult miss(ResourceLocation dim, String reason) {
        return new EquipPackets.PickResult(dim, -1, null, -1, 0.0F,
                new short[EquipData.SLOT_COUNT], (byte) 0, reason);
    }

    /** 把一件物品塞给玩家；装不下就丢在他脚下（和原版「交换」的体感一致） */
    private static void give(ServerPlayer player, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    private static String fmt(float v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }
}
