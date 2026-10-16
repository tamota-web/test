package dev.swordkingdom.horde.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.swordkingdom.horde.HordeMod;
import net.minecraft.client.model.EntityModel;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * **通用**模型部件系统适配器 —— 不针对任何具体模组。
 *
 * 思路：不假设「部件类叫什么、成员叫什么」，而是从模型对象图里**发现**部件，再按
 * 「名称族 + 探针校验」确定各成员的含义：
 *
 *  1. 发现部件：模型上任何「返回 Iterable 的无参方法」（{@code getAllParts/getAllBones/parts…}）
 *     的元素，加上从模型字段出发做有限深度遍历得到、且**结构像部件**的对象
 *     （有 ≥3 个「角度族」成员 + ≥3 个「位置族」成员）。
 *  2. 分类成员（按名称族，不绑定具体类）：
 *     - 旋转：{@code xRot/x/y/z?  rotX/rotateAngleX/rotationAngleX/angleX/…X}
 *     - 位置：{@code x/y/z（原版）  posX/positionX/rotationPointX/pivotX/…X}
 *     - 位移：{@code offsetX/offX/translateX/…X}
 *     - 可见性：{@code showModel/visible/isVisible/hidden/isHidden}
 *     - 复位：{@code resetPose/resetToDefaultPose/…（无参、名字含 reset）}
 *  3. 层级：优先 {@code getParent()}，否则找「元素是同类部件的集合/映射字段」，再不行按渲染顺序推断。
 *  4. 自己那部分几何：优先用「参数是 {@code PoseStack.Pose} + {@code VertexConsumer}」的私有方法
 *     （Citadel 的 doRender、原版 ModelPart 的 compile 都是这个形状）；
 *     否则用「参数是 {@code PoseStack} + {@code VertexConsumer}」的渲染方法，并**临时摘掉子部件**。
 *  5. 探针校验：烘焙时用行走姿态跑一次 setupAnim，检查「角度族」选中的成员是否真的在变，
 *     没变就换用变化最大的那组 —— 这样即使命名习惯完全不同也不会选错。
 *
 * 覆盖：原版 ModelPart 派生体系、Citadel AdvancedModelBox、以及任何「部件对象自持角度/位置」
 * 的自定义体系。GeckoLib/AzureLib 这类「骨骼只是数据、由模型集中渲染」且渲染器不是
 * {@code LivingEntityRenderer} 的体系不属于本路径（它们本来就不会走到这里）。
 */
final class ReflectiveRig implements ModelRig {

    /** 位置/位移成员到「格」的换算：原版与 Bedrock 系模型统一是 1/16 */
    private static final float UNIT = 16.0F;
    private static final int MAX_DEPTH = 5;
    private static final int MAX_OBJECTS = 4096;

    private static final IdentityHashMap<Class<?>, Refl> REFL_CACHE = new IdentityHashMap<>();

    private final List<Part> parts;

    private ReflectiveRig(List<Part> parts) {
        this.parts = parts;
    }

    @Override
    public List<Part> allParts() {
        return parts;
    }

    // ------------------------------------------------------------------
    // 发现
    // ------------------------------------------------------------------

