package dev.swordkingdom.horde.action;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;

/**
 * **通用**动作/技能动画驱动 —— 不针对任何模组。
 *
 * 大量模组（Citadel 系、Lionfish 系、灾变 Cataclysm 的 Ignis / Ender Guardian 等）的「动作动画」
 * 都不是写死在 {@code setupAnim} 里的，而是由实体上的**动画状态 + 动画计时**选择：
 *
 * <pre>
 *   Animation getAnimation();            void setAnimation(Animation);
 *   int       getAnimationTick();        void setAnimationTick(int);
 *   Animation[] getAnimations();         // 可选的动画清单
 * </pre>
 *
 * 这个类只按「形状」发现这套 API（获取器/设置器成对、返回类型一致、清单来自返回数组的无参方法
 * 或类里的 public static final 常量），因此对任何长这样的模组都通用。
 * 群集单位没有真实实体，我们就在**替身实体**上写这套状态，再跑模型自己的 {@code setupAnim}，
 * 于是 boss 的挥砍/砸地/咆哮等动作动画会以**和真实实体完全相同**的方式播出来。
 *
 * 注意：这里只负责**动画**。技能的实际效果（伤害、弹射物、地形）必须由真实实体跑，
 * 群集单位能复现的是它们的属性（见 CrowdManager.TypeStats）与动作动画。
 */
public final class ActionDriver {

    private static final IdentityHashMap<Class<?>, ActionDriver> CACHE = new IdentityHashMap<>();

    private final Method stateGetter;
    private final Method stateSetter;
    private final Method tickGetter;
    private final Method tickSetter;
    private final Object[] choices;
    private final Object none;

    private ActionDriver(Method stateGetter, Method stateSetter, Method tickGetter, Method tickSetter,
                         Object[] choices, Object none) {
        this.stateGetter = stateGetter;
        this.stateSetter = stateSetter;
        this.tickGetter = tickGetter;
        this.tickSetter = tickSetter;
        this.choices = choices;
        this.none = none;
    }

    /** 该实体的动作数（不含「无动作」）。0 = 这套 API 不存在，退化成普通 setupAnim。 */
    public int actionCount() {
        return choices.length;
    }

    public boolean usable() {
        return stateSetter != null && choices.length > 0;
    }

    /** 把「第 actionIndex 个动作、已播 actionTick tick」写到替身实体上；actionIndex = 0 表示停止动作。 */
    public void apply(Object entity, int actionIndex, int actionTick) {
        if (entity == null || stateSetter == null) {
            return;
        }
        try {
            if (actionIndex <= 0 || actionIndex > choices.length) {
                stateSetter.invoke(entity, none);
                if (tickSetter != null) {
                    tickSetter.invoke(entity, 0);
                }
                return;
            }
            stateSetter.invoke(entity, choices[actionIndex - 1]);
            if (tickSetter != null) {
                tickSetter.invoke(entity, Math.max(0, actionTick));
            }
        } catch (Throwable ignored) {
            // 写不进去就当没有动作
        }
    }

    /** 诊断用：这套 API 具体挂在哪些成员上 */
    public String describe() {
        return "getter=" + name(stateGetter) + " setter=" + name(stateSetter)
                + " tickGetter=" + name(tickGetter) + " tickSetter=" + name(tickSetter)
                + " none=" + (none == null ? "null" : none.getClass().getSimpleName())
                + " choices=" + choices.length;
    }

    private static String name(Method m) {
        return m == null ? "-" : m.getName();
    }

    public static ActionDriver of(Class<?> entityClass) {
        if (entityClass == null) {
            return null;
        }
        return CACHE.computeIfAbsent(entityClass, ActionDriver::discover);
    }

