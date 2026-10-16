package dev.swordkingdom.horde.client;

import dev.swordkingdom.horde.HordeMod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * GeckoLib 动画器的**显式驱动**口 —— 「靠渲染来推动动画」这条路在真机上不成立，这里是替代方案。
 *
 * <h2>为什么渲染驱动不成立（真机日志）</h2>
 * <pre>
 *   [horde] geo 姿态采样：DuckRenderer 按形状找到渲染入口 GeoEntityRenderer.m_7392_(Entity,float,float,PoseStack,MultiBufferSource,int)
 *   [horde] untitledduckmod:duck 的 geo 动画采样失败…：行走样本塌缩（2048 个样本完全相同，说明 GeckoLib 没有真的重算每帧动画）
 * </pre>
 * 2048 = 64 相位 × 32 时间样本，全部逐位相同 —— 也就是说采样期间的骨骼姿态**一次都没有被重算**。
 * 根因是 GeckoLib 的节流（对着 {@code geckolib-forge-1.20.1-4.8.4.jar} 用 {@code javap -p -c} 确认）：
 * {@code GeoModel.handleAnimations(T, long, AnimationState)} 的字节码在「本次动画时间 == 上次动画时间」
 * 且「同一个 instanceId」时直接 {@code return}，而动画时间来自
 * <pre>
 *   animTime += (TICK + partialTick) - lastGameTickTime
 * </pre>
 * 群集采样是**同一个模型实例、同一个傀儡**（同一 instanceId）连续采几千帧，
 * 只要 TICK 没变，整帧动画都会被跳过 —— 采到的其实是上一帧的静止姿态。
 *
 * <h2>GeckoLib 4.8.4 的真实签名（javap 结果，逐字抄自生产 jar）</h2>
 * <pre>
 * public class software.bernie.geckolib.model.GeoModel&lt;T extends GeoAnimatable&gt; {
 *     private final AnimationProcessor&lt;T&gt; processor;      // 真的字段名就叫 processor
 *     private BakedGeoModel currentModel;
 *     private double animTime;
 *     private double lastGameTickTime;
 *     private long lastRenderedInstance;                  // 节流用的每实例字段
 *     public AnimationProcessor&lt;T&gt; getAnimationProcessor();
 *     public void handleAnimations(T, long instanceId, AnimationState&lt;T&gt;);
 *     public void applyMolangQueries(T, double);
 * }
 * public class software.bernie.geckolib.core.animation.AnimationProcessor&lt;T&gt; {
 *     //                                                          ↓ 这个 long 就是 instanceId
 *     public void tickAnimation(T, long, CoreGeoModel&lt;T&gt;, AnimatableManager&lt;T&gt;, double tick,
 *                              AnimationState&lt;T&gt;, boolean crashIfBoneMissing);
 *     public void preAnimationSetup(T, double);           // 只是 model.applyMolangQueries(animatable, tick)
 * }
 * public abstract class AnimatableInstanceCache {
 *     public abstract &lt;T&gt; AnimatableManager&lt;T&gt; getManagerForId(long);
 * }
 * </pre>
 *
 * <h2>为什么直接调 {@code AnimationProcessor.tickAnimation} 而不是 {@code GeoModel.handleAnimations}</h2>
 * <ul>
 *   <li><b>tick 是我传进去的</b>：{@code tickAnimation} 的第 5 个参数就是「本次动画时间」，
 *       于是「推进到目标 tick」这件事是**参数**而不是要绕过的一堆私有累加器
 *       （{@code animTime}/{@code lastGameTickTime}）—— 干净且可验证；</li>
 *   <li><b>没有节流</b>：{@code lastRenderedInstance}/{@code lastUpdateTime} 那套判断在
 *       {@code handleAnimations} 里，{@code tickAnimation} 里没有，所以每次调用都真的重算骨骼；</li>
 *   <li><b>{@code preAnimationSetup} 会顺带做掉</b>：它就是 {@code model.applyMolangQueries(animatable, tick)}，
 *       而 {@code handleAnimations} 里也只多做了一件事 —— 更新 Molang 的 {@code query.controller_speed}，
 *       与骨骼姿态无关。</li>
 * </ul>
 *
 * <h2>不写死任何 GeckoLib 类名</h2>
 * 线上 mod jar 是 SRG 名重混淆的（字段/方法名会变），所以这里一律**按形状**找：
 * 「公开的 void 方法 + 参数 ≥ 5 + 位置 1 是 {@code long}（instanceId）+ 位置 4 是 {@code double}（tick）」
 * ＝ 动画处理入口。实参按**真实参数类型**逐个填（按形状造 AnimationState、按 id 取 AnimatableManager）。
 * 与 {@link GeoRig}、{@link GeoPoseTable.RenderEntry} 是同一套做法。
 */
public final class GeoAnimator {

    private GeoAnimator() {}

    // ------------------------------------------------------------------
    // 发现（只有这一份判据；生产路径与离线用例走同一条）
    // ------------------------------------------------------------------