    static ModelRig tryCreate(EntityModel<?> model, Entity puppet) {
        try {
            List<Object> candidates = discover(model);
            if (candidates.isEmpty()) {
                return null;
            }
            // 只保留「像部件」的对象
            List<Object> parts = new ArrayList<>();
            IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
            for (Object o : candidates) {
                if (o == null || seen.put(o, Boolean.TRUE) != null) {
                    continue;
                }
                Refl r = REFL_CACHE.computeIfAbsent(o.getClass(), Refl::new);
                if (r.looksLikePart()) {
                    parts.add(o);
                }
            }
            if (parts.size() < 2) {
                return null;
            }
            Refl refl = REFL_CACHE.get(parts.get(0).getClass());

            // 探针校验：用行走姿态跑一次 setupAnim，把「角度族」换成真正会动的那个成员
            verifyRotationMembers(model, puppet, parts, refl);

            // 建立父子关系：优先 getParent()，否则用子部件容器
            IdentityHashMap<Object, GenPart> wrappers = new IdentityHashMap<>();
            for (Object o : parts) {
                wrappers.put(o, new GenPart(o, refl));
            }
            IdentityHashMap<Object, GenPart> byObject = wrappers;
            for (Object o : parts) {
                GenPart part = wrappers.get(o);
                Object parent = refl.parent(part.handle);
                if (parent != null) {
                    part.parent = byObject.get(parent);
                }
            }
            // 用容器补充（getParent 缺失时）
            for (Object o : parts) {
                GenPart part = wrappers.get(o);
                for (Object child : refl.children(part.handle)) {
                    GenPart c = byObject.get(child);
                    if (c != null && c.parent == null && c != part) {
                        c.parent = part;
                    }
                }
            }
            for (GenPart part : wrappers.values()) {
                if (part.parent != null) {
                    part.parent.kids.add(part);
                }
            }
            // 兜底：剩下的挂到第一个部件下面，保证不丢几何
            GenPart root = wrappers.get(parts.get(0));
            for (Object o : parts) {
                GenPart part = wrappers.get(o);
                if (part.parent == null && part != root) {
                    part.parent = root;
                    root.kids.add(part);
                }
            }

            List<Part> ordered = new ArrayList<>();
            addTree(root, ordered);
            for (GenPart part : wrappers.values()) {
                if (!part.visited) {
                    addTree(part, ordered);
                }
            }
            if (ordered.isEmpty()) {
                return null;
            }
            // 安全网：全部部件都「不可见」几乎一定是可见性字段识别错了
            // （AdvancedModelBox 的 isHidden 就是这么坑过一次：整个 boss 一个顶点都烘焙不出来且不报错）。
            // 这种情况下按可见处理，并大声说出来。
            int visibleCount = 0;
            for (Part p : ordered) {
                if (p.visible()) {
                    visibleCount++;
                }
            }
            if (visibleCount == 0) {
                HordeMod.LOGGER.warn("[horde] 该模型的 {} 个部件全被判为不可见，已按「全部可见」处理"
                        + "（可见性字段可能识别有误，请把这条日志发出来）", ordered.size());
                for (Object o : parts) {
                    GenPart g = wrappers.get(o);
                    if (g != null) {
                        g.forceVisible = true;
                    }
                }
            }
            return new ReflectiveRig(ordered);
        } catch (Throwable t) {
            HordeMod.LOGGER.debug("[horde] 通用部件系统识别失败: {}", t.toString());
            return null;
        }
    }

    private static void addTree(GenPart part, List<Part> out) {
        if (part.visited) {
            return;
        }
        part.visited = true;
        out.add(part);
        for (GenPart child : part.kids) {
            addTree(child, out);
        }
    }

