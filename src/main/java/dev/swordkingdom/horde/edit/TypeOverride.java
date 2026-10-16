package dev.swordkingdom.horde.edit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.swordkingdom.horde.HordeMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * **全局共享**的实体覆盖表：玩家在游戏内 GUI（或 `/horde edit`）里改的东西都存在这里。
 *
 * 三种内容：
 *  - **模型**：换成任意已注册实体类型的模型（直接复用模组自己的模型/渲染器，不写死任何模组）；
 *  - **动作/技能**：指定播放它自己的第几个动画（清单来自该实体自己的动作 API），
 *    以及攻击/技能参数（伤害、范围、间隔、命中帧、击退）；
 *  - **属性**：生命、移速、攻击力、索敌、护甲、碰撞盒、击退抗性、攻击冷却。
 *
 * 字段用「哨兵值」表示未覆盖（数值 -1、字符串空），这样能区分「没改」和「改成 0」。
 * 改动立刻生效：服务端重算该类型的 {@code TypeStats}，客户端重新烘焙该类型的网格。
 * 存盘位置：{@code config/horde-overrides.json}（全局，不随存档）。
 */
public final class TypeOverride {

    /** 未覆盖的数值哨兵 */
    public static final float UNSET_F = -1.0F;
    public static final int UNSET_I = -1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<ResourceLocation, TypeOverride> TABLE = new LinkedHashMap<>();
    private static Path file;

    // ---- 模型 ----
    /** 用哪个实体类型的模型渲染（空 = 用它自己的） */
    public String model = "";

    // ---- 动作 / 攻击 ----
    /** -1 = 自动轮换，0 = 不播动作，k = 固定播第 k 个动画 */
    public int action = UNSET_I;
    public float damageScale = UNSET_F;
    public float radiusScale = UNSET_F;
    public float knockbackScale = UNSET_F;
    public float impactFraction = UNSET_F;
    public int intervalTicks = UNSET_I;

    // ---- 模型整体变换（乘/加到渲染器自身变换之上）----
    /** 整体缩放（-1 = 用渲染器自己的 scale） */
    public float modelScaleX = UNSET_F;
    public float modelScaleY = UNSET_F;
    public float modelScaleZ = UNSET_F;
    /** 整体位置偏移（格；-1 = 不偏移 —— 需要负偏移时用 -1000 之类的兜底值见 offsetUnset） */
    public float modelOffsetX = OFFSET_UNSET;
    public float modelOffsetY = OFFSET_UNSET;
    public float modelOffsetZ = OFFSET_UNSET;

    /**
     * 逐部件（骨骼）编辑：部件名 -> 变换。
     *
     * 部件名来自模型自己（Citadel 的 boxName / GeckoLib 的 getName / 原版 ModelPart 的字段路径），
     * 所以配置写的是「模型里那个部件的名字」，换模型后能对得上。
     */
    public Map<String, BoneEdit> bones = new LinkedHashMap<>();

    // ---- 技能（虚拟投掷物）：动作编号 -> 该动作的投掷物配置 ----
    /**
     * 键 = 动作编号（1..actionCount，和 {@code ActionDriver} 的编号一致；0 = 近战攻击那一下）。
     * 值 = 那个动作命中帧要发射的虚拟投掷物配置。
     *
     * 用 Map 而不是数组：动作编号是**该实体自己的**动画清单下标（对任何模组通用），
     * 数量可多可少，用 Map 天然支持「只配了第 3 个动作」这种情况。
     */
    public Map<String, ProjectileSkill> actions = new LinkedHashMap<>();

    // ---- 全局投掷物项（不属于某个动作）----
    /** 本类型同时存在的虚拟投掷物上限（-1 = 用全局配置） */
    public int maxProjectiles = UNSET_I;
    /** 该类型的投掷物是否伤害真实实体（-1 = 未设 / 0 = 否 / 1 = 是） */
    public int hurtRealEntities = UNSET_I;

    /** 取某个动作编号的技能配置（没有就返回 null） */
    public ProjectileSkill action(int actionIndex) {
        return actions == null ? null : actions.get(Integer.toString(actionIndex));
    }

    /** 取（必要时创建）某个动作编号的可变配置 */
    public ProjectileSkill mutableAction(int actionIndex) {
        if (actions == null) {
            actions = new LinkedHashMap<>();
        }
        return actions.computeIfAbsent(Integer.toString(actionIndex), k -> new ProjectileSkill());
    }

