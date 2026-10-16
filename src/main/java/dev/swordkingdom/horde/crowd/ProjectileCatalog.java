package dev.swordkingdom.horde.crowd;

import dev.swordkingdom.horde.HordeMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.CrossbowAttackMob;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.Fireball;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * **通用**投掷物发现与打分 —— 一个模组名都不写。
 *
 * <h2>候选池怎么来</h2>
 * 扫 {@code ForgeRegistries.ENTITY_TYPES}，凡是其实体类能赋值给下面这些**原版基类**之一的就算候选：
 * <pre>
 *   AbstractArrow          箭类（原版箭/药箭，模组的骨箭、火箭、鱼叉……）
 *   ThrowableProjectile    雪球/蛋/末影珍珠这一类
 *   AbstractHurtingProjectile / Fireball   火球类（含各种自定义弹幕）
 *   Projectile             兜底：任何别的投掷物
 * </pre>
 * 这些是**原版**基类，任何模组想做投掷物都得继承它们，所以这条判据天生对所有模组通用。
 *
 * <h2>给施法者挑投掷物</h2>
 * 打分（越高越像「这个 boss 自己的那发弹」）：
 * <ol>
 *   <li>命名空间相同：<b>强加分</b>（{@code cataclysm:ignis} → {@code cataclysm:*}）；</li>
 *   <li>投掷物 id 的 path 里含施法者 id 的**词干**（{@code ignis} ⊂ {@code ignis_fireball}）：
 *       <b>更强加分</b>，且按词干长度加权（短词干噪音大，权重自然更低）；</li>
 *   <li>基类优先级：火球/箭 &gt; 投掷物兜底；</li>
 *   <li>尽量同命名空间内取；取不到再放宽到全表（这一条由命名空间加分自然实现，
 *       因为同一命名空间的候选一定比外来的高）；</li>
 *   <li><b>施法者自己的代码里引用过这枚弹的类</b>（{@link #CASTER_DISPATCH_SCORE}）：
 *       最高权重。前面四条全是「按名字猜」，而这一条是**确凿证据** ——
 *       {@code Blaze$BlazeAttackGoal} 里 new 的是 {@code SmallFireball}、恶魂的 goal 里
 *       new 的是 {@code LargeFireball}。没有它，同命名空间的骷髅与烈焰人只能都拿到「自我扣分
 *       最少的那一枚」（实测是 arrow），烈焰人放箭显然不对。</li>
 * </ol>
 * 全部是**纯字符串 + 类形状**运算，所以可以离线用替身类型验证。
 *
 * <h2>真机事故与修复（务必保留这些注释）</h2>
 * 上一版真机日志里 {@code 投掷物来源 无}，骷髅/烈焰人因此永远是近战。根因是**候选池一个都建不起来**：
 * <ol>
 *   <li>{@code EntityType#getBaseClass()} 在 1.20.1 运行时的实现就是 {@code return Entity.class}
 *       （对着 {@code forge-1.20.1-47.4.20-srg.jar} 反汇编确认），靠它判基类永远判不出投掷物；</li>
 *   <li>上一版的兜底「反射工厂字段」在真机上找不到字段：线上字段名是 SRG {@code f_20535_}，
 *       而代码只认 {@code factory}/{@code f_20620_}；而且那个字段的类型是
 *       {@code EntityType$EntityFactory}（两参自定义接口），不是 {@code java.util.function.Function}。</li>
 * </ol>
 * 现在改成「**真的构造一次**（{@code type.create(level)}）再 instanceof 原版基类」，
 * 并且池是「失败可重试 + 成功才缓存 + 成功/失败都有日志」（见 {@link #pool()}）。
 */
public final class ProjectileCatalog {

    private ProjectileCatalog() {}

    /** 投掷物分类（由原版基类推导，不认模组类名） */
    public enum Kind {
        /** 不是投掷物 */
        NONE,
        /** 箭类：AbstractArrow */
        ARROW,
        /** 雪球/蛋类：ThrowableProjectile */
        THROWABLE,
        /** 火球类：AbstractHurtingProjectile / Fireball */
        FIREBALL,
        /** 其它投掷物：Projectile 兜底 */
        PROJECTILE;

        /** 基类优先级（火球/箭 &gt; 投掷物兜底） */
        public int priority() {
            return switch (this) {
                case FIREBALL -> 400;
                case ARROW -> 350;
                case THROWABLE -> 200;
                case PROJECTILE -> 100;
                case NONE -> 0;
            };
        }
    }

    /** 由一个**实体类**判断它属于哪一类投掷物（用原版基类，不看类名） */
    public static Kind kindOfClass(Class<?> entityClass) {
        if (entityClass == null) {
            return Kind.NONE;
        }
        if (Fireball.class.isAssignableFrom(entityClass)
                || AbstractHurtingProjectile.class.isAssignableFrom(entityClass)) {
            return Kind.FIREBALL;
        }
        if (AbstractArrow.class.isAssignableFrom(entityClass)) {
            return Kind.ARROW;
        }
        if (ThrowableProjectile.class.isAssignableFrom(entityClass)) {
            return Kind.THROWABLE;
        }
        if (Projectile.class.isAssignableFrom(entityClass)) {
            return Kind.PROJECTILE;
        }
        return Kind.NONE;
    }

    /** 按类名判断失败的实体类型缓存（避免每 tick 重复试 registry） */
    /**
     * 已判定的分类缓存。
     *
     * <b>只缓存「成功构造过实体」的类型</b>：成功构造出来的阴性（不是投掷物）是确定的，
     * 可以永久缓存；而**构造失败**（注册表没就绪、需要真实 Level……）绝不能缓存成 NONE ——
     * 一旦缓存，那个类型就永远不远程了。真机上「候选池为空」之所以难查，就是因为上一版
     * 把「判不出来」也当成了结论（还配了一个永久 UNKNOWN 否决集）。
     */
    private static final Map<ResourceLocation, Kind> KIND_CACHE = new ConcurrentHashMap<>();
    /** 判定时实际 new 出来的实体类（同一实体类型只有一个）；给「施法者代码引用」判据与 GUI 用 */
    private static final Map<ResourceLocation, Class<?>> CLASS_CACHE = new ConcurrentHashMap<>();
    /** 探测失败原因（按 id 限频打 WARN，绝不静默） */
    private static final Map<ResourceLocation, String> PROBE_FAILURE = new ConcurrentHashMap<>();
    /** 「同一类原因只打一次 WARN」的集合 */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
    /**
     * 探测**构造失败**的类型（每个类型只记一次）。
     *
     * 真机事故：用 null Level 构造探针会触发其它模组在实体构造路径上的 NPE
     * （它们把异常打到 Forge 事件总线上，我们拦不住，于是刷出上百条 ERROR 堆栈）。
     * 现在改成「真实 Level 优先」，并且把逐个类型的失败**归并**成一条汇总日志
     * （见 {@link #scanPool()}），不再逐条刷屏。
     */
    private static final Set<ResourceLocation> PROBE_WARNED = ConcurrentHashMap.newKeySet();
    /** 构造失败次数（跨类型累计；扫描前后取差值就是「这次扫描失败了多少个」） */
    private static final java.util.concurrent.atomic.AtomicLong probeFailures =
            new java.util.concurrent.atomic.AtomicLong();
    /** 「没有 Level 可用」的构造次数（真机上应当是 0；非 0 说明探针退化成了 null 构造） */
    private static final java.util.concurrent.atomic.AtomicLong probeWithoutLevel =
            new java.util.concurrent.atomic.AtomicLong();
    /** 注册表扫描的总次数（诊断/离线断言用：池建好之后**不该**再涨） */
    private static final java.util.concurrent.atomic.AtomicLong scanInvocations =
            new java.util.concurrent.atomic.AtomicLong();
    /** 「池已建好却又扫了一次」的次数 */
    private static final java.util.concurrent.atomic.AtomicLong rescansWhileReady =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * 由一个已注册实体类型判断分类。
     *
     * <h2>真机上「候选池为空」的两层原因（都已用 javap 对着生产 jar 确认）</h2>
     * <ol>
     *   <li>{@code EntityType#getBaseClass()} 在 1.20.1 运行时**恒为 {@code Entity.class}**：
     *       {@code forge-1.20.1-47.4.20-srg.jar} 里那个方法（SRG {@code m_142225_}）的方法体就是
     *       {@code ldc Entity.class; areturn}。所以靠它判基类永远判不出 ARROW/FIREBALL；</li>
     *   <li>上一版的兜底是「反射 EntityType 的工厂字段按参数个数调用」，但那条路在真机上
     *       <b>一个字段都找不到</b>：
     *       <ul>
     *         <li>工厂字段在线上是 SRG 名 {@code f_20535_}，而代码只认名字里含 {@code factory}
     *             或等于 {@code f_20620_}（后者其实是另一个方法名，不是字段）；</li>
     *         <li>它的类型是 {@code EntityType$EntityFactory}（**两个参数**的自定义函数式接口），
     *             不是 {@code java.util.function.Function}，所以那句
     *             {@code factory instanceof Function} 即便命中了字段也会直接跳过。</li>
     *       </ul>
     *   </li>
     * </ol>
     * 于是全表扫描一个候选都判不出来 → {@link #resolve} 返回「候选池为空」→ 骷髅/烈焰人永远是近战。
     *
     * <h2>正确的判据：真的构造一次</h2>
     * {@code EntityType#create(Level)} 就是「把实体工厂调起来」，它与映射/版本无关，
     * 而且它**只 new、不入世界**（不会 spawn、不会进 tick 列表）。拿到实例后
     * {@code instanceof} 原版基类即可 —— 这也是本项目在 {@code HordeCommand} / {@code EntityMeshBaker}
     * 里早就写下的结论（注释：「不能用 getBaseClass()，它在运行时并不返回实体类，恒为 Entity.class」）。
     * Level 先试 null（大多数实体的构造函数只是把 level 存起来），失败再试真实 Level。
     */
    public static Kind kindOf(EntityType<?> type) {
        if (type == null) {
            return Kind.NONE;
        }
        // 0) 有些环境（数据包/离线工具/别的映射实现）下 getBaseClass() 是有效的，先白拿一次
        Kind byClass = kindOfClass(baseClassOf(type));
        if (byClass != Kind.NONE) {
            return byClass;
        }
        ResourceLocation id = idOf(type);
        if (id != null) {
            Kind cached = KIND_CACHE.get(id);
            if (cached != null) {
                return cached;
            }
        }
        // 1) 真机唯一可靠的路：真的构造一次，再拿实例的类去 instanceof 原版基类
        Object probe = createProbe(type, id);
        if (probe == null) {
            return Kind.NONE;   // 不缓存：下次拿到真实 Level 再试
        }
        Kind probed = kindOfClass(probe.getClass());
        if (id != null) {
            KIND_CACHE.put(id, probed);
            CLASS_CACHE.put(id, probe.getClass());
        }
        return probed;
    }

    /** 实体类型的注册 id（拿不到返回 null） */
    private static ResourceLocation idOf(EntityType<?> type) {
        try {
            return ForgeRegistries.ENTITY_TYPES.getKey(type);
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@code getBaseClass()} 的取值；运行时恒为 {@code Entity.class}，所以只当一次白拿 */
    private static Class<?> baseClassOf(EntityType<?> type) {
        try {
            return type.getBaseClass();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 真的构造一次实体（**不入世界**：{@code create} 只 new，不 spawn）。
     *
     * <h2>真机事故（本轮）：拿 {@code null} Level 构造探针 → 上百条 ERROR 刷屏 + 服务端卡顿</h2>
     * 真机逐字：
     * <pre>
     *   java.lang.NullPointerException: Cannot invoke "Level.m_5776_()" because the return value of
     *       "LivingEntity.m_9236_()" is null
     *     at TRANSFORMER/l2library@2.5.3/…ClientEffectCap.lambda$static$0(ClientEffectCap.java:27)
     *     at …/ProjectileCatalog.createProbe(ProjectileCatalog.java:221)   ← 我们传了 null Level
     * </pre>
     * 上一版是「**先** {@code create(null)}、失败了再试真实 Level」。而这个整合包里
     * 有些模组在实体构造路径上直接解引用 {@code entity.level()}（甚至挂在 Forge 事件总线上），
     * 于是 {@code create(null)} 会抛 NPE —— 我们虽然 catch 得到，但**事件总线那边的异常
     * Forge 会自己打成 ERROR 堆栈**，我们根本拦不住。于是刷屏 + 每次都要建堆栈（卡顿的主要嫌疑）。
     *
     * <h2>现在的顺序（关键修改）</h2>
     * <ol>
     *   <li><b>先拿真实 Level</b>（{@link #probeLevel()}：注入来源 → 服务端 overworld → 客户端 level），
     *       有就用 {@code create(level)} —— 从根上避免那些模组解引用 null；</li>
     *   <li>真实 Level 构造失败（或**真的**拿不到 Level）才退回 {@code create(null)}；</li>
     *   <li>仍然拿不到就返回 null，并**按类型只记一次**（DEBUG 一行，不带堆栈），
     *       失败计数汇进扫描结束那条日志（见 {@link #scanPool()}）。</li>
     * </ol>
     * 诊断用：本方法每走一次「没有 Level 可用」就累加 {@link #probeWithoutLevel}，
     * 扫描日志里会把这个数字打出来 —— 真机上它应当是 0。
     *
     * @return 实例；构造不出来返回 null（逐类型去重记录，绝不静默）
     */
    private static Object createProbe(EntityType<?> type, ResourceLocation id) {
        return probeWith(level -> {
            ProbeFactory f = probeFactory;
            return f != null ? f.create(type, level) : type.create(level);
        }, id);
    }

    /**
     * 一次探针构造的尝试（离线用例用它记录「先拿到哪个 Level」、或故意制造失败）。
     * 生产实现在 {@link #createProbe} 里，就是 {@code type.create(level)}。
     */
    public interface ProbeAttempt {
        Object create(Level level) throws Throwable;
    }

    /** 探针构造器的注入点（只给离线用例：记录/制造失败用；生产为 null = 直接 {@code type.create}） */
    public interface ProbeFactory {
        Object create(EntityType<?> type, Level level) throws Throwable;
    }

    private static volatile ProbeFactory probeFactory;

    /** 注入探针构造器（只给离线用例；传 null 恢复 {@code type.create}） */
    public static void setProbeFactory(ProbeFactory f) {
        probeFactory = f;
    }

    /**
     * 探针构造的**唯一实现**：Level 选择顺序 + 失败归并都在这里。
     *
     * <pre>
     *   ① 先拿真实 Level（probeLevel()：注入来源 → 服务端 overworld → 客户端 level），
     *      有就用它构造 —— 从根上避免「拿 null Level 构造」把别的模组踩崩；
     *   ② 真实 Level 构造失败（或真的拿不到 Level）才退回 null 构造；
     *   ③ 仍然失败：按类型**只记一次**（DEBUG 一行、不带堆栈），计数汇进扫描汇总日志。
     * </pre>
     *
     * <p>它被抽成公开方法是为了让离线用例能**精确断言这个顺序**（注入一个记录用的 attempt）——
     * 真机上这条顺序错了就是「上百条 ERROR + 卡顿」。
     */
    public static Object probeWith(ProbeAttempt attempt, ResourceLocation id) {
        Level level = probeLevel();
        Throwable failure = null;
        Object probe = null;
        if (level != null) {
            try {
                probe = attempt.create(level);
            } catch (Throwable t) {
                failure = t;
            }
        } else {
            probeWithoutLevel.incrementAndGet();
        }
        if (probe == null) {
            // 兜底：真的没有 Level（或真实 Level 构造失败）才退回 null
            try {
                probe = attempt.create(null);
            } catch (Throwable t) {
                if (failure == null) {
                    failure = t;
                }
            }
        }
        if (probe != null) {
            if (id != null) {
                PROBE_FAILURE.remove(id);
            }
            return probe;
        }
        probeFailures.incrementAndGet();
        String reason = failure == null ? "create 返回 null" : root(failure);
        if (id != null) {
            PROBE_FAILURE.put(id, reason);
            if (PROBE_WARNED.add(id)) {
                HordeMod.LOGGER.debug("[horde] 投掷物分类探测失败（每个类型只记一次）：{} 构造不出实体（{}）"
                        + " —— 该类型这次不计入候选池（下次拿到真实世界会自动重试）", id, reason);
            }
        }
        return null;
    }

    /** 「构造失败」的日志条数（= 去重后的失败类型数；离线用例断言「每个类型只记一次」） */
    public static int probeFailureLogCount() {
        return PROBE_WARNED.size();
    }

    /** 这个类型这次能不能拿到真实 Level（诊断/离线断言用） */
    public static boolean probeHasLevel() {
        return probeLevel() != null;
    }

    // ------------------------------------------------------------------
    // 探测用的 Level：客户端/服务端各一条入口（都不需要外部注册）
    // ------------------------------------------------------------------

    /** 外部注入的 Level 来源（可选；不注入就自动找服务端/客户端） */
    private static volatile Supplier<Level> levelSource;
    /** 已经拿到的 Level（只缓存非 null：加载早期拿不到，之后要能再拿到） */
    private static volatile Level cachedLevel;

    /** 注入「探测用 Level」的来源（测试/特殊环境用；线上会自动找） */
    public static void setLevelSource(Supplier<Level> source) {
        levelSource = source;
    }

    /**
     * 探测用的 Level：注入的来源 → 服务端（{@code ServerLifecycleHooks}）→ 客户端（{@code Minecraft.level}）。
     *
     * 为什么用反射而不是直接引用：{@code net.minecraft.client.Minecraft} 是**客户端专属类**，
     * 在专用服务端上直接引用会 {@code NoClassDefFoundError} —— 共同代码里不能出现它的字面量。
     * Forge 的 {@code ServerLifecycleHooks} 两边都有，但这里也用反射调，避免在离线 harness 里
     * 触发 Forge 的静态初始化。
     */
    private static Level probeLevel() {
        Level cached = cachedLevel;
        if (cached != null) {
            return cached;
        }
        Supplier<Level> injected = levelSource;
        if (injected != null) {
            try {
                Level l = injected.get();
                if (l != null) {
                    cachedLevel = l;
                    return l;
                }
            } catch (Throwable t) {
                warnOnce("level-source", "[horde] 注入的 Level 来源抛异常：{} —— 改用自动查找", root(t));
            }
        }
        Level server = anyLevel(reflectStatic("net.minecraftforge.server.ServerLifecycleHooks", "getCurrentServer"));
        if (server != null) {
            cachedLevel = server;
            return server;
        }
        Level client = anyLevel(reflectMinecraft());
        if (client != null) {
            cachedLevel = client;
        }
        return client;
    }

    /** 反射调一个 Forge 静态方法（Forge 自己的方法名不会被重映射，可以按名字找） */
    private static Object reflectStatic(String className, String method) {
        try {
            Class<?> c = Class.forName(className);
            java.lang.reflect.Method m = c.getMethod(method);
            m.setAccessible(true);
            return m.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 取客户端单例（{@code Minecraft.getInstance()} —— 按**形状**找，不按名字：线上是 SRG 名） */
    private static Object reflectMinecraft() {
        try {
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
            for (java.lang.reflect.Method m : mcClass.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 0
                        && mcClass.isAssignableFrom(m.getReturnType())) {
                    m.setAccessible(true);
                    return m.invoke(null);
                }
            }
        } catch (Throwable ignored) {
            // 专用服务端：这个类根本不存在，属正常
        }
        return null;
    }

    /**
     * 从一个对象身上取「任何一个 Level」：**按类型找**，不按名字（线上字段/方法名是 SRG）。
     * 先找无参方法（{@code MinecraftServer.overworld()}），再找实例字段（{@code Minecraft.level}）。
     */
    private static Level anyLevel(Object holder) {
        if (holder == null) {
            return null;
        }
        for (Class<?> k = holder.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (java.lang.reflect.Method m : k.getDeclaredMethods()) {
                if (m.getParameterCount() != 0 || java.lang.reflect.Modifier.isStatic(m.getModifiers())
                        || !Level.class.isAssignableFrom(m.getReturnType())) {
                    continue;
                }
                try {
                    m.setAccessible(true);
                    Object v = m.invoke(holder);
                    if (v instanceof Level l) {
                        return l;
                    }
                } catch (Throwable ignored) {
                    // 试下一个
                }
            }
            for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                        || !Level.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object v = f.get(holder);
                    if (v instanceof Level l) {
                        return l;
                    }
                } catch (Throwable ignored) {
                    // 试下一个
                }
            }
        }
        return null;
    }

    /** 缓存清理（实体类型在运行时不会变，所以只在诊断/测试里用得上） */
    public static void clearCache() {
        KIND_CACHE.clear();
        CLASS_CACHE.clear();
        PROBE_FAILURE.clear();
        invalidatePool();
    }
    /** 由一个实体实例判断分类（最可靠的一条路）。 */
    public static Kind kindOf(Object entity) {
        return entity == null ? Kind.NONE : kindOfClass(entity.getClass());
    }

    // ------------------------------------------------------------------
    // 基类默认参数
    // ------------------------------------------------------------------

    /**
     * 按**原版基类**推导出来的默认发射参数。
     *
     * 这些数字刻意只依赖「它继承的是哪个原版基类」，不为任何具体模组写数字；
     * 玩家可以在 GUI 里逐项覆盖（见 skill 页签）。
     */
    public static final class Defaults {
        public final float speed;
        public final float gravity;
        public final float drag;
        public final int life;
        public final float hitRadius;
        public final float damageScale;
        public final float blastRadius;
        public final float blastScale;
        public final int pierce;
        public final boolean stickOnBlock;
        public final float pitch;

        Defaults(float speed, float gravity, float drag, int life, float hitRadius,
                 float damageScale, float blastRadius, float blastScale, int pierce,
                 boolean stickOnBlock, float pitch) {
            this.speed = speed;
            this.gravity = gravity;
            this.drag = drag;
            this.life = life;
            this.hitRadius = hitRadius;
            this.damageScale = damageScale;
            this.blastRadius = blastRadius;
            this.blastScale = blastScale;
            this.pierce = pierce;
            this.stickOnBlock = stickOnBlock;
            this.pitch = pitch;
        }
    }

    /**
     * 基类默认参数表（原版语义来源写在每行后面）：
     * <ul>
     *   <li><b>箭</b>：原版 {@code AbstractArrow} 初速 ≈ 3.0 格/tick（满蓄力弓），
     *       每 tick 重力 0.05、空气阻力 0.99，命中即插住（stickOnBlock）；
     *       伤害默认就是施法者攻击力（骷髅的箭=弓的伤害，这里用它的 ATTACK_DAMAGE 近似）；</li>
     *   <li><b>投掷物</b>（雪球/蛋）：原版 {@code ThrowableProjectile} 初速 ≈ 1.5，
     *       重力 0.03、阻力 0.99，撞方块消失；</li>
     *   <li><b>火球</b>：原版 {@code AbstractHurtingProjectile} 初速 ≈ 0.6，
     *       <b>不受重力</b>（直线飞，加速度靠自身），命中带 AoE；</li>
     *   <li><b>兜底</b>：按投掷物处理（这是最保守的一组，覆盖不到时才用）。</li>
     * </ul>
     */
    public static Defaults defaultsOf(Kind kind) {
        return switch (kind) {
            //        速度   重力   阻力  寿命 命中半径 伤害倍 爆半径 爆倍 穿透 卡住 仰角
            case ARROW -> new Defaults(3.0F, 0.05F, 0.99F, 100, 0.30F, 1.0F, 0.0F, 0.0F, 0, true, 5.0F);
            case THROWABLE -> new Defaults(1.5F, 0.03F, 0.99F, 100, 0.25F, 1.0F, 0.0F, 0.0F, 0, false, 0.0F);
            case FIREBALL -> new Defaults(0.6F, 0.0F, 1.0F, 80, 0.50F, 1.0F, 1.0F, 0.6F, 0, false, 0.0F);
            case PROJECTILE -> new Defaults(1.5F, 0.03F, 0.99F, 100, 0.25F, 1.0F, 0.0F, 0.0F, 0, false, 0.0F);
            case NONE -> new Defaults(1.5F, 0.03F, 0.99F, 100, 0.25F, 1.0F, 0.0F, 0.0F, 0, false, 0.0F);
        };
    }

    // ------------------------------------------------------------------
    // 打分选择
    // ------------------------------------------------------------------

    /** 命名空间相同的加分 */
    public static final int SAME_NAMESPACE_SCORE = 1000;
    /** id 是「命名空间:施法者路径」这种同名情形的加分（几乎可以确定就是它） */
    public static final int EXACT_PATH_SCORE = 3000;
    /**
     * 名字词干支路的**上限**。
     *
     * 必须小于 {@link #SAME_NAMESPACE_SCORE}：需求里写的是「**尽量同一命名空间内取**，
     * 取不到再放宽到全表」，所以「同命名空间但名字不沾边」一定要压过
     * 「跨命名空间但名字沾边」。这条优先级靠**权重分层**保证，而不是靠碰运气：
     * <pre>
     *   同名(3000) &gt; 同命名空间(1000) &gt; 词干(≤ 500) &gt; 基类优先级(≤ 400)
     * </pre>
     */
    public static final int STEM_SCORE_CAP = 500;

    /**
     * 「借了别的实体名字」的扣分**上限**。
     *
     * 为什么需要这一条（真机排查出来的）：原版 {@code minecraft:skeleton} 的候选池里，
     * 所有投掷物都在同一命名空间，于是命名空间加分对谁都一样；而基类优先级让
     * {@code minecraft:dragon_fireball}（火球 400）压过 {@code minecraft:arrow}（箭 350），
     * 结果一个普通骷髅会去放龙的火球。
     *
     * 判据：候选投掷物的 path 里若含有**另一个已注册实体**的 path 词干（{@code dragon}
     * ⊂ {@code dragon_fireball}，而 {@code minecraft:ender_dragon} 就在注册表里），
     * 说明这枚弹是「那个实体的专属弹」，不该被通用怪借走。
     * 这条判据同样是**通用**的（只查注册表里的实体 id，不写死任何模组名）。
     *
     * 上限必须小于 {@link #SAME_NAMESPACE_SCORE}：「跨命名空间但名字不沾边」的得分
     * （= 基类优先级 ≤ 400）加上词干分（≤ 500）也追不上同命名空间，
     * 所以扣分只用来排**同一个命名空间内部**的先后，不会把外来的候选抬上来。
     * <pre>
     *   同命名空间(1000) + 同名(3000) &gt; 同命名空间(1000) &gt; 扣分后仍 ≥ 同命名空间(1000) - 1200 + 基类(≤400) &gt; 跨命名空间(≤900)
     * </pre>
     */
    public static final int FOREIGN_STEM_MAX = 1200;

    /**
     * 「施法者自己的代码里引用过这枚投掷物」的加分。
     *
     * <h2>为什么需要这一条（真机上的下一层问题）</h2>
     * 原版 {@code minecraft:skeleton} 与 {@code minecraft:blaze} 的候选池**全在 minecraft 命名空间**，
     * 名字里也没有施法者的词干，于是「同命名空间 + 词干 + 基类优先级」这套打分对它们只能给出
     * 「谁的自我扣分最少」——实测结果是不管骷髅还是烈焰人都会选中 {@code minecraft:arrow}
     * （烈焰人放箭显然不对）。靠名字猜名字，信息量根本不够。
     *
     * <h2>确凿证据在哪</h2>
     * 在施法者自己的字节码里：
     * <pre>
     *   Blaze$BlazeAttackGoal  里 new SmallFireball     -> 烈焰人用的是 small_fireball
     *   Ghast$GhastShootFireballGoal 里 new LargeFireball -> 恶魂用的是 fireball
     *   WitherBoss 里 new WitherSkull / Llama 里 new LlamaSpit / SnowGolem 里 new Snowball ...
     * </pre>
     * 这条判据**与模组名无关、与映射无关**（线上只有成员名被重映射成 SRG，**类名不变**），
     * 而且对任何模组都成立：Java 里想发一枚弹，就必须 new 它的类。所以它对「外来模组的自定义弹幕」
     * 一样有效 —— 这正是「一个模组名都不写」这条原则下能拿到的最强证据。
     *
     * <h2>权重</h2>
     * 取得比「同命名空间 1000 + 同名 3000 + 词干 500 + 基类 400 = 4900」还大，
     * 于是「有确凿证据的那一枚」永远压过「靠名字猜出来的那一枚」。
     */
    public static final int CASTER_DISPATCH_SCORE = 8000;

    /**
     * 给一个 (施法者, 投掷物候选) 打分。**纯字符串运算**，可离线验证。
     *
     * @param casterNs    施法者的命名空间（如 {@code cataclysm}）
     * @param casterPath  施法者的 path（如 {@code ignis}）
     * @param projNs      候选投掷物的命名空间
     * @param projPath    候选投掷物的 path（如 {@code ignis_fireball}）
     * @param kind        候选的基类分类（可为 null = NONE）
     */
    public static int score(String casterNs, String casterPath, String projNs, String projPath, Kind kind) {
        return score(casterNs, casterPath, projNs, projPath, kind, null, false);
    }

    /**
     * 带「外来实体名」判据的打分。
     *
     * @param foreignPath 判据：{@code (完整 id, 词干)} 这个**已注册实体**在不在（同命名空间的）注册表里。
     *                    传 null = 不做这一项扣分（纯字符串打分，老行为）。
     *                    之所以做成回调：离线 harness 起不了注册表，而这一段数学必须能在离线跑。
     *                    <b>注意第一个参数现在带命名空间</b>（见 {@link #foreignStemPenalty}）：
     *                    不带命名空间时判据只能默认 minecraft，模组候选会去查 minecraft 的词干表。
     */
    public static int score(String casterNs, String casterPath, String projNs, String projPath, Kind kind,
                            BiPredicate<String, String> foreignPath) {
        return score(casterNs, casterPath, projNs, projPath, kind, foreignPath, false);
    }

    /**
     * 完整版打分。
     *
     * @param referencedByCaster 施法者自己的代码里引用过这枚投掷物的类（见 {@link #CASTER_DISPATCH_SCORE}）
     */
    public static int score(String casterNs, String casterPath, String projNs, String projPath, Kind kind,
                            BiPredicate<String, String> foreignPath, boolean referencedByCaster) {
        return breakdown(casterNs, casterPath, projNs, projPath, kind, foreignPath, referencedByCaster).total;
    }

    /**
     * 打分的**逐项拆解**（语义与 {@link #score} 完全一致）。
     *
     * 为什么要把拆解单独做一个类型：真机上「为什么给骷髅挑了这枚弹」必须能一眼说清楚，
     * 日志与 GUI 都要显示它；而这次排查的最大障碍恰恰是「链路上每一步都对，但没有任何一处说明」。
     */
    public static final class Breakdown {
        public int sameNamespace;
        public int exactPath;
        public int stem;
        public int kind;
        public int lengthPenalty;
        public int foreignPenalty;
        /** 施法者自己的代码里引用了这枚投掷物 */
        public int dispatch;
        public int total;

        /** 一行中文拆解，直接进日志/报告 */
        public String describe() {
            StringBuilder sb = new StringBuilder(96);
            sb.append("同命名空间 ").append(sameNamespace);
            if (exactPath > 0) {
                sb.append(" + 同名 ").append(exactPath);
            }
            sb.append(" + 词干 ").append(stem);
            if (dispatch > 0) {
                sb.append(" + 施法者代码引用 ").append(dispatch);
            }
            sb.append(" + 基类 ").append(kind);
            if (lengthPenalty > 0) {
                sb.append(" - 长度 ").append(lengthPenalty);
            }
            if (foreignPenalty > 0) {
                sb.append(" - 外来实体名 ").append(foreignPenalty);
            }
            return sb.append(" = ").append(total).toString();
        }
    }

    /** 打分解算总次数（**诊断/离线用例**用：用来证明「门槛不放行时打分一次都没跑」） */
    private static final java.util.concurrent.atomic.AtomicLong SCORE_CALLS =
            new java.util.concurrent.atomic.AtomicLong();

    /** 打分解算总次数（见 {@link #SCORE_CALLS}） */
    public static long scoreInvocations() {
        return SCORE_CALLS.get();
    }

    /** 打分拆解（见 {@link Breakdown}） */
    public static Breakdown breakdown(String casterNs, String casterPath, String projNs, String projPath,
                                      Kind kind, BiPredicate<String, String> foreignPath,
                                      boolean referencedByCaster) {
        SCORE_CALLS.incrementAndGet();
        Breakdown b = new Breakdown();
        if (casterNs == null || casterPath == null || projNs == null || projPath == null) {
            b.total = Integer.MIN_VALUE;
            return b;
        }
        String cns = casterNs.toLowerCase(Locale.ROOT);
        String cp = casterPath.toLowerCase(Locale.ROOT);
        String pns = projNs.toLowerCase(Locale.ROOT);
        String pp = projPath.toLowerCase(Locale.ROOT);

        boolean sameNs = cns.equals(pns);
        if (sameNs) {
            b.sameNamespace = SAME_NAMESPACE_SCORE;
        }
        if (sameNs && cp.equals(pp)) {
            // 同名（例如施法者与投掷物都叫 xxx:ignis）：一定就是它
            b.exactPath = EXACT_PATH_SCORE;
        }

        // ---- 名字词干：加权后夹在上限内（保证它绝不越过「同命名空间」这一档）----
        int stem = 0;
        for (String s : stems(cp)) {
            if (s.length() >= 2 && pp.contains(s)) {
                // 长度权重：短词干（2 字符）噪音大，长词干（>=6）几乎不会误判
                int w = 20 * Math.min(8, s.length());
                // 整词匹配（被 _ 或边界包围）再加权，避免 "fire" 命中 "fireball"
                if (isWordMatch(pp, s)) {
                    w += 10;
                }
                if (w > stem) {
                    stem = w;
                }
            }
        }
        b.stem = Math.min(STEM_SCORE_CAP, stem);

        // 基类优先级（火球/箭 > 投掷物兜底），同样保持在词干之下
        b.kind = kind == null ? 0 : kind.priority();

        // 轻微的「名字更短更简单」偏好：ignis_fireball 优于 ignis_fireball_big_charged
        b.lengthPenalty = Math.min(50, Math.max(0, pp.length() - cns.length() - cp.length()));

        // 「借了别的实体名字」的扣分（见 FOREIGN_STEM_MAX 的注释）
        if (foreignPath != null) {
            b.foreignPenalty = foreignStemPenalty(cp, pns, pp, foreignPath);
        }

        // 「施法者自己的代码引用过它」的最强证据
        if (referencedByCaster) {
            b.dispatch = CASTER_DISPATCH_SCORE;
        }

        b.total = b.sameNamespace + b.exactPath + b.stem + b.kind + b.dispatch
                - b.lengthPenalty - b.foreignPenalty;
        return b;
    }

    /**
     * 候选名字里含**另一个已注册实体**的名字词干时扣多少分（纯字符串 + 注入判据）。
     *
     * 只留一条豁免：这个 path **就是施法者自己**（{@code xxx:goblin} 的弹
     * {@code xxx:goblin_arrow} 里的 {@code goblin} 命中施法者本体，不算外来）。
     *
     * 刻意**不**豁免「这个 path 就是候选自己」：原版的投掷物实体类型（箭/雪球/火球）
     * 在注册表里确实存在，如果豁免「名字等于自己」，那 {@code minecraft:fireball}
     * 就永远不会被扣分，于是它又会盖过 {@code minecraft:arrow} ——
     * 这正是真机上「骷髅放火球」的第二种形态（第一次是 dragon_fireball）。
     * 也就是说：**原版把投掷物也注册成实体类型，所以「名字撞上实体名」必须算扣分**。
     *
     * @param projNs 候选的命名空间；判据要按**同一个命名空间**查其它实体的词干，
     *               传 null 表示把 {@code projPath} 原样递给判据（老行为，兼容既有调用点）
     */
    public static int foreignStemPenalty(String casterPath, String projNs, String projPath,
                                         BiPredicate<String, String> foreignPath) {
        if (foreignPath == null) {
            return 0;
        }
        String full = projNs == null || projNs.isEmpty() ? projPath : projNs + ":" + projPath;
        int worst = 0;
        for (String s : stems(projPath)) {
            if (s.length() < 2 || s.equals(casterPath)) {
                continue;
            }
            if (!foreignPath.test(full, s)) {
                continue;
            }
            int w = 60 * Math.min(10, s.length());
            if (w > worst) {
                worst = w;
            }
        }
        return Math.min(FOREIGN_STEM_MAX, worst);
    }

    /** 老签名（不带命名空间）：保留给既有调用点/用例，内部把 projPath 原样递给判据 */
    public static int foreignStemPenalty(String casterPath, String projPath,
                                         BiPredicate<String, String> foreignPath) {
        return foreignStemPenalty(casterPath, null, projPath, foreignPath);
    }

    /** 把 path 拆成候选词干（整段 + 按 {@code _} 切开的片段） */
    public static List<String> stems(String path) {
        List<String> out = new ArrayList<>();
        if (path == null || path.isEmpty()) {
            return out;
        }
        String p = path.toLowerCase(Locale.ROOT);
        if (!out.contains(p)) {
            out.add(p);
        }
        for (String part : p.split("[_\\-.]")) {
            if (part.length() >= 2 && !out.contains(part)) {
                out.add(part);
            }
        }
        return out;
    }

    /** {@code stem} 在 {@code path} 里是否是个「整词」（两侧是边界或分隔符） */
    public static boolean isWordMatch(String path, String stem) {
        int from = 0;
        while (true) {
            int i = path.indexOf(stem, from);
            if (i < 0) {
                return false;
            }
            boolean leftOk = i == 0 || !Character.isLetterOrDigit(path.charAt(i - 1));
            int end = i + stem.length();
            boolean rightOk = end >= path.length() || !Character.isLetterOrDigit(path.charAt(end));
            if (leftOk && rightOk) {
                return true;
            }
            from = i + 1;
        }
    }

    /** 一个候选（含打分，供 GUI 显示与调试） */
    public static final class Candidate {
        public final ResourceLocation id;
        public final Kind kind;
        public final int score;
        /**
         * 这个实体类型实际 new 出来的类（可能为 null：注入的替身池不带类）。
         *
         * 为什么候选要带上类：打分里有一项「施法者自己的代码引用过这枚弹」
         * （见 {@link #CASTER_DISPATCH_SCORE}），它需要候选的实体类；
         * 扫描时我们本来就已经构造过一次实体，顺手把类存下来，避免再查一次注册表。
         */
        public final Class<?> entityClass;

        public Candidate(ResourceLocation id, Kind kind, int score) {
            this(id, kind, score, null);
        }

        public Candidate(ResourceLocation id, Kind kind, int score, Class<?> entityClass) {
            this.id = id;
            this.kind = kind;
            this.score = score;
            this.entityClass = entityClass;
        }

        @Override
        public String toString() {
            return id + "(" + kind + ":" + score + ")";
        }
    }

    /**
     * 一次注册表扫描的结果：候选 + 扫到多少个实体类型 + 异常。
     *
     * <b>为什么要把「扫到几个实体类型」和「异常」也带出来</b>：
     * 「注册表还没就绪」与「注册表就绪但一个投掷物都没有」必须走不同处理 ——
     * 前者要**重试**（启动早期查注册表必然为空），后者才是确定的结论。
     * 上一版把两者都当成「空池」并永久缓存，于是真机上再也等不到候选池。
     */
    public static final class Scan {
        public final List<Candidate> candidates;
        public final int entities;
        public final String error;
        /** 这次扫描耗时（毫秒） —— 真机排查「首次扫描卡一下」用的 */
        public final long millis;
        /** 这次扫描期间构造失败的实体类型数（已按类型归并） */
        public final int probeFailures;
        /** 这次扫描里有多少类型是在「没有 Level」的情况下探的（真机上应当是 0） */
        public final long withoutLevel;

        Scan(List<Candidate> candidates, int entities, String error) {
            this(candidates, entities, error, 0L, 0, 0L);
        }

        Scan(List<Candidate> candidates, int entities, String error, long millis,
             int probeFailures, long withoutLevel) {
            this.candidates = candidates;
            this.entities = entities;
            this.error = error;
            this.millis = millis;
            this.probeFailures = probeFailures;
            this.withoutLevel = withoutLevel;
        }
    }

    /**
     * 扫全注册表，收集**所有**投掷物候选（每次现扫；缓存见 {@link #pool()}）。
     *
     * 只按原版基类判断，所以对任何模组通用；判据是「真的构造一次实体再 instanceof」
     * （见 {@link #kindOf(EntityType)}，那里写了真机上「一个候选都判不出来」的两层原因）。
     *
     * 扫描异常**不再被静默吞掉**：原因会随返回值带出去（调用方 {@link #pool()} 会打 WARN 并重试）。
     *
     * <p>另外把「耗时 / 失败数 / 无 Level 数」也带出去：真机上「首次扫描卡一下」与
     * 「上百条 ERROR」都要能一眼看见（见 {@link #pool()} 的汇总日志）。
     */
    public static Scan scanPool() {
        long t0 = System.nanoTime();
        scanInvocations.incrementAndGet();
        // 「池已经建好却又扫了一次」= 有调用点绕过了缓存。真机上这会直接表现为卡顿，
        // 所以记一笔并可被离线断言（门槛/打分那条链**不许**触发第二次扫描）。
        if (poolReady && rescansWhileReady.incrementAndGet() == 1) {
            HordeMod.LOGGER.warn("[horde] 投掷物候选池已建好却又触发了一次注册表扫描（不该发生）——"
                    + "调用点应当走 pool() 的缓存；这里继续按现扫结果返回");
        }
        long failBefore = probeFailures.get();
        long noLevelBefore = probeWithoutLevel.get();
        List<Candidate> out = new ArrayList<>();
        Collection<EntityType<?>> all;
        try {
            all = new ArrayList<>(ForgeRegistries.ENTITY_TYPES.getValues());
        } catch (Throwable t) {
            // 注册表还没建起来（离线 harness / 极早的加载阶段）
            return new Scan(out, 0, root(t), millisSince(t0), 0, probeWithoutLevel.get() - noLevelBefore);
        }
        int entities = 0;
        for (EntityType<?> type : all) {
            entities++;
            ResourceLocation id = idOf(type);
            if (id == null) {
                continue;
            }
            Kind kind = kindOf(type);
            if (kind == Kind.NONE) {
                continue;
            }
            out.add(new Candidate(id, kind, kind.priority(), CLASS_CACHE.get(id)));
        }
        return new Scan(out, entities, null, millisSince(t0),
                (int) (probeFailures.get() - failBefore), probeWithoutLevel.get() - noLevelBefore);
    }

    private static long millisSince(long t0) {
        return (System.nanoTime() - t0) / 1_000_000L;
    }

    /** 注册表扫描的总次数（诊断/离线断言用：池建好之后**不该**再涨） */
    public static long scanInvocations() {
        return scanInvocations.get();
    }

    /** 「池已建好却又扫了一次」的次数（诊断/离线断言用：正常应当是 0） */
    public static long rescansWhileReady() {
        return rescansWhileReady.get();
    }

    /** 探针在「没有 Level」的情况下构造的次数（真机上应当是 0；非 0 说明会去踩别的模组） */
    public static long probesWithoutLevel() {
        return probeWithoutLevel.get();
    }

    /**
     * 只要候选表（GUI 的候选列表走这条）。
     *
     * <p><b>池已经建好时直接用缓存</b>：GUI 每次打开都重新扫一遍 861 个实体类型是没必要的，
     * 而且真机上「扫描被触发多次」正是卡顿的嫌疑之一（见 {@link #scanInvocations()}）。
     */
    public static List<Candidate> scanAll() {
        if (poolReady) {
            return cachedPool;
        }
        return scanPool().candidates;
    }

    /**
     * 为某个施法者自动选一枚投掷物（取最高分）。
     *
     * @param caster 施法者的实体类型 id
     * @param pool   候选池（{@link #scanAll()}）；传 null 时现扫一次
     * @return 选中的投掷物；一个候选都没有时返回 null（调用方退回「不发射」）
     */
    public static Candidate pickFor(ResourceLocation caster, List<Candidate> pool) {
        return pickFor(caster, pool, foreignEntityPath());
    }

    /**
     * 为某个施法者自动选一枚投掷物（可注入「外来实体名」判据版本）。
     *
     * @param foreignPath 判据；传 null = 用纯字符串打分（不做「借了别人名字」的扣分）
     */
    public static Candidate pickFor(ResourceLocation caster, List<Candidate> pool,
                                    BiPredicate<String, String> foreignPath) {
        if (caster == null) {
            return null;
        }
        List<Candidate> list = pool != null ? pool : pool();
        if (list.isEmpty()) {
            return null;
        }
        Class<?> casterClass = casterClassOf(caster);
        Candidate best = null;
        int bestScore = Integer.MIN_VALUE;
        Breakdown bestBreakdown = null;
        for (Candidate c : list) {
            Breakdown bd = breakdown(caster.getNamespace(), caster.getPath(),
                    c.id.getNamespace(), c.id.getPath(), c.kind, foreignPath,
                    referencesCaster(casterClass, c));
            if (bd.total > bestScore) {
                bestScore = bd.total;
                best = c;
                bestBreakdown = bd;
            }
        }
        if (best != null) {
            HordeMod.LOGGER.info("[horde] 为 {} 自动选中的投掷物：{}（{} 个候选里打分最高 = {}：{}）",
                    caster, best.id, list.size(), bestScore, bestBreakdown.describe());
        }
        return best;
    }

    // ------------------------------------------------------------------
    // 候选池：可注入（离线 harness）、可缓存、失败可重试
    // ------------------------------------------------------------------

    /**
     * 「取候选池」的**注入点**。
     *
     * 线上（不注入）走 {@link #scanPool()} 真实扫注册表；离线 harness 起不了注册表
     * （{@code Bootstrap} 会连带拉起 Forge 网络常量），所以把这一步做成可注入的 ——
     * 于是「池建不起来要重试」「池建起来了要怎么打分」这些逻辑在离线也能验。
     */
    private static volatile Supplier<List<Candidate>> poolSupplier;
    /** 建好的候选池（只有 {@link #poolReady} 为 true 时它才是「结论」） */
    private static volatile List<Candidate> cachedPool = List.of();
    /** 候选池**是否已经成功建立过**（与「池是不是空的」是两件事） */
    private static volatile boolean poolReady;
    /** 上一次没建起来的原因（进日志与 GUI） */
    private static volatile String poolReason = "还没有建立过（第一次用到时才扫注册表）";
    /** 上一次扫描覆盖了多少个实体类型（诊断：0 = 注册表还没就绪） */
    private static volatile int poolEntities;

    /** 注入候选池来源（只给离线 harness 用；传 null = 恢复真实扫描） */
    public static void setPoolSupplier(Supplier<List<Candidate>> supplier) {
        poolSupplier = supplier;
        invalidatePool();
    }

    /** 作废缓存（下次用到时重新建；注册表在运行时不会变，所以只在诊断/测试里用得上） */
    public static void invalidatePool() {
        poolReady = false;
        cachedPool = List.of();
        poolReason = "已被作废，下次用到时重新建立";
        // 门槛结论依赖候选池（「字节码有没有引用候选弹」），池一换就得重判；
        // 「自动解析出的弹」与「注册表负缓存」同样依赖池，一并作废
        RANGED_GATE.clear();
        GATE_REASON.clear();
        AUTO_ATTACK_MEMO.clear();
        NOT_FOUND.clear();
    }

    /** 候选池是否已经成功建立（调用方用它区分「池是空的」与「池还没建起来」） */
    public static boolean poolReady() {
        return poolReady;
    }

    /** 上一次没建起来/没作废时的原因（中文，可进日志） */
    public static String poolReason() {
        return poolReason;
    }

    /** 上一次扫描覆盖的实体类型数（0 = 注册表还没就绪） */
    public static int poolEntities() {
        return poolEntities;
    }

    /**
     * 取候选池：**缓存 + 失败可重试**。
     *
     * <pre>
     *   注入了 Supplier（离线 harness）  -> 直接用，算成功
     *   扫描抛异常                       -> 返回空、**不缓存**、打 WARN，下次重试
     *   扫到 0 个实体类型（注册表没就绪） -> 返回空、**不缓存**、打 WARN，下次重试
     *   其它                             -> 缓存 + 成功 INFO（候选数 + 分类计数 + 词干计数）
     * </pre>
     *
     * 上一版把「注册表还没就绪」时的空表**永久缓存**（而且调用方还额外缓存了一次），
     * 于是启动早期问过一次就再也等不到候选池 —— 这是真机上「投掷物来源 无」的最后一层原因。
     */
    public static List<Candidate> pool() {
        // 计量统一由调用方（CrowdManager.ensureStats / autoProjectileFor）负责：
        // 这里不再自己计时，否则一次自动解析会被记两遍（STATS 段被重复累加）。
        return pool0();
    }

    private static List<Candidate> pool0() {
        if (poolReady) {
            return cachedPool;
        }
        Supplier<List<Candidate>> injected = poolSupplier;
        if (injected != null) {
            // 卡顿归因：注入式替身池同样算一次「候选池建立」
            dev.swordkingdom.horde.HordeProfiler.notePoolScan();
            List<Candidate> list;
            try {
                List<Candidate> got = injected.get();
                list = got == null ? List.of() : List.copyOf(got);
            } catch (Throwable t) {
                poolReason = "注入的候选池抛异常: " + root(t);
                warnOnce("pool-inject", "[horde] 投掷物候选池（注入）失败：{} —— 下次调用会重试", poolReason);
                return List.of();
            }
            poolReady = true;
            cachedPool = list;
            poolEntities = list.size();
            logPoolReady("注入的替身池");
            return cachedPool;
        }
        Scan scan = scanPool();
        // 卡顿归因：只有真的扫了一遍注册表才打这个事件标记（命中缓存不打）
        dev.swordkingdom.horde.HordeProfiler.notePoolScan();
        if (scan.error != null) {
            poolReason = "扫描注册表抛异常: " + scan.error;
            warnOnce("pool-scan", "[horde] 投掷物候选池建立失败：{} —— 下次调用会重试（注册表可能还没就绪）；"
                    + "本次扫描耗时 {} ms", poolReason, scan.millis);
            return List.of();
        }
        // 扫描汇总：真机排查「首次扫描卡一下」「上百条 ERROR」都靠这一行。
        // 「构造失败 N 个」是**按类型归并**的计数（逐条的细节在 DEBUG 里），所以不会刷屏。
        HordeMod.LOGGER.info("[horde] 投掷物候选池扫描完成：{} 个实体类型，判出 {} 个候选，耗时 {} ms"
                        + "（期间构造失败 {} 个，已按类型归并记录{}）",
                scan.entities, scan.candidates.size(), scan.millis, scan.probeFailures,
                scan.withoutLevel > 0 ? "；其中 " + scan.withoutLevel + " 个是在**没有 Level** 的情况下探的" : "");
        if (scan.millis > 150L) {
            warnOnce("pool-slow", "[horde] 投掷物候选池**首次**扫描耗时 {} ms（{} 个实体类型）——"
                            + "首次扫描会卡一下，之后走缓存，不会再重复扫描（已扫描 {} 次）",
                    scan.millis, scan.entities, scanInvocations());
        }
        if (scan.entities == 0) {
            poolReason = "注册表里一个实体类型都没有（还没就绪）";
            warnOnce("pool-empty-registry", "[horde] 投掷物候选池建立失败：{} —— 下次调用会重试", poolReason);
            return List.of();
        }
        poolReady = true;
        cachedPool = List.copyOf(scan.candidates);
        poolEntities = scan.entities;
        logPoolReady("注册表扫描");
        return cachedPool;
    }

    /**
     * 池建成功时的 INFO：**候选数 + 分类计数 + 词干计数**。
     *
     * 真机排查时的第一眼证据就在这一行：池有多少、四类各多少、判据建没建起来。
     * 上一版那行「投掷物打分已纳入『外来实体名』判据：… 个实体 path 词干」之所以在日志里缺席，
     * 是因为 {@code resolve()} 在「池为空」时提前 return，判据根本没被建 —— 现在池建成功
     * 与判据建立绑在一起（且 {@code resolve()} 也会先建判据），那行 INFO 一定会出现。
     */
    private static void logPoolReady(String source) {
        List<Candidate> list = cachedPool;
        int arrow = 0;
        int throwable = 0;
        int fireball = 0;
        int other = 0;
        for (Candidate c : list) {
            switch (c.kind) {
                case ARROW -> arrow++;
                case THROWABLE -> throwable++;
                case FIREBALL -> fireball++;
                default -> other++;
            }
        }
        HordeMod.LOGGER.info("[horde] 投掷物候选池已建立（{}）：扫描 {} 个实体类型，按原版基类判出 {} 个候选"
                        + "（箭 {} / 投掷物 {} / 火球 {} / 其它 {}）",
                source, poolEntities, list.size(), arrow, throwable, fireball, other);
        // 顺带把「外来实体名」判据建起来（它会自己打那行「词干计数」INFO）
        foreignEntityPath();
    }

    /** 限频 WARN：同一类原因只打一次，避免「每个类型登记都刷一遍」把日志淹没 */
    private static void warnOnce(String key, String format, Object... args) {
        if (WARNED.add(key)) {
            HordeMod.LOGGER.warn(format, args);
        }
    }

    // ------------------------------------------------------------------
    // registry-free 决策 + 把「解析不出投掷物」的原因也一并返回
    // ------------------------------------------------------------------

    /**
     * 一次自动解析的结果：**选中的投掷物**（可能为 null）、**为什么选不出来**（中文原因）
     * 与**打分**（诊断用）。
     *
     * 为什么把原因也返回：真机上「完全没有发生」的根本原因是这条链静默失败，
     * 调用方需要一条能写进日志的原因串（候选池为空 / 注册表不可用 / 类型未注册）。
     */
    public static final class Pick {
        public final Candidate candidate;
        public final String reason;
        /** 选中者的总分（没选中时为 0） */
        public final int score;

        Pick(Candidate candidate, String reason) {
            this(candidate, reason, 0);
        }

        Pick(Candidate candidate, String reason, int score) {
            this.candidate = candidate;
            this.reason = reason;
            this.score = score;
        }

        public boolean found() {
            return candidate != null;
        }
    }

    /**
     * 离线注入替身候选池（只给 harness 用；等价于 {@code setPoolSupplier(() -> pool)}）。
     *
     * 保留这个名字是因为既有用例与注释都按「替身池」在讲；实现上统一走
     * {@link #setPoolSupplier} —— 于是「注入的池」与「真实扫的池」走**同一条**决策代码。
     */
    public static void setDryPool(List<Candidate> pool) {
        setPoolSupplier(pool == null ? null : () -> pool);
    }

    /** 每个施法者只打一次「选中了什么」（resolve 会被类型登记与发射点反复调用） */
    private static final Set<String> LOGGED_PICKS = ConcurrentHashMap.newKeySet();

    // ------------------------------------------------------------------
    // ★ 唯一入口：先门槛、后打分（真机事故③的修复点）
    // ------------------------------------------------------------------

    /**
     * 「给这个施法者自动配一枚投掷物」的**唯一入口** —— 先门槛，后打分，顺序写死在这里。
     *
     * <h2>真机事故③：门槛被跳过，末影龙被判成「远程 + arrow」</h2>
     * <pre>
     *   [horde] 为 minecraft:ender_dragon 自动选中的投掷物：minecraft:arrow
     *           （161 个候选里最高 1050 分：同命名空间 1000 + 词干 0 + 基类 350 - 外来实体名 300 = 1050）
     *   [horde] 类型 minecraft:ender_dragon -&gt; 攻击模式 远程（距离 16.00 格，间隔 40 tick）
     * </pre>
     * 根因是**两条路并存**：
     * <ol>
     *   <li>类型登记（{@code CrowdManager.TypeStats}）直接调 {@link #resolve} 拿「有没有自动挑到弹」
     *       → 打分一命中就 {@code hasAutoProjectile = true} → {@code stats.ranged = true}，
     *       <b>门槛 {@link #rangedGate} 根本没被问过</b>。于是末影龙（既不实现
     *       {@code RangedAttackMob}/{@code CrossbowAttackMob}，也没 new 过任何候选弹）也会射箭；</li>
     *   <li>发射点（{@code ProjectileCombat.isRangedAttacker}）走的是「先门槛、后打分」，两条路结论不一致。</li>
     * </ol>
     * 现在把顺序收进这一个方法，所有调用点（类型登记、发射点判定、GUI 预览）都走它，
     * <b>打分绝不可能抢在门槛之前发生</b>。
     *
     * <h2>顺序（每一步都可以单独解释）</h2>
     * <pre>
     *   1) 门槛 rangedGate(caster)：
     *        RANGED  -> 继续（此时才允许碰候选池与打分）
     *        MELEE   -> 直接返回 null（确定没证据）
     *        UNKNOWN -> 直接返回 null，但**不写永久否决**：下次拿到实体类/池/字节码会重判
     *   2) 打分 resolve(...)：在「已经确定是远程」的类型内部排序，选最像它的那一枚
     * </pre>
     *
     * @return 选中的投掷物；不合格/没候选返回 null（调用方退回近战）
     */
    public static Candidate autoAttack(ResourceLocation caster) {
        return autoAttack(caster, null);
    }

    /** {@link #autoAttack} 的布尔形式（调用方只关心「有没有」时用） */
    public static boolean hasAutoAttack(ResourceLocation caster) {
        return autoAttackCached(caster) != null;
    }

    /**
     * ★ 「这个类型自动解析出哪枚弹」的**最终结果缓存**（含「没有」这个结论）。
     *
     * <h2>真机病根</h2>
     * {@code CrowdManager.TypeStats.ranged()} 每个单位每 tick 被问 3 次
     * （aiTick / resolveCombat / skillTick 各一次）。它的实现链原来一路走到
     * {@code autoAttack -> rangedGate -> casterClassOf -> entityClassOf}
     * 并且**在门槛判为 RANGED 之后还要再跑一遍 {@link #resolve}**（遍历整个候选池打分）
     * —— 而 {@code resolve} 与 {@code hasAutoAttack} 的结果**没有任何缓存**。
     * 100 个单位 ⇒ 每 tick 几百次「扫候选池 + 注册表查询 + 构造实体」，
     * 这就是卡顿行里 {@code ai=533ms combat=523ms skill=539ms} 的直接来源
     * （三段共用同一个被反复重算的判定）。
     *
     * <h2>缓存规则（必须与「UNKNOWN 不缓存」这条既有语义兼容）</h2>
     * <ul>
     *   <li>{@link RangedGate#UNKNOWN} ⇒ **不写缓存**（注册表/字节码还没就绪，下次必须能重判）；</li>
     *   <li>{@link RangedGate#MELEE} ⇒ 缓存「没有」（确定结论）；</li>
     *   <li>{@link RangedGate#RANGED} 且 {@code resolve} 挑到了弹 ⇒ 缓存那枚弹；
     *       挑不到 ⇒ **不写缓存**（候选池可能还会建立/变化，下次要能重挑）。</li>
     * </ul>
     * 换候选池或换类解析来源时（{@code setPoolSupplier} / {@code setCasterClassResolver}）
     * 这张缓存会被一起清掉 —— 于是「先门槛、后打分」的既有语义与验证用例都不受影响。
     */
    public static Candidate autoAttackCached(ResourceLocation caster) {
        autoAttackCalls++;
        if (caster == null) {
            return null;
        }
        if (!autoAttackMemoEnabled) {
            // 只给离线对照实验用：全局关掉结果缓存 = 复现「每单位每 tick 重扫候选池」的旧行为
            return autoAttack(caster);
        }
        Candidate memo = AUTO_ATTACK_MEMO.get(caster);
        if (memo != null) {
            autoAttackHits++;
            return memo == MEMO_NONE ? null : memo;
        }
        Candidate picked = autoAttack(caster);
        if (picked != null) {
            AUTO_ATTACK_MEMO.put(caster, picked);
        } else if (rangedGate(caster) == RangedGate.MELEE) {
            // 确定不是远程：缓存「没有」这个结论
            AUTO_ATTACK_MEMO.put(caster, MEMO_NONE);
        }
        // RANGED 但还没挑到弹、或 UNKNOWN：不缓存，下次重试
        return picked;
    }

    /** 诊断：{@link #autoAttackCached} 被调用 / 命中缓存的次数 */
    public static long autoAttackCalls;
    public static long autoAttackHits;

    private static volatile boolean autoAttackMemoEnabled = true;

    /**
     * 只给离线对照实验用：关掉结果缓存 = 复现「每单位每 tick 重扫候选池」的旧行为。
     * 线上永远是 true。
     */
    public static void setAutoAttackMemoEnabled(boolean on) {
        autoAttackMemoEnabled = on;
    }

    public static boolean autoAttackMemoEnabled() {
        return autoAttackMemoEnabled;
    }

    private static final Map<ResourceLocation, Candidate> AUTO_ATTACK_MEMO = new ConcurrentHashMap<>();
    private static final Candidate MEMO_NONE = new Candidate(
            ResourceLocation.tryParse("minecraft:air"), Kind.PROJECTILE, Integer.MIN_VALUE);

    /**
     * 把一次自动解析的**门槛结论 + 打分**拼成一行可读文字（写进日志/GUI，也是离线用例的断言对象）。
     *
     * 为什么要有它：真机上「末影龙为什么射箭」必须能一眼看出来 ——
     * 上一版日志里只有「打分最高 1050」，完全看不出**门槛压根没跑**。
     */
    public static String describeAutoAttack(ResourceLocation caster) {
        if (caster == null) {
            return "施法者为空";
        }
        RangedGate gate = rangedGate(caster);
        if (gate != RangedGate.RANGED) {
            return "门槛 " + gate + "（不是远程，不参与打分：" + gateReason(caster) + "）";
        }
        Pick pick = resolve(caster.getNamespace(), caster.getPath());
        return "门槛 RANGED（" + gateReason(caster) + "），打分 -> "
                + (pick.found() ? pick.candidate.id + "（" + pick.reason + "）" : "无候选：" + pick.reason);
    }

    /** 上一次门槛判定的原因（给日志用；判定时被记下来的那一句） */
    private static final Map<ResourceLocation, String> GATE_REASON = new ConcurrentHashMap<>();

    /** 上一次门槛判定的原因（没有记录时给一句通用的） */
    public static String gateReason(ResourceLocation caster) {
        String r = GATE_REASON.get(caster);
        return r == null ? "还没有判定过" : r;
    }

    /**
     * 完整版自动解析（**门槛在前、打分在后**，并支持把「观察到的顺序」记进回调里）。
     *
     * @param watch 顺序观察点（可为 null）：门槛阶段收到 {@code "gate"}，打分阶段收到 {@code "score"}。
     *              它存在的唯一目的是让「顺序」这件事**可被离线验证**（见 skillDiscoveryHarness）。
     */
    public static Candidate autoAttack(ResourceLocation caster, java.util.function.Consumer<String> watch) {
        if (caster == null) {
            return null;
        }
        if (watch != null) {
            watch.accept("gate");
        }
        if (rangedGate(caster) != RangedGate.RANGED) {
            return null;
        }
        if (watch != null) {
            watch.accept("score");
        }
        Pick pick = resolve(caster.getNamespace(), caster.getPath());
        return pick.found() ? pick.candidate : null;
    }

    /** 清掉「自动解析」相关的全部缓存（换池/换类来源时必须调） */
    static void clearAutoAttackCaches() {
        AUTO_ATTACK_MEMO.clear();
    }

    /**
     * 自动挑一枚投掷物（线上**不依赖任何注入**：池来自真实注册表扫描）。
     *
     * 与上一版的关键差别：
     * <ol>
     *   <li>候选池走 {@link #pool()}（**失败可重试 + 成功才缓存**），不再「扫一次空表就永远空」；</li>
     *   <li>打分纳入「施法者自己的代码引用过这枚弹」这条证据（见 {@link #CASTER_DISPATCH_SCORE}）；</li>
     *   <li>每一次成功/失败都**有日志**（成功 INFO 一次含逐项打分，失败 WARN 限频）。</li>
     * </ol>
     */
    public static Pick resolve(String casterNs, String casterPath) {
        if (casterNs == null || casterPath == null) {
            return new Pick(null, "施法者类型为空");
        }
        ResourceLocation caster = ResourceLocation.tryParse(casterNs + ":" + casterPath);
        // 先把「外来实体名」判据建起来：真机上那行「词干计数」INFO 曾经整体缺席，
        // 正是因为它挂在「池非空」之后 —— 现在无论池建不建得起来，判据都会先试一次。
        BiPredicate<String, String> foreign = foreignEntityPath();
        List<Candidate> pool = pool();
        if (pool.isEmpty()) {
            String why = "候选池为空（" + poolReason + "）";
            warnOnce("resolve-empty:" + caster, "[horde] 解析不出投掷物：{} —— {}", caster, why);
            return new Pick(null, why);
        }
        Class<?> casterClass = casterClassOf(caster);
        Candidate best = null;
        int bestScore = Integer.MIN_VALUE;
        Breakdown bestBreakdown = null;
        for (Candidate c : pool) {
            Breakdown bd = breakdown(casterNs, casterPath, c.id.getNamespace(), c.id.getPath(), c.kind,
                    foreign, referencesCaster(casterClass, c));
            if (bd.total > bestScore) {
                bestScore = bd.total;
                best = c;
                bestBreakdown = bd;
            }
        }
        if (best == null) {
            return new Pick(null, "候选池非空但没有可打分的候选");
        }
        logPick(caster, best, bestBreakdown, pool.size());
        return new Pick(best, "打分最高 " + bestScore + "（" + bestBreakdown.describe() + "）", bestScore);
    }

    /** 每个施法者只打一次「选中了哪一枚、凭什么」 */
    private static void logPick(ResourceLocation caster, Candidate best, Breakdown bd, int poolSize) {
        if (LOGGED_PICKS.size() > 512 || !LOGGED_PICKS.add(caster + "->" + best.id)) {
            return;
        }
        HordeMod.LOGGER.info("[horde] 为 {} 自动选中的投掷物：{}（{} 个候选里最高 {} 分：{}）",
                caster, best.id, poolSize, bd.total, bd.describe());
    }

    /** 施法者是否「自己就发这枚弹」（见 {@link #CASTER_DISPATCH_SCORE}） */
    private static boolean referencesCaster(Class<?> casterClass, Candidate candidate) {
        if (casterClass == null || candidate == null) {
            return false;
        }
        Class<?> projectile = candidate.entityClass != null ? candidate.entityClass : entityClassOf(candidate.id);
        return projectile != null && casterReferencesClass(casterClass, projectile);
    }

    // ------------------------------------------------------------------
    // 「施法者自己的代码引用过这枚弹」判据：读 class 字节码找投掷物的内部类名
    // ------------------------------------------------------------------

    /** 施法者（含父类与嵌套类）字节码文本的缓存；空串 = 读不出来 */
    private static final Map<Class<?>, String> CASTER_TEXT = new ConcurrentHashMap<>();
    /** 单个类的 class 字节码文本缓存；空串 = 读不出来 */
    private static final Map<Class<?>, String> CLASS_BYTES = new ConcurrentHashMap<>();
    /** 「读不到字节码」的提示只打一次（同一个类） */
    private static final Set<Class<?>> CLASS_BYTES_MISSING = ConcurrentHashMap.newKeySet();
    /** 施法者代码范围的类数上限（防某个巨型实体类把整棵嵌套树都读一遍） */
    private static final int MAX_CASTER_CLASSES = 48;

    /**
     * 施法者自己的代码里引用了这枚投掷物的类吗 —— 「这枚弹就是它发的」的最强证据。
     *
     * 实现：把施法者（含父类与**嵌套类**）的 class 字节码拼起来，找候选投掷物类的内部名。
     * 为什么可行且与模组无关：
     * <ul>
     *   <li>线上只有**成员名**被重映射成 SRG，**类名不变**，所以这条判据不受映射影响；</li>
     *   <li>Java 里想发一枚弹就必须 new 它的类，任何模组都逃不掉；</li>
     *   <li>嵌套类必须算进来：原版烈焰人的攻击逻辑在 {@code Blaze$BlazeAttackGoal} 里
     *       （{@code new SmallFireball}），恶魂在 {@code Ghast$GhastShootFireballGoal} 里
     *       （{@code new LargeFireball}）—— 只看外层类会漏掉这两条最典型的证据。</li>
     * </ul>
     * 读不到字节码时**不抛异常**、也不误判：这条判据不生效，打分退回纯名字（并提示一次）。
     */
    public static boolean casterReferencesClass(Class<?> caster, Class<?> projectile) {
        if (caster == null || projectile == null) {
            return false;
        }
        String internal = projectile.getName().replace('.', '/');
        String text = casterText(caster);
        return text != null && text.contains(internal);
    }

    /** 施法者（含父类/嵌套类）字节码的拼接文本；每个施法者类只拼一次 */
    private static String casterText(Class<?> caster) {
        String cached = CASTER_TEXT.get(caster);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        casterTextReads++;
        StringBuilder sb = new StringBuilder(8192);
        for (Class<?> c : casterHierarchy(caster)) {
            String t = classBytesText(c);
            if (t != null) {
                sb.append(t);
            }
        }
        String text = sb.toString();
        CASTER_TEXT.put(caster, text);
        return text.isEmpty() ? null : text;
    }

    /**
     * 施法者的「代码范围」：它自己 + 所有父类 + 所有嵌套类（递归、去重、有上限）。
     * 顺序是从具体到抽象，嵌套类排在父类之后。
     */
    private static List<Class<?>> casterHierarchy(Class<?> caster) {
        List<Class<?>> out = new ArrayList<>(8);
        collectClasses(caster, out);
        return out;
    }

    private static void collectClasses(Class<?> c, List<Class<?>> out) {
        if (c == null || c == Object.class || out.size() >= MAX_CASTER_CLASSES || out.contains(c)) {
            return;
        }
        out.add(c);
        Class<?>[] nested;
        try {
            nested = c.getDeclaredClasses();
        } catch (Throwable t) {
            nested = new Class<?>[0];
        }
        for (Class<?> n : nested) {
            collectClasses(n, out);
        }
        collectClasses(c.getSuperclass(), out);
    }

    /**
     * 读一个类的 class 字节码，压成 ISO-8859-1 字符串（逐字节一一对应，可安全做子串查找）。
     *
     * 三条路径依次试（真机的 Forge 类加载器与开发环境行为不同，只留一条很容易静默失效）：
     * <ol>
     *   <li>{@code Class#getResourceAsStream}；</li>
     *   <li>该类自己的 {@code ClassLoader#getResourceAsStream}；</li>
     *   <li>{@code getProtectionDomain().getCodeSource()} 指到的 jar/目录直接读
     *       —— Forge 的 SecureJar 会拦前两条时，这一条通常仍然可用。</li>
     * </ol>
     *
     * @return 字节码文本；读不到返回 null（调用方只提示一次，判据不生效）
     */
    static String classBytesText(Class<?> c) {
        String cached = CLASS_BYTES.get(c);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        String resource = c.getName().replace('.', '/') + ".class";
        byte[] data = readClassBytes(c, resource);
        if (data == null) {
            CLASS_BYTES.put(c, "");
            if (CLASS_BYTES_MISSING.add(c)) {
                HordeMod.LOGGER.warn("[horde] 读不到 {} 的 class 字节码（三条路径都失败）——"
                        + "「施法者代码引用」这条判据对它不生效，投掷物打分退回纯名字（不影响能不能发射）",
                        c.getName());
            }
            return null;
        }
        String text = new String(data, java.nio.charset.StandardCharsets.ISO_8859_1);
        CLASS_BYTES.put(c, text);
        return text;
    }

    private static byte[] readClassBytes(Class<?> c, String resource) {
        try (java.io.InputStream in = c.getResourceAsStream("/" + resource)) {
            if (in != null) {
                return in.readAllBytes();
            }
        } catch (Throwable ignored) {
            // 试下一条
        }
        try {
            ClassLoader cl = c.getClassLoader();
            if (cl != null) {
                try (java.io.InputStream in = cl.getResourceAsStream(resource)) {
                    if (in != null) {
                        return in.readAllBytes();
                    }
                }
            }
        } catch (Throwable ignored) {
            // 试下一条
        }
        try {
            java.security.CodeSource cs = c.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                java.io.File file = new java.io.File(cs.getLocation().toURI());
                if (file.isFile()) {
                    try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file)) {
                        java.util.zip.ZipEntry e = zip.getEntry(resource);
                        if (e != null) {
                            try (java.io.InputStream in = zip.getInputStream(e)) {
                                return in.readAllBytes();
                            }
                        }
                    }
                } else if (file.isDirectory()) {
                    java.io.File cf = new java.io.File(file, resource);
                    if (cf.isFile()) {
                        return java.nio.file.Files.readAllBytes(cf.toPath());
                    }
                }
            }
        } catch (Throwable ignored) {
            // 三条都不行：返回 null
        }
        return null;
    }

    /** 「施法者的实体类」的来源（线上 = 注册表里构造一次；离线 harness = 注入替身） */
    private static volatile Function<ResourceLocation, Class<?>> casterClassResolver;
    /** 施法者类型 -> 实体类（**成功的才缓存**） */
    private static final Map<ResourceLocation, Class<?>> CASTER_CLASS = new ConcurrentHashMap<>();

    /** 注入「施法者实体类」解析器（只给离线 harness 用：离线起不了注册表） */
    public static void setCasterClassResolver(Function<ResourceLocation, Class<?>> resolver) {
        casterClassResolver = resolver;
        CASTER_CLASS.clear();
        // 门槛结论与「自动解析出的弹」都是从「实体类」推出来的，换了来源必须重判
        RANGED_GATE.clear();
        GATE_REASON.clear();
        NOT_FOUND.clear();
        AUTO_ATTACK_MEMO.clear();
    }

    /** 施法者类型 -> 实体类（拿不到返回 null） */
    public static Class<?> casterClassOf(ResourceLocation id) {
        casterClassCalls++;
        if (id == null) {
            return null;
        }
        Class<?> cached = CASTER_CLASS.get(id);
        if (cached != null) {
            return cached;
        }
        casterClassMisses++;
        Function<ResourceLocation, Class<?>> resolver = casterClassResolver;
        if (resolver != null) {
            try {
                Class<?> c = resolver.apply(id);
                if (c != null) {
                    CASTER_CLASS.put(id, c);
                }
                return c;
            } catch (Throwable t) {
                warnOnce("caster-class-inject", "[horde] 施法者类解析（注入）失败：{} -> {}", id, root(t));
                return null;
            }
        }
        return entityClassOf(id);
    }

    /** 诊断：{@link #casterClassOf} 被调用次数 / 其中真正去解析（未命中缓存）的次数 */
    public static long casterClassCalls;
    public static long casterClassMisses;
    /** 诊断：{@link #casterText} 真的去读字节码的次数 */
    public static long casterTextReads;

    /** 注册表里那个实体类型实际 new 出来的类（判据与 {@link #kindOf(EntityType)} 完全同一条） */
    public static Class<?> entityClassOf(ResourceLocation id) {
        entityClassCalls++;
        if (id == null) {
            return null;
        }
        Class<?> cached = CLASS_CACHE.get(id);
        if (cached != null) {
            return cached;
        }
        // ★ 「查不到」也必须缓存（负缓存）——否则每一次调用都要重走一遍注册表查询 + 构造实体。
        //
        //   真机病根：{@code TypeStats.ranged()} 每个单位每 tick 被问 3 次
        //   （aiTick / resolveCombat / skillTick），而它的实现链
        //   {@code hasAutoAttack -> rangedGate -> casterClassOf -> entityClassOf} 在
        //   「这个 id 在注册表里取不到类」时**每次都重做**，100 单位就是每 tick 几百次重活
        //   —— 卡顿行里 ai/combat/skill 三处各约 500ms 正是这么来的。
        //   注意区分两种「取不到」：
        //     * 注册表里根本没有这个 id（或它是原版那个恒为 Entity.class 的基类）=> **确定**，可缓存；
        //     * 注册表还没 bootstrap / 类初始化异常 => **只是这次不行**，绝不缓存（否则会永久失效）。
        if (NOT_FOUND.contains(id)) {
            return null;
        }
        try {
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(id);
            if (type == null) {
                NOT_FOUND.add(id);
                return null;
            }
            kindOf(type);   // 顺带把类填进 CLASS_CACHE
            Class<?> got = CLASS_CACHE.get(id);
            if (got == null) {
                NOT_FOUND.add(id);
            }
            return got;
        } catch (Throwable t) {
            if (isDeterministic(t)) {
                NOT_FOUND.add(id);
            }
            warnOnce("entity-class:" + t.getClass().getSimpleName(),
                    "[horde] 从注册表取 {} 的实体类失败：{}", id, root(t));
            return null;
        }
    }

    /** 诊断：{@link #entityClassOf} 被调用次数（含命中负缓存） */
    public static long entityClassCalls;
    /** 「注册表里确定取不到这个类型」的负缓存 */
    private static final Set<ResourceLocation> NOT_FOUND = ConcurrentHashMap.newKeySet();

    /**
     * 取「某个投掷物 id 实际 new 出来的实体类」，用于按**原版基类**推导它的弹道参数
     * （远程单位的距离保持要按弹道站，见 {@code RangedKiting}）。
     *
     * <p>候选池是**扫描时**就构造过实体的，那一刻的类已经在 {@link #CLASS_CACHE} 里；
     * 命中缓存时连注册表都不用碰，缓存没命中才回落到 {@link #entityClassOf}
     * （它会做负缓存，代价可控）。{@code kind} 只是调用方的语义提示（没有候选时直接返回 null），
     * 真正的分类判决始终由 {@link #kindOfClass} 做 —— 不在这里另开一条判据。
     *
     * @param hintId 候选池里那枚弹的 id（null = 没有候选）
     * @param kind   已知的基类分类（诊断用）
     */
    public static Class<?> entityClassOf(ResourceLocation hintId, Kind kind) {
        if (hintId == null) {
            return null;
        }
        Class<?> cached = CLASS_CACHE.get(hintId);
        if (cached != null) {
            return cached;
        }
        return entityClassOf(hintId);
    }

    /**
     * 这个异常是「确定查不到」还是「现在还不具备条件」。
     *
     * <p>只有前者能进负缓存：注册表未 bootstrap / 类静态初始化失败都属于「等一会就好」，
     * 一旦缓存就会永久失效 —— 那正是上一版「注册表早期问过一次就再也等不到」的老坑。
     */
    private static boolean isDeterministic(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            String s = c.toString();
            if (s.contains("Not bootstrapped") || s.contains("ExceptionInInitializerError")
                    || s.contains("NoClassDefFoundError") || s.contains("ClassNotFoundException")) {
                return false;
            }
        }
        return true;
    }

    /** 换掉候选池/类解析来源时，负缓存必须一起作废（结论依赖它们） */
    static void clearEntityClassCaches() {
        NOT_FOUND.clear();
        RANGED_GATE.clear();
        GATE_REASON.clear();
        AUTO_ATTACK_MEMO.clear();
    }

    /** 异常链最内层原因压成一行（日志用） */
    static String root(Throwable t) {
        if (t == null) {
            return "未知原因";
        }
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        String m = c.toString();
        return m.length() > 200 ? m.substring(0, 200) : m;
    }

    // ------------------------------------------------------------------
    // 自动解析（远程）的门槛：「这个实体真的会远程攻击吗」
    // ------------------------------------------------------------------

    /**
     * 自动解析门槛的判定结果。
     *
     * <h2>为什么必须有门槛</h2>
     * 候选池修好之后，{@link #resolve} 对**任何**施法者都能挑出一枚弹 —— 于是僵尸也会射箭。
     * 但「这枚弹是不是它的」与「它到底会不会远程攻击」是两件事：前者靠候选池打分（会挑，只是挑得准不准），
     * 后者必须由**实体自己声明**。判据一律用原版接口 + 字节码事实，不写死任何模组名：
     * <ol>
     *   <li>{@link RangedAttackMob}（原版接口 {@code performRangedAttack(LivingEntity,float)}）：
     *       {@code AbstractSkeleton}（骷髅/流浪者/凋灵骷髅）、女巫、溺尸、雪傀儡、羊驼、幻术师、凋灵……；</li>
     *   <li>{@link CrossbowAttackMob}（原版弩接口）：掠夺者/猪灵；</li>
     *   <li><b>施法者自己的字节码里 new 过候选池里的某枚投掷物</b>：有些原版怪的远程攻击写在
     *       AI Goal 里、**根本不实现任何接口** —— 烈焰人（{@code Blaze$BlazeAttackGoal} 里 new
     *       {@code SmallFireball}）、恶魂（{@code Ghast$GhastShootFireballGoal} 里 new
     *       {@code LargeFireball}）、潜影贝（new {@code ShulkerBullet}）。
     *       这三条我用 javap/字节搜索对着生产 jar 逐个核对过（见 HARNESS 用例）。</li>
     * </ol>
     * 三条都不成立 = 近战（僵尸/苦力怕/蜘蛛）。
     *
     * <h2>判定路径</h2>
     * 与分类判据**完全同一条**：{@link #casterClassOf}（线上在注册表里真的构造一次实体拿类，
     * 离线 harness 注入替身类），绝不用 {@code EntityType#getBaseClass()}（它恒为 Entity.class）。
     * 结论只在**确定**时按类型缓存（见 {@link #rangedGate}）。
     */
    public enum RangedGate {
        /** 有远程攻击的确凿证据（原版接口 或 字节码里真的 new 过候选投掷物） */
        RANGED,
        /** 确定没有：拿到实体类 + 候选池已就绪 + 字节码读到了 + 三条都不成立 —— 可按类型缓存 */
        MELEE,
        /** 这次判不出来（构造不出实体 / 读不到字节码 / 候选池未就绪）—— **不缓存**，下次重试 */
        UNKNOWN
    }

    /** 门槛结论缓存（只写 RANGED / MELEE；UNKNOWN 一律不写，保证下次能重判） */
    private static final Map<ResourceLocation, RangedGate> RANGED_GATE = new ConcurrentHashMap<>();
    /** 「这次判不出来」的提示（同一类型同一原因只打一次） */
    private static final Set<String> RANGED_UNKNOWN_LOGGED = ConcurrentHashMap.newKeySet();

    /**
     * 自动解析（远程）的门槛，见 {@link RangedGate}。
     *
     * <p>调用方的优先级必须是：**玩家在 GUI 里显式指定过投掷物 → 一律远程（最高优先，
     * 根本不看这个门槛）**；否则才问这里；门槛说不行就保持近战，而且**不写永久否决**
     * （UNKNOWN 时下次必须能重试）。
     */
    public static RangedGate rangedGate(ResourceLocation caster) {
        if (caster == null) {
            return RangedGate.UNKNOWN;
        }
        RangedGate cached = RANGED_GATE.get(caster);
        if (cached != null) {
            return cached;
        }
        Class<?> casterClass = casterClassOf(caster);
        if (casterClass == null) {
            return unknown(caster, "拿不到实体类（注册表未就绪 / 构造不出实体）");
        }
        // 1) 原版接口：任何模组实现它就是在声明「我会远程攻击」
        //    先判子接口 CrossbowAttackMob（它 extends RangedAttackMob），这样日志能说清是「弩」那一种
        if (CrossbowAttackMob.class.isAssignableFrom(casterClass)) {
            return decide(caster, RangedGate.RANGED, "实现原版 CrossbowAttackMob 接口（弩；它继承 RangedAttackMob）");
        }
        if (RangedAttackMob.class.isAssignableFrom(casterClass)) {
            return decide(caster, RangedGate.RANGED, "实现原版 RangedAttackMob 接口");
        }
        // 2) 字节码证据：它自己（含父类与嵌套类）真的 new 过候选池里的某枚弹
        List<Candidate> pool = pool();
        if (pool.isEmpty()) {
            return unknown(caster, "候选池还没建起来（" + poolReason + "）");
        }
        if (!casterTextAvailable(casterClass)) {
            return unknown(caster, "读不到 " + casterClass.getName() + " 的 class 字节码，无法判断它会不会发弹");
        }
        for (Candidate candidate : pool) {
            Class<?> projectile = candidate.entityClass != null
                    ? candidate.entityClass : entityClassOf(candidate.id);
            if (projectile != null && casterReferencesClass(casterClass, projectile)) {
                return decide(caster, RangedGate.RANGED,
                        "字节码里引用了候选投掷物 " + candidate.id + "（它自己就 new 这枚弹）");
            }
        }
        return decide(caster, RangedGate.MELEE,
                "既不实现 RangedAttackMob/CrossbowAttackMob，字节码里也没有引用任何候选投掷物");
    }

    /** 记下门槛结论并**按类型打一次**日志（真机排查要能一眼看出「它为什么是近战」） */
    private static RangedGate decide(ResourceLocation caster, RangedGate gate, String why) {
        RANGED_GATE.put(caster, gate);
        GATE_REASON.put(caster, why);
        if (gate == RangedGate.RANGED) {
            HordeMod.LOGGER.info("[horde] 自动解析门槛：{} 判为**远程**（{}）", caster, why);
        } else {
            HordeMod.LOGGER.info("[horde] 自动解析门槛：{} 判为**近战**（{}）—— 该实体没有远程攻击接口"
                    + "（RangedAttackMob/CrossbowAttackMob）也没有发弹证据，按近战处理"
                    + "（玩家仍可在 GUI 里显式指定投掷物，那条路不受门槛限制）", caster, why);
        }
        return gate;
    }

    private static RangedGate unknown(ResourceLocation caster, String why) {
        // **不写缓存**：下次拿到实体类 / 池就绪 / 能读到字节码时要能重判
        GATE_REASON.put(caster, why + "（这次判不出来，未缓存，下次重试）");
        if (RANGED_UNKNOWN_LOGGED.add(caster + ":" + why)) {
            HordeMod.LOGGER.warn("[horde] 自动解析门槛：{} 这次判不出来（{}）—— 本次按近战处理，"
                    + "且**不缓存**这个结论，下次会重试", caster, why);
        }
        return RangedGate.UNKNOWN;
    }

    /** 施法者代码范围（含父类/嵌套类）的字节码读没读出来（读不到 = 判据不生效，结论不可缓存） */
    static boolean casterTextAvailable(Class<?> caster) {
        return caster != null && casterText(caster) != null;
    }

    // ------------------------------------------------------------------
    // 「外来实体名」判据的真实实现（查注册表；只能懒建，且必须能失败）
    // ------------------------------------------------------------------

    private static volatile java.util.function.BiPredicate<String, String> FOREIGN_ENTITY_PATH;
    /**
     * 判据**已经建好**了吗（与 {@link #FOREIGN_ENTITY_PATH} 是否可用区分开）。
     *
     * 为什么要区分：「注册表还没建起来」和「注册表是空的」必须走不同处理 ——
     * 前者要**下次再来**（启动早期查注册表必然为空），后者才该永远返回 false。
     * 真机上这个区别很关键：如果第一次查询发生在注册表就绪之前，判据就会永久失效。
     */
    private static volatile boolean foreignReady;
    /** 全注册表的实体 path 词干集合 */
    private static final Set<String> ENTITY_STEMS = ConcurrentHashMap.newKeySet();
    /** 每个命名空间的 path 词干集合（判据要求 path 属于**同一个命名空间**的其它实体） */
    private static final Map<String, Set<String>> NS_STEMS = new ConcurrentHashMap<>();

    /**
     * 替换「外来实体名」判据（只给离线 harness 用：离线环境起不了注册表）。
     *
     * 判据的签名是 {@code (完整 id 或 path, 词干)}：第一个参数**优先带命名空间**
     * （{@link #foreignStemPenalty} 现在传的就是 {@code ns:path}），
     * 于是判据能查「同一命名空间」的词干表；不带冒号时按 minecraft 处理（兼容老调用点）。
     */
    public static void setForeignPathCheck(BiPredicate<String, String> check) {
        FOREIGN_ENTITY_PATH = check;
        foreignReady = check != null;
    }

    private static BiPredicate<String, String> foreignEntityPath() {
        if (foreignReady) {
            BiPredicate<String, String> cached = FOREIGN_ENTITY_PATH;
            if (cached != null) {
                return cached;
            }
        }
        try {
            // 第一次用到时才扫全表：注册表在启动早期可能还没建起来
            scanEntityStems();
        } catch (Throwable t) {
            // 拿不到注册表：这次不做这项扣分，**下次再试**（并且说出来，别静默）
            warnOnce("foreign-scan", "[horde] 「外来实体名」判据建立失败：{} —— 这次打分不做这项扣分，下次重试",
                    root(t));
            return (path, stem) -> false;
        }
        if (NS_STEMS.isEmpty()) {
            // 注册表是空的（还没就绪）：不缓存结果，下次再来
            return (path, stem) -> false;
        }
        HordeMod.LOGGER.info("[horde] 投掷物打分已纳入「外来实体名」判据：注册表里共 {} 个实体 path 词干"
                        + "（{} 个命名空间）", ENTITY_STEMS.size(), NS_STEMS.size());
        FOREIGN_ENTITY_PATH = (fullId, stem) -> {
            int i = fullId.indexOf(':');
            String ns = i < 0 ? "minecraft" : fullId.substring(0, i);
            Set<String> nsSet = NS_STEMS.get(ns);
            if (nsSet == null) {
                return false;
            }
            // 注意：**不豁免**「stem 就是候选自己的 path」（老实现那句 `!stem.equals(path)` 是错的）——
            // 原版把投掷物也注册成了实体类型，一豁免 minecraft:fireball 就永远不扣分，
            // 于是它又会盖过 minecraft:arrow（真机上「骷髅放火球」的病根，见 FOREIGN_STEM_MAX 的注释）。
            return nsSet.contains(stem);
        };
        foreignReady = true;
        return FOREIGN_ENTITY_PATH;
    }

    /** 扫全注册表，收集每个命名空间的实体 path 词干 */
    private static void scanEntityStems() {
        for (EntityType<?> type : ForgeRegistries.ENTITY_TYPES.getValues()) {
            ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(type);
            if (id == null) {
                continue;
            }
            String ns = id.getNamespace().toLowerCase(Locale.ROOT);
            Set<String> set = NS_STEMS.computeIfAbsent(ns, k -> ConcurrentHashMap.newKeySet());
            for (String s : stems(id.getPath())) {
                if (s.length() >= 2) {
                    set.add(s);
                    ENTITY_STEMS.add(s);
                }
            }
        }
    }
}