    /**
     * 探针校验「角度族」成员。
     *
     * 同类成员名可能命中多个（例如同时存在 {@code xRot} 与 {@code rotateAngleX}），
     * 也可能第一个命中的其实是别的用途。这里用行走姿态跑一遍 setupAnim，
     * 每个轴改选「变化最大」的那个候选 —— 完全靠行为判断，不依赖命名习惯。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void verifyRotationMembers(EntityModel model, Entity puppet,
                                              List<Object> parts, Refl refl) {
        try {
            float[][] probes = {{0.0F, 0.0F}, {2.0F, 1.0F}, {4.5F, 0.7F}};
            float[][][] sum = new float[3][][];
            for (int axis = 0; axis < 3; axis++) {
                List<Field> cand = refl.rotCand(axis);
                sum[axis] = new float[cand.size()][2];   // [候选][基准, 变化量]
                for (int i = 0; i < cand.size(); i++) {
                    sum[axis][i][0] = refl.getF(parts.get(0), cand.get(i));
                }
            }
            for (float[] probe : probes) {
                model.attackTime = 0.0F;
                model.riding = false;
                model.young = false;
                model.prepareMobModel(puppet, probe[0], probe[1], 0.0F);
                model.setupAnim(puppet, probe[0], probe[1], 0.0F, 0.0F, 0.0F);
                for (int axis = 0; axis < 3; axis++) {
                    List<Field> cand = refl.rotCand(axis);
                    for (int i = 0; i < cand.size(); i++) {
                        float v = refl.getF(parts.get(0), cand.get(i));
                        sum[axis][i][1] += Math.abs(v - sum[axis][i][0]);
                    }
                }
            }
            for (int axis = 0; axis < 3; axis++) {
                List<Field> cand = refl.rotCand(axis);
                if (cand.size() <= 1) {
                    continue;
                }
                int best = 0;
                for (int i = 1; i < cand.size(); i++) {
                    if (sum[axis][i][1] > sum[axis][best][1]) {
                        best = i;
                    }
                }
                if (best != 0) {
                    HordeMod.LOGGER.debug("[horde] 通用部件适配：轴 {} 的旋转成员改用 {}（探针变化最大）",
                            axis, cand.get(best).getName());
                    refl.rot[axis] = cand.get(best);
                }
            }
        } catch (Throwable t) {
            HordeMod.LOGGER.debug("[horde] 旋转成员探针校验失败（按名称族首选继续）: {}", t.toString());
        }
    }

    /** 收集候选对象：Iterable 无参方法 + 字段图有限深度遍历。 */
    private static List<Object> discover(EntityModel<?> model) {
        List<Object> out = new ArrayList<>();
        // 1) 返回 Iterable 的无参方法
        for (Class<?> c = model.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() != 0 || !Iterable.class.isAssignableFrom(m.getReturnType())) {
                    continue;
                }
                try {
                    m.setAccessible(true);
                    Object res = m.invoke(model);
                    if (res instanceof Iterable<?> it) {
                        for (Object o : it) {
                            if (o != null) {
                                out.add(o);
                            }
                            if (out.size() > MAX_OBJECTS) {
                                break;
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    // 试下一个
                }
            }
        }
        // 2) 字段图遍历（含集合/映射/数组元素）
        IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
        for (Class<?> c = model.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    walk(f.get(model), out, visited, 0);
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
        }
        return out;
    }

    private static void walk(Object value, List<Object> out, IdentityHashMap<Object, Boolean> visited, int depth) {
        if (value == null || depth > MAX_DEPTH || out.size() > MAX_OBJECTS) {
            return;
        }
        if (value instanceof Collection<?> col) {
            for (Object o : col) {
                walk(o, out, visited, depth + 1);
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Object o : map.values()) {
                walk(o, out, visited, depth + 1);
            }
            return;
        }
        Class<?> cls = value.getClass();
        if (cls.isPrimitive() || cls.isArray() || value instanceof String || value instanceof Number
                || value instanceof Boolean || cls.getName().startsWith("java.")) {
            return;
        }
        if (visited.put(value, Boolean.TRUE) != null) {
            return;
        }
        out.add(value);
        // 不要顺着实体对象爬；原版内部对象只在「它本身就像部件」时才继续下钻
        if (cls.getName().startsWith("net.minecraft.world.entity")) {
            return;
        }
        if (cls.getName().startsWith("net.minecraft.")) {
            Refl r = REFL_CACHE.computeIfAbsent(cls, Refl::new);
            if (!r.looksLikePart()) {
                return;
            }
        }
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    walk(f.get(value), out, visited, depth + 1);
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 成员分类（按名称族；「位置」优先判定，避免 rotationPointX 被当成旋转）
    // ------------------------------------------------------------------

    /** 位置族：原版 {@code x}、基岩系 {@code posX/positionX}、Citadel {@code rotationPointX} 等 */
    private static boolean posName(String name, char axis) {
        String n = name.toLowerCase(Locale.ROOT);
        char a = Character.toLowerCase(axis);
        if (n.length() == 1) {
            return n.charAt(0) == a;
        }
        return n.equals("pos" + a) || n.equals("position" + a) || n.equals("pivot" + a)
                || n.equals("rotationpoint" + a) || n.equals("defaultposition" + a)
                || n.equals(a + "pos") || n.endsWith("pos" + a) || n.endsWith("position" + a)
                || n.endsWith("point" + a) || n.endsWith("pivot" + a);
    }

    /** 旋转族：原版 {@code xRot}、基岩系 {@code rotX}、Citadel {@code rotateAngleX} 等 */
    private static boolean rotName(String name, char axis) {
        String n = name.toLowerCase(Locale.ROOT);
        char a = Character.toLowerCase(axis);
        return n.equals("rot" + a) || n.equals("rotateangle" + a) || n.equals("rotationangle" + a)
                || n.equals("rotation" + a) || n.equals("angle" + a) || n.equals(a + "rot")
                || n.endsWith("rot" + a) || n.endsWith("angle" + a);
    }

    private static boolean offsetAxis(String name, char axis) {
        String n = name.toLowerCase(Locale.ROOT);
        char a = Character.toLowerCase(axis);
        return n.equals("offset" + a) || n.equals("off" + a) || n.equals("translate" + a)
                || n.equals("translation" + a) || n.equals(a + "offset") || n.endsWith("offset" + a);
    }

    private static boolean visibleName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.equals("showmodel") || n.equals("visible") || n.equals("isvisible") || n.equals("show");
    }

    /**
     * 「隐藏」族字段：语义与「可见」**相反**。
     *
     * 踩过的坑：`AdvancedModelBox` 有一个私有 `isHidden`，如果把它当成「可见」字段直接读，
     * 得到的是「是否隐藏」——于是所有部件都被判成不可见，整个 boss 一个顶点都烘焙不出来，
     * 而且不报任何错。所以这里单独记，取值时取反。
     */
    private static boolean hiddenName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.equals("hidden") || n.equals("ishidden") || n.equals("invisible") || n.equals("isinvisible");
    }