    /** 该类型是否配了任何投掷物技能 */
    public boolean hasProjectileSkills() {
        if (actions == null || actions.isEmpty()) {
            return false;
        }
        for (ProjectileSkill s : actions.values()) {
            if (s != null && !s.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** 单个部件的覆盖：显隐 + 位置偏移 + 缩放 */
    public static final class BoneEdit {
        /** -1 = 不改，0 = 隐藏，1 = 强制显示 */
        public int visible = UNSET_I;
        public float offX = OFFSET_UNSET;
        public float offY = OFFSET_UNSET;
        public float offZ = OFFSET_UNSET;
        public float scaleX = UNSET_F;
        public float scaleY = UNSET_F;
        public float scaleZ = UNSET_F;

        public boolean isEmpty() {
            return visible == UNSET_I
                    && offX == OFFSET_UNSET && offY == OFFSET_UNSET && offZ == OFFSET_UNSET
                    && scaleX == UNSET_F && scaleY == UNSET_F && scaleZ == UNSET_F;
        }

        public BoneEdit copy() {
            BoneEdit b = new BoneEdit();
            b.visible = visible;
            b.offX = offX;
            b.offY = offY;
            b.offZ = offZ;
            b.scaleX = scaleX;
            b.scaleY = scaleY;
            b.scaleZ = scaleZ;
            return b;
        }
    }

    /** 位置偏移的「未设置」哨兵：允许真实的负偏移（-1 = 未设置，所以用 -9999 表示"没有偏移"） */
    public static final float OFFSET_UNSET = -9999.0F;

    /** 该类型某个部件的覆盖（没有就返回 null） */
    public BoneEdit bone(String name) {
        return bones == null ? null : bones.get(name);
    }

    public BoneEdit mutableBone(String name) {
        if (bones == null) {
            bones = new LinkedHashMap<>();
        }
        return bones.computeIfAbsent(name, k -> new BoneEdit());
    }
    public float maxHp = UNSET_F;
    public float moveSpeed = UNSET_F;
    public float damage = UNSET_F;
    public float followRange = UNSET_F;
    public float armor = UNSET_F;
    public float armorToughness = UNSET_F;
    public float knockbackResistance = UNSET_F;
    public float width = UNSET_F;
    public float height = UNSET_F;
    public float eyeHeight = UNSET_F;
    public int attackCooldownTicks = UNSET_I;

    /**
     * 远程攻击距离（格）的覆盖项（-1 = 用全局配置）。
     *
     * 为什么单独一项：远程距离高度依赖该实体「本来怎么打」——
     * 骷髅十几格、法师可能 24 格、而扔雪球的雪傀儡只有 10 格量级。
     * 玩家在技能页签里配这个类型的弹幕时，顺手就能把它调到和真实实体一致。
     */
    public float rangedRange = UNSET_F;

    /** 属性字段（上面那批）之后，是模型整体变换与逐部件覆盖 */

    public TypeOverride copy() {
        TypeOverride o = new TypeOverride();
        o.model = model;
        o.action = action;
        o.damageScale = damageScale;
        o.radiusScale = radiusScale;
        o.knockbackScale = knockbackScale;
        o.impactFraction = impactFraction;
        o.intervalTicks = intervalTicks;
        o.maxHp = maxHp;
        o.moveSpeed = moveSpeed;
        o.damage = damage;
        o.followRange = followRange;
        o.armor = armor;
        o.armorToughness = armorToughness;
        o.knockbackResistance = knockbackResistance;
        o.width = width;
        o.height = height;
        o.eyeHeight = eyeHeight;
        o.attackCooldownTicks = attackCooldownTicks;
        o.rangedRange = rangedRange;
        o.modelScaleX = modelScaleX;
        o.modelScaleY = modelScaleY;
        o.modelScaleZ = modelScaleZ;
        o.modelOffsetX = modelOffsetX;
        o.modelOffsetY = modelOffsetY;
        o.modelOffsetZ = modelOffsetZ;
        o.bones = new LinkedHashMap<>();
        if (bones != null) {
            for (Map.Entry<String, BoneEdit> e : bones.entrySet()) {
                if (e.getValue() != null && !e.getValue().isEmpty()) {
                    o.bones.put(e.getKey(), e.getValue().copy());
                }
            }
        }
        o.actions = new LinkedHashMap<>();
        if (actions != null) {
            for (Map.Entry<String, ProjectileSkill> e : actions.entrySet()) {
                if (e.getValue() != null && !e.getValue().isEmpty()) {
                    o.actions.put(e.getKey(), e.getValue().copy());
                }
            }
        }
        o.maxProjectiles = maxProjectiles;
        o.hurtRealEntities = hurtRealEntities;
        return o;
    }

    /** 是否什么都没覆盖 */
    public boolean isEmpty() {
        return model.isEmpty() && action == UNSET_I && damageScale == UNSET_F && radiusScale == UNSET_F
                && knockbackScale == UNSET_F && impactFraction == UNSET_F && intervalTicks == UNSET_I
                && maxHp == UNSET_F && moveSpeed == UNSET_F && damage == UNSET_F && followRange == UNSET_F
                && armor == UNSET_F && armorToughness == UNSET_F && knockbackResistance == UNSET_F
                && width == UNSET_F && height == UNSET_F && eyeHeight == UNSET_F && attackCooldownTicks == UNSET_I
                && rangedRange == UNSET_F
                && modelScaleX == UNSET_F && modelScaleY == UNSET_F && modelScaleZ == UNSET_F
                && modelOffsetX == OFFSET_UNSET && modelOffsetY == OFFSET_UNSET && modelOffsetZ == OFFSET_UNSET
                && (bones == null || bones.isEmpty())
                && !hasProjectileSkills()
                && maxProjectiles == UNSET_I && hurtRealEntities == UNSET_I;
    }

    // ------------------------------------------------------------------
    // 覆盖表的读写
    // ------------------------------------------------------------------

    public static TypeOverride get(ResourceLocation type) {
        return TABLE.get(type);
    }

    /** 取（必要时创建）可变副本；调用方改完后要 {@link #put} */
    public static TypeOverride mutable(ResourceLocation type) {
        TypeOverride o = TABLE.get(type);
        if (o == null) {
            o = new TypeOverride();
            TABLE.put(type, o);
        }
        return o;
    }

    public static void put(ResourceLocation type, TypeOverride override) {
        if (override == null || override.isEmpty()) {
            TABLE.remove(type);
        } else {
            TABLE.put(type, override);
        }
        save();
    }

    public static void reset(ResourceLocation type) {
        TABLE.remove(type);
        save();
    }

    public static Map<ResourceLocation, TypeOverride> all() {
        return TABLE;
    }

    /** 该类型实际使用的模型类型（考虑覆盖） */
    public static ResourceLocation modelFor(ResourceLocation type) {
        TypeOverride o = TABLE.get(type);
        if (o == null || o.model == null || o.model.isEmpty()) {
            return type;
        }
        ResourceLocation parsed = ResourceLocation.tryParse(o.model);
        return parsed == null ? type : parsed;
    }

    // ------------------------------------------------------------------
    // 存盘
    // ------------------------------------------------------------------

    private static Path file() {
        if (file == null) {
            file = FMLPaths.CONFIGDIR.get().resolve("horde-overrides.json");
        }
        return file;
    }

    public static void load() {
        TABLE.clear();
        Path p = file();
        if (!Files.isRegularFile(p)) {
            return;
        }
        try {
            String json = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            Map<String, TypeOverride> raw = GSON.fromJson(json,
                    new TypeToken<LinkedHashMap<String, TypeOverride>>() { }.getType());
            if (raw == null) {
                return;
            }
            for (Map.Entry<String, TypeOverride> e : raw.entrySet()) {
                ResourceLocation id = ResourceLocation.tryParse(e.getKey());
                if (id != null && e.getValue() != null) {
                    TABLE.put(id, e.getValue());
                }
            }
            HordeMod.LOGGER.info("[horde] 已载入 {} 条实体覆盖", TABLE.size());
        } catch (Throwable t) {
            HordeMod.LOGGER.warn("[horde] 覆盖表读取失败（忽略，用默认值）: {}", t.toString());
        }
    }

    public static void save() {
        Path p = file();
        try {
            Map<String, TypeOverride> raw = new LinkedHashMap<>();
            for (Map.Entry<ResourceLocation, TypeOverride> e : TABLE.entrySet()) {
                if (!e.getValue().isEmpty()) {
                    raw.put(e.getKey().toString(), e.getValue());
                }
            }
            Files.createDirectories(p.getParent());
            Files.write(p, GSON.toJson(raw).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            HordeMod.LOGGER.warn("[horde] 覆盖表写入失败: {}", e.toString());
        }
    }
}
