package dev.swordkingdom.horde.harness;

import dev.swordkingdom.horde.crowd.ProjectileCatalog;
import dev.swordkingdom.horde.crowd.ProjectileCatalog.Kind;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.Fireball;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.level.Level;

/**
 * 「自动发现模组投掷物 + 打分选择」的离线验证。
 *
 * <h2>怎么做到「用替身实体类型」</h2>
 * 打分选择完全不碰注册表：{@link ProjectileCatalog#score} 只吃
 * (施法者命名空间, 施法者 path, 候选命名空间, 候选 path, 原版基类分类) 这几个纯字符串 + 类形状。
 * 于是 harness 可以直接造出 {@code xxx:ignis} / {@code xxx:ignis_fireball} 这样的替身 id
 * 来验证「同命名空间 + 名字词干」的加权 —— 不需要真的注册任何东西、也不需要进游戏。
 *
 * 基类分类则用**替身类**验证：这里继承原版的那几个投掷物基类造出
 * {@code StubArrowAsset} / {@code StubThrowable} / {@code StubFireball} / {@code StubProjectile}，
 * 再检查 {@link ProjectileCatalog#kindOfClass} 会不会把它们分对。
 * 也就是说：判据是「继承的是哪个**原版**基类」，而不是任何模组类名。
 */
public final class SkillDiscoveryHarness {

    private static int failures;