    private static boolean resetName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.startsWith("reset") && (n.contains("pose") || n.contains("default"));
    }

    /** 某个部件类的能力表（按类缓存）。 */
    private static final class Refl {
        /** 各轴的候选成员（同一个轴上可能命中多个，最后靠探针挑真正会动的那个） */
        final List<Field> rotCandidates = new ArrayList<>();
        final List<Field> rotY = new ArrayList<>();
        final List<Field> rotZ = new ArrayList<>();
        Field[] rot = new Field[3];
        Field[] pos = new Field[3];
        Field[] off = new Field[3];
        Field visible;
        /** 语义相反的「隐藏」字段（取值要取反），与 visible 一起决定可见性 */
        Field hiddenField;
        final List<Field> containerCandidates = new ArrayList<>();
        Field containerField;
        Method parentMethod;
        Method translateRotate;
        Method renderOwnPose;    // (PoseStack.Pose, VertexConsumer, int, int, float×4) —— 只画自己的立方体
        Method renderSubtree;    // (PoseStack, VertexConsumer, int, int[, float×4]) —— 画自己 + 子部件
        Method reset;
        boolean poseStyle;

        Refl(Class<?> cls) {
            for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    String name = f.getName();
                    Class<?> t = f.getType();
                    if (t == float.class) {
                        for (int i = 0; i < 3; i++) {
                            char a = "XYZ".charAt(i);
                            List<Field> cand = switch (i) {
                                case 0 -> rotCandidates;
                                case 1 -> rotY;
                                default -> rotZ;
                            };
                            if (pos[i] == null && posName(name, a)) {
                                pos[i] = f;
                            } else if (rotName(name, a)) {
                                cand.add(f);
                            } else if (off[i] == null && offsetAxis(name, a)) {
                                off[i] = f;
                            }
                        }
                    } else if (t == boolean.class && visible == null && visibleName(name)) {
                        visible = f;
                    } else if (t == boolean.class && hiddenField == null && hiddenName(name)) {
                        hiddenField = f;
                    } else if (Collection.class.isAssignableFrom(t) || Map.class.isAssignableFrom(t)) {
                        containerCandidates.add(f);
                    }
                    try {
                        f.setAccessible(true);
                    } catch (Throwable ignored) {
                        // 公开字段无需
                    }
                }
                for (Method m : c.getDeclaredMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    String name = m.getName();
                    if (m.getParameterCount() == 0 && !m.getReturnType().isPrimitive()
                            && cls.isAssignableFrom(m.getReturnType())) {
                        if (parentMethod == null) {
                            parentMethod = m;
                        }
                    } else if (p.length == 1 && p[0] == PoseStack.class && m.getReturnType() == void.class) {
                        if (translateRotate == null && name.toLowerCase(Locale.ROOT).contains("translate")) {
                            translateRotate = m;
                        }
                    } else if (p.length >= 4 && p[0] == PoseStack.Pose.class && VertexConsumer.class.isAssignableFrom(p[1])) {
                        if (renderOwnPose == null) {
                            renderOwnPose = m;
                        }
                    } else if (p.length >= 4 && p[0] == PoseStack.class && VertexConsumer.class.isAssignableFrom(p[1])) {
                        if (renderSubtree == null) {
                            renderSubtree = m;
                        }
                    } else if (m.getParameterCount() == 0 && resetName(name)) {
                        if (reset == null) {
                            reset = m;
                        }
                    }
                    try {
                        m.setAccessible(true);
                    } catch (Throwable ignored) {
                        // 公开方法无需
                    }
                }
            }
            for (int i = 0; i < 3; i++) {
                List<Field> cand = switch (i) {
                    case 0 -> rotCandidates;
                    case 1 -> rotY;
                    default -> rotZ;
                };
                if (!cand.isEmpty()) {
                    rot[i] = cand.get(0);
                }
            }
            poseStyle = renderOwnPose != null;
        }

        List<Field> rotCand(int axis) {
            return switch (axis) {
                case 0 -> rotCandidates;
                case 1 -> rotY;
                default -> rotZ;
            };
        }

        boolean looksLikePart() {
            return rot[0] != null && rot[1] != null && rot[2] != null
                    && pos[0] != null && pos[1] != null && pos[2] != null
                    && (poseStyle || renderSubtree != null);
        }

        /** 子部件容器：取「元素确实是同类部件」的那个字段（原版 ModelPart 的 cubes 会被排除） */
        @SuppressWarnings("unchecked")
        Field container(Object h) {
            if (containerField != null) {
                return containerField;
            }
            for (Field f : containerCandidates) {
                try {
                    Object v = f.get(h);
                    Iterable<?> values = null;
                    if (v instanceof Collection<?> col) {
                        values = col;
                    } else if (v instanceof Map<?, ?> map) {
                        values = map.values();
                    }
                    if (values == null) {
                        continue;
                    }
                    for (Object o : values) {
                        if (o != null && h.getClass().isAssignableFrom(o.getClass())) {
                            containerField = f;
                            return f;
                        }
                        break;
                    }
                    if (containerField == null && v instanceof Collection<?> col && col.isEmpty()) {
                        containerField = f;   // 空集合也当作容器候选
                    }
                } catch (Throwable ignored) {
                    // 试下一个
                }
            }
            return containerField;
        }

        float getF(Object h, Field f) {
            if (f == null) {
                return 0.0F;
            }
            try {
                return f.getFloat(h);
            } catch (Throwable t) {
                return 0.0F;
            }
        }

        void setF(Object h, Field f, float v) {
            if (f == null) {
                return;
            }
            try {
                f.setFloat(h, v);
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        boolean getB(Object h, Field f, boolean fallback) {
            if (f == null) {
                return fallback;
            }
            try {
                return f.getBoolean(h);
            } catch (Throwable t) {
                return fallback;
            }
        }

        void setB(Object h, Field f, boolean v) {
            if (f == null) {
                return;
            }
            try {
                f.setBoolean(h, v);
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        Object parent(Object h) {
            if (parentMethod == null) {
                return null;
            }
            try {
                return parentMethod.invoke(h);
            } catch (Throwable t) {
                return null;
            }
        }

        @SuppressWarnings("unchecked")
        List<Object> children(Object h) {
            List<Object> out = new ArrayList<>();
            Field f = container(h);
            if (f == null) {
                return out;
            }
            try {
                Object v = f.get(h);
                if (v instanceof Collection<?> col) {
                    for (Object o : col) {
                        if (o != null && o != h && h.getClass().isAssignableFrom(o.getClass())) {
                            out.add(o);
                        }
                    }
                } else if (v instanceof Map<?, ?> map) {
                    for (Object o : map.values()) {
                        if (o != null && o != h && h.getClass().isAssignableFrom(o.getClass())) {
                            out.add(o);
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 忽略
            }
            return out;
        }

        /** 临时摘掉子部件（用于「渲染方法会连子部件一起画」的体系） */
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object detachChildren(Object h) {
            Field f = container(h);
            if (f == null) {
                return null;
            }
            try {
                Object v = f.get(h);
                if (v instanceof Collection<?> col) {
                    List<Object> saved = new ArrayList<>((Collection<Object>) col);
                    ((Collection) col).clear();
                    return saved;
                }
                if (v instanceof Map<?, ?> map) {
                    Map<Object, Object> saved = new java.util.LinkedHashMap<>((Map) map);
                    ((Map) map).clear();
                    return saved;
                }
            } catch (Throwable ignored) {
                // 忽略
            }
            return null;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        void reattachChildren(Object h, Object saved) {
            if (saved == null) {
                return;
            }
            Field f = container(h);
            if (f == null) {
                return;
            }
            try {
                Object v = f.get(h);
                if (v instanceof Collection<?> col && saved instanceof Collection<?> s) {
                    ((Collection) col).addAll(s);
                } else if (v instanceof Map<?, ?> map && saved instanceof Map<?, ?> s) {
                    ((Map) map).putAll(s);
                }
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    // ------------------------------------------------------------------

    private static final class GenPart implements Part {
        private final Object handle;
        private final Refl refl;
        private final List<GenPart> kids = new ArrayList<>();
        private GenPart parent;
        private boolean visited;

        private final float[] pivot = new float[3];
        private final float[] bindRot = new float[3];
        private final float[] bindOff = new float[3];
        private boolean saved;
        private boolean svis;
        /** 全部部件都被判为不可见时强制按可见处理（几乎一定是可见性字段识别错了） */
        private boolean forceVisible;
        private String lastError;
        private Object savedChildren;

        GenPart(Object handle, Refl refl) {
            this.handle = handle;
            this.refl = refl;
            for (int i = 0; i < 3; i++) {
                pivot[i] = refl.getF(handle, refl.pos[i]);
                bindRot[i] = refl.getF(handle, refl.rot[i]);
                bindOff[i] = refl.getF(handle, refl.off[i]);
            }
        }

        @Override
        public String name() {
            return handle.getClass().getSimpleName();
        }

        @Override
        public Part parent() {
            return parent;
        }

        @Override
        public List<Part> children() {
            return new ArrayList<>(kids);
        }

        @Override
        public float pivotX() {
            return pivot[0];
        }

        @Override
        public float pivotY() {
            return pivot[1];
        }

        @Override
        public float pivotZ() {
            return pivot[2];
        }

        @Override
        public float rotX() {
            return refl.getF(handle, refl.rot[0]);
        }

        @Override
        public float rotY() {
            return refl.getF(handle, refl.rot[1]);
        }

        @Override
        public float rotZ() {
            return refl.getF(handle, refl.rot[2]);
        }

        @Override
        public float offX() {
            return (refl.getF(handle, refl.pos[0]) - pivot[0]) + refl.getF(handle, refl.off[0]);
        }

        @Override
        public float offY() {
            return (refl.getF(handle, refl.pos[1]) - pivot[1]) + refl.getF(handle, refl.off[1]);
        }

        @Override
        public float offZ() {
            return (refl.getF(handle, refl.pos[2]) - pivot[2]) + refl.getF(handle, refl.off[2]);
        }

        @Override
        public boolean visible() {
            if (forceVisible) {
                return true;
            }
            boolean v = true;
            if (refl.hiddenField != null) {
                v = !refl.getB(handle, refl.hiddenField, false);
            }
            if (v && refl.visible != null) {
                v = refl.getB(handle, refl.visible, true);
            }
            return v;
        }

        @Override
        public boolean supportsOffset() {
            // 位置成员被动画改写（基岩系习惯）或存在独立的 offset 成员（Citadel 习惯）都算支持
            return true;
        }

        @Override
        public void translateAndRotate(PoseStack stack) {
            if (refl.translateRotate != null) {
                try {
                    refl.translateRotate.invoke(handle, stack);
                    return;
                } catch (Throwable ignored) {
                    // 退回手工变换
                }
            }
            // 系统没有自带 translateAndRotate：按原版/基岩惯例 T(pos+off)·R(ZYX) 手工施加
            float dx = (refl.getF(handle, refl.pos[0]) + refl.getF(handle, refl.off[0])) / UNIT;
            float dy = (refl.getF(handle, refl.pos[1]) + refl.getF(handle, refl.off[1])) / UNIT;
            float dz = (refl.getF(handle, refl.pos[2]) + refl.getF(handle, refl.off[2])) / UNIT;
            stack.translate(dx, dy, dz);
            float rx = refl.getF(handle, refl.rot[0]);
            float ry = refl.getF(handle, refl.rot[1]);
            float rz = refl.getF(handle, refl.rot[2]);
            if (rx != 0.0F || ry != 0.0F || rz != 0.0F) {
                stack.mulPose(new org.joml.Quaternionf().rotationZYX(rz, ry, rx));
            }
        }

        @Override
        public String lastError() {
            return lastError;
        }

        @Override
        public void renderOwn(PoseStack parentStack, VertexConsumer buffer, int light, int overlay) {
            // 优先「只画自己立方体」的私有方法（Citadel/lionfish 的 doRender 就是这个形状）
            if (refl.poseStyle) {
                boolean pushed = false;
                try {
                    parentStack.pushPose();
                    pushed = true;
                    translateAndRotate(parentStack);
                    refl.renderOwnPose.invoke(handle, parentStack.last(), buffer, light, overlay,
                            1.0F, 1.0F, 1.0F, 1.0F);
                    lastError = null;
                    return;
                } catch (Throwable t) {
                    // 关键：不能静默失败。画不出东西就退到「子部件渲染法」，
                    // 并把原因记下来（烘焙出 0 个顶点时会打到日志里）。
                    lastError = "doRender(Pose) 失败: " + root(t);
                } finally {
                    if (pushed) {
                        parentStack.popPose();
                    }
                }
            }
            if (refl.renderSubtree == null) {
                if (lastError == null) {
                    lastError = "该部件既没有 doRender(Pose) 也没有 render(PoseStack)";
                }
                return;
            }
            // 退路：渲染方法会连子部件一起画 —— 临时摘掉子部件
            try {
                Object saved = refl.detachChildren(handle);
                try {
                    refl.renderSubtree.invoke(handle, parentStack, buffer, light, overlay,
                            1.0F, 1.0F, 1.0F, 1.0F);
                } finally {
                    refl.reattachChildren(handle, saved);
                }
                lastError = null;
            } catch (Throwable t) {
                lastError = "render(PoseStack) 失败: " + root(t);
            }
        }

        private static String root(Throwable t) {
            Throwable c = t;
            while (c.getCause() != null && c.getCause() != c) {
                c = c.getCause();
            }
            String m = c.toString();
            return m.length() > 160 ? m.substring(0, 160) : m;
        }

        @Override
        public void resetBind() {
            // 绑定姿态 = 定义位置 + 零旋转/零位移
            if (refl.reset != null) {
                try {
                    refl.reset.invoke(handle);
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
            for (int i = 0; i < 3; i++) {
                refl.setF(handle, refl.rot[i], 0.0F);
                refl.setF(handle, refl.off[i], 0.0F);
            }
            // 位置在复位之后才是「定义位置」，此时再快照枢轴
            for (int i = 0; i < 3; i++) {
                pivot[i] = refl.getF(handle, refl.pos[i]);
            }
        }

        @Override
        public void saveState() {
            svis = visible();
            bindRot[0] = rotX();
            bindRot[1] = rotY();
            bindRot[2] = rotZ();
            bindOff[0] = offX();
            bindOff[1] = offY();
            bindOff[2] = offZ();
            saved = true;
        }

        @Override
        public void restoreState() {
            if (!saved) {
                return;
            }
            for (int i = 0; i < 3; i++) {
                refl.setF(handle, refl.rot[i], bindRot[i]);
                refl.setF(handle, refl.off[i], bindOff[i]);
            }
            refl.setB(handle, refl.visible, svis);
        }
    }
}