    private static ActionDriver discover(Class<?> cls) {
        Method stateGetter = null;
        Method stateSetter = null;
        Method tickGetter = null;
        Method tickSetter = null;
        Method listGetter = null;

        for (Class<?> k = cls; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                // 必须在这里就放开访问权限 —— 后面每个分支都会 continue，
                // 放到循环末尾会因为 continue 被跳过，导致 invoke 静默失败。
                try {
                    m.setAccessible(true);
                } catch (Throwable ignored) {
                    // 公开方法无需
                }
                Class<?> ret = m.getReturnType();
                int params = m.getParameterCount();
                String name = m.getName().toLowerCase(Locale.ROOT);

                // 清单：无参、返回「某个非基本类型」的数组
                if (params == 0 && ret.isArray() && !ret.getComponentType().isPrimitive()
                        && (name.contains("animation") || name.contains("action") || listGetter == null)) {
                    listGetter = m;
                    continue;
                }
                // 状态获取器：无参、返回非基本类型（既不是 int 也不是 String 之类）
                if (params == 0 && !ret.isPrimitive() && ret != String.class
                        && !ret.isArray() && !Number.class.isAssignableFrom(ret)
                        && (name.contains("animation") || name.contains("state")
                        || name.contains("action") || name.contains("phase"))) {
                    if (stateGetter == null) {
                        stateGetter = m;
                    }
                    continue;
                }
                // 状态设置器：一个参数、无返回，参数类型非基本类型
                if (params == 1 && ret == void.class && !m.getParameterTypes()[0].isPrimitive()
                        && (name.contains("animation") || name.contains("state")
                        || name.contains("action") || name.contains("phase"))) {
                    if (stateSetter == null) {
                        stateSetter = m;
                    }
                    continue;
                }
                // 计时：无参 int / 一个 int 参数
                if (params == 0 && ret == int.class && name.contains("tick")) {
                    if (tickGetter == null) {
                        tickGetter = m;
                    }
                    continue;
                }
                if (params == 1 && m.getParameterTypes()[0] == int.class && ret == void.class && name.contains("tick")) {
                    if (tickSetter == null) {
                        tickSetter = m;
                    }
                }
            }
        }

        // 设置器的参数类型必须和获取器的返回类型一致
        if (stateGetter != null && stateSetter != null
                && stateSetter.getParameterTypes()[0] != stateGetter.getReturnType()) {
            stateSetter = null;
        }
        if (stateGetter == null) {
            return new ActionDriver(null, null, tickGetter, tickSetter, new Object[0], null);
        }

        Class<?> stateType = stateGetter.getReturnType();
        List<Object> choices = new ArrayList<>();
        Object none = null;

        // 1) 优先用清单方法（Cataclysm 的 getAnimations()）
        if (listGetter != null && listGetter.getReturnType().getComponentType() == stateType) {
            try {
                listGetter.setAccessible(true);
                Object arr = listGetter.invoke(null);
                if (arr instanceof Object[] a) {
                    for (Object o : a) {
                        if (o != null) {
                            choices.add(o);
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 清单取不到就退回常量扫描
            }
        }
        // 2) 退回：类里的 public static final 同类型常量
        if (choices.isEmpty()) {
            for (Class<?> k = cls; k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers()) || !Modifier.isFinal(f.getModifiers())
                            || f.getType() != stateType) {
                        continue;
                    }
                    try {
                        f.setAccessible(true);
                        Object v = f.get(null);
                        if (v == null) {
                            continue;
                        }
                        String n = f.getName().toUpperCase(Locale.ROOT);
                        if (n.contains("NONE") || n.contains("NO_ANIMATION") || n.contains("EMPTY")) {
                            if (none == null) {
                                none = v;
                            }
                        } else {
                            choices.add(v);
                        }
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                }
            }
        }
        if (none == null) {
            // 没有明确的「无动作」常量：用获取器的当前值兜底（构造时通常就是无动作）
            try {
                stateGetter.setAccessible(true);
                none = null;
            } catch (Throwable ignored) {
                // 忽略
            }
        }
        return new ActionDriver(stateGetter, stateSetter, tickGetter, tickSetter,
                choices.toArray(), none);
    }

    /** 供离线验证读取：把当前状态与计时读回来 */
    public int[] read(Object entity) {
        int state = -1;
        int tick = 0;
        try {
            if (stateGetter != null) {
                Object v = stateGetter.invoke(entity);
                for (int i = 0; i < choices.length; i++) {
                    if (choices[i] == v) {
                        state = i + 1;
                        break;
                    }
                }
                if (state < 0 && v == none) {
                    state = 0;
                }
            }
            if (tickGetter != null) {
                tick = (Integer) tickGetter.invoke(entity);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return new int[]{state, tick};
    }
}
