package dev.swordkingdom.horde.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.swordkingdom.horde.HordeMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * GeckoLib 系（Bedrock / geo.json 模型）部件系统适配器 —— 同样**不写死任何 GeckoLib 类名**。
 *
 * 为什么需要单独一层：这类模型的渲染器和模型都**不是**原版体系
 * （渲染器继承 {@code EntityRenderer} 而不是 {@code LivingEntityRenderer}，模型实现
 * {@code CoreGeoModel} 而不是 {@code EntityModel}），所以 {@link VanillaRig} 找不到
 * {@code ModelPart} 字段、{@link ReflectiveRig} 也找不到「部件对象自持角度」的字段
 * （GeoBone 的角度是私有字段 + 公开 getter，且没有 doRender/render(PoseStack) 形状的方法）。
 *
 * 识别方式沿用 {@link ReflectiveRig} 的「结构化发现 + 名称族 + 形状校验」：
 *
 *  1. **发现骨骼集合**：从传入对象（geo 模型、渲染器、baked model、AnimationProcessor 都行）
 *     出发做有界对象图遍历，遇到「形状像 GeoBone」的对象就当骨骼，再沿
 *     {@code getParent()} / 子骨骼容器把整棵骨架收全。
 *  2. **形状像骨骼**（按名称族，不绑类名）：{@code getName} + {@code getPivotX/Y/Z} +
 *     {@code getRotX/Y/Z} + {@code getPosX/Y/Z}（或 {@code getScaleX/Y/Z}）+ 父/子成员。
 *  3. **几何**：方块对象有两种可能形状，都支持：
 *     - **四边形形状**（GeckoLib 4 的真实形状，{@code GeoCube}）：方块自己带一组预先算好的
 *       四边形（顶点含位置 + UV）与法线，位置单位已经是**格**；方块还能带自己的枢轴/旋转；
 *     - **方块形状**（{@code 原点 + 尺寸 + UV + 镜像}，原版 {@code ModelPart.Cube} 那一类）：
 *       由 6 个面现场拼出，坐标相对本骨骼枢轴、单位 1/16 格。
 *     两种形状最终都换算成同一套「Bedrock 模型空间」的绝对坐标，才和下方枢轴约定自洽。
 *
 * 两个必须搞对的约定（用 {@code javap} 对着 geckolib-forge-1.20.1-4.8.4.jar 逐个确认过）：
 * <ul>
 *   <li><b>枢轴是模型空间绝对量</b>：geo.json 里每个骨骼的 {@code pivot} 都是绝对坐标，
 *       而 {@link RigBaker} 会把 {@link Part#pivotX()} 沿父链**累加**，所以这里返回
 *       「自己绝对枢轴 − 父绝对枢轴」的差值（伸缩求和后正好是绝对枢轴）。</li>
 *   <li><b>位置动画的 X 取反</b>：GeckoLib 的 {@code RenderUtils.translateMatrixToBone} 用的是
 *       {@code (-posX, +posY, +posZ)/16}（Bedrock 习惯），而着色器只能加
 *       {@code +off/16}，所以这里的 {@code offX()} 返回 {@code -getPosX()}。</li>
 * </ul>
 */
public final class GeoRig implements ModelRig {

    /** Bedrock/GeckoLib 的位置类成员到「格」的换算：1 格 = 16 单位 */
    private static final float UNIT = 16.0F;
    /** 对象图探查上限：只用来找「骨骼集合」，不做无限遍历 */
    private static final int MAX_SCAN_DEPTH = 6;
    private static final int MAX_OBJECTS = 8192;

    /** 按类缓存的成员形状表（和 {@link ReflectiveRig} 的 Refl 一个思路：每个类只分析一次） */
    private static final IdentityHashMap<Class<?>, Refl> REFL_CACHE = new IdentityHashMap<>();

    /** 探查时顺手调用的「容器型」无参取值方法名（模型还没被渲染过时，骨骼也可能只在 processor 里） */
    private static final String[] CONTAINER_GETTERS = {
            "getBones", "getBoneMap", "getRegisteredBones", "getTopLevelBones", "getCurrentModel",
            "getBakedModel", "getBakedGeoModel", "getAnimationProcessor", "getGeoModel", "getModel",
            "getActiveModel", "getBone",
    };

    private final List<Part> parts;

    private GeoRig(List<Part> parts) {
        this.parts = parts;
    }

    @Override
    public List<Part> allParts() {
        return parts;
    }

    // ------------------------------------------------------------------
    // 创建
    // ------------------------------------------------------------------

    /**
     * 按形状识别 geo 部件系统。
     *
     * @param model  模型对象（也接受渲染器 / baked model / AnimationProcessor —— 发现过程是结构化的）
     * @param puppet 替身实体，仅用于日志；动画由调用方在别处驱动
     * @return 识别失败返回 null（例如该 geo 模型还没被加载，骨骼集合是空的）
     */
    public static ModelRig tryCreate(Object model, Entity puppet) {
        if (model == null) {
            return null;
        }
        try {
            List<Object> found = new ArrayList<>();
            scan(model, 0, found, new IdentityHashMap<>());
            if (found.isEmpty() && tryForceLoad(model, puppet)) {
                // 强制加载之后模型对象图里就有骨骼了，再扫一遍
                scan(model, 0, found, new IdentityHashMap<>());
            }
            if (found.isEmpty()) {
                HordeMod.LOGGER.debug("[horde] geo 部件适配：{} 的对象图里没找到骨骼"
                        + "（模型可能还没被加载/烘焙，等渲染过一次再试）", model.getClass().getSimpleName());
                return null;
            }

            // 校验形状并建包装（同一个对象只包装一次）
            List<Object> kept = new ArrayList<>();
            IdentityHashMap<Object, GeoPart> wrappers = new IdentityHashMap<>();
            for (Object bone : found) {
                if (bone == null || wrappers.containsKey(bone)) {
                    continue;
                }
                Refl r = REFL_CACHE.computeIfAbsent(bone.getClass(), Refl::new);
                if (!r.isBone()) {
                    continue;
                }
                wrappers.put(bone, new GeoPart(bone, r));
                kept.add(bone);
            }
            if (kept.isEmpty()) {
                return null;
            }

            // 层级：优先 getParent()，否则用子骨骼容器
            for (Object bone : kept) {
                GeoPart p = wrappers.get(bone);
                Object parent = p.refl.get(bone, p.refl.parentM);
                if (parent != null) {
                    GeoPart pp = wrappers.get(parent);
                    if (pp != null && pp != p) {
                        p.parent = pp;
                    }
                }
            }
            for (Object bone : kept) {
                GeoPart p = wrappers.get(bone);
                for (Object child : elements(p.refl.get(bone, p.refl.childrenM))) {
                    GeoPart c = wrappers.get(child);
                    if (c != null && c != p && c.parent == null) {
                        c.parent = p;
                    }
                }
            }
            for (Object bone : kept) {
                GeoPart p = wrappers.get(bone);
                if (p.parent != null) {
                    p.parent.kids.add(p);
                }
            }

            List<Part> ordered = new ArrayList<>();
            for (Object bone : kept) {
                GeoPart p = wrappers.get(bone);
                if (p.parent == null) {
                    addTree(p, ordered);
                }
            }
            for (Object bone : kept) {
                GeoPart p = wrappers.get(bone);
                if (!p.visited) {
                    // 环/断链兜底：剩下的当根，保证不丢几何
                    p.parent = null;
                    addTree(p, ordered);
                }
            }
            if (ordered.isEmpty()) {
                return null;
            }
            return new GeoRig(ordered);
        } catch (Throwable t) {
            HordeMod.LOGGER.debug("[horde] geo 部件适配识别失败: {}", t.toString());
            return null;
        }
    }

    /**
     * 兜底：模型可能**还没被加载过**。
     *
     * GeckoLib 的 {@code GeoModel.currentModel} 只在第一次取 baked model 时才有值，而骨骼集合就藏在
     * 它（或 AnimationProcessor 的骨骼表）里。群集网格的烘焙时机不受我们控制，所以这里按形状找
     * 「拿实体换模型资源」+「拿资源换 baked model」这两个方法，主动加载一次 ——
     * 反正渲染第一帧也会走同一段代码，只是提前了。
     *
     * @return 是否真的触发了加载（没触发就不用重扫）
     */
    private static boolean tryForceLoad(Object model, Entity puppet) {
        if (model == null || puppet == null) {
            return false;
        }
        try {
            Object resource = null;
            Method bake = null;
            for (Class<?> c = model.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (Modifier.isStatic(m.getModifiers()) || m.getParameterCount() > 1) {
                        continue;
                    }
                    Class<?>[] ps = m.getParameterTypes();
                    Class<?> rt = m.getReturnType();
                    if (resource == null && ResourceLocation.class.isAssignableFrom(rt)
                            && (ps.length == 0 || ps[0].isInstance(puppet))
                            && norm(m.getName()).contains("modelresource")) {
                        try {
                            m.setAccessible(true);
                            resource = ps.length == 0 ? m.invoke(model) : m.invoke(model, puppet);
                        } catch (Throwable ignored) {
                            // 试下一个
                        }
                    } else if (bake == null && ps.length == 1
                            && ResourceLocation.class.isAssignableFrom(ps[0])
                            && !rt.isPrimitive() && rt != void.class
                            && norm(m.getName()).contains("baked")) {
                        try {
                            m.setAccessible(true);
                            bake = m;
                        } catch (Throwable ignored) {
                            // 试下一个
                        }
                    }
                }
            }
            if (resource == null || bake == null) {
                return false;
            }
            bake.invoke(model, resource);
            HordeMod.LOGGER.debug("[horde] geo 部件适配：模型之前没被烘焙过，已按形状主动加载一次（{}）",
                    model.getClass().getSimpleName());
            return true;
        } catch (Throwable t) {
            HordeMod.LOGGER.debug("[horde] geo 部件适配：主动加载模型失败（忽略）: {}", t.toString());
            return false;
        }
    }

    private static void addTree(GeoPart part, List<Part> out) {        if (part.visited) {
            return;
        }
        part.visited = true;
        out.add(part);
        for (GeoPart child : part.kids) {
            addTree(child, out);
        }
    }

    // ------------------------------------------------------------------
    // 发现：对象图里有界搜索 + 沿父子链收全骨架
    // ------------------------------------------------------------------

    private static void scan(Object value, int depth, List<Object> out, IdentityHashMap<Object, Boolean> seen) {
        if (value == null || depth > MAX_SCAN_DEPTH || out.size() >= MAX_OBJECTS) {
            return;
        }
        Class<?> cls = value.getClass();
        if (seen.put(value, Boolean.TRUE) != null) {
            return;
        }
        if (REFL_CACHE.computeIfAbsent(cls, Refl::new).isBone()) {
            collectBoneTree(value, out);
            return;
        }
        // 容器要先展开：集合/映射/数组本身都在 java.* 里，不能当成「不用下钻的对象」
        if (value instanceof Collection<?> col) {
            for (Object o : col) {
                scan(o, depth + 1, out, seen);
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Object o : map.values()) {
                scan(o, depth + 1, out, seen);
            }
            return;
        }
        if (cls.isArray()) {
            int n = Array.getLength(value);
            for (int i = 0; i < n; i++) {
                scan(Array.get(value, i), depth + 1, out, seen);
            }
            return;
        }
        // 根对象永远遍历（调用方可能直接把 MC 的渲染器传进来）；
        // 但往下钻时不进 MC / 库的内部对象 —— 骨骼一定在模组自己的类里。
        if (depth > 0 && isScalar(cls)) {
            return;
        }
        // 结构化遍历：字段 + 「容器型」无参取值方法
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    scan(f.get(value), depth + 1, out, seen);
                } catch (Throwable ignored) {
                    // 忽略不可访问的字段
                }
            }
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() != 0 || Modifier.isStatic(m.getModifiers())
                        || m.getReturnType().isPrimitive() || m.getReturnType() == void.class) {
                    continue;
                }
                if (!isContainerGetter(m.getName())) {
                    continue;
                }
                try {
                    m.setAccessible(true);
                    scan(m.invoke(value), depth + 1, out, seen);
                } catch (Throwable ignored) {
                    // 取不到就算了
                }
            }
        }
    }

    private static boolean isContainerGetter(String name) {
        for (String s : CONTAINER_GETTERS) {
            if (s.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从一棵骨架的任意一个骨骼出发，沿「父」和「子」两个方向把整棵树收全。
     *
     * 必须向上收：geo 模型的枢轴是**绝对量**，少了父枢轴就换算不出「相对父部件的枢轴」。
     */
    private static void collectBoneTree(Object seed, List<Object> out) {
        ArrayDeque<Object> queue = new ArrayDeque<>();
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        queue.add(seed);
        while (!queue.isEmpty() && out.size() < MAX_OBJECTS) {
            Object bone = queue.poll();
            if (bone == null || seen.put(bone, Boolean.TRUE) != null) {
                continue;
            }
            Refl r = REFL_CACHE.computeIfAbsent(bone.getClass(), Refl::new);
            if (!r.isBone()) {
                continue;
            }
            out.add(bone);
            Object parent = r.get(bone, r.parentM);
            if (parent != null) {
                queue.add(parent);
            }
            for (Object child : elements(r.get(bone, r.childrenM))) {
                queue.add(child);
            }
        }
    }

    private static boolean isScalar(Class<?> cls) {
        if (cls.isPrimitive() || cls.isEnum() || cls.isArray()) {
            return false;
        }
        if (Number.class.isAssignableFrom(cls) || CharSequence.class.isAssignableFrom(cls)
                || cls == Boolean.class || cls == Character.class) {
            return true;
        }
        String n = cls.getName();
        // 这些包里不可能有骨骼，下钻只会浪费时间/内存
        return n.startsWith("java.") || n.startsWith("javax.") || n.startsWith("jdk.")
                || n.startsWith("sun.") || n.startsWith("org.joml.") || n.startsWith("it.unimi.")
                || n.startsWith("com.google.") || n.startsWith("org.apache.")
                || n.startsWith("com.mojang.blaze3d.") || n.startsWith("net.minecraft.");
    }

    /** 把「集合 / 映射 / 数组 / Iterable」统一成元素列表。 */
    private static List<Object> elements(Object container) {
        List<Object> out = new ArrayList<>();
        if (container == null) {
            return out;
        }
        if (container instanceof Collection<?> col) {
            for (Object o : col) {
                if (o != null) {
                    out.add(o);
                }
            }
        } else if (container instanceof Map<?, ?> map) {
            for (Object o : map.values()) {
                if (o != null) {
                    out.add(o);
                }
            }
        } else if (container instanceof Iterable<?> it) {
            for (Object o : it) {
                if (o != null) {
                    out.add(o);
                }
            }
        } else if (container.getClass().isArray()) {
            int n = Array.getLength(container);
            for (int i = 0; i < n; i++) {
                Object o = Array.get(container, i);
                if (o != null) {
                    out.add(o);
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 成员名称族（不绑类名，只认形状）
    // ------------------------------------------------------------------

    /** 去掉 get/is/set 前缀并小写：GeoBone 的 getRotX → rotx。 */
    private static String norm(String name) {
        String n = name;
        if (n.startsWith("get") || n.startsWith("set")) {
            n = n.substring(3);
        } else if (n.startsWith("is")) {
            n = n.substring(2);
        }
        return n.toLowerCase(Locale.ROOT);
    }

    private static boolean rotName(String n, char a) {
        return n.equals("rot" + a) || n.equals("rotation" + a) || n.equals("rotateangle" + a)
                || n.equals("rotationangle" + a) || n.equals("angle" + a)
                || n.equals(a + "rot") || n.equals(a + "rotation") || n.equals(a + "angle");
    }

    private static boolean pivotName(String n, char a) {
        return n.equals("pivot" + a) || n.equals("rotationpoint" + a) || n.equals("defaultposition" + a)
                || n.equals("basepivot" + a) || n.equals(a + "pivot");
    }

    private static boolean posName(String n, char a) {
        return n.equals("pos" + a) || n.equals("position" + a) || n.equals("offset" + a)
                || n.equals("translate" + a) || n.equals("translation" + a) || n.equals(a + "pos");
    }

    private static boolean scaleName(String n, char a) {
        return n.equals("scale" + a);
    }

    private static boolean originName(String n, char a) {
        return n.equals("origin" + a) || n.equals("min" + a) || n.equals("mincorner" + a);
    }

    private static boolean sizeName(String n, char a) {
        if (n.equals("size" + a) || n.equals("extent" + a)) {
            return true;
        }
        return switch (a) {
            case 'x' -> n.equals("width");
            case 'y' -> n.equals("height");
            default -> n.equals("depth") || n.equals("length");
        };
    }

    private static boolean hiddenName(String n) {
        return n.equals("hidden") || n.equals("invisible") || n.equals("isinvisible");
    }

    /** 语义与「隐藏」相反的可见性成员（取值要取反） */
    private static boolean visibleName(String n) {
        return n.equals("visible") || n.equals("show") || n.equals("showmodel");
    }

    private static boolean hideChildrenName(String n) {
        return n.equals("hidingchildren") || n.equals("childrenhidden") || n.equals("hiddenchildren");
    }

    private static boolean number(Class<?> t) {
        return t == float.class || t == double.class || t == int.class || t == long.class
                || Number.class.isAssignableFrom(t);
    }

    private static boolean booleanType(Class<?> t) {
        return t == boolean.class || t == Boolean.class;
    }

    // ------------------------------------------------------------------
    // 形状表
    // ------------------------------------------------------------------

    /** 某个类的成员形状表（按类缓存）。同一个类要同时容纳骨骼/方块/四边形/顶点/向量的判断。 */
    private static final class Refl {
        Method nameM;
        Method parentM;
        Method childrenM;
        Method cubesM;
        Method quadsM;
        Method verticesM;
        Method normalM;
        Method positionM;
        Method hiddenM;
        Method hideChildrenM;
        Method mirrorM;
        Method uM;
        Method vM;
        final Method[] pivot = new Method[3];
        final Method[] rot = new Method[3];
        final Method[] pos = new Method[3];
        final Method[] scale = new Method[3];
        final Method[] setRot = new Method[3];
        final Method[] setPos = new Method[3];
        final Method[] setScale = new Method[3];
        /** 枢轴的 setter（GeckoLib 的 GeoBone 提供 setPivotX/Y/Z；没有就保持模型自身枢轴） */
        final Method[] setPivot = new Method[3];
        final Method[] origin = new Method[3];
        final Method[] size = new Method[3];
        /** 向量形状：整体取值（GeoCube 的 pivot()/rotation()/size()/origin()） */
        Method pivotVecM;
        Method rotationVecM;
        Method sizeVecM;
        Method originVecM;
        /** 向量分量（Vector3f / Vec3 / 自定义向量：x()/y()/z()） */
        Method vxM;
        Method vyM;
        Method vzM;
        /** 私有分量字段兜底（同样是 Vector3f/Vec3 的自定义替身） */
        Field vxF;
        Field vyF;
        Field vzF;
        /** 命中的可见性成员语义相反（名字是「可见」而不是「隐藏」），取值要取反 */
        boolean hiddenInverted;
        /** 形状判定结果（每个类只判一次） */
        private Boolean bone;

        Refl(Class<?> cls) {
            for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.isSynthetic() || Modifier.isStatic(m.getModifiers())) {
                        continue;
                    }
                    Class<?> rt = m.getReturnType();
                    Class<?>[] ps = m.getParameterTypes();
                    if (m.getParameterCount() == 0 && number(rt)) {
                        floatMember(m, rt);
                    } else if (m.getParameterCount() == 0 && booleanType(rt)) {
                        booleanMember(m);
                    } else if (m.getParameterCount() == 0 && rt == String.class) {
                        String n = norm(m.getName());
                        if (nameM == null && (n.equals("name") || n.equals("bonename"))) {
                            nameM = m;
                        }
                    } else if (m.getParameterCount() == 0 && (Iterable.class.isAssignableFrom(rt)
                            || Map.class.isAssignableFrom(rt) || Collection.class.isAssignableFrom(rt)
                            || rt.isArray())) {
                        containerMember(m);
                    } else if (m.getParameterCount() == 0 && !rt.isPrimitive() && rt != void.class) {
                        referenceMember(m);
                    } else if (m.getParameterCount() == 1 && number(ps[0])) {
                        setterMember(m);
                    }
                    try {
                        m.setAccessible(true);
                    } catch (Throwable ignored) {
                        // 公开方法无需
                    }
                }
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || !number(f.getType())) {
                        continue;
                    }
                    String n = f.getName().toLowerCase(Locale.ROOT);
                    if (n.equals("x") && vxF == null) {
                        vxF = f;
                    } else if (n.equals("y") && vyF == null) {
                        vyF = f;
                    } else if (n.equals("z") && vzF == null) {
                        vzF = f;
                    }
                    try {
                        f.setAccessible(true);
                    } catch (Throwable ignored) {
                        // 公开字段无需
                    }
                }
            }
        }

        private void floatMember(Method m, Class<?> rt) {
            String n = norm(m.getName());
            for (int i = 0; i < 3; i++) {
                char a = "xyz".charAt(i);
                if (rot[i] == null && rotName(n, a)) {
                    rot[i] = m;
                } else if (pivot[i] == null && pivotName(n, a)) {
                    pivot[i] = m;
                } else if (pos[i] == null && posName(n, a)) {
                    pos[i] = m;
                } else if (scale[i] == null && scaleName(n, a)) {
                    scale[i] = m;
                } else if (origin[i] == null && originName(n, a)) {
                    origin[i] = m;
                } else if (size[i] == null && sizeName(n, a)) {
                    size[i] = m;
                }
            }
            // 向量分量 + UV
            if (n.equals("x")) {
                vxM = m;
            } else if (n.equals("y")) {
                vyM = m;
            } else if (n.equals("z")) {
                vzM = m;
            } else if (uM == null && (n.equals("u") || n.equals("texu") || n.equals("uvu"))) {
                uM = m;
            } else if (vM == null && (n.equals("v") || n.equals("texv") || n.equals("uvv"))) {
                vM = m;
            }
        }

        private void booleanMember(Method m) {
            String n = norm(m.getName());
            if (hiddenM == null && hiddenName(n)) {
                hiddenM = m;
                hiddenInverted = false;
            } else if (hiddenM == null && visibleName(n)) {
                hiddenM = m;
                hiddenInverted = true;
            } else if (hideChildrenM == null && hideChildrenName(n)) {
                hideChildrenM = m;
            } else if (mirrorM == null && n.equals("mirror")) {
                mirrorM = m;
            }
        }

        private void containerMember(Method m) {
            switch (norm(m.getName())) {
                case "cubes" -> cubesM = cubesM == null ? m : cubesM;
                case "quads" -> quadsM = quadsM == null ? m : quadsM;
                case "vertices", "vertexes" -> verticesM = verticesM == null ? m : verticesM;
                case "children", "childbones", "childs", "subbones", "kids", "bones" ->
                        childrenM = childrenM == null ? m : childrenM;
                default -> {
                    // 其余集合成员（locators、polyMesh…）与本层无关
                }
            }
        }

        private void referenceMember(Method m) {
            String n = norm(m.getName());
            if (parentM == null && (n.equals("parent") || n.equals("parentbone"))) {
                parentM = m;
            } else if (pivotVecM == null && n.equals("pivot")) {
                pivotVecM = m;
            } else if (rotationVecM == null && n.equals("rotation")) {
                rotationVecM = m;
            } else if (sizeVecM == null && n.equals("size")) {
                sizeVecM = m;
            } else if (originVecM == null && n.equals("origin")) {
                originVecM = m;
            } else if (positionM == null && n.equals("position")) {
                positionM = m;
            } else if (normalM == null && n.equals("normal")) {
                normalM = m;
            }
        }

        private void setterMember(Method m) {
            String n = norm(m.getName());
            for (int i = 0; i < 3; i++) {
                char a = "xyz".charAt(i);
                if (setRot[i] == null && rotName(n, a)) {
                    setRot[i] = m;
                } else if (setPos[i] == null && posName(n, a)) {
                    setPos[i] = m;
                } else if (setScale[i] == null && scaleName(n, a)) {
                    setScale[i] = m;
                } else if (setPivot[i] == null && pivotName(n, a)) {
                    setPivot[i] = m;
                }
            }
        }

        /** 形状像 GeoBone：三轴旋转 + 三轴枢轴 + 三轴位置，且能连上父/子。 */
        boolean isBone() {
            if (bone != null) {
                return bone;
            }
            boolean ok = rot[0] != null && rot[1] != null && rot[2] != null
                    && pivot[0] != null && pivot[1] != null && pivot[2] != null
                    && pos[0] != null && pos[1] != null && pos[2] != null
                    && (parentM != null || childrenM != null);
            bone = ok;
            return ok;
        }

        boolean hasQuads() {
            return quadsM != null;
        }

        /** 方块形状（原点 + 尺寸）：可以是三轴分量，也可以是整体的 origin()/size() 向量。 */
        boolean isBox() {
            boolean originOk = (origin[0] != null && origin[1] != null && origin[2] != null) || originVecM != null;
            boolean sizeOk = (size[0] != null && size[1] != null && size[2] != null) || sizeVecM != null;
            return originOk && sizeOk;
        }

        Object get(Object owner, Method m) {
            if (m == null || owner == null) {
                return null;
            }
            try {
                return m.invoke(owner);
            } catch (Throwable t) {
                return null;
            }
        }

        float getF(Object owner, Method m, float fallback) {
            if (m == null || owner == null) {
                return fallback;
            }
            try {
                Object v = m.invoke(owner);
                return v instanceof Number n ? n.floatValue() : fallback;
            } catch (Throwable t) {
                return fallback;
            }
        }

        boolean getB(Object owner, Method m, boolean fallback) {
            if (m == null || owner == null) {
                return fallback;
            }
            try {
                Object v = m.invoke(owner);
                return v instanceof Boolean b ? b : fallback;
            } catch (Throwable t) {
                return fallback;
            }
        }

        float off(Object owner, int axis) {
            return getF(owner, pos[axis], 0.0F);
        }

        boolean hidden(Object owner) {
            boolean v = getB(owner, hiddenM, false);
            return hiddenInverted ? !v : v;
        }

        /** 读一个「向量」成员的三个分量。 */
        boolean readVec(Object owner, Method vecM, float[] out) {
            return readVecObject(get(owner, vecM), out);
        }
    }

    /**
     * 把任意「向量样」对象读成三个 float。
     *
     * 注意这里对 MC/joml 的类型做了**编译期**分支：反射按名字找 {@code x()} 在线上会失效
     * （MC 成员在线上是 SRG 名），而编译期写死的调用会被 ForgeGradle 自动重映射，所以
     * {@code Vec3}/{@code Vector3f} 走快路径，只有自定义/替身向量才走名字族反射。
     */
    private static boolean readVecObject(Object v, float[] out) {
        if (v == null) {
            return false;
        }
        if (v instanceof Vector3f f) {
            out[0] = f.x();
            out[1] = f.y();
            out[2] = f.z();
            return true;
        }
        if (v instanceof Vector3d d) {
            out[0] = (float) d.x();
            out[1] = (float) d.y();
            out[2] = (float) d.z();
            return true;
        }
        if (v instanceof Vec3 d) {
            out[0] = (float) d.x;
            out[1] = (float) d.y;
            out[2] = (float) d.z;
            return true;
        }
        if (v instanceof float[] a && a.length >= 3) {
            out[0] = a[0];
            out[1] = a[1];
            out[2] = a[2];
            return true;
        }
        if (v instanceof double[] a && a.length >= 3) {
            out[0] = (float) a[0];
            out[1] = (float) a[1];
            out[2] = (float) a[2];
            return true;
        }
        Refl r = REFL_CACHE.computeIfAbsent(v.getClass(), Refl::new);
        if (r.vxM != null && r.vyM != null && r.vzM != null) {
            out[0] = r.getF(v, r.vxM, 0.0F);
            out[1] = r.getF(v, r.vyM, 0.0F);
            out[2] = r.getF(v, r.vzM, 0.0F);
            return true;
        }
        if (r.vxF != null && r.vyF != null && r.vzF != null) {
            try {
                out[0] = r.vxF.getFloat(v);
                out[1] = r.vyF.getFloat(v);
                out[2] = r.vzF.getFloat(v);
                return true;
            } catch (Throwable ignored) {
                // 落空
            }
        }
        return false;
    }

    /** 与 GeckoLib 的 {@code RenderUtils.rotateMatrixAroundBone} 完全一致的顺序：Z → Y → X（即 Rz·Ry·Rx）。 */
    private static void rotateZYX(PoseStack stack, float rx, float ry, float rz) {
        if (rz != 0.0F) {
            stack.mulPose(Axis.ZP.rotation(rz));
        }
        if (ry != 0.0F) {
            stack.mulPose(Axis.YP.rotation(ry));
        }
        if (rx != 0.0F) {
            stack.mulPose(Axis.XP.rotation(rx));
        }
    }

    // ------------------------------------------------------------------
    // 部件
    // ------------------------------------------------------------------

    private static final class GeoPart implements Part {
        private final Object handle;
        private final Refl refl;
        private final List<GeoPart> kids = new ArrayList<>();
        private GeoPart parent;
        private boolean visited;
        /** 模型空间**绝对**枢轴快照（1/16 格，已含模型自身的 X 取反约定） */
        private final float[] pivot = new float[3];
        private final float[] savedRot = new float[3];
        private final float[] savedPos = new float[3];
        private final float[] savedScale = new float[3];
        private boolean savedHidden;
        private boolean savedHideChildren;
        private boolean hasSaved;
        private String lastError;

        GeoPart(Object handle, Refl refl) {
            this.handle = handle;
            this.refl = refl;
            refreshPivot();
        }

        private void refreshPivot() {
            for (int i = 0; i < 3; i++) {
                pivot[i] = refl.getF(handle, refl.pivot[i], 0.0F);
            }
        }

        @Override
        public String name() {
            Object n = refl.get(handle, refl.nameM);
            return n instanceof String s && !s.isEmpty() ? s : handle.getClass().getSimpleName();
        }

        @Override
        public Part parent() {
            return parent;
        }

        @Override
        public List<Part> children() {
            return new ArrayList<>(kids);
        }

        /**
         * 相对**父部件**的枢轴（1/16 格）。
         *
         * GeoBone 的 pivot 是模型空间绝对量，而 {@link RigBaker} 会沿父链累加，所以这里返回差值；
         * 伸缩求和后正好等于绝对枢轴，着色器里「绕绝对枢轴旋转」就成立了。
         */
        @Override
        public float pivotX() {
            return pivot[0] - (parent == null ? 0.0F : parent.pivot[0]);
        }

        @Override
        public float pivotY() {
            return pivot[1] - (parent == null ? 0.0F : parent.pivot[1]);
        }

        @Override
        public float pivotZ() {
            return pivot[2] - (parent == null ? 0.0F : parent.pivot[2]);
        }

        @Override
        public float rotX() {
            return refl.getF(handle, refl.rot[0], 0.0F);
        }

        @Override
        public float rotY() {
            return refl.getF(handle, refl.rot[1], 0.0F);
        }

        @Override
        public float rotZ() {
            return refl.getF(handle, refl.rot[2], 0.0F);
        }

        /** Bedrock 的位置动画是「X 取反」的（RenderUtils.translateMatrixToBone 用 -posX），着色器只能加，所以这里取反。 */
        @Override
        public float offX() {
            return -refl.off(handle, 0);
        }

        @Override
        public float offY() {
            return refl.off(handle, 1);
        }

        @Override
        public float offZ() {
            return refl.off(handle, 2);
        }

        /**
         * GeckoLib 里「隐藏」只跳过**自己的立方体**，子骨骼照常渲染（GeoRenderer.renderCubesOfBone），
         * 所以隐藏映射到 {@link #skipCubes()}；而「隐藏子骨骼」才会整棵子树不画。
         */
        @Override
        public boolean visible() {
            return !refl.getB(handle, refl.hideChildrenM, false);
        }

        @Override
        public boolean skipCubes() {
            return refl.hidden(handle);
        }

        @Override
        public boolean supportsOffset() {
            return refl.pos[0] != null && refl.pos[1] != null && refl.pos[2] != null;
        }

        @Override
        public float scaleX() {
            return refl.getF(handle, refl.scale[0], 1.0F);
        }

        @Override
        public float scaleY() {
            return refl.getF(handle, refl.scale[1], 1.0F);
        }

        @Override
        public float scaleZ() {
            return refl.getF(handle, refl.scale[2], 1.0F);
        }

        /** 等价于 GeckoLib 的 {@code RenderUtils.prepMatrixForBone}。 */
        @Override
        public void translateAndRotate(PoseStack stack) {
            stack.translate(offX() / UNIT, offY() / UNIT, offZ() / UNIT);
            stack.translate(pivot[0] / UNIT, pivot[1] / UNIT, pivot[2] / UNIT);
            rotateZYX(stack, rotX(), rotY(), rotZ());
            float sx = scaleX();
            float sy = scaleY();
            float sz = scaleZ();
            if (sx != 1.0F || sy != 1.0F || sz != 1.0F) {
                stack.scale(sx, sy, sz);
            }
            stack.translate(-pivot[0] / UNIT, -pivot[1] / UNIT, -pivot[2] / UNIT);
        }

        @Override
        public String lastError() {
            return lastError;
        }

        @Override
        public void renderOwn(PoseStack parentStack, VertexConsumer buffer, int light, int overlay) {
            List<Object> cubeList = elements(refl.get(handle, refl.cubesM));
            if (cubeList.isEmpty()) {
                return;
            }
            String err = null;
            parentStack.pushPose();
            try {
                translateAndRotate(parentStack);
                for (Object cube : cubeList) {
                    Refl cr = REFL_CACHE.computeIfAbsent(cube.getClass(), Refl::new);
                    boolean quads = cr.hasQuads();
                    boolean box = !quads && cr.isBox();
                    if (!quads && !box) {
                        err = "方块形状不认识: " + cube.getClass().getName()
                                + "（既没有 quads()，也没有 origin/size）";
                        continue;
                    }
                    parentStack.pushPose();
                    if (box) {
                        // 方块形状的坐标相对本骨骼枢轴（原版 ModelPart 约定），
                        // 这里补上枢轴平移，换算成与四边形形状一致的「模型空间绝对量」。
                        parentStack.translate(pivot[0] / UNIT, pivot[1] / UNIT, pivot[2] / UNIT);
                    }
                    applyCubeFrame(parentStack, cube, cr);
                    if (quads) {
                        emitQuads(parentStack, cube, cr, buffer, light, overlay);
                    } else {
                        emitBox(parentStack, cube, cr, buffer, light, overlay);
                    }
                    parentStack.popPose();
                }
            } catch (Throwable t) {
                err = "geo 方块渲染失败: " + root(t);
            } finally {
                parentStack.popPose();
            }
            lastError = err;
        }

        private static String root(Throwable t) {
            Throwable c = t;
            while (c.getCause() != null && c.getCause() != c) {
                c = c.getCause();
            }
            String m = c.toString();
            return m.length() > 160 ? m.substring(0, 160) : m;
        }

        /** 方块自己的枢轴 + 旋转（Blockbench 允许单个 cube 单独转），烘焙进几何。 */
        private void applyCubeFrame(PoseStack stack, Object cube, Refl cr) {
            float[] pv = new float[3];
            boolean hasPivot = cr.readVec(cube, cr.pivotVecM, pv);
            float[] rt = new float[3];
            boolean hasRot = cr.readVec(cube, cr.rotationVecM, rt);
            if (hasPivot) {
                stack.translate(pv[0] / UNIT, pv[1] / UNIT, pv[2] / UNIT);
            }
            if (hasRot) {
                rotateZYX(stack, rt[0], rt[1], rt[2]);
            }
            if (hasPivot) {
                stack.translate(-pv[0] / UNIT, -pv[1] / UNIT, -pv[2] / UNIT);
            }
        }

        /** 四边形形状（GeckoLib 4 的 GeoCube）：顶点位置单位已经是格，直接按当前姿态矩阵变换。 */
        private void emitQuads(PoseStack stack, Object cube, Refl cr, VertexConsumer buffer, int light, int overlay) {
            Matrix4f m = stack.last().pose();
            Matrix3f nrm = stack.last().normal();
            for (Object quad : elements(cr.get(cube, cr.quadsM))) {
                Refl qr = REFL_CACHE.computeIfAbsent(quad.getClass(), Refl::new);
                float[] normal = {0.0F, 1.0F, 0.0F};
                qr.readVec(quad, qr.normalM, normal);
                for (Object vertex : elements(qr.get(quad, qr.verticesM))) {
                    Refl vr = REFL_CACHE.computeIfAbsent(vertex.getClass(), Refl::new);
                    float[] pos = new float[3];
                    if (!vr.readVec(vertex, vr.positionM, pos)) {
                        continue;
                    }
                    emitVertex(buffer, m, nrm, pos[0], pos[1], pos[2],
                            normal[0], normal[1], normal[2],
                            vr.getF(vertex, vr.uM, 0.0F), vr.getF(vertex, vr.vM, 0.0F), light, overlay);
                }
            }
        }

        /** 方块形状（原点 + 尺寸 + UV + 镜像）：现场拼 6 个面。 */
        private void emitBox(PoseStack stack, Object cube, Refl cr, VertexConsumer buffer, int light, int overlay) {
            float[] origin = new float[3];
            float[] size = new float[3];
            if (!readAxisOrVec(cube, cr, cr.origin, cr.originVecM, origin)
                    || !readAxisOrVec(cube, cr, cr.size, cr.sizeVecM, size)) {
                return;
            }
            boolean mirror = cr.getB(cube, cr.mirrorM, false);
            float u0 = cr.getF(cube, cr.uM, 0.0F);
            float v0 = cr.getF(cube, cr.vM, 0.0F);
            // 方块形状的单位是 1/16 格，姿态矩阵用的是「格」，这里先换算
            float x0 = origin[0] / UNIT;
            float y0 = origin[1] / UNIT;
            float z0 = origin[2] / UNIT;
            float dx = size[0] / UNIT;
            float dy = size[1] / UNIT;
            float dz = size[2] / UNIT;
            Matrix4f m = stack.last().pose();
            Matrix3f nrm = stack.last().normal();
            float cursor = u0;
            for (int f = 0; f < FACE_NORMALS.length; f++) {
                float[] n = FACE_NORMALS[f];
                int[][] cs = FACE_CORNERS[f];
                float w = FACES_WIDTH[f] == 0 ? dx : (FACES_WIDTH[f] == 1 ? dy : dz);
                float h = FACES_HEIGHT[f] == 0 ? dx : (FACES_HEIGHT[f] == 1 ? dy : dz);
                // 方块形状不带贴图尺寸，UV 按「1 模型单位（1/16 格）= 1/16 UV」归一化（等价 16×16 贴图）
                float du = w;
                float dv = h;
                if (mirror) {
                    n = new float[]{-n[0], n[1], n[2]};
                }
                for (int c = 0; c < 4; c++) {
                    int ci = mirror ? 3 - c : c;
                    int[] corner = cs[ci];
                    float px = x0 + dx * corner[0];
                    float py = y0 + dy * corner[1];
                    float pz = z0 + dz * corner[2];
                    if (mirror) {
                        px = x0 + dx - (px - x0);
                    }
                    float uu = cursor + du * (ci == 1 || ci == 2 ? 1.0F : 0.0F);
                    float vv = v0 + dv * (ci >= 2 ? 1.0F : 0.0F);
                    emitVertex(buffer, m, nrm, px, py, pz, n[0], n[1], n[2], uu, vv, light, overlay);
                }
                cursor += du;
            }
        }

        private boolean readAxisOrVec(Object cube, Refl cr, Method[] axis, Method vecM, float[] out) {
            if (axis[0] != null && axis[1] != null && axis[2] != null) {
                out[0] = cr.getF(cube, axis[0], 0.0F);
                out[1] = cr.getF(cube, axis[1], 0.0F);
                out[2] = cr.getF(cube, axis[2], 0.0F);
                return true;
            }
            return cr.readVec(cube, vecM, out);
        }

        private void emitVertex(VertexConsumer buffer, Matrix4f m, Matrix3f nrm,
                                float x, float y, float z, float nx, float ny, float nz,
                                float u, float v, int light, int overlay) {
            Vector4f p = m.transform(new Vector4f(x, y, z, 1.0F));
            Vector3f n = nrm.transform(new Vector3f(nx, ny, nz));
            buffer.vertex(p.x(), p.y(), p.z())
                    .color(255, 255, 255, 255)
                    .uv(u, v)
                    .overlayCoords(overlay)
                    .uv2(light)
                    .normal(n.x(), n.y(), n.z())
                    .endVertex();
        }

        /**
         * 绑定姿态：旋转归零、位移归零、缩放归一，并刷新绝对枢轴快照。
         *
         * 为什么要把旋转归零：几何以「零旋转」烘焙（只有平移），角度由顶点着色器沿父链一次性施加，
         * 否则动画角度会被叠加两次。缩放同理（归一后由着色器的缩放通道施加）。
         */
        @Override
        public void resetBind() {
            for (int i = 0; i < 3; i++) {
                setNumber(refl.setRot[i], "rot", i, 0.0F);
                setNumber(refl.setPos[i], "position", i, 0.0F);
                setNumber(refl.setScale[i], "scale", i, 1.0F);
            }
            refreshPivot();
        }

        private void setNumber(Method setter, String fieldPrefix, int axis, float value) {
            char a = "xyz".charAt(axis);
            if (setter != null) {
                try {
                    setter.invoke(handle, value);
                    return;
                } catch (Throwable ignored) {
                    // 退回字段
                }
            }
            try {
                for (Class<?> c = handle.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                    Field f;
                    try {
                        f = c.getDeclaredField(fieldPrefix + a);
                    } catch (NoSuchFieldException e) {
                        continue;
                    }
                    f.setAccessible(true);
                    f.setFloat(handle, value);
                    return;
                }
                HordeMod.LOGGER.debug("[horde] geo 部件 {} 没有可写的 {} 成员，绑定姿态会保留当前姿态",
                        handle.getClass().getSimpleName(), fieldPrefix + a);
            } catch (Throwable ignored) {
                // 只读体系：绑定姿态就只能用当前姿态
            }
        }

        @Override
        public void saveState() {
            savedRot[0] = rotX();
            savedRot[1] = rotY();
            savedRot[2] = rotZ();
            savedPos[0] = refl.off(handle, 0);
            savedPos[1] = refl.off(handle, 1);
            savedPos[2] = refl.off(handle, 2);
            savedScale[0] = scaleX();
            savedScale[1] = scaleY();
            savedScale[2] = scaleZ();
            savedHidden = refl.hidden(handle);
            savedHideChildren = refl.getB(handle, refl.hideChildrenM, false);
            hasSaved = true;
        }

        @Override
        public void restoreState() {
            if (!hasSaved) {
                return;
            }
            for (int i = 0; i < 3; i++) {
                setNumber(refl.setRot[i], "rot", i, savedRot[i]);
                setNumber(refl.setPos[i], "position", i, savedPos[i]);
                setNumber(refl.setScale[i], "scale", i, savedScale[i]);
            }
            Method m = refl.hiddenM;
            if (m != null) {
                try {
                    Method setter = findSetter(handle.getClass(), m.getName());
                    if (setter != null) {
                        setter.invoke(handle, refl.hiddenInverted ? !savedHidden : savedHidden);
                    }
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
            Method hc = refl.hideChildrenM;
            if (hc != null) {
                try {
                    Method setter = findSetter(handle.getClass(), hc.getName());
                    if (setter != null) {
                        setter.invoke(handle, savedHideChildren);
                    }
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
            refreshPivot();
        }

        private static Method findSetter(Class<?> cls, String getterName) {
            String setterName;
            if (getterName.startsWith("is")) {
                setterName = "set" + getterName.substring(2);
            } else if (getterName.startsWith("get")) {
                setterName = "set" + getterName.substring(3);
            } else {
                return null;
            }
            for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals(setterName) && m.getParameterCount() == 1
                            && booleanType(m.getParameterTypes()[0])) {
                        try {
                            m.setAccessible(true);
                        } catch (Throwable ignored) {
                            // 公开方法无需
                        }
                        return m;
                    }
                }
            }
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 方块形状的面表（原版 ModelPart.Cube 那一类：坐标相对枢轴，1/16 格）
    // ------------------------------------------------------------------

    private static final float[][] FACE_NORMALS = {
            {0.0F, 1.0F, 0.0F}, {0.0F, -1.0F, 0.0F},
            {0.0F, 0.0F, -1.0F}, {0.0F, 0.0F, 1.0F},
            {-1.0F, 0.0F, 0.0F}, {1.0F, 0.0F, 0.0F},
    };

    /** 每个面 4 个角，按「最小/最大」的 0/1 组合给（顺序成环，便于按四边形切三角形） */
    private static final int[][][] FACE_CORNERS = {
            {{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}},   // 上
            {{0, 0, 1}, {0, 0, 0}, {1, 0, 0}, {1, 0, 1}},   // 下
            {{1, 0, 0}, {0, 0, 0}, {0, 1, 0}, {1, 1, 0}},   // 北（z 最小）
            {{0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}},   // 南（z 最大）
            {{0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0}},   // 西（x 最小）
            {{1, 0, 1}, {1, 0, 0}, {1, 1, 0}, {1, 1, 1}},   // 东（x 最大）
    };

    /** 每个面 UV 展开用的两个轴（0 = x, 1 = y, 2 = z） */
    private static final int[] FACES_WIDTH = {0, 0, 0, 0, 2, 2};
    private static final int[] FACES_HEIGHT = {2, 2, 1, 1, 1, 1};
}