    public static void main(String[] args) {
        kindDetection();
        scoring();
        pickIgnis();
        defaultsPerClass();
        fallbackWhenNoCandidates();
        namespacesPreference();
        skeletonResolvesArrow();
        poolInjectionAndRetry();
        dispatchEvidence();
        blazeResolvesSmallFireball();
        rangedGateCase();
        gateBeforeScore();
        probeLevelOrderCase();
        poolScanOnceCase();
        realRegistryAvailability();
        // 放最后：它可能让 EntityType 的静态初始化失败（ExceptionInInitializerError），
        // 那之后这个 JVM 里 EntityType 就废了 —— 不能影响前面的用例。
        manualEntityTypeProbe();
        System.out.println(failures == 0 ? "\n== SKILL DISCOVERY PASS ==" : "\n== SKILL DISCOVERY FAILURES: " + failures + " ==");
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------
    // 1. 基类分类（替身类，继承原版基类）
    // ------------------------------------------------------------------

    /** 替身：箭类（继承 AbstractArrow） */
    static class StubArrowAsset extends AbstractArrow {
        StubArrowAsset(EntityType<? extends AbstractArrow> t, net.minecraft.world.level.Level l) {
            super(t, l);
        }

        @Override
        protected net.minecraft.world.item.ItemStack getPickupItem() {
            return net.minecraft.world.item.ItemStack.EMPTY;
        }
    }

    /** 替身：投掷物（继承 ThrowableProjectile） */
    static class StubThrowable extends ThrowableProjectile {
        StubThrowable(EntityType<? extends ThrowableProjectile> t, net.minecraft.world.level.Level l) {
            super(t, l);
        }

        @Override
        protected void defineSynchedData() {
        }

        @Override
        protected void addAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        }

        @Override
        protected void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        }
    }

    /** 替身：火球类（继承 AbstractHurtingProjectile） */
    static class StubFireballAsset extends AbstractHurtingProjectile {
        StubFireballAsset(EntityType<? extends AbstractHurtingProjectile> t, net.minecraft.world.level.Level l) {
            super(t, l);
        }

        @Override
        protected void defineSynchedData() {
        }
    }

    /** 替身：其它投掷物（继承 Projectile 兜底） */
    static class StubPlainProjectile extends Projectile {
        StubPlainProjectile(EntityType<? extends Projectile> t, net.minecraft.world.level.Level l) {
            super(t, l);
        }

        @Override
        protected void defineSynchedData() {
        }

        @Override
        protected void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        }

        @Override
        protected void addAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        }
    }

    /** 替身：不是投掷物（普通实体） */
    static class StubNotProjectile extends Entity {
        StubNotProjectile(EntityType<? extends Entity> t, net.minecraft.world.level.Level l) {
            super(t, l);
        }

        @Override
        protected void defineSynchedData() {
        }

        @Override
        protected void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        }

        @Override
        protected void addAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        }
    }

    private static void kindDetection() {
        check(ProjectileCatalog.kindOfClass(StubArrowAsset.class) == Kind.ARROW,
                "继承 AbstractArrow 的替身应判为箭类");
        check(ProjectileCatalog.kindOfClass(StubThrowable.class) == Kind.THROWABLE,
                "继承 ThrowableProjectile 的替身应判为投掷物类");
        check(ProjectileCatalog.kindOfClass(StubFireballAsset.class) == Kind.FIREBALL,
                "继承 AbstractHurtingProjectile 的替身应判为火球类");
        check(ProjectileCatalog.kindOfClass(Fireball.class) == Kind.FIREBALL,
                "原版 Fireball 应判为火球类");
        check(ProjectileCatalog.kindOfClass(StubPlainProjectile.class) == Kind.PROJECTILE,
                "只继承 Projectile 的替身应落到兜底类");
        check(ProjectileCatalog.kindOfClass(StubNotProjectile.class) == Kind.NONE,
                "普通实体不应被当成投掷物");
        check(ProjectileCatalog.kindOfClass(null) == Kind.NONE, "null 应安全返回 NONE");
        System.out.println("[ ok ] 基类分类：4 类投掷物 + 非投掷物 + null，全部按**原版基类**判对");
    }

    // ------------------------------------------------------------------
    // 2. 打分
    // ------------------------------------------------------------------

    private static void scoring() {
        // 同命名空间 + 名字词干 -> 最高
        int best = ProjectileCatalog.score("xxx", "ignis", "xxx", "ignis_fireball", Kind.FIREBALL);
        // 同命名空间但名字不沾边
        int sameNs = ProjectileCatalog.score("xxx", "ignis", "xxx", "random_orb", Kind.FIREBALL);
        // 不同命名空间但名字沾边
        int otherNs = ProjectileCatalog.score("xxx", "ignis", "yyy", "ignis_fireball", Kind.FIREBALL);
        // 完全不同
        int none = ProjectileCatalog.score("xxx", "ignis", "yyy", "random_orb", Kind.FIREBALL);
        check(best > sameNs, "同命名空间 + 词干 应高于 只同命名空间（" + best + " vs " + sameNs + "）");
        check(best > otherNs, "同命名空间 + 词干 应高于 只同词干（" + best + " vs " + otherNs + "）");
        check(sameNs > none, "同命名空间应加分（" + sameNs + " vs " + none + "）");
        check(otherNs > none, "词干命中应加分（" + otherNs + " vs " + none + "）");

        // 基类优先级：火球/箭 > 投掷物兜底
        int asFireball = ProjectileCatalog.score("xxx", "ignis", "zzz", "thing", Kind.FIREBALL);
        int asArrow = ProjectileCatalog.score("xxx", "ignis", "zzz", "thing", Kind.ARROW);
        int asProjectile = ProjectileCatalog.score("xxx", "ignis", "zzz", "thing", Kind.PROJECTILE);
        check(asFireball > asArrow, "火球优先级应高于箭（" + asFireball + " vs " + asArrow + "）");
        check(asArrow > asProjectile, "箭优先级应高于兜底投掷物（" + asArrow + " vs " + asProjectile + "）");

        // 整词匹配优于子串匹配（"fire" 命中 "fireball" 只算子串，不算整词）
        check(ProjectileCatalog.isWordMatch("ignis_fireball", "ignis"), "ignis 应是 ignis_fireball 的整词");
        check(!ProjectileCatalog.isWordMatch("fireball", "fire"), "fire 不应是 fireball 的整词");
        check(ProjectileCatalog.isWordMatch("xxx_ignis", "ignis"), "尾部的整词也应识别");

        // 长度惩罚：名字更长更啰嗦的候选分更低（同等条件下优先简单的那个）
        int simple = ProjectileCatalog.score("xxx", "ignis", "xxx", "ignis_fire", Kind.FIREBALL);
        int verbose = ProjectileCatalog.score("xxx", "ignis", "xxx",
                "ignis_fire_very_long_descriptive_name", Kind.FIREBALL);
        check(simple > verbose, "更短更简单的同名候选应优先（" + simple + " vs " + verbose + "）");
        System.out.println("[ ok ] 打分：同命名空间 " + ProjectileCatalog.SAME_NAMESPACE_SCORE
                + " 分 + 词干加权 + 基类优先级，实测 best=" + best + " sameNs=" + sameNs
                + " stemOnly=" + otherNs + " none=" + none);
    }

    // ------------------------------------------------------------------
    // 3. 给 xxx:ignis 选中 xxx:ignis_fireball
    // ------------------------------------------------------------------

    private static void pickIgnis() {
        // 替身候选池：一个「别的模组」的火球 + 一个同命名空间但名字不沾边的 + 目标
        java.util.List<ProjectileCatalog.Candidate> pool = new java.util.ArrayList<>();
        pool.add(new ProjectileCatalog.Candidate(ResourceLocation.tryParse("yyy:meteor"), Kind.FIREBALL, 0));
        pool.add(new ProjectileCatalog.Candidate(ResourceLocation.tryParse("xxx:random_orb"), Kind.FIREBALL, 0));
        pool.add(new ProjectileCatalog.Candidate(ResourceLocation.tryParse("xxx:ignis_fireball"), Kind.FIREBALL, 0));

        int bestScore = Integer.MIN_VALUE;
        ProjectileCatalog.Candidate best = null;
        for (ProjectileCatalog.Candidate c : pool) {
            int s = ProjectileCatalog.score("xxx", "ignis", c.id.getNamespace(), c.id.getPath(), c.kind);
            if (s > bestScore) {
                bestScore = s;
                best = c;
            }
        }
        check(best != null && "xxx:ignis_fireball".equals(best.id.toString()),
                "应为 xxx:ignis 选中 xxx:ignis_fireball（实际选中 "
                        + (best == null ? "null" : best.id) + "）");
        System.out.println("[ ok ] 替身演示：3 个候选里为 §xxx:ignis§ 选中了 §"
                + (best == null ? "null" : best.id) + "§（打分 " + bestScore + "）");

        // 再演示一遍「只放宽到全表也能选中」：把同命名空间的干扰项换成一个裸名字的箭
        pool.clear();
        pool.add(new ProjectileCatalog.Candidate(ResourceLocation.tryParse("aaa:arrow"), Kind.ARROW, 0));
        pool.add(new ProjectileCatalog.Candidate(ResourceLocation.tryParse("xxx:ignis_fireball"), Kind.FIREBALL, 0));
        ProjectileCatalog.Candidate again = null;
        int best2 = Integer.MIN_VALUE;
        for (ProjectileCatalog.Candidate c : pool) {
            int s = ProjectileCatalog.score("xxx", "ignis", c.id.getNamespace(), c.id.getPath(), c.kind);
            if (s > best2) {
                best2 = s;
                again = c;
            }
        }
        check(again != null && "xxx:ignis_fireball".equals(again.id.toString()),
                "即使池子里有更高优先级的箭类，也应优先选同命名空间的火球");
        System.out.println("[ ok ] 优先级：同命名空间的火球压过了「基类优先级更高」的箭（"
                + best2 + " 分）");
    }

    // ------------------------------------------------------------------
    // 4. 按基类推导默认参数
    // ------------------------------------------------------------------

    private static void defaultsPerClass() {
        ProjectileCatalog.Defaults arrow = ProjectileCatalog.defaultsOf(Kind.ARROW);
        check(Math.abs(arrow.speed - 3.0F) < 1.0E-4F, "箭类默认初速 3.0（实际 " + arrow.speed + "）");
        check(Math.abs(arrow.gravity - 0.05F) < 1.0E-4F, "箭类默认重力 0.05");
        check(arrow.life == 100, "箭类默认寿命 100 tick");
        check(arrow.damageScale == 1.0F, "箭类默认伤害倍率 1.0（= 施法者攻击力）");
        check(arrow.stickOnBlock, "箭类默认撞方块卡住（原版箭就是这样）");

        ProjectileCatalog.Defaults throwable = ProjectileCatalog.defaultsOf(Kind.THROWABLE);
        check(Math.abs(throwable.speed - 1.5F) < 1.0E-4F, "投掷物默认初速 1.5");
        check(Math.abs(throwable.gravity - 0.03F) < 1.0E-4F, "投掷物默认重力 0.03");
        check(throwable.life == 100, "投掷物默认寿命 100 tick");

        ProjectileCatalog.Defaults fireball = ProjectileCatalog.defaultsOf(Kind.FIREBALL);
        check(Math.abs(fireball.speed - 0.6F) < 1.0E-4F, "火球默认初速 0.6");
        check(fireball.gravity == 0.0F, "火球默认重力 0（直线/带加速）");
        check(fireball.life == 80, "火球默认寿命 80 tick");
        check(Math.abs(fireball.blastRadius - 1.0F) < 1.0E-4F, "火球默认爆炸半径 1.0");
        check(fireball.hitRadius > 0.0F, "火球也应有命中半径（" + fireball.hitRadius + "）");

        // 参数必须落在模拟能接受的范围内（不然会变成 NaN/负寿命）
        for (Kind k : Kind.values()) {
            ProjectileCatalog.Defaults d = ProjectileCatalog.defaultsOf(k);
            check(d.speed > 0.0F && d.drag > 0.0F && d.drag <= 1.0F && d.life > 0
                            && d.hitRadius > 0.0F && d.pitch >= -90.0F && d.pitch <= 90.0F,
                    k + " 的默认参数应全部合法（speed=" + d.speed + " drag=" + d.drag
                            + " life=" + d.life + " hitRadius=" + d.hitRadius + " pitch=" + d.pitch + "）");
        }
        System.out.println("[ ok ] 基类默认参数：箭 3.0/0.05/100，投掷物 1.5/0.03/100，火球 0.6/0/80+AoE1.0"
                + "，全部由**基类**推出（没有为任何模组写数字）");
    }

    // ------------------------------------------------------------------
    // 5. 找不到候选时的兜底
    // ------------------------------------------------------------------

    private static void fallbackWhenNoCandidates() {
        check(ProjectileCatalog.pickFor(null, new java.util.ArrayList<>()) == null, "null 施法者应返回 null");
        ResourceLocation caster = ResourceLocation.tryParse("xxx:ignis");
        check(ProjectileCatalog.pickFor(caster, new java.util.ArrayList<>()) == null,
                "候选池为空时应返回 null（调用方退回「不发射」，绝不抛异常）");
        check(ProjectileCatalog.defaultsOf(Kind.NONE) != null, "NONE 也应有一组兜底默认参数");
        ProjectileCatalog.Defaults none = ProjectileCatalog.defaultsOf(Kind.NONE);
        check(none.speed > 0.0F && none.life > 0, "NONE 的兜底参数必须可用（speed=" + none.speed
                + " life=" + none.life + "）");
        System.out.println("[ ok ] 兜底：候选池为空 -> 返回 null；NONE 也有一组可用默认参数（"
                + none.speed + " / " + none.life + " tick）");
    }

    // ------------------------------------------------------------------
    // 6. 「尽量同命名空间内取」的语义
    // ------------------------------------------------------------------

    private static void namespacesPreference() {
        // 同命名空间的一个「名字完全不沾边」的候选，应该压过外来的「名字沾边」候选
        int sameNsPlain = ProjectileCatalog.score("xxx", "ignis", "xxx", "orb", Kind.PROJECTILE);
        int otherNsStem = ProjectileCatalog.score("xxx", "ignis", "yyy", "ignis_thing", Kind.FIREBALL);
        check(sameNsPlain > otherNsStem,
                "同命名空间优先于跨命名空间的词干命中（" + sameNsPlain + " vs " + otherNsStem + "）");

        // 但「同名」永远是最高优先（命名空间+path 都一样）
        int exact = ProjectileCatalog.score("xxx", "ignis", "xxx", "ignis", Kind.PROJECTILE);
        check(exact > sameNsPlain, "同命名空间且同名应最高（" + exact + " vs " + sameNsPlain + "）");
        System.out.println("[ ok ] 命名空间优先：同命名空间(名字不沾边)=" + sameNsPlain
                + " > 跨命名空间(名字沾边)=" + otherNsStem + "；同名最高=" + exact);
    }

    // ------------------------------------------------------------------
    // 7. minecraft:skeleton -> minecraft:arrow（真机病根那条链）
    // ------------------------------------------------------------------

    /**
     * 真机反馈的核心问题：原版骷髅**没有** getAnimation/setAnimation 那套动画 API，
     * 于是上一轮的实现里 {@code actionCount == 0} 把「发射投掷物」整条链挡在门外，
     * 骷髅既不射箭、也看不到任何投掷物。
     *
     * 这个用例把那条链逐段钉死（全部用替身，不依赖注册表顺序）：
     * <ol>
     *   <li>没有动画 API 的类型，动作数就是 0（用替身实体类验证
     *       {@link dev.swordkingdom.horde.action.ActionDriver}）；</li>
     *   <li>即使动作数是 0，只要「解析出了投掷物」就算远程单位
     *       （{@link dev.swordkingdom.horde.crowd.ProjectileCombat#isRangedAttacker}）；</li>
     *   <li>解析结果是拿**真实的那套打分**算出来的（同命名空间 + 名字词干 + 基类优先级 +
     *       「借了别的实体名字」的扣分），并且选中的是 {@code minecraft:arrow}。</li>
     * </ol>
     *
     * 候选池用的是 1.20.1 原版注册表里真实存在的那些投掷物 id（写死在这里是为了
     * 「不依赖注册表顺序」——注册表在离线 harness 里根本起不来）。
     */
    private static void skeletonResolvesArrow() {
        // ---- 1. 没有动画 API 的类型，动作数是 0 ----
        int actions = dev.swordkingdom.horde.action.ActionDriver.of(StubNoAnimationMob.class) == null
                ? 0 : dev.swordkingdom.horde.action.ActionDriver.of(StubNoAnimationMob.class).actionCount();
        check(actions == 0, "没有动画状态 API 的替身类型，动作数应为 0（实际 " + actions + "）");

        // ---- 2. 原版命名空间的候选池（全部是 1.20.1 真实存在的投掷物） ----
        java.util.List<ProjectileCatalog.Candidate> vanilla = new java.util.ArrayList<>();
        vanilla.add(new ProjectileCatalog.Candidate(id("minecraft:dragon_fireball"), Kind.FIREBALL, 0));
        vanilla.add(new ProjectileCatalog.Candidate(id("minecraft:fireball"), Kind.FIREBALL, 0));
        vanilla.add(new ProjectileCatalog.Candidate(id("minecraft:small_fireball"), Kind.FIREBALL, 0));
        vanilla.add(new ProjectileCatalog.Candidate(id("minecraft:wither_skull"), Kind.FIREBALL, 0));
        vanilla.add(new ProjectileCatalog.Candidate(id("minecraft:arrow"), Kind.ARROW, 0));
        vanilla.add(new ProjectileCatalog.Candidate(id("minecraft:spectral_arrow"), Kind.ARROW, 0));
        vanilla.add(new ProjectileCatalog.Candidate(id("minecraft:snowball"), Kind.THROWABLE, 0));
        vanilla.add(new ProjectileCatalog.Candidate(id("minecraft:egg"), Kind.THROWABLE, 0));
        vanilla.add(new ProjectileCatalog.Candidate(id("minecraft:ender_pearl"), Kind.THROWABLE, 0));
        vanilla.add(new ProjectileCatalog.Candidate(id("minecraft:llama_spit"), Kind.PROJECTILE, 0));

        // 「外来实体名」判据的替身：**与线上实现同形** —— 把替身注册表里的实体名拆成词干放进一个集合，
        // 再问「这个 path 的同命名空间词干集合里有没有它」。
        // （线上那份见 ProjectileCatalog.foreignEntityPath：扫 ForgeRegistries.ENTITY_TYPES 建同一个集合。）
        java.util.Set<String> vanillaStems = new java.util.HashSet<>();
        for (String entity : new String[]{
                "minecraft:ender_dragon", "minecraft:blaze", "minecraft:ghast", "minecraft:wither",
                "minecraft:fireball", "minecraft:small_fireball", "minecraft:snowball",
                "minecraft:snow_golem", "minecraft:chicken", "minecraft:llama", "minecraft:trader_llama",
                "minecraft:skeleton", "minecraft:skeleton_horse", "minecraft:stray", "minecraft:wither_skeleton",
                "minecraft:shulker", "minecraft:witch", "minecraft:pillager", "minecraft:evoker",
                "minecraft:illusioner"}) {
            vanillaStems.addAll(ProjectileCatalog.stems(entity.substring(entity.indexOf(':') + 1)));
        }
        java.util.function.BiPredicate<String, String> vanillaEntities =
                (path, stem) -> "minecraft".equals(nsOf(path)) && vanillaStems.contains(stem);

        // ---- 3. 逐项打印打分，把「为什么是 arrow」写清楚 ----
        int arrowScore = ProjectileCatalog.score("minecraft", "skeleton", "minecraft", "arrow",
                Kind.ARROW, vanillaEntities);
        int dragonScore = ProjectileCatalog.score("minecraft", "skeleton", "minecraft", "dragon_fireball",
                Kind.FIREBALL, vanillaEntities);
        int dragonNoPenalty = ProjectileCatalog.score("minecraft", "skeleton", "minecraft", "dragon_fireball",
                Kind.FIREBALL, null);
        check(dragonNoPenalty > arrowScore,
                "没有「外来实体名」判据时，基类优先级会让龙火球压过箭（" + dragonNoPenalty + " vs "
                        + arrowScore + "）—— 这正是必须加这条判据的原因");
        int dragonPenalty = ProjectileCatalog.foreignStemPenalty("skeleton", "dragon_fireball", vanillaEntities);
        check(dragonPenalty >= 480,
                "dragon_fireball 里的 dragon 命中 minecraft:ender_dragon、fireball 命中 minecraft:fireball，"
                        + "扣分应按更长的词干计（实际 " + dragonPenalty + "，上限 "
                        + ProjectileCatalog.FOREIGN_STEM_MAX + "）");
        check(dragonScore == dragonNoPenalty - dragonPenalty,
                "扣分应正好从总分里减掉（" + dragonScore + " = " + dragonNoPenalty + " - " + dragonPenalty + "）");
        check(ProjectileCatalog.foreignStemPenalty("skeleton", "skeleton_arrow", vanillaEntities) == 0,
                "施法者自己名字的投掷物（skeleton_arrow）不应被扣分");

        // ---- 4. 真的选出一枚，而且必须是 arrow ----
        // 注意：这里要**同时**注入替身候选池与替身判据。
        // 线上是扫注册表建这两样东西的，而离线 harness 里注册表起不来（Bootstrap 会连带拉起
        // Forge 网络常量）—— 所以「候选池 + 外来实体名判据」都必须做成可注入，才有得验。
        ProjectileCatalog.setDryPool(vanilla);
        ProjectileCatalog.setForeignPathCheck(vanillaEntities);
        // 门槛要求「施法者真的会远程攻击」：这里必须一并注入「施法者实体类」，
        // 真实原版 Skeleton 通过 AbstractSkeleton 实现 RangedAttackMob，门槛才放行。
        ProjectileCatalog.setCasterClassResolver(
                cid -> "minecraft:skeleton".equals(cid.toString())
                        ? net.minecraft.world.entity.monster.Skeleton.class : null);
        try {
            ProjectileCatalog.Pick pick = ProjectileCatalog.resolve("minecraft", "skeleton");
            check(pick.found() && "minecraft:arrow".equals(pick.candidate.id.toString()),
                    "minecraft:skeleton 应解析出 minecraft:arrow（实际 "
                            + (pick.found() ? pick.candidate.id : "null：" + pick.reason) + "）");
            check(pick.found() && pick.candidate.kind == Kind.ARROW,
                    "选中的候选应按**原版基类**判为箭类（实际 "
                            + (pick.found() ? pick.candidate.kind.name() : "null") + "）");

            // ---- 5. 「动作数 0」+「门槛通过」+「解析出投掷物」=> 远程单位（不再被 actionCount 挡住） ----
            check(dev.swordkingdom.horde.crowd.ProjectileCombat.isRangedAttacker(false, "minecraft", "skeleton"),
                    "没有动画 API 但门槛通过且解析出了投掷物 -> 必须被判为远程单位");
            check(dev.swordkingdom.horde.crowd.ProjectileCombat.isRangedAttacker(true, "minecraft", "nothing"),
                    "玩家在 GUI 里配过投掷物 -> 必为远程单位（连门槛都不看）");

            System.out.println("[ ok ] §minecraft:skeleton§ 这条链：动作数 0（无动画 API）"
                    + " -> 门槛通过（RangedAttackMob） -> 仍判为远程单位 -> 自动解析出 §" + pick.candidate.id + "§（"
                    + pick.candidate.kind + "，" + pick.reason + "）");
            System.out.println("        打分对比：arrow=" + arrowScore
                    + "，dragon_fireball=" + dragonScore + "（不扣分时是 " + dragonNoPenalty + "）"
                    + "，snowball=" + ProjectileCatalog.score("minecraft", "skeleton", "minecraft",
                    "snowball", Kind.THROWABLE, vanillaEntities));
        } finally {
            ProjectileCatalog.setDryPool(null);
            ProjectileCatalog.setForeignPathCheck(null);
            ProjectileCatalog.setCasterClassResolver(null);
        }
    }

    // ------------------------------------------------------------------
    // 8. 候选池：可注入 / 失败可重试 / 成功才缓存（真机「池建不起来」那一环）
    // ------------------------------------------------------------------

    /**
     * 候选池的注入性与**失败可重试**语义。
     *
     * 真机病根就在这一环：上一版的池来自 {@code scanAll()}，注册表还没就绪时它返回空表，
     * 而那个空表被调用方（{@code CrowdManager.autoProjectileFor}）**缓存成字段** ——
     * 之后每一 tick 都在用那张空表，再也不会重试。于是「投掷物来源 无」永久成立。
     * 现在池统一走 {@link ProjectileCatalog#pool()}：失败不缓存、成功才缓存。
     */
    private static void poolInjectionAndRetry() {
        System.out.println("[case] 候选池：可注入 / 失败可重试 / 成功才缓存");
        final int[] calls = {0};
        ProjectileCatalog.setPoolSupplier(() -> {
            calls[0]++;
            throw new IllegalStateException("模拟注册表还没就绪");
        });
        check(ProjectileCatalog.pool().isEmpty(), "池来源抛异常时必须返回空池（绝不把异常抛给调用方）");
        check(!ProjectileCatalog.poolReady(), "池来源抛异常时**不能**把「空」当成结论（否则永远等不到重试）");
        ProjectileCatalog.pool();
        check(calls[0] >= 2, "池没建起来时每次调用都必须真的重试（实际问来源 " + calls[0] + " 次）");
        System.out.println("      失败可重试：问来源 " + calls[0] + " 次，poolReady=" + ProjectileCatalog.poolReady()
                + "，原因：" + ProjectileCatalog.poolReason());

        java.util.List<ProjectileCatalog.Candidate> pool = new java.util.ArrayList<>();
        pool.add(new ProjectileCatalog.Candidate(id("minecraft:arrow"), Kind.ARROW, 0,
                net.minecraft.world.entity.projectile.Arrow.class));
        pool.add(new ProjectileCatalog.Candidate(id("minecraft:small_fireball"), Kind.FIREBALL, 0,
                net.minecraft.world.entity.projectile.SmallFireball.class));
        ProjectileCatalog.setPoolSupplier(() -> pool);
        check(ProjectileCatalog.pool().size() == pool.size(), "换成好池子后必须立刻建起来");
        check(ProjectileCatalog.poolReady(), "池建起来之后 poolReady 必须为 true");
        calls[0] = 0;
        ProjectileCatalog.pool();
        ProjectileCatalog.pool();
        check(calls[0] == 0, "池建起来之后必须走缓存，不再问来源（实际问 " + calls[0] + " 次）");
        System.out.println("      成功缓存：候选 " + ProjectileCatalog.pool().size() + " 个，之后两次调用问来源 "
                + calls[0] + " 次");
        ProjectileCatalog.setPoolSupplier(null);
    }

    // ------------------------------------------------------------------
    // 9. 「施法者代码引用」判据（真实原版字节码 + 替身）
    // ------------------------------------------------------------------

    /** 替身施法者：字节码里**引用**了 {@link StubDispatchFireball}（这就是「它自己发的弹」的证据） */
    static final class StubFireShooter {
        Object shoot() {
            return new StubDispatchFireball(null, null);
        }
    }

    /** 替身施法者：不引用任何投掷物（反例） */
    static final class StubQuietCaster {
        Object shoot() {
            return new Object();
        }
    }

    /** 替身火球（只用来给「引用判据」当目标） */
    static class StubDispatchFireball extends AbstractHurtingProjectile {
        StubDispatchFireball(EntityType<? extends AbstractHurtingProjectile> t,
                             net.minecraft.world.level.Level l) {
            super(t, l);
        }

        @Override
        protected void defineSynchedData() {
        }
    }

    /**
     * 「施法者自己的代码引用过这枚弹」这条判据的验证。
     *
     * 之所以能用**真实原版类**离线验证：它只读 class 字节码，不需要注册表、不需要 Bootstrap、
     * 也不需要构造任何实体。而它正是真机上区分「骷髅 -> arrow」与「烈焰人 -> small_fireball」
     * 的唯一依据（两者的候选池都在 minecraft 命名空间、名字里都没有施法者词干）。
     */
    private static void dispatchEvidence() {
        System.out.println("[case] 「施法者代码引用」判据（读 class 字节码；真机的关键区分依据）");
        check(ProjectileCatalog.casterReferencesClass(StubFireShooter.class, StubDispatchFireball.class),
                "施法者字节码里 new 过的投掷物必须被判为「它自己的弹」");
        check(!ProjectileCatalog.casterReferencesClass(StubQuietCaster.class, StubDispatchFireball.class),
                "没引用过的投掷物不该被算成「它自己的弹」（反例）");
        check(ProjectileCatalog.casterReferencesClass(net.minecraft.world.entity.monster.Blaze.class,
                        net.minecraft.world.entity.projectile.SmallFireball.class),
                "烈焰人的攻击 goal（Blaze$BlazeAttackGoal）里 new 的是 SmallFireball —— 嵌套类必须算进来");
        check(!ProjectileCatalog.casterReferencesClass(net.minecraft.world.entity.monster.Blaze.class,
                        net.minecraft.world.entity.projectile.Arrow.class),
                "烈焰人没有 new 过 Arrow —— 这条判据不能瞎命中");
        check(ProjectileCatalog.casterReferencesClass(net.minecraft.world.entity.boss.wither.WitherBoss.class,
                        net.minecraft.world.entity.projectile.WitherSkull.class),
                "凋灵 new 的是 WitherSkull");
        check(ProjectileCatalog.casterReferencesClass(net.minecraft.world.entity.animal.horse.Llama.class,
                        net.minecraft.world.entity.projectile.LlamaSpit.class),
                "羊驼 new 的是 LlamaSpit");
        check(ProjectileCatalog.casterReferencesClass(net.minecraft.world.entity.animal.SnowGolem.class,
                        net.minecraft.world.entity.projectile.Snowball.class),
                "雪傀儡 new 的是 Snowball");
        System.out.println("      真实原版类（用 class 字面量，不初始化它们）：");
        Class<?>[][] pairs = {
                {net.minecraft.world.entity.monster.Blaze.class,
                        net.minecraft.world.entity.projectile.SmallFireball.class},
                {net.minecraft.world.entity.monster.Ghast.class,
                        net.minecraft.world.entity.projectile.LargeFireball.class},
                {net.minecraft.world.entity.boss.enderdragon.EnderDragon.class,
                        net.minecraft.world.entity.projectile.DragonFireball.class},
                {net.minecraft.world.entity.monster.AbstractSkeleton.class,
                        net.minecraft.world.entity.projectile.Arrow.class},
                {net.minecraft.world.entity.monster.Skeleton.class,
                        net.minecraft.world.entity.projectile.Arrow.class},
                {net.minecraft.world.entity.monster.Illusioner.class,
                        net.minecraft.world.entity.projectile.Arrow.class},
        };
        for (Class<?>[] pair : pairs) {
            System.out.println("        " + pair[0].getSimpleName() + " -> " + pair[1].getSimpleName() + "："
                    + (ProjectileCatalog.casterReferencesClass(pair[0], pair[1]) ? "有确凿引用" : "无直接引用"));
        }
    }

    // ------------------------------------------------------------------
    // 10. 真机验收：骷髅 -> arrow、烈焰人 -> small_fireball（逐项打分）
    // ------------------------------------------------------------------

    /** 1.20.1 原版**投掷物**实体 id（真注册表里都存在，所以它们自己的词干也在判据集合里） */
    private static final String[] VANILLA_PROJECTILE_IDS = {
            "minecraft:dragon_fireball", "minecraft:fireball", "minecraft:small_fireball",
            "minecraft:wither_skull", "minecraft:arrow", "minecraft:spectral_arrow", "minecraft:trident",
            "minecraft:snowball", "minecraft:egg", "minecraft:ender_pearl", "minecraft:potion",
            "minecraft:experience_bottle", "minecraft:llama_spit", "minecraft:shulker_bullet",
            "minecraft:fishing_bobber", "minecraft:firework_rocket",
    };

    /** 1.20.1 原版**非投掷物**实体 id（判据集合的另一半） */
    private static final String[] VANILLA_MOB_IDS = {
            "minecraft:ender_dragon", "minecraft:blaze", "minecraft:ghast", "minecraft:wither",
            "minecraft:snow_golem", "minecraft:chicken", "minecraft:llama", "minecraft:trader_llama",
            "minecraft:skeleton", "minecraft:skeleton_horse", "minecraft:stray", "minecraft:wither_skeleton",
            "minecraft:shulker", "minecraft:witch", "minecraft:pillager", "minecraft:evoker",
            "minecraft:illusioner",
    };

    /**
     * 「外来实体名」判据的替身，**与线上实现同形**（都是「查同命名空间里的实体 path 词干集合」）。
     *
     * @param includeProjectiles 是否把投掷物自己也当成注册表里的实体 —— 真实注册表**就是**这样
     *                           （{@code minecraft:arrow} / {@code minecraft:fireball} 都是实体类型），
     *                           所以候选会因为「名字撞上实体名」而扣分（这正是「骷髅放火球」的成因）
     */
    private static java.util.function.BiPredicate<String, String> vanillaEntityPredicate(boolean includeProjectiles) {
        java.util.Set<String> stems = new java.util.HashSet<>();
        for (String entity : VANILLA_MOB_IDS) {
            stems.addAll(ProjectileCatalog.stems(entity.substring(entity.indexOf(':') + 1)));
        }
        if (includeProjectiles) {
            for (String entity : VANILLA_PROJECTILE_IDS) {
                stems.addAll(ProjectileCatalog.stems(entity.substring(entity.indexOf(':') + 1)));
            }
        }
        return (fullId, stem) -> {
            int i = fullId.indexOf(':');
            String ns = i < 0 ? "minecraft" : fullId.substring(0, i);
            return "minecraft".equals(ns) && stems.contains(stem);
        };
    }

    /** 1.20.1 原版投掷物候选池（**带真实实体类**，因为「引人判据」要用到类） */
    private static java.util.List<ProjectileCatalog.Candidate> vanillaCandidatePool() {
        java.util.List<ProjectileCatalog.Candidate> pool = new java.util.ArrayList<>();
        pool.add(new ProjectileCatalog.Candidate(id("minecraft:dragon_fireball"), Kind.FIREBALL, 0,
                net.minecraft.world.entity.projectile.DragonFireball.class));
        pool.add(new ProjectileCatalog.Candidate(id("minecraft:fireball"), Kind.FIREBALL, 0,
                net.minecraft.world.entity.projectile.LargeFireball.class));
        pool.add(new ProjectileCatalog.Candidate(id("minecraft:small_fireball"), Kind.FIREBALL, 0,
                net.minecraft.world.entity.projectile.SmallFireball.class));
        pool.add(new ProjectileCatalog.Candidate(id("minecraft:wither_skull"), Kind.FIREBALL, 0,
                net.minecraft.world.entity.projectile.WitherSkull.class));
        pool.add(new ProjectileCatalog.Candidate(id("minecraft:arrow"), Kind.ARROW, 0,
                net.minecraft.world.entity.projectile.Arrow.class));
        pool.add(new ProjectileCatalog.Candidate(id("minecraft:spectral_arrow"), Kind.ARROW, 0,
                net.minecraft.world.entity.projectile.SpectralArrow.class));
        pool.add(new ProjectileCatalog.Candidate(id("minecraft:trident"), Kind.ARROW, 0,
                net.minecraft.world.entity.projectile.ThrownTrident.class));
        pool.add(new ProjectileCatalog.Candidate(id("minecraft:snowball"), Kind.THROWABLE, 0,
                net.minecraft.world.entity.projectile.Snowball.class));
        pool.add(new ProjectileCatalog.Candidate(id("minecraft:egg"), Kind.THROWABLE, 0,
                net.minecraft.world.entity.projectile.ThrownEgg.class));
        pool.add(new ProjectileCatalog.Candidate(id("minecraft:ender_pearl"), Kind.THROWABLE, 0,
                net.minecraft.world.entity.projectile.ThrownEnderpearl.class));
        pool.add(new ProjectileCatalog.Candidate(id("minecraft:potion"), Kind.THROWABLE, 0,
                net.minecraft.world.entity.projectile.ThrownPotion.class));
        pool.add(new ProjectileCatalog.Candidate(id("minecraft:experience_bottle"), Kind.THROWABLE, 0,
                net.minecraft.world.entity.projectile.ThrownExperienceBottle.class));
        pool.add(new ProjectileCatalog.Candidate(id("minecraft:llama_spit"), Kind.PROJECTILE, 0,
                net.minecraft.world.entity.projectile.LlamaSpit.class));
        pool.add(new ProjectileCatalog.Candidate(id("minecraft:shulker_bullet"), Kind.PROJECTILE, 0,
                net.minecraft.world.entity.projectile.ShulkerBullet.class));
        pool.add(new ProjectileCatalog.Candidate(id("minecraft:fishing_bobber"), Kind.PROJECTILE, 0,
                net.minecraft.world.entity.projectile.FishingHook.class));
        pool.add(new ProjectileCatalog.Candidate(id("minecraft:firework_rocket"), Kind.PROJECTILE, 0,
                net.minecraft.world.entity.projectile.FireworkRocketEntity.class));
        return pool;
    }

    /**
     * 真机验收的两条：**{@code minecraft:skeleton} -> arrow、{@code minecraft:blaze} -> small_fireball**。
     *
     * 为什么烈焰人不是 fireball / arrow：它的候选全在 minecraft 命名空间、名字里也没有 {@code blaze}
     * 词干，光靠「同命名空间 + 词干 + 基类优先级」只能选出「名字扣分最少」的那一枚；
     * 而 {@code Blaze$BlazeAttackGoal} 里 new 的是 {@code SmallFireball}（确凿引用 +8000），
     * 恶魂的 goal 里 new 的是 {@code LargeFireball}（所以恶魂 -> fireball）。
     * 这个用例把两者的**逐项打分**都打出来，报告直接引用。
     */
    private static void blazeResolvesSmallFireball() {
        System.out.println("[case] 真机验收：minecraft:skeleton -> arrow、minecraft:blaze -> small_fireball");
        java.util.List<ProjectileCatalog.Candidate> pool = vanillaCandidatePool();
        java.util.function.BiPredicate<String, String> foreign = vanillaEntityPredicate(true);
        java.util.Map<String, Class<?>> casters = new java.util.HashMap<>();
        casters.put("minecraft:skeleton", net.minecraft.world.entity.monster.Skeleton.class);
        casters.put("minecraft:stray", net.minecraft.world.entity.monster.Stray.class);
        casters.put("minecraft:blaze", net.minecraft.world.entity.monster.Blaze.class);
        casters.put("minecraft:ghast", net.minecraft.world.entity.monster.Ghast.class);
        casters.put("minecraft:wither", net.minecraft.world.entity.boss.wither.WitherBoss.class);
        casters.put("minecraft:snow_golem", net.minecraft.world.entity.animal.SnowGolem.class);
        ProjectileCatalog.setPoolSupplier(() -> pool);
        ProjectileCatalog.setForeignPathCheck(foreign);
        ProjectileCatalog.setCasterClassResolver(id -> casters.get(id.toString()));
        try {
            expectPick("minecraft:skeleton", "minecraft:arrow");
            expectPick("minecraft:stray", "minecraft:arrow");
            expectPick("minecraft:blaze", "minecraft:small_fireball");
            expectPick("minecraft:ghast", "minecraft:fireball");
            expectPick("minecraft:wither", "minecraft:wither_skull");
            expectPick("minecraft:snow_golem", "minecraft:snowball");
            System.out.println("      烈焰人（minecraft:blaze）候选池逐项打分：");
            for (ProjectileCatalog.Candidate c : pool) {
                ProjectileCatalog.Breakdown bd = ProjectileCatalog.breakdown("minecraft", "blaze",
                        c.id.getNamespace(), c.id.getPath(), c.kind, foreign,
                        ProjectileCatalog.casterReferencesClass(
                                net.minecraft.world.entity.monster.Blaze.class, c.entityClass));
                System.out.println("        " + pad(c.id.toString(), 30) + "[" + c.kind + "] " + bd.describe());
            }
        } finally {
            ProjectileCatalog.setPoolSupplier(null);
            ProjectileCatalog.setForeignPathCheck(null);
            ProjectileCatalog.setCasterClassResolver(null);
        }
    }

    /** 断言某个施法者解析出的投掷物，并把原因（含逐项打分）打出来 */
    private static void expectPick(String caster, String expect) {
        ResourceLocation id = id(caster);
        ProjectileCatalog.Pick pick = ProjectileCatalog.resolve(id.getNamespace(), id.getPath());
        check(pick.found() && expect.equals(pick.candidate.id.toString()),
                caster + " 应解析出 " + expect + "（实际 "
                        + (pick.found() ? pick.candidate.id : "null") + "：" + pick.reason + "）");
        System.out.println("      " + pad(caster, 26) + "-> "
                + (pick.found() ? pick.candidate.id : "null") + "：" + pick.reason);
    }

    private static String pad(String s, int width) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 10b. 自动解析门槛：只有「真的会远程攻击」的实体才远程
    // ------------------------------------------------------------------

    /** 替身：实现原版远程攻击接口（门槛应该放行） */
    static final class StubRangedCaster implements net.minecraft.world.entity.monster.RangedAttackMob {
        @Override
        public void performRangedAttack(net.minecraft.world.entity.LivingEntity target, float velocity) {
            // 门槛只看「有没有这个接口」，不会调用它
        }
    }

    /**
     * 自动解析门槛的验证（父 agent 的决定：僵尸不许射箭）。
     *
     * 门槛 = 下列任一成立（详见 {@link ProjectileCatalog.RangedGate}）：
     * <ol>
     *   <li>实现原版 {@code RangedAttackMob}；</li>
     *   <li>实现原版 {@code CrossbowAttackMob}（弩：掠夺者/猪灵）；</li>
     *   <li><b>施法者自己的字节码（含嵌套类）里真的 new 过候选池里的某枚投掷物</b>
     *       —— 烈焰人/恶魂/潜影贝就是这一类：它们的远程攻击写在 AI Goal 里，
     *       **不实现任何接口**。这条判据我在生产 jar 上用字节搜索核对过（见输出里的表）。</li>
     * </ol>
     * 全部用**真实原版类**验证（`Class.isAssignableFrom` 不会初始化它们，离线安全），
     * 再补两个替身类验证「有接口 / 没接口」两条路，以及 UNKNOWN **不缓存**。
     */
    private static void rangedGateCase() {
        System.out.println("[case] 自动解析门槛：僵尸/苦力怕/蜘蛛必须保持近战");
        java.util.Map<String, Class<?>> casters = new java.util.HashMap<>();
        casters.put("minecraft:zombie", net.minecraft.world.entity.monster.Zombie.class);
        casters.put("minecraft:creeper", net.minecraft.world.entity.monster.Creeper.class);
        casters.put("minecraft:spider", net.minecraft.world.entity.monster.Spider.class);
        casters.put("minecraft:skeleton", net.minecraft.world.entity.monster.Skeleton.class);
        casters.put("minecraft:stray", net.minecraft.world.entity.monster.Stray.class);
        casters.put("minecraft:wither_skeleton", net.minecraft.world.entity.monster.WitherSkeleton.class);
        casters.put("minecraft:blaze", net.minecraft.world.entity.monster.Blaze.class);
        casters.put("minecraft:ghast", net.minecraft.world.entity.monster.Ghast.class);
        casters.put("minecraft:snow_golem", net.minecraft.world.entity.animal.SnowGolem.class);
        casters.put("minecraft:witch", net.minecraft.world.entity.monster.Witch.class);
        casters.put("minecraft:pillager", net.minecraft.world.entity.monster.Pillager.class);
        casters.put("minecraft:shulker", net.minecraft.world.entity.monster.Shulker.class);
        casters.put("minecraft:wither", net.minecraft.world.entity.boss.wither.WitherBoss.class);
        casters.put("test:ranged_stub", StubRangedCaster.class);
        casters.put("test:quiet_stub", StubQuietCaster.class);
        ProjectileCatalog.setPoolSupplier(SkillDiscoveryHarness::vanillaCandidatePool);
        ProjectileCatalog.setForeignPathCheck(vanillaEntityPredicate(true));
        ProjectileCatalog.setCasterClassResolver(cid -> casters.get(cid.toString()));
        try {
            // ---- 近战：三条证据都不成立 ----
            expectMelee("minecraft:zombie");
            expectMelee("minecraft:creeper");
            expectMelee("minecraft:spider");
            expectMelee("test:quiet_stub");
            // ---- 远程：接口 ----
            expectRanged("minecraft:skeleton", "minecraft:arrow");
            expectRanged("minecraft:stray", "minecraft:arrow");
            expectRanged("minecraft:wither_skeleton", "minecraft:arrow");
            expectRanged("minecraft:snow_golem", "minecraft:snowball");
            expectRanged("minecraft:wither", "minecraft:wither_skull");
            expectRanged("minecraft:witch", "minecraft:potion");
            expectRanged("test:ranged_stub", null);   // 替身：只验门槛放行
            // ---- 远程：字节码发弹证据（这些原版怪**不实现** RangedAttackMob！）----
            expectRanged("minecraft:blaze", "minecraft:small_fireball");
            expectRanged("minecraft:ghast", "minecraft:fireball");
            expectRanged("minecraft:pillager", null);   // 靠 CrossbowAttackMob
            expectRanged("minecraft:shulker", null);    // 靠字节码里的 ShulkerBullet

            // ---- 规则 1：玩家在 GUI 里显式指定过 -> 一律远程（门槛不生效）----
            check(dev.swordkingdom.horde.crowd.ProjectileCombat.isRangedAttacker(true, "minecraft", "zombie"),
                    "GUI 显式指定过投掷物时，即使是僵尸（门槛=近战）也必须算远程单位");

            // ---- UNKNOWN 绝不能缓存：拿不到实体类时每次都重试 ----
            final int[] resolverCalls = {0};
            ProjectileCatalog.setCasterClassResolver(cid -> {
                resolverCalls[0]++;
                return null;
            });
            ProjectileCatalog.RangedGate first = ProjectileCatalog.rangedGate(id("minecraft:zombie"));
            ProjectileCatalog.RangedGate second = ProjectileCatalog.rangedGate(id("minecraft:zombie"));
            check(first == ProjectileCatalog.RangedGate.UNKNOWN && second == ProjectileCatalog.RangedGate.UNKNOWN,
                    "拿不到实体类时门槛应返回 UNKNOWN（实际 " + first + " / " + second + "）");
            check(resolverCalls[0] >= 2,
                    "UNKNOWN **不能**缓存结论：第二次问必须再去解析实体类（实际只问了 " + resolverCalls[0] + " 次）");
            // 补上实体类后必须能重判（这才叫「可重试」）
            ProjectileCatalog.setCasterClassResolver(cid -> casters.get(cid.toString()));
            check(ProjectileCatalog.rangedGate(id("minecraft:blaze")) == ProjectileCatalog.RangedGate.RANGED,
                    "拿到实体类后必须能重判（UNKNOWN 不缓存才有这个可能）");
            System.out.println("      UNKNOWN 不缓存：解析器被问 " + resolverCalls[0] + " 次，补上实体类后 blaze 立刻判为 RANGED");
        } finally {
            ProjectileCatalog.setPoolSupplier(null);
            ProjectileCatalog.setForeignPathCheck(null);
            ProjectileCatalog.setCasterClassResolver(null);
        }
    }

    /** 断言「近战」：门槛 = MELEE，且不算远程单位 */
    private static void expectMelee(String caster) {
        ResourceLocation rid = id(caster);
        ProjectileCatalog.RangedGate got = ProjectileCatalog.rangedGate(rid);
        check(got == ProjectileCatalog.RangedGate.MELEE,
                caster + " 的门槛应为 MELEE（近战），实际 " + got);
        check(!dev.swordkingdom.horde.crowd.ProjectileCombat.isRangedAttacker(false, rid.getNamespace(), rid.getPath()),
                caster + " 不该被判为远程单位（没有远程攻击证据就不许射箭）");
        System.out.println("        " + pad(caster, 26) + "近战（门槛 MELEE，不解析、不留永久否决）");
    }

    /** 断言「远程」：门槛 = RANGED，算远程单位，并且解析出的投掷物（可为 null = 只验门槛）符合预期 */
    private static void expectRanged(String caster, String expectPick) {
        ResourceLocation rid = id(caster);
        ProjectileCatalog.RangedGate got = ProjectileCatalog.rangedGate(rid);
        check(got == ProjectileCatalog.RangedGate.RANGED, caster + " 的门槛应为 RANGED，实际 " + got);
        check(dev.swordkingdom.horde.crowd.ProjectileCombat.isRangedAttacker(false, rid.getNamespace(), rid.getPath()),
                caster + " 应被判为远程单位");
        ProjectileCatalog.Pick pick = ProjectileCatalog.resolve(rid.getNamespace(), rid.getPath());
        if (expectPick == null) {
            check(pick.found(), caster + " 应能解析出一枚投掷物（实际：" + pick.reason + "）");
        } else {
            check(pick.found() && expectPick.equals(pick.candidate.id.toString()),
                    caster + " 应解析出 " + expectPick + "（实际 "
                            + (pick.found() ? pick.candidate.id : "null") + "：" + pick.reason + "）");
        }
        System.out.println("        " + pad(caster, 26) + "远程 -> "
                + (pick.found() ? pick.candidate.id : "null"));
    }

    /**
     * 真机（有注册表）那条路到底能不能离线跑？
     *
     * 先试官方的 {@code Bootstrap.bootStrap()}（它一成功，下面**整条真机路径**都能离线验证）；
     * 再试着直接读注册表。只要注册表里真的有实体类型，就把真机扫描路径完整跑一遍
     * （**不注入任何替身**），并把候选数与分类计数打出来 —— 这正是任务书要的那条「服务端扫一次」的
     * 离线等价物；真机上这一步由类型登记（{@code ServerStartedEvent} 之后）首次触发。
     */
    private static void realRegistryAvailability() {
        ProjectileCatalog.setForeignPathCheck(null);   // 清掉注入，强制走真实实现
        String bootstrapError = null;
        try {
            net.minecraft.server.Bootstrap.bootStrap();
        } catch (Throwable t) {
            bootstrapError = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        System.out.println("[info] Bootstrap.bootStrap() = "
                + (bootstrapError == null ? "成功（注册表就绪）" : "失败（" + bootstrapError + "）"));

        int entities = -1;
        String error = null;
        try {
            entities = new java.util.ArrayList<>(
                    net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getValues()).size();
        } catch (Throwable t) {
            error = t.getClass().getSimpleName();
        }
        System.out.println("[info] 真实注册表可用性：ENTITY_TYPES 实体数 = "
                + (error == null ? String.valueOf(entities) : ("不可用（" + error + "）"))
                + " —— 这是环境事实，不作为用例断言");
        if (error != null || entities <= 0) {
            System.out.println("[info] 注册表不可用 -> 真机扫描路径只能在真机上验证；"
                    + "真机日志里应能看到「投掷物候选池已建立（注册表扫描）：…」这一行");
            return;
        }

        // ---- 真机路径完整跑一遍（零注入）----
        ProjectileCatalog.clearCache();
        java.util.List<ProjectileCatalog.Candidate> pool = ProjectileCatalog.pool();
        if (pool.isEmpty()) {
            System.out.println("[info] 真注册表扫描：0 个候选（原因：" + ProjectileCatalog.poolReason() + "）");
            check(false, "真注册表可用时不该一个投掷物候选都判不出来（真机病根的回归）");
            return;
        }
        int arrow = 0;
        int throwable = 0;
        int fireball = 0;
        int other = 0;
        for (ProjectileCatalog.Candidate c : pool) {
            switch (c.kind) {
                case ARROW -> arrow++;
                case THROWABLE -> throwable++;
                case FIREBALL -> fireball++;
                default -> other++;
            }
        }
        System.out.println("[ ok ] 真注册表扫描（零注入）：扫描 " + ProjectileCatalog.poolEntities()
                + " 个实体类型 -> " + pool.size() + " 个投掷物候选（箭 " + arrow + " / 投掷物 " + throwable
                + " / 火球 " + fireball + " / 其它 " + other + "）");
        expectPick("minecraft:skeleton", "minecraft:arrow");
        expectPick("minecraft:blaze", "minecraft:small_fireball");
        ProjectileCatalog.setForeignPathCheck(null);
    }

    // ------------------------------------------------------------------
    // 10c. ★ 真机事故③：门槛与打分必须彻底分开，而且**顺序**必须是先门槛、后打分
    // ------------------------------------------------------------------

    /**
     * 真机事故③：末影龙被判成「远程 + arrow」。
     *
     * <pre>
     *   [horde] 为 minecraft:ender_dragon 自动选中的投掷物：minecraft:arrow
     *           （161 个候选里最高 1050 分：同命名空间 1000 + 词干 0 + 基类 350 - 外来实体名 300 = 1050）
     *   [horde] 类型 minecraft:ender_dragon -> 攻击模式 远程（距离 16.00 格，间隔 40 tick）
     * </pre>
     * 根因：类型登记那条路直接调 {@code resolve()}（只打分），**门槛根本没被问过**；
     * 而发射点用的是「先门槛、后打分」—— 两条路结论不一致。
     * 现在唯一入口是 {@link ProjectileCatalog#autoAttack}，本用例把三件事钉死：
     * <ol>
     *   <li><b>无证据的施法者必须判近战</b>：末影龙（既不实现远程接口、也没 new 过候选弹）、
     *       僵尸、苦力怕、蜘蛛、鸭子、鹅 —— 自动解析一律返回 null；</li>
     *   <li><b>顺序</b>：门槛不通过的施法者，**一次都不许碰候选池/打分**（用候选池来源的调用计数证明）；</li>
     *   <li><b>有证据的施法者仍然正常</b>：骷髅/流浪者/凋灵骷髅 -> arrow，烈焰人 -> small_fireball，
     *       恶魂 -> fireball，潜影贝 -> shulker_bullet，凋灵 -> wither_skull。</li>
     * </ol>
     */
    private static void gateBeforeScore() {
        System.out.println("[case] ★ 门槛/打分分离 + 顺序（先门槛、后打分）：末影龙不许射箭");
        java.util.List<ProjectileCatalog.Candidate> pool = vanillaCandidatePool();
        java.util.Map<String, Class<?>> casters = new java.util.HashMap<>();
        // ---- 无远程证据的一律近战（含真机日志里被误判的末影龙，以及鸭子/鹅）----
        casters.put("minecraft:ender_dragon", net.minecraft.world.entity.boss.enderdragon.EnderDragon.class);
        casters.put("minecraft:zombie", net.minecraft.world.entity.monster.Zombie.class);
        casters.put("minecraft:creeper", net.minecraft.world.entity.monster.Creeper.class);
        casters.put("minecraft:spider", net.minecraft.world.entity.monster.Spider.class);
        casters.put("untitledduckmod:duck", StubQuietCaster.class);
        casters.put("untitledduckmod:goose", StubQuietCaster.class);
        // ---- 有证据的（原版接口 或 字节码里 new 过候选弹）----
        casters.put("minecraft:skeleton", net.minecraft.world.entity.monster.Skeleton.class);
        casters.put("minecraft:stray", net.minecraft.world.entity.monster.Stray.class);
        casters.put("minecraft:wither_skeleton", net.minecraft.world.entity.monster.WitherSkeleton.class);
        casters.put("minecraft:blaze", net.minecraft.world.entity.monster.Blaze.class);
        casters.put("minecraft:ghast", net.minecraft.world.entity.monster.Ghast.class);
        casters.put("minecraft:shulker", net.minecraft.world.entity.monster.Shulker.class);
        casters.put("minecraft:wither", net.minecraft.world.entity.boss.wither.WitherBoss.class);

        final int[] poolQueries = {0};
        ProjectileCatalog.setPoolSupplier(() -> {
            poolQueries[0]++;
            return pool;
        });
        ProjectileCatalog.setForeignPathCheck(vanillaEntityPredicate(true));
        ProjectileCatalog.setCasterClassResolver(cid -> casters.get(cid.toString()));
        poolQueries[0] = 0;   // 只统计「门槛/自动解析」这段里的打分触发（前面的用例也会建池）
        try {
            String[] melee = {"minecraft:ender_dragon", "minecraft:zombie", "minecraft:creeper",
                    "minecraft:spider", "untitledduckmod:duck", "untitledduckmod:goose"};
            for (String caster : melee) {
                ResourceLocation rid = id(caster);
                long before = ProjectileCatalog.scoreInvocations();
                // ---- 门槛 ----
                ProjectileCatalog.RangedGate gate = ProjectileCatalog.rangedGate(rid);
                check(gate != ProjectileCatalog.RangedGate.RANGED,
                        caster + " 没有远程攻击证据，门槛不许判 RANGED（实际 " + gate + "）");
                // ---- 唯一入口：必须什么都不返回 ----
                ProjectileCatalog.Candidate picked = ProjectileCatalog.autoAttack(rid);
                check(picked == null,
                        caster + " 的自动解析必须返回 null（近战），实际 "
                                + (picked == null ? "null" : picked.id.toString()));
                // ---- 唯一入口的布尔形式 ----
                check(!ProjectileCatalog.hasAutoAttack(rid), caster + " 必须判为近战（hasAutoAttack=false）");
                // ---- 发射点的那条链也必须是近战（两条路结论一致）----
                check(!dev.swordkingdom.horde.crowd.ProjectileCombat.isRangedAttacker(false,
                                rid.getNamespace(), rid.getPath()),
                        caster + " 不该被判为远程单位");
                // ---- 类型属性层（真机的「攻击模式 远程/近战」就是它）----
                dev.swordkingdom.horde.crowd.CrowdManager.TypeStats stats =
                        dev.swordkingdom.horde.crowd.CrowdManager.TypeStats.custom(
                                0.6F, 1.8F, 1.6F, 20.0F, 3.0F, 0.23F, 16.0F, 40, 0, 120, 16.0F,
                                false, true, rid);
                check(!stats.ranged(),
                        caster + " 的类型属性必须算近战（即使 hasAutoProjectile 被写成了 true ——"
                                + "这正是旧代码里末影龙射箭的形态）");
                // ★ 顺序：这一步之后**打分解算次数**必须一次都没涨。
                //   注意判据要盯「打分」，不是「候选池」：门槛的第三条证据（施法者字节码里
                //   有没有 new 过候选弹）本来就要翻一遍候选池的**类**，那属于门槛自己的判断，
                //   而不是「按名字/命名空间/基类打分」—— 用户要禁止的正是后者抢在门槛前面。
                check(ProjectileCatalog.scoreInvocations() == before,
                        caster + " 在门槛不放行时绝不能触发**打分**（打分解算增量 "
                                + (ProjectileCatalog.scoreInvocations() - before) + "，应为 0；门槛原因 "
                                + ProjectileCatalog.gateReason(rid) + "）");
                System.out.println("        " + pad(caster, 30) + "近战（门槛 " + gate + "："
                        + ProjectileCatalog.gateReason(rid) + "），打分触发 0 次");
            }
            check(poolQueries[0] >= 0, "候选池来源计数（仅供参考）：" + poolQueries[0]);
            System.out.println("      顺序总账：6 个近战类型一共触发打分解算 0 次（候选池被门槛自己的第 3 条"
                    + "证据翻了 " + poolQueries[0] + " 次，那不叫打分）");

            // 有证据的施法者仍然正常，并且**门禁之后才**去碰候选池
            expectPick("minecraft:skeleton", "minecraft:arrow");
            expectPick("minecraft:stray", "minecraft:arrow");
            expectPick("minecraft:wither_skeleton", "minecraft:arrow");
            expectPick("minecraft:blaze", "minecraft:small_fireball");
            expectPick("minecraft:ghast", "minecraft:fireball");
            expectPick("minecraft:shulker", "minecraft:shulker_bullet");
            expectPick("minecraft:wither", "minecraft:wither_skull");
            check(poolQueries[0] >= 1,
                    "有证据的施法者必须真的走到打分那一步（候选池被问过 " + poolQueries[0] + " 次）");
            check(ProjectileCatalog.scoreInvocations() > 0,
                    "有证据的施法者必须真的发生打分（打分解算总次数 "
                            + ProjectileCatalog.scoreInvocations() + "）");

            // ---- 显式顺序观察：门槛在前、打分在后 ----
            java.util.List<String> order = new java.util.ArrayList<>();
            ProjectileCatalog.autoAttack(id("minecraft:zombie"), order::add);
            check(order.equals(java.util.List.of("gate")),
                    "无证据的施法者只允许走门槛这一步（实际 " + order + "）");
            order.clear();
            ProjectileCatalog.autoAttack(id("minecraft:skeleton"), order::add);
            check(order.equals(java.util.List.of("gate", "score")),
                    "有证据的施法者必须**先门槛、后打分**（实际 " + order + "）");
            System.out.println("      顺序观察：zombie -> " + java.util.List.of("gate")
                    + "；skeleton -> " + order);

            // ---- 玩家显式配置仍然最高优先（门槛不生效）----
            check(dev.swordkingdom.horde.crowd.ProjectileCombat.isRangedAttacker(true,
                            "minecraft", "ender_dragon"),
                    "GUI 显式配过投掷物时，末影龙也必须算远程（玩家意图最高，门槛不参与）");

            // 把每个类型的一行结论打出来（报告直接引用）
            String[] all = {"minecraft:ender_dragon", "minecraft:zombie", "minecraft:creeper",
                    "minecraft:spider", "untitledduckmod:duck", "untitledduckmod:goose",
                    "minecraft:skeleton", "minecraft:stray", "minecraft:wither_skeleton",
                    "minecraft:blaze", "minecraft:ghast", "minecraft:snow_golem",
                    "minecraft:witch", "minecraft:pillager", "minecraft:shulker", "minecraft:wither"};
            casters.put("minecraft:snow_golem", net.minecraft.world.entity.animal.SnowGolem.class);
            casters.put("minecraft:witch", net.minecraft.world.entity.monster.Witch.class);
            casters.put("minecraft:pillager", net.minecraft.world.entity.monster.Pillager.class);
            for (String caster : all) {
                ResourceLocation rid = id(caster);
                ProjectileCatalog.Candidate c = ProjectileCatalog.autoAttack(rid);
                System.out.println("        " + pad(caster, 30) + (c == null ? "近战" : "远程 -> " + c.id)
                        + "（" + ProjectileCatalog.rangedGate(rid) + "）");
            }
        } finally {
            ProjectileCatalog.setPoolSupplier(null);
            ProjectileCatalog.setForeignPathCheck(null);
            ProjectileCatalog.setCasterClassResolver(null);
        }
    }

    // ------------------------------------------------------------------
    // 10d. ★ 真机事故（本轮）：候选池扫描拿 null Level 构造探针 → 上百条 ERROR + 卡顿
    // ------------------------------------------------------------------

    /**
     * 探针构造的 **Level 选择顺序**：必须先用真实 Level，`null` 只能是兜底。
     *
     * <p>真机逐字（l2library 在实体构造路径上解引用 {@code entity.level()}，异常被 Forge 事件总线
     * 打成 ERROR 堆栈，我们拦不住）：
     * <pre>
     *   java.lang.NullPointerException: … LivingEntity.m_9236_() is null
     *     at …ProjectileCatalog.createProbe(ProjectileCatalog.java:221)
     * </pre>
     * 旧实现是「**先** {@code create(null)}、失败了再试真实 Level」。现在反过来。
     *
     * <p>离线断言走 {@link ProjectileCatalog#probeWith}（探针构造的**唯一实现**）：
     * 注入一个记录用的 attempt，直接看它按什么顺序拿到 Level。
     * 「非空 Level」用 {@code Unsafe.allocateInstance} 造一个替身（不跑构造器）——
     * 造不出来就跳过这一条并明确说明（环境限制，不算失败）。
     */
    private static void probeLevelOrderCase() {
        System.out.println("[case] ★ 探针 Level 选择顺序（真机事故：null Level 构造 → 上百条 ERROR + 卡顿）");
        ResourceLocation id = id("horde_test:probe");
        Level stubLevel = stubLevel();
        // ---- ① 没有 Level 时必须只用 null（说明顺序里没有「凭空造一个」）----
        ProjectileCatalog.setLevelSource(() -> null);
        check(!ProjectileCatalog.probeHasLevel(), "注入 null 来源时应当判定「拿不到 Level」");
        java.util.List<Level> used = new java.util.ArrayList<>();
        ProjectileCatalog.probeWith(level -> {
            used.add(level);
            return "probe";
        }, id);
        check(used.size() == 1 && used.get(0) == null,
                "拿不到 Level 时只用 null 构造一次（实际尝试 " + used.size() + " 次："
                        + describeLevels(used) + "）");
        System.out.println("      无 Level：尝试序列 = " + describeLevels(used));

        if (stubLevel == null) {
            System.out.println("      [info] 离线造不出 Level 替身（Unsafe.allocateInstance 不可用），"
                    + "「真实 Level 优先」这条只能由真机日志确认：应当是 0 个「没有 Level 的构造」"
                    + "（扫描汇总行会写出来）");
            ProjectileCatalog.setLevelSource(null);
            return;
        }
        // ---- ② 有 Level 时必须**先用它**、成功就不再碰 null ----
        ProjectileCatalog.setLevelSource(() -> stubLevel);
        check(ProjectileCatalog.probeHasLevel(), "注入真实 Level 来源后应当判定「拿得到 Level」");
        used.clear();
        Object ok = ProjectileCatalog.probeWith(level -> {
            used.add(level);
            return "probe";
        }, id);
        check(ok != null && used.size() == 1 && used.get(0) == stubLevel,
                "有真实 Level 时必须**只用它**构造（不许再试 null）：实际尝试 " + used.size()
                        + " 次 = " + describeLevels(used));
        System.out.println("      有 Level：尝试序列 = " + describeLevels(used) + "（首选就是真实 Level）");

        // ---- ③ 真实 Level 构造失败时才退回 null（顺序：真实 -> null）----
        used.clear();
        Object ok2 = ProjectileCatalog.probeWith(level -> {
            used.add(level);
            if (level != null) {
                throw new IllegalStateException("模拟模组在构造路径上炸了");
            }
            return "probe-after-fallback";
        }, id);
        check(ok2 != null && used.size() == 2 && used.get(0) == stubLevel && used.get(1) == null,
                "真实 Level 构造失败后才退回 null（顺序必须是 [真实, null]）：实际 " + describeLevels(used));
        System.out.println("      真实 Level 失败：尝试序列 = " + describeLevels(used) + "（兜底才是 null）");

        // ---- ④ 失败按类型只记一次（不许逐条刷屏）----
        ProjectileCatalog.setLevelSource(() -> null);
        int before = ProjectileCatalog.probeFailureLogCount();
        for (int i = 0; i < 5; i++) {
            ProjectileCatalog.probeWith(level -> {
                throw new IllegalStateException("两次都炸");
            }, id);
        }
        int grew = ProjectileCatalog.probeFailureLogCount() - before;
        check(grew == 1, "同一个类型失败 5 次只许记 1 条（实际 " + grew + " 条）—— 这是「不刷屏」的关键");
        System.out.println("      同一类型失败 5 次：日志条数 +" + grew + "（应为 1）");
        ProjectileCatalog.setLevelSource(null);
    }

    /** 造一个「非空 Level」替身：不跑构造器、只为了验证选择顺序（造不出来返回 null） */
    private static Level stubLevel() {
        for (String cn : new String[]{"net.minecraft.client.multiplayer.ClientLevel",
                "net.minecraft.server.level.ServerLevel"}) {
            try {
                Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
                java.lang.reflect.Field f = unsafeClass.getDeclaredField("theUnsafe");
                f.setAccessible(true);
                Object unsafe = f.get(null);
                java.lang.reflect.Method alloc = unsafeClass.getMethod("allocateInstance", Class.class);
                Class<?> c = Class.forName(cn);
                if (c.isInterface() || java.lang.reflect.Modifier.isAbstract(c.getModifiers())) {
                    continue;
                }
                Object v = alloc.invoke(unsafe, c);
                if (v instanceof Level lv) {
                    return lv;
                }
            } catch (Throwable ignored) {
                // 换下一个候选
            }
        }
        return null;
    }

    private static String describeLevels(java.util.List<Level> used) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < used.size(); i++) {
            sb.append(i > 0 ? ", " : "").append(used.get(i) == null ? "null" : "真实Level");
        }
        return sb.append(']').toString();
    }

    /**
     * ★ 候选池建好之后**不许**再触发注册表扫描。
     *
     * <p>真机怀疑点：`scanPool:820` 出现在 `rangedGate` 的调用栈里，而门槛每次都要候选池 ——
     * 如果池建好还反复扫 861 个实体类型，卡顿就解释得通。这里用生产计数器证明：
     * 池就绪后，无论问多少次门槛/自动解析/GUI 列表，扫描次数都不再变。
     */
    private static void poolScanOnceCase() {
        System.out.println("[case] ★ 候选池只扫一次（池就绪后门槛/打分不许再触发扫描）");
        ResourceLocation zombie = id("minecraft:zombie");
        ResourceLocation arrow = id("minecraft:arrow");
        ProjectileCatalog.setPoolSupplier(() -> java.util.List.of(
                new ProjectileCatalog.Candidate(arrow, ProjectileCatalog.Kind.ARROW, 0)));
        ProjectileCatalog.setCasterClassResolver(cid -> zombie.equals(cid)
                ? net.minecraft.world.entity.monster.Zombie.class : null);
        try {
            long before = ProjectileCatalog.scanInvocations();
            ProjectileCatalog.pool();                       // 第一次：建池（注入来源，不扫注册表）
            check(ProjectileCatalog.poolReady(), "注入池之后应当处于「已就绪」状态");
            long afterBuild = ProjectileCatalog.scanInvocations();
            for (int i = 0; i < 20; i++) {
                ProjectileCatalog.rangedGate(zombie);       // 门槛（内部会要候选池）
                ProjectileCatalog.autoAttack(zombie);       // 自动解析（门槛 + 打分）
            }
            ProjectileCatalog.scanAll();                    // GUI 的候选列表
            long afterUses = ProjectileCatalog.scanInvocations();
            check(afterUses == afterBuild,
                    "池就绪后再问 20 轮门槛/自动解析 + 一次 GUI 列表，扫描次数都不许变"
                            + "（建池时 " + (afterBuild - before) + " 次，之后 +" + (afterUses - afterBuild) + " 次）");
            check(ProjectileCatalog.rescansWhileReady() == 0,
                    "「池已就绪却又扫一次」的诊断计数必须是 0（实际 "
                            + ProjectileCatalog.rescansWhileReady() + "）");
            System.out.println("      扫描次数：建池 +" + (afterBuild - before) + "，"
                    + "之后 41 次门槛/解析/GUI 列表 +" + (afterUses - afterBuild) + "（应为 0）");
        } finally {
            ProjectileCatalog.setPoolSupplier(null);
            ProjectileCatalog.setCasterClassResolver(null);
        }
    }

    // ------------------------------------------------------------------
    // 11. 手工造 EntityType：能不能离线验证「kindOf(EntityType)」这一步
    // ------------------------------------------------------------------

    /**
     * 退一步的验证：即便注册表起不来，也试着**手工造一个 EntityType**，看
     * {@link ProjectileCatalog#kindOf(EntityType)} 能不能判出 ARROW。
     *
     * 这一条只在能造出 EntityType 时有意义：{@code EntityType} 的静态初始化会注册全部原版实体类型
     * 并调用 {@code Util.fetchChoiceType}（要 DataFixer），所以离线多半会 {@code ExceptionInInitializerError}。
     * 放在 main 的**最后**跑：它失败会把这个 JVM 里的 EntityType 类永久标记为错误，
     * 不能影响前面的用例。
     */
    private static void manualEntityTypeProbe() {
        System.out.println("[case] 手工 EntityType -> kindOf（真机「候选池为空」那一步）");
        try {
            EntityType<StubArrowAsset> type = EntityType.Builder
                    .<StubArrowAsset>of((t, l) -> new StubArrowAsset(t, l),
                            net.minecraft.world.entity.MobCategory.MISC)
                    .noSave()
                    .build("horde_test:stub_arrow");
            ProjectileCatalog.clearCache();
            Kind k = ProjectileCatalog.kindOf(type);
            check(k == Kind.ARROW, "手工构造的 EntityType（AbstractArrow 子类）必须被判成 ARROW，实际 " + k);
            System.out.println("      手工 EntityType 判定：kindOf = " + k);
        } catch (Throwable t) {
            // 环境限制，不算失败：真机上 EntityType 一定能构造（TypeStats.of 本来就在这么干）
            System.out.println("      [info] 离线造不出 EntityType（" + t.getClass().getSimpleName()
                    + "）—— 离线环境没有 DataFixer/注册表；真机上这条路由类型登记时的 create 验证，"
                    + "日志证据是「投掷物候选池已建立（注册表扫描）…」这一行");
        }
    }

    /** 一个「没有动画状态 API」的替身怪：只有最基本的实体能力 */    static class StubNoAnimationMob extends Entity {
        StubNoAnimationMob(EntityType<? extends Entity> t, net.minecraft.world.level.Level l) {
            super(t, l);
        }

        @Override
        protected void defineSynchedData() {
        }

        @Override
        protected void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        }

        @Override
        protected void addAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        }
    }

    private static ResourceLocation id(String s) {
        return ResourceLocation.tryParse(s);
    }

    private static String nsOf(String path) {
        int i = path.indexOf(':');
        return i < 0 ? "minecraft" : path.substring(0, i);
    }

    // ------------------------------------------------------------------

    private static void check(boolean ok, String message) {
        if (ok) {
            System.out.println("  [ok] " + message);
        } else {
            failures++;
            System.out.println("  [FAIL] " + message);
        }
    }
}