    /**
     * 为一个**模型对象**建驱动口。
     *
     * @param model GeckoLib 的 {@code GeoModel} 实例（或任何暴露同样形状的对象）
     * @return 找不到动画处理入口时返回 null（调用方退回「渲染驱动」并打日志，绝不抛异常）
     */
    public static Handle fromModel(Object model) {
        if (model == null) {
            return null;
        }
        clearDiagnostics();
        diag("fromModel " + model.getClass().getName());
        try {
            return discover(model);
        } catch (Throwable t) {
            diag("发现过程抛异常：" + root(t));
            HordeMod.LOGGER.debug("[horde] geo 动画驱动发现失败: {}", t.toString());
            return null;
        }
    }

    /**
     * 按形状找「动画处理入口」—— **判据只有这一份**。
     *
     * <h2>真机事故（第二版）：入口找错类 + 形参位置凭印象写</h2>
     * 第一版的判据是「在**模型类**自己身上找 7 参方法（位置 1 long、位置 4 double）」，
     * 真机日志是：
     * <pre>
     *   [horde] geo 姿态采样：DuckModel 上按形状找不到动画处理入口…；退回渲染驱动
     *   [horde] untitledduckmod:duck 的 geo 动画采样失败…：行走样本塌缩
     * </pre>
     * 两处都错，而且两处都必然失败：
     * <ol>
     *   <li><b>入口不在模型上</b>：{@code tickAnimation} 在 {@code AnimationProcessor} 上，
     *       必须经 {@code GeoModel.getAnimationProcessor()}（它声明在 {@code CoreGeoModel} 接口上）
     *       拿到那个对象，再在**它的类**及其父类上找。模型自己身上只有
     *       {@code handleAnimations(T, long, AnimationState)}（3 参、没有 double）。</li>
     *   <li><b>形参位置写错了</b>：对 jar 重新 javap 确认，真实签名是
     *       <pre>
     *   public void tickAnimation(T, CoreGeoModel&lt;T&gt;, AnimatableManager&lt;T&gt;, double, AnimationState&lt;T&gt;, boolean);
     *       </pre>
     *       —— <b>6 个参数、double 在下标 3、根本没有 long</b>（那个 long 是
     *       {@code handleAnimations(T, long, AnimationState)} 的 instanceId，被我串到 tickAnimation 上了）。
     *       旧判据要求「位置 1 是 long」，于是永远匹配不上。</li>
     * </ol>
     *
     * <h2>现在的判据（不写死任何下标，全部按名族 + 相对顺序）</h2>
     * 在 {@code getAnimationProcessor()} 返回对象的类及其父类上找方法，要求：
     * <ol>
     *   <li>返回 {@code void}、非 static，且**优先非桥接/非合成**方法；</li>
     *   <li>参数里**恰好一个 {@code double}**（这就是 tick）；0 个或 ≥2 个都判不匹配并说明；</li>
     *   <li>参数里**存在**（按类型名族，沿其父类/接口链找）：一个 {@code CoreGeoModel}、
     *       一个 {@code AnimatableManager}、一个 {@code AnimationState}；末位是 {@code boolean}；</li>
     *   <li><b>相对顺序</b>：{@code AnimatableManager} &lt; {@code double} &lt; {@code AnimationState}；</li>
     *   <li>若存在 {@code long} 形参，它就是 instanceId（可选，不强制 —— 真实签名里没有）；</li>
     *   <li>若存在参数字段能收下动画宿主（{@code GeoAnimatable} 之类），优先它当第 0 参。</li>
     * </ol>
     * 「参数里有一个 Entity 子类」这条**没有**做成硬判据：真实签名里第 0 参擦除后是
     * {@code GeoAnimatable}（纯接口，见 javap），它并不是 {@code Entity} 的子类 ——
     * 硬要求它反而会再次「找不到入口」。所以它只作为**加分项**（有则优先）。
     */
    static Handle discover(Object model) {
        // 1) 有些实现把入口直接放在模型上：先在模型自己身上找
        TickEntry own = matchTick(model.getClass(), null);
        if (own != null) {
            diag("模型自己身上就有动画处理入口（" + own.describe() + "）");
            return new Handle(model, own, model, "模型自身");
        }
        // 2) GeckoLib 的正路：getAnimationProcessor() 返回的那个对象
        List<String> why = new ArrayList<>();
        Object processor = null;
        String via = null;
        // 2a) 优先「无参、返回类型名字含 AnimationProcessor」的取值方法（getAnimationProcessor 就是这个形状）
        for (Class<?> c = model.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getMethods()) {
                if (m.getParameterCount() != 0 || Modifier.isStatic(m.getModifiers())
                        || m.getReturnType().isPrimitive() || m.getReturnType() == void.class) {
                    continue;
                }
                if (!typeNameContains(m.getReturnType(), "AnimationProcessor")) {
                    continue;
                }
                Object value = invokeGetter(model, m, why);
                if (value == null) {
                    continue;
                }
                TickEntry entry = matchTick(value.getClass(), why);
                sbPick(why, m, value, entry);
                if (entry != null) {
                    processor = value;
                    via = m.getName() + "()";
                    break;
                }
            }
            if (processor != null) {
                break;
            }
        }
        // 2b) 兜底：任何无参取值方法返回的对象上都试着找一次（有些库把它包装了一层）
        if (processor == null) {
            for (Class<?> c = model.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getMethods()) {
                    if (m.getParameterCount() != 0 || Modifier.isStatic(m.getModifiers())
                            || m.getReturnType().isPrimitive() || m.getReturnType() == void.class
                            || m.getReturnType() == String.class || m.getReturnType() == Class.class) {
                        continue;
                    }
                    if (typeNameContains(m.getReturnType(), "AnimationProcessor")) {
                        continue;   // 上面已经试过
                    }
                    Object value = invokeGetter(model, m, null);
                    if (value == null || value == model) {
                        continue;
                    }
                    TickEntry entry = matchTick(value.getClass(), why);
                    sbPick(why, m, value, entry);
                    if (entry != null) {
                        processor = value;
                        via = m.getName() + "()（兜底路径）";
                        break;
                    }
                }
                if (processor != null) {
                    break;
                }
            }
        }
        if (processor == null) {
            diag("没找到动画处理入口。原因清单：");
            for (String w : why) {
                diag("  - " + w);
            }
            return null;
        }
        TickEntry entry = matchTick(processor.getClass(), null);
        if (entry == null) {
            return null;
        }
        return new Handle(processor, entry, model, via);
    }

    /** 记一行「这个取值方法返回的对象形状如何」（失败诊断用） */
    private static void sbPick(List<String> why, Method getter, Object value, TickEntry entry) {
        if (why == null || entry != null) {
            return;
        }
        why.add(getter.getName() + "() -> " + value.getClass().getName()
                + "：没有形状相符的动画处理入口（" + (entry == null ? whyOf(value.getClass()) : "") + "）");
    }

    /** 调一个无参取值方法（失败只记诊断，不抛） */
    private static Object invokeGetter(Object owner, Method m, List<String> why) {
        try {
            // setAccessible 是必需的：方法声明所在的**类**可能不是 public
            // （模组的模型类常常是包私有的内部类），此时公开方法也会 IllegalAccessException
            m.setAccessible(true);
            return m.invoke(owner);
        } catch (Throwable t) {
            if (why != null) {
                why.add(m.getName() + "() 调用失败：" + root(t));
            }
            diag("取值方法 " + m.getName() + "() 调用失败：" + root(t));
            return null;
        }
    }

    // ------------------------------------------------------------------
    // ★ 形状判据：只有这一份实现（生产路径与 harness 都走它）
    // ------------------------------------------------------------------

    /**
     * 在一个类（含父类与接口）上找「形状像 tickAnimation 的动画处理入口」。
     *
     * @param why 失败原因（可为 null）；每个候选为什么不算都会追加进去，便于真机排查
     * @return 命中的入口（含各关键形参的下标，供日志与断言使用）；没有返回 null
     */
    static TickEntry matchTick(Class<?> cls, List<String> why) {
        TickEntry best = null;
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getMethods()) {
                if (m.getReturnType() != void.class || Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                TickEntry entry = shapeOf(m, why);
                if (entry == null) {
                    continue;
                }
                // 优先非桥接/非合成方法（泛型擦除会额外生成一个桥接方法）；
                // 若签名里还带一个 Entity 子类形参（模组把傀儡实体本身也传进来时），再优先一点 ——
                // 这只是**加分项**：真实 tickAnimation 的第 0 参擦除后是 GeoAnimatable（纯接口），
                // 不是 Entity 子类，硬要求它反而会「找不到入口」（javap 已确认）。
                int score = (m.isBridge() || m.isSynthetic() ? 0 : 2) + (entry.hasEntityParam ? 1 : 0);
                if (best == null || score > best.score) {
                    best = entry;
                    best.score = score;
                }
            }
        }
        return best;
    }

    /**
     * 单个方法的形状判定 —— **判据的唯一实现**。
     *
     * 形状要求见 {@link #discover(Object)} 的注释。任何一条不满足都会往 {@code why} 里写一句
     * 「哪个方法、差在哪」，并且**绝不**因为「看起来像别的 void 方法」就放行。
     */
    private static TickEntry shapeOf(Method m, List<String> why) {
        Class<?>[] p = m.getParameterTypes();
        String name = m.getName();
        if (p.length < 4) {
            return null;   // 形状差得太远，不值得进诊断清单（避免刷屏）
        }
        // ---- 恰好一个 double（tick）----
        int doubleIndex = -1;
        int doubleCount = 0;
        for (int i = 0; i < p.length; i++) {
            if (p[i] == double.class) {
                doubleCount++;
                if (doubleIndex < 0) {
                    doubleIndex = i;
                }
            }
        }
        if (doubleCount != 1) {
            reason(why, m, "参数里有 " + doubleCount + " 个 double（tick 时间必须恰好一个）");
            return null;
        }
        // ---- 名族：CoreGeoModel / AnimatableManager / AnimationState 各一个 ----
        int modelIndex = indexOfName(p, "CoreGeoModel");
        int managerIndex = indexOfName(p, "AnimatableManager");
        int stateIndex = indexOfName(p, "AnimationState");
        if (modelIndex < 0 || managerIndex < 0 || stateIndex < 0) {
            reason(why, m, "缺少名族参数（CoreGeoModel@"
                    + (modelIndex < 0 ? "无" : modelIndex) + "、AnimatableManager@"
                    + (managerIndex < 0 ? "无" : managerIndex) + "、AnimationState@"
                    + (stateIndex < 0 ? "无" : stateIndex) + "）");
            return null;
        }
        // ---- 末位 boolean（crashIfBoneMissing）----
        if (p[p.length - 1] != boolean.class) {
            reason(why, m, "末位不是 boolean（实际 " + p[p.length - 1].getSimpleName() + "）");
            return null;
        }
        // ---- 相对顺序：manager < double < state（真实的 tickAnimation 就是这个次序）----
        if (!(managerIndex < doubleIndex && doubleIndex < stateIndex)) {
            reason(why, m, "相对顺序不对（要求 AnimatableManager@" + managerIndex + " < double@"
                    + doubleIndex + " < AnimationState@" + stateIndex + "）");
            return null;
        }
        // ---- 可选：long 形参就是 instanceId ----
        int longIndex = -1;
        for (int i = 0; i < p.length; i++) {
            if (p[i] == long.class) {
                longIndex = i;
                break;
            }
        }
        // ---- 可选加分：存在能收下动画宿主的参数（GeoAnimatable 之类）与 Entity 子类 ----
        boolean hasEntityParam = false;
        for (Class<?> t : p) {
            if (net.minecraft.world.entity.Entity.class.isAssignableFrom(t)) {
                hasEntityParam = true;
                break;
            }
        }
        try {
            m.setAccessible(true);
        } catch (Throwable ignored) {
            // 公开成员无需；失败也不影响后面的 invoke（真机上由驱动统计报错）
        }
        return new TickEntry(m, doubleIndex, longIndex, modelIndex, managerIndex, stateIndex, hasEntityParam);
    }

    /** 把一个方法为什么不匹配写进诊断清单（限长，避免刷屏） */
    private static void reason(List<String> why, Method m, String text) {
        if (why != null && why.size() < 20) {
            why.add(signatureOf(m) + "：" + text);
        }
    }

    /** 参数里第一个「类型名（含父类/接口链）含该名字」的下标；没有返回 -1 */
    private static int indexOfName(Class<?>[] params, String name) {
        for (int i = 0; i < params.length; i++) {
            if (typeNameContains(params[i], name)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 类型自身或它的父类/父接口链里有没有名字含 {@code name} 的类型。
     *
     * 为什么沿父类/接口链找：接口方法返回/接收的具体类型可能是实现类
     * （例如某个模组自己包的 {@code MyGeoModel implements CoreGeoModel}），
     * 只看类型自身的名字会漏掉。这条判据与 {@link GeoRig} 的「名称族」思路一致。
     */
    private static boolean typeNameContains(Class<?> t, String name) {
        if (t == null) {
            return false;
        }
        if (t.getName().contains(name)) {
            return true;
        }
        for (Class<?> i : t.getInterfaces()) {
            if (typeNameContains(i, name)) {
                return true;
            }
        }
        return typeNameContains(t.getSuperclass(), name);
    }

    /** 「这个类上为什么不匹配」的一行摘要（真机排查/离线断言用） */
    public static String whyOf(Class<?> cls) {
        List<String> why = new ArrayList<>();
        matchTick(cls, why);
        return why.isEmpty() ? "没有任何 void 方法带 double 形参" : String.join("；", why);
    }

    /**
     * 诊断（离线用例/真机排查用）：把发现过程看到的东西打出来。
     *
     * 为什么值得留一个公开的诊断串：真机上「找不到动画处理入口」必须能一眼看出是
     * 「模型不对」「处理器不对」还是「形状判据不对」。
     */
    public static String describeDiscovery(Object model) {
        if (model == null) {
            return "模型为 null";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("模型 ").append(model.getClass().getName());
        List<String> why = new ArrayList<>();
        TickEntry own = matchTick(model.getClass(), why);
        sb.append("；模型自身的入口=").append(own == null ? "无" : own.describe());
        Object processor = null;
        String getter = null;
        for (Class<?> c = model.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getMethods()) {
                if (m.getParameterCount() != 0 || Modifier.isStatic(m.getModifiers())
                        || m.getReturnType().isPrimitive() || m.getReturnType() == void.class) {
                    continue;
                }
                if (!typeNameContains(m.getReturnType(), "AnimationProcessor")) {
                    continue;
                }
                Object v = invokeGetter(model, m, null);
                if (v != null) {
                    if (processor == null) {
                        processor = v;
                        getter = m.getName() + "()";
                    }
                }
            }
        }
        sb.append("；处理器取值方法=").append(getter == null ? "未找到" : getter);
        sb.append("；处理器=").append(processor == null ? "无" : processor.getClass().getName());
        if (processor != null) {
            TickEntry entry = matchTick(processor.getClass(), why);
            sb.append("；处理器上的入口=").append(entry == null ? "无" : entry.describe());
            sb.append("；处理器上的 void 方法 = ");
            for (Method m : processor.getClass().getMethods()) {
                if (m.getReturnType() == void.class && m.getParameterCount() >= 2) {
                    sb.append(signatureOf(m)).append(' ');
                }
            }
        }
        if (!why.isEmpty()) {
            sb.append("；诊断：").append(String.join("；", why));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 驱动口
    // ------------------------------------------------------------------

    /**
     * 命中的动画处理入口：方法 + 各关键形参的**实际下标**（不是写死的常量）。
     *
     * <p>把下标记下来有两个用处：日志里能写出「long@1、double@3」这种可核对的信息；
     * harness 能直接断言「double 的下标就是真实 jar 里的那个」。
     */
    public static final class TickEntry {
        final Method method;
        final int doubleIndex;
        final int longIndex;
        final int modelIndex;
        final int managerIndex;
        final int stateIndex;
        final boolean hasEntityParam;
        int score;

        TickEntry(Method method, int doubleIndex, int longIndex, int modelIndex, int managerIndex,
                  int stateIndex, boolean hasEntityParam) {
            this.method = method;
            this.doubleIndex = doubleIndex;
            this.longIndex = longIndex;
            this.modelIndex = modelIndex;
            this.managerIndex = managerIndex;
            this.stateIndex = stateIndex;
            this.hasEntityParam = hasEntityParam;
        }

        /** 声明类上的方法签名（如 {@code AnimationProcessor.tickAnimation(...)}） */
        public String full() {
            return method.getDeclaringClass().getSimpleName() + "." + signatureOf(method);
        }

        /** 可读的形状描述（日志/断言用） */
        public String describe() {
            return full() + "（long@" + longIndex + "、double@" + doubleIndex + "、CoreGeoModel@"
                    + modelIndex + "、AnimatableManager@" + managerIndex + "、AnimationState@"
                    + stateIndex + "）";
        }

        /** double（tick）在形参里的下标 */
        public int doubleIndex() {
            return doubleIndex;
        }

        /** long（instanceId）在形参里的下标；-1 = 该签名没有 long */
        public int longIndex() {
            return longIndex;
        }

        /** 方法签名 */
        public String signature() {
            return signatureOf(method);
        }
    }

    // ------------------------------------------------------------------
    // 发现
    // ------------------------------------------------------------------

    /**
     * 最近一次发现的**诊断文本**（离线用例/真机排查用）。
     *
     * <p>为什么不只用日志：harness 里日志后端不一定可用（见 HARNESS.md 的踩坑第 8 条），
     * 而「找不到入口 / 拿不到管理器 / 调用抛异常」这三种情况必须能区分开 ——
     * 上一版只有一句「样本塌缩」，完全看不出是哪一种。
     */
    private static final StringBuilder DIAG = new StringBuilder();

    /** 清空诊断（每次 {@link #fromModel} 开始时自动清） */
    public static void clearDiagnostics() {
        synchronized (DIAG) {
            DIAG.setLength(0);
        }
    }

    /** 读取诊断文本（离线用例/真机排查用） */
    public static String diagnostics() {
        synchronized (DIAG) {
            return DIAG.toString();
        }
    }

    private static void diag(String s) {
        synchronized (DIAG) {
            if (DIAG.length() < 4000) {
                DIAG.append(s).append('\n');
            }
        }
    }


    /**
     * 驱动统计：采样期间「尝试了几次 / 成功几次 / 建了几次管理器 / 最后为什么失败」。
     *
     * 为什么要把成败**记下来**而不是只看姿态差异：真机上排查「样本塌缩」时，必须能区分
     * 「压根没找到入口」「找到了但拿不到 AnimatableManager」「调用了但抛异常」这三种情况 ——
     * 上一版只有一句「样本完全相同」，完全看不出是哪一种。
     */
    public static final class DriveStatus {
        /** 尝试过的驱动调用次数 */
        public int driveCalls;
        /** 其中真的调用成功的次数 */
        public int driveApplied;
        /** 建立 AnimatableManager 的次数（0 = 傀儡实体上找不到动画实例缓存） */
        public int managers;
        /** 最后一次失败原因（异常链最内层一行） */
        public String lastFailure;

        /** 一行可读统计（日志/报告用） */
        public String describe() {
            return "驱动尝试 " + driveCalls + " 次、成功 " + driveApplied + " 次、建立管理器 " + managers
                    + " 次" + (lastFailure == null ? "" : "，最后失败：" + lastFailure);
        }
    }

    /**
     * 一个可用的驱动口：对外只有一件事 —— <b>把动画推进到某个 tick 并让骨骼姿态被真的算出来</b>。
     *
     * 注意时间的单位与语义：{@code tick} 必须**单调递增**且是**真实 tick 数**
     * （不是渲染帧、也不是「第几次采样」）。GeckoLib 的动画时间累了多少，取决于相邻两次
     * 的差值，所以采样表里「第 t 行 = 动画第 t*step tick」这条映射就是由它保证的。
     */
    public static final class Handle {
        private final Object processor;
        /** 调用入口的宿主（模型自己，或它的 AnimationProcessor） */
        private final Object model;
        /** 命中的入口（含各形参的实际下标） */
        private final TickEntry entry;
        /** 入口是怎么找到的（日志用：模型自身 / getAnimationProcessor() / 兜底取值方法） */
        private final String via;
        /** preAnimationSetup 形状只探测一次 */
        private boolean preTickProbed;
        private Method preTickMethod;
        /** 可读签名（日志/报告用；线上方法名是 SRG，但参数类型是真实的） */
        public final String signature;
        /**
         * 驱动统计（采样器每次读都往里累加）。
         *
         * 为什么要把成败**记下来**而不是只看姿态差异：真机上排查「样本塌缩」时，必须能区分
         * 「压根没找到入口」「找到了但拿不到 AnimatableManager」「调用了但抛异常」这三种情况 ——
         * 上一版只有一句「样本完全相同」，完全看不出是哪一种。
         * <p>统计由 {@link Handle#drive} 自己累加（manager 非空才算「建立管理器」），
         * 所以不管走采样源还是被离线用例直接调用，数字都是同一套语义。
         */
        public final DriveStatus status = new DriveStatus();

        Handle(Object processor, TickEntry entry, Object model, String via) {
            this.processor = processor;
            this.entry = entry;
            this.model = model;
            this.via = via == null ? "未知" : via;
            this.signature = signatureOf(entry.method);
        }

        /** 命中的入口（形参下标等信息，日志/断言用） */
        public TickEntry entry() {
            return entry;
        }

        /** 入口是怎么找到的 */
        public String via() {
            return via;
        }

        /** 可读签名，例如 {@code tickAnimation(GeoAnimatable,CoreGeoModel,AnimatableManager,double,AnimationState,boolean)} */
        public String signature() {
            return signature;
        }

        /**
         * 把动画推进到 {@code tick} 并让 GeckoLib 重算骨骼姿态。
         *
         * <h2>实参按**形状**逐个填，不按下标写死</h2>
         * 真机事故（第二版）就是「按下标填实参」造成的：真实签名
         * {@code tickAnimation(T, CoreGeoModel, AnimatableManager, double, AnimationState, boolean)}
         * 只有 6 个参数、没有 long，而我按「[0]=animatable、[1]=long、[2]=model、[3]=manager、
         * [4]=tick…」硬填，于是 manager 被塞进了 double 的位置。
         * 现在改成：遍历形参，**按类型决定填什么**
         * （{@code double}=tick、{@code long}=instanceId、名族含 CoreGeoModel/AnimatableManager/
         * AnimationState 的分别填模型/管理器/新建状态、末位 boolean=false、能收下动画宿主的填宿主）。
         * 这样 GeckoLib 小版本改了形参顺序也不会再错。
         *
         * @param animatable 该动画的宿主（渲染路径里就是正在渲染的傀儡实体）
         * @param manager    该实例的 {@code AnimatableManager}（{@link #managerOf(Object,long)} 取）
         * @param tick       目标动画时间（真实 tick，单调递增）
         * @param limbSwing  行走**相位**（弧度）—— 必须与真实渲染一致地传进去
         * @param limbSwingAmount 行走**幅度/速度** —— 必须与真实渲染一致地传进去
         * @return 是否真的驱动成功（失败只记 debug + 统计，由调用方在采样自检里判定并退回绑定姿态）
         */
        public boolean drive(Object animatable, Object manager, double tick,
                             float limbSwing, float limbSwingAmount) {
            status.driveCalls++;
            if (manager == null) {
                // 失败的**第一环**就在这里：入口找到了，但拿不到该实例的 AnimatableManager。
                // 单独判一次是为了让日志说清是「没管理器」而不是「调用抛了个莫名其妙的异常」。
                status.lastFailure = "拿不到 AnimatableManager（AnimatableInstanceCache.getManagerForId 没返回）";
                return false;
            }
            status.managers++;
            try {
                Method m = entry.method;
                Class<?>[] p = m.getParameterTypes();
                long instanceId = animatable instanceof net.minecraft.world.entity.Entity e
                        ? e.getId() : 0L;
                // GeckoLib 自己的 handleAnimations 在 tickAnimation 之前会先调
                // preAnimationSetup(animatable, animTime)（= model.applyMolangQueries）。
                // 它只影响 Molang 的 memoized 值，不影响骨骼姿态，所以是**尽力而为**：
                // 找不到/调失败都只记 debug，绝不因此判驱动失败。
                preTick(animatable, tick);
                Object[] args = new Object[p.length];
                for (int i = 0; i < p.length; i++) {
                    args[i] = argFor(p[i], animatable, manager, tick, instanceId,
                            limbSwing, limbSwingAmount);
                }
                m.invoke(processor, args);
                status.driveApplied++;
                return true;
            } catch (Throwable t) {
                status.lastFailure = root(t);
                HordeMod.LOGGER.debug("[horde] geo 动画驱动调用失败（{}）：{}", signature, status.lastFailure);
                return false;
            }
        }

        /** 兼容旧调用：相位/幅度都按 0 处理（离线用例/探针；生产采样一定要用带参的那个） */
        public boolean drive(Object animatable, Object manager, double tick) {
            return drive(animatable, manager, tick, 0.0F, 0.0F);
        }

        /** 惰性查找 {@code preAnimationSetup} 形状（void、恰好 2 参、第 2 参 double）并尽力调一次 */
        private void preTick(Object animatable, double tick) {
            if (!preTickProbed) {
                preTickProbed = true;
                for (Method m : processor.getClass().getMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (m.getReturnType() == void.class && !Modifier.isStatic(m.getModifiers())
                            && p.length == 2 && p[1] == double.class) {
                        preTickMethod = m;
                        try {
                            m.setAccessible(true);
                        } catch (Throwable ignored) {
                            // 公开成员无需
                        }
                        break;
                    }
                }
            }
            if (preTickMethod == null) {
                return;
            }
            try {
                preTickMethod.invoke(processor, animatable, tick);
            } catch (Throwable t) {
                HordeMod.LOGGER.debug("[horde] geo 动画驱动：preAnimationSetup 形状调用失败（忽略）：{}", root(t));
                preTickMethod = null;   // 只失败一次就不再试
            }
        }

        /** 单个形参该填什么（**按类型**决定，不看下标） */
        private Object argFor(Class<?> t, Object animatable, Object manager, double tick,
                              long instanceId, float limbSwing, float limbSwingAmount) {
            if (t == double.class) {
                return tick;
            }
            if (t == long.class) {
                return instanceId;
            }
            if (t == boolean.class) {
                return Boolean.FALSE;      // crashIfBoneMissing
            }
            if (typeNameContains(t, "CoreGeoModel")) {
                return resolveModelArg();
            }
            if (typeNameContains(t, "AnimatableManager")) {
                return manager;
            }
            if (typeNameContains(t, "AnimationState")) {
                return newAnimationState(animatable, t, limbSwing, limbSwingAmount);
            }
            // 能收下动画宿主的形参（GeoAnimatable 之类）就是第 0 参那个 T
            if (animatable != null && t.isInstance(animatable)) {
                return animatable;
            }
            Object model2 = resolveModelArg();
            if (model2 != null && t.isInstance(model2)) {
                return model2;
            }
            return defaultValue(t);
        }

        /**
         * {@code CoreGeoModel} 那个实参该填什么。
         *
         * <p>调用方（{@link GeoPoseTable}）手上有模型，所以 {@link #model} 一般不为 null，直接用；
         * 只有在入口就在模型自己身上（{@link #model} 为空）时才从 processor 的字段里按形状反查，
         * 再不行就填 processor 自己（GeckoLib 的默认实现里该参数只被用来读 Molang）。
         */
        private Object resolveModelArg() {
            if (model != null) {
                return model;
            }
            if (processor != null && !isProcessorLike(processor.getClass())) {
                return processor;   // 方法就在模型自己身上：那 processor 字段里存的就是模型
            }
            Object found = findFieldOfShape(processor, "getBakedModel");
            return found != null ? found : processor;
        }

        private static boolean isProcessorLike(Class<?> cls) {
            return cls.getName().contains("AnimationProcessor");
        }

        /** 在对象的字段里找一个「带某形状方法」的值（用来从 processor 反查模型） */
        private static Object findFieldOfShape(Object owner, String methodHint) {
            if (owner == null) {
                return null;
            }
            for (Class<?> c = owner.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) {
                        continue;
                    }
                    try {
                        f.setAccessible(true);
                        Object v = f.get(owner);
                        if (v == null || v == owner) {
                            continue;
                        }
                        for (Method m : v.getClass().getMethods()) {
                            if (m.getParameterCount() == 1 && m.getName().contains(methodHint)) {
                                return v;
                            }
                        }
                    } catch (Throwable ignored) {
                        // 试下一个字段
                    }
                }
            }
            return null;
        }

        /**
         * 造一个 {@code AnimationState<T>}（GeckoLib 的构造签名是
         * {@code (T animatable, float limbSwing, float limbSwingAmount, float partialTick, boolean isMoving)}）。
         *
         * 为什么每帧都新建：{@code AnimationState} 是**每帧一次**的载体（动画控制器往里写当前动画），
         * 复用同一个实例会把上一帧的控制器状态带过来。构造失败返回 null ——
         * 调用方会看到驱动失败，再由「姿态差异」判定并退回绑定姿态（绝不画错）。
         *
         * @param type 方法形参的真实类型（可能来自另一个类加载器，所以只能按形状找构造器）
         */
        private static Object newAnimationState(Object animatable, Class<?> type,
                                                float limbSwing, float limbSwingAmount) {
            if (type == null) {
                return null;
            }
            for (java.lang.reflect.Constructor<?> ctor : type.getDeclaredConstructors()) {
                Class<?>[] p = ctor.getParameterTypes();
                if (p.length != 5 || p[1] != float.class || p[2] != float.class
                        || p[3] != float.class || p[4] != boolean.class) {
                    continue;
                }
                try {
                    ctor.setAccessible(true);
                    // 三个数值实参必须**照 GeckoLib 自己的渲染路径**填
                    // （javap 确认：GeoEntityRenderer.actuallyRender 是
                    //  new AnimationState(animatable, walkAnimation.position(partialTick),
                    //                     walkAnimation.speed(partialTick), partialTick, isMoving)）：
                    //  · limbSwing / limbSwingAmount = 傀儡实体 walkAnimation 的 position/speed
                    //    —— **这是本轮真机事故的修复点**：模组默认的动画选择器就是读
                    //    state.getLimbSwingAmount()（untitledduckmod 是 |amount| >= 0.05 ? WALK : IDLE），
                    //    我们过去一律传 0 ⇒ 控制器永远选待机动画，走表里根本没有走路姿态；
                    //  · partialTick **必须是常量 0**（否则 animTime 的增量不再等于 age 增量，动画会变速）；
                    //  · isMoving：GeckoLib 的规则是「实体水平速度 >= 阈值 且 limbSwingAmount != 0」。
                    //    群集采样里傀儡不位移，所以用「幅度非 0」作为等价信号 —— 否则所有走/跑动画
                    //    都会掉进 idle 分支（那是同一类事故的另一个入口）。
                    boolean moving = limbSwingAmount >= 0.05F || limbSwingAmount <= -0.05F;
                    return ctor.newInstance(animatable, limbSwing, limbSwingAmount, 0.0F, moving);
                } catch (Throwable t) {
                    HordeMod.LOGGER.debug("[horde] geo 动画驱动：AnimationState 构造失败: {}", root(t));
                    return null;
                }
            }
            return null;
        }

        private static Object defaultValue(Class<?> t) {
            if (t == boolean.class) {
                return Boolean.FALSE;
            }
            if (t == int.class) {
                return 0;
            }
            if (t == long.class) {
                return 0L;
            }
            if (t == float.class) {
                return 0.0F;
            }
            if (t == double.class) {
                return 0.0D;
            }
            return null;
        }
    }

    /**
     * 该动画宿主的「实例管理器」（{@code AnimatableInstanceCache.getManagerForId(long)}）。
     *
     * <p>只有这一条实现（反射），没有可注入的替代路径 —— 理由与形状判据一样：
     * 多一条路就会多一次「生产走的是另一条、离线测的是这一条」的事故。
     * {@code geoHarness} 也是直接跑它（替身类的类名带 AnimatableInstanceCache / AnimatableManager）。
     *
     * <p>GeckoLib 用它把**同一个模型实例**上不同动画宿主（每只傀儡）的状态分开，
     * 所以我们的每个采样维度必须用不同的 instanceId（见 {@link GeoPoseTable} 里的傀儡建立）。
     *
     * @return 拿不到返回 null（驱动会失败并退回绑定姿态；三个阶段都写诊断）
     */
    public static Object managerOf(Object animatable, long instanceId) {
        return managerOfReflect(animatable, instanceId);
    }

    /** 反射实现（线上路径）：宿主 -> 动画实例缓存 -> 按 instanceId 取管理器 */
    static Object managerOfReflect(Object animatable, long instanceId) {
        if (animatable == null) {
            return null;
        }
        try {
            Object cache = null;
            for (Method m : animatable.getClass().getMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() != void.class
                        && m.getReturnType().getName().contains("AnimatableInstanceCache")) {
                    try {
                        m.setAccessible(true);
                    } catch (Throwable ignored) {
                        // 公开成员无需
                    }
                    cache = m.invoke(animatable);
                    break;
                }
            }
            if (cache == null) {
                diag("managerOf：宿主上找不到「无参、返回 AnimatableInstanceCache」的取值点");
                return null;
            }
            for (Method m : cache.getClass().getMethods()) {
                if (m.getParameterCount() != 1 || m.getParameterTypes()[0] != long.class
                        || !m.getReturnType().getName().contains("AnimatableManager")) {
                    continue;
                }
                try {
                    m.setAccessible(true);
                } catch (Throwable ignored) {
                    // 公开成员无需
                }
                Object v = m.invoke(cache, instanceId);
                diag("managerOf：用 " + m.getName() + "(" + instanceId + ") 拿到 "
                        + (v == null ? "null" : v.getClass().getName()));
                return v;
            }
            diag("managerOf：缓存 " + cache.getClass().getName()
                    + " 上没有「单参 long、返回 AnimatableManager」的方法");
        } catch (Throwable t) {
            diag("managerOf 失败：" + root(t));
            HordeMod.LOGGER.debug("[horde] geo 动画驱动：取 AnimatableManager 失败（{}）：{}",
                    animatable.getClass().getSimpleName(), root(t));
        }
        return null;
    }

    /** 方法签名的可读形式（**用完整类名**：线上形参类型就是 software.bernie.geckolib...，简名会丢信息） */
    static String signatureOf(Method m) {
        StringBuilder sb = new StringBuilder(m.getName()).append('(');
        Class<?>[] p = m.getParameterTypes();
        for (int i = 0; i < p.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(simpleName(p[i]));
        }
        return sb.append(')').toString();
    }

    /** 类型的一个好认的名字（GeckoLib 那几个用简名，其余用完整名 —— 免得简名全是 Object） */
    private static String simpleName(Class<?> t) {
        if (t == long.class || t == double.class || t == float.class || t == int.class || t == boolean.class) {
            return t.getName();
        }
        String n = t.getName();
        int i = n.lastIndexOf('.');
        return i < 0 ? n : n.substring(i + 1);
    }

    /** 异常链最内层原因压成一行 */
    static String root(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        String m = c.toString();
        return m.length() > 200 ? m.substring(0, 200) : m;
    }
}
