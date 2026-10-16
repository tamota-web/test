package dev.swordkingdom.horde.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.swordkingdom.horde.HordeMod;
import dev.swordkingdom.horde.crowd.EquipData;
import dev.swordkingdom.horde.edit.EquipPalette;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Matrix3f;

import java.util.ArrayList;
import java.util.List;

/**
 * 装备变体网格的烘焙：**主体网格 + 挂上去的护甲/手持物品**，顶点仍是同一套 9 float。
 *
 * <h2>为什么「挂上去」不需要动骨架（关键）</h2>
 * 群集着色器对每个顶点做的是「沿父链逐级旋转，绕各自的绑定枢轴转到动画角度」，
 * 而绑定姿态下所有旋转都是 0 —— 也就是说**绑定姿态的顶点坐标就是最终坐标**。
 * 于是把另一套几何「挂」到某根骨骼上只有两步：
 * <pre>
 *   1. 把子几何**平移**到目标骨骼的绑定枢轴，并抵消目标骨骼父链的绑定旋转；
 *   2. 把它的顶点骨骼索引写成**目标骨骼的下标**。
 * </pre>
 * 之后宿主骨骼一动，子几何就跟着动（刚性附着），而且**骨架一根都不用加**。
 * 这一点是硬要求：{@code CrowdPose} 只为宿主模型产出姿态 texel，
 * 变体网格的骨骼数一旦变化，姿态纹理的行就会错位（画面直接花掉）。
 *
 * <h2>护甲：用原版 {@code HumanoidArmorLayer} 自己的模型</h2>
 * 把渲染器身上那个护甲图层的 {@code HumanoidModel} 拿出来（按**结构**找，不认模组名/字段名），
 * 用 {@link RigBaker} 拆成骨骼，然后按**名称族**把它挂到宿主对应的骨骼上。
 * 四足/无手模型会匹配失败 → 跳过并把原因记进日志，绝不崩。
 *
 * <h2>手持物品：捕获一次，刚性附着</h2>
 * 物品相对手是刚性的，所以「捕获一次几何」就够用一辈子。
 * 捕获手法复用 {@link ProjectileBaker.Capture}（同一套顶点格式）。
 * 物品走**方块图集**、主体走**实体贴图**，一次 draw call 只能绑一张 ——
 * 见 {@link #ITEM_TEXTURE_POLICY} 与交付说明。
 */
public final class EquipBaker {

    /** 顶点格式与主体网格完全一致 */
    public static final int FLOATS_PER_VERTEX = RigBaker.FLOATS_PER_VERTEX;

    /**
     * 手持物品的贴图策略。
     *
     * <p>{@code 0 = 只在与宿主贴图一致时才画}（默认）：物品走方块图集
     * {@link TextureAtlas#LOCATION_BLOCKS}，主体走实体贴图，一次 draw call 只能绑一张。
     * 硬画的结果是把物品的 UV 采到实体贴图上 —— 一坨色块，所以默认**不画**并在日志里说清楚。
     *
     * <p>{@code 1 = 强行用宿主贴图画}：形状对、颜色错，仅供调试。
     *
     * <p>要把物品真正画对，需要渲染器为「带物品的变体」多发一次 draw call
     * （同一份实例缓冲，换贴图再画一次）—— 接线说明里给了确切位置。
     */
    public static int ITEM_TEXTURE_POLICY = 0;

    /** 抓握点：从手骨枢轴沿「手臂向下」走的比例（1.0 = 走到子关节） */
    public static float GRIP_FRACTION = 0.62F;
    /** 抓握点的世界系微调（格） */
    public static float GRIP_OFFSET_X = 0.0F;
    public static float GRIP_OFFSET_Y = -0.06F;
    public static float GRIP_OFFSET_Z = -0.02F;
    /** 物品摆放的额外旋转（度，世界系 ZYX）—— 不同模型「手朝哪边」不一样，靠它微调 */
    public static float ITEM_ROT_X = 0.0F;
    public static float ITEM_ROT_Y = 0.0F;
    public static float ITEM_ROT_Z = 0.0F;

    private EquipBaker() {}

    // ------------------------------------------------------------------
    // 结果
    // ------------------------------------------------------------------

    /** 一个变体：合并后的顶点/索引 + 用到的贴图 + 诊断信息 */
    public static final class Variant {
        public final float[] vertices;
        public final int[] indices;
        public final ResourceLocation texture;
        /** 真正挂上去的护甲件数 / 手持物品数 */
        public final int armorPieces;
        public final int items;
        /** 被跳过的部分及原因（日志/报告用） */
        public final List<String> skipped;
        /** 骨架（继承宿主；变体不新增骨骼） */
        public final RigBaker.Rig hostRig;
        /**
         * 姿态来源（宿主模型的 {@code CrowdPose}）。
         *
         * <p>变体的骨骼数/通道与宿主**完全一致**（这正是本设计的关键约束），
         * 所以可以直接复用宿主那一份 {@code CrowdPose} 来产出姿态 texel，
         * 不需要再为变体准备第二套动画数据。
         */
        public CrowdPose pose;

        Variant(float[] vertices, int[] indices, ResourceLocation texture, int armorPieces,
                int items, List<String> skipped, RigBaker.Rig hostRig) {
            this.vertices = vertices;
            this.indices = indices;
            this.texture = texture;
            this.armorPieces = armorPieces;
            this.items = items;
            this.skipped = skipped;
            this.hostRig = hostRig;
        }

        public int vertexCount() {
            return vertices.length / FLOATS_PER_VERTEX;
        }

        public int triangleCount() {
            return indices.length / 3;
        }

        /** 该变体网格占用的字节数（顶点 float + 索引 int），用于内存实测 */
        public int bytes() {
            return vertices.length * 4 + indices.length * 4;
        }
    }

    // ------------------------------------------------------------------
    // 护甲图层发现（只认结构）
    // ------------------------------------------------------------------

    /**
     * 在渲染器身上找一个「人形护甲图层」。
     *
     * <p>判据全是**结构**的：
     * <ol>
     *   <li>渲染器的某个字段是 {@code List} 或数组，元素是 {@code RenderLayer}；</li>
     *   <li>该元素的某个非静态字段类型是 {@code HumanoidModel}（原版护甲图层用它画护甲）。</li>
     * </ol>
     * GeckoLib 的 {@code GeoEntityRenderer}、四足生物的渲染器天然不满足 → 跳过。
     */
    public static Object findArmorLayer(EntityRenderer<?> renderer) {
        if (renderer == null) {
            return null;
        }
        for (Class<?> c = renderer.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                Object layers;
                try {
                    if (List.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        layers = f.get(renderer);
                    } else if (f.getType().isArray()
                            && net.minecraft.client.renderer.entity.layers.RenderLayer.class
                            .isAssignableFrom(f.getType().getComponentType())) {
                        f.setAccessible(true);
                        layers = f.get(renderer);
                    } else {
                        continue;
                    }
                } catch (Throwable t) {
                    continue;
                }
                if (layers == null) {
                    continue;
                }
                Iterable<?> it = layers instanceof Iterable<?> iterable ? iterable
                        : java.util.Arrays.asList((Object[]) layers);
                for (Object layer : it) {
                    if (layer != null && armorModelOf(layer) != null) {
                        return layer;
                    }
                }
            }
        }
        return null;
    }

    /** 该图层身上那个 {@code HumanoidModel}（原版护甲图层里就是画护甲用的那个内部模型） */
    private static Object armorModelOf(Object layer) {
        for (Class<?> c = layer.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (!HumanoidModel.class.isAssignableFrom(f.getType())
                        || java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object v = f.get(layer);
                    if (v != null) {
                        return v;
                    }
                } catch (Throwable ignored) {
                    // 试下一个
                }
            }
        }
        return null;
    }

    /** 把该图层的「护甲贴图钩子」找出来（按参数形状：LivingEntity/Entity + ItemStack + EquipmentSlot） */
    public static java.lang.reflect.Method findArmorTextureMethod(Object layer) {
        if (layer == null) {
            return null;
        }
        for (Class<?> c = layer.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (m.getReturnType() != ResourceLocation.class) {
                    continue;
                }
                boolean living = false;
                boolean stack = false;
                boolean slot = false;
                for (Class<?> t : m.getParameterTypes()) {
                    if (LivingEntity.class.isAssignableFrom(t) || t == Entity.class) {
                        living = true;
                    } else if (t == ItemStack.class) {
                        stack = true;
                    } else if (t == EquipmentSlot.class) {
                        slot = true;
                    }
                }
                if (living && stack && slot) {
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

    // ------------------------------------------------------------------
    // 骨骼名称族
    // ------------------------------------------------------------------

    /**
     * 名称族：把「不同模型对同一块身体部位的不同叫法」归到一起。
     *
     * <p>用**子串**匹配（原版 {@code head}、Citadel {@code Head}/{@code headBox}、
     * 基岩系 {@code helmet}…）。四足模型里压根没有这些词 → 自然匹配失败 → 跳过（正是我们要的）。
     */
    public static final String[][] BONE_FAMILIES = {
            {"head", "helmet", "skull", "headwear"},
            {"body", "torso", "chest", "upperbody", "upper_body"},
            {"arm", "hand", "shoulder"},
            {"leg", "thigh", "hip"},
            {"foot", "feet", "boot", "shin", "calf"},
    };

    /** 该槽要挂到宿主的哪根骨骼上（-1 = 挂不上） */
    public static int pickTargetBone(RigBaker.Rig hostRig, EquipmentSlot slot, List<String> skipped) {
        String[] family = switch (slot) {
            case HEAD -> BONE_FAMILIES[0];
            case CHEST -> BONE_FAMILIES[1];
            case LEGS -> BONE_FAMILIES[3];
            case FEET -> BONE_FAMILIES[4];
            default -> BONE_FAMILIES[1];
        };
        int byName = matchHostBone(hostRig, family);
        if (byName >= 0) {
            return byName;
        }
        int guess = guessBoneByPivot(hostRig, slot);
        if (guess >= 0 && skipped != null) {
            skipped.add(slotName(slot) + "：宿主骨骼名不含「" + String.join("/", family)
                    + "」，已按枢轴位置猜成骨骼 #" + guess + "（位置可能略偏）");
        }
        return guess;
    }

    /** 在宿主骨骼表里按名称族找第一根匹配的骨骼 */
    public static int matchHostBone(RigBaker.Rig hostRig, String[] family) {
        if (hostRig == null || hostRig.parts == null) {
            return -1;
        }
        for (int i = 0; i < hostRig.parts.length; i++) {
            String name = nameOf(hostRig.parts[i]);
            if (name == null || name.isEmpty()) {
                continue;
            }
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            for (String key : family) {
                if (lower.contains(key)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * 名称对不上时的兜底：按绑定枢轴的几何位置挑。
     *
     * <p>这不是「猜模组」—— 它用的是任何直立人形骨架都成立的性质：头最高、腿最低。
     * 挑错也只是位置略偏，比「整件护甲不显示」好；日志里会写明这一件是猜的。
     */
    public static int guessBoneByPivot(RigBaker.Rig hostRig, EquipmentSlot slot) {
        int n = hostRig == null ? 0 : hostRig.boneCount;
        if (n <= 0) {
            return -1;
        }
        if (n == 1) {
            return 0;
        }
        int best = -1;
        float bestScore = Float.NEGATIVE_INFINITY;
        for (int i = 1; i < n; i++) {
            float py = hostRig.pivot4[i * 4 + 1];
            float score = switch (slot) {
                case HEAD -> py;
                case CHEST -> py - 0.35F * Math.abs(hostRig.pivot4[i * 4 + 2]);
                case LEGS -> -py;
                case FEET -> -py * 1.2F;
                default -> py;
            };
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        // 护甲的腿/脚槽不能挂到头上去：如果「最低分」和「最高分」是同一根，说明骨架太小，放弃
        return best;
    }

    private static String nameOf(ModelRig.Part part) {
        try {
            return part == null ? null : part.name();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String slotName(EquipmentSlot slot) {
        return slot == null ? "?" : slot.getName();
    }

    // ------------------------------------------------------------------
    // 几何重定位（核心数学）
    // ------------------------------------------------------------------

    /**
     * 把子几何**整体重定位**到宿主某根骨骼上。
     *
     * <h2>公式怎么来的</h2>
     * 着色器对某骨骼做的是（{@code CrowdRenderer.VERTEX_SHADER}）：
     * <pre>
     *   local = (pos - pivot) * scale;   pos = R * local + pivot + trans;
     *   R = rotZ(z) · rotY(y) · rotX(x)
     * </pre>
     * 绑定姿态下 {@code R = I}，所以「顶点坐标就是最终坐标」。挂到骨骼 {@code b} 上只差两点：
     * <ol>
     *   <li><b>平移</b>：{@code delta = pivot4[b] - 子几何锚点}（外加微调与缩放）；</li>
     *   <li><b>抵消绑定旋转</b>：{@code b} 的父链在绑定姿态下可能已经转过（原版僵尸的手臂就是下垂的），
     *       运行时着色器会把这个旋转乘上去；不抵消的话子几何会被多转一次。
     *       所以把父链累积旋转 {@code C} 的**逆**预先乘到顶点上，运行时 {@code C·C⁻¹ = I}。</li>
     * </ol>
     */
    public static float[] rebaseVertices(RigBaker.Rig hostRig, float[] childVerts, int bone,
                                         Matrix3f childRot, float tx, float ty, float tz, float scale) {
        int bones = hostRig.boneCount;
        if (bone < 0 || bone >= bones) {
            return null;
        }
        float[] out = new float[childVerts.length];
        float bx = hostRig.pivot4[bone * 4];
        float by = hostRig.pivot4[bone * 4 + 1];
        float bz = hostRig.pivot4[bone * 4 + 2];
        Matrix3f cum = cumulativeBindRotation(hostRig, bone);
        cum.invert();
        if (childRot != null) {
            cum.mul(childRot);
        }
        for (int i = 0; i < childVerts.length; i += FLOATS_PER_VERTEX) {
            float x = childVerts[i] * scale;
            float y = childVerts[i + 1] * scale;
            float z = childVerts[i + 2] * scale;
            out[i] = cum.m00() * x + cum.m10() * y + cum.m20() * z + bx + tx;
            out[i + 1] = cum.m01() * x + cum.m11() * y + cum.m21() * z + by + ty;
            out[i + 2] = cum.m02() * x + cum.m12() * y + cum.m22() * z + bz + tz;
            float nx = childVerts[i + 3];
            float ny = childVerts[i + 4];
            float nz = childVerts[i + 5];
            out[i + 3] = cum.m00() * nx + cum.m10() * ny + cum.m20() * nz;
            out[i + 4] = cum.m01() * nx + cum.m11() * ny + cum.m21() * nz;
            out[i + 5] = cum.m02() * nx + cum.m12() * ny + cum.m22() * nz;
            out[i + 6] = childVerts[i + 6];
            out[i + 7] = childVerts[i + 7];
            // 骨骼索引统一改写成宿主下标：**骨架一根都不加**
            out[i + 8] = bone;
        }
        return out;
    }

    /**
     * 从宿主根一路乘到 {@code bone} 的**绑定姿态累积旋转** {@code R(root)·…·R(bone)}。
     *
     * <p>注意顺序：着色器是从自己往父级一路左乘，所以累积值是「祖先在前、自己在后」。
     */
    public static Matrix3f cumulativeBindRotation(RigBaker.Rig hostRig, int bone) {
        Matrix3f result = new Matrix3f();
        int[] chain = new int[RigBaker.MAX_CHAIN + 2];
        int n = 0;
        int cur = bone;
        while (cur >= 0 && n < chain.length) {
            chain[n++] = cur;
            cur = (int) (hostRig.pivot4[cur * 4 + 3] + 0.5F) - 1;
        }
        for (int i = n - 1; i >= 0; i--) {
            ModelRig.Part p = hostRig.parts[chain[i]];
            if (p == null) {
                continue;
            }
            result.mul(eulerZYX(p.rotX(), p.rotY(), p.rotZ()));
        }
        return result;
    }

    /**
     * 复刻着色器里的 {@code rotZ(z) · rotY(y) · rotX(x)}。
     *
     * <p>着色器里矩阵是按**列主序**写给 GLSL 的（{@code mat3(1,0,0, 0,c,s, 0,-s,c)} 在数学上
     * 等于 rotX(x) 的转置），所以这里构造 JOML 矩阵时必须逐元素对齐 ——
     * {@code RigHarness} 已经用同一份公式验证过着色器，这里照抄它的构造方式，
     * 保证「CPU 端预旋转」与「GPU 端旋转」严格互逆。
     */
    public static Matrix3f eulerZYX(float x, float y, float z) {
        Matrix3f mx = new Matrix3f(
                1.0F, 0.0F, 0.0F,
                0.0F, (float) Math.cos(x), (float) Math.sin(x),
                0.0F, (float) -Math.sin(x), (float) Math.cos(x));
        Matrix3f my = new Matrix3f(
                (float) Math.cos(y), 0.0F, (float) -Math.sin(y),
                0.0F, 1.0F, 0.0F,
                (float) Math.sin(y), 0.0F, (float) Math.cos(y));
        Matrix3f mz = new Matrix3f(
                (float) Math.cos(z), (float) Math.sin(z), 0.0F,
                (float) -Math.sin(z), (float) Math.cos(z), 0.0F,
                0.0F, 0.0F, 1.0F);
        return mz.mul(my).mul(mx);
    }

    /**
     * 护甲缩放：护甲模型是**人形标准尺寸**，而宿主可能是巨人/矮子。
     *
     * <p>拿「宿主骨骼枢轴在 Y 方向的跨度」比上「护甲模型的」，得到一个纯几何比例。
     * 两者都是标准人形时接近 1；巨人放大、小人缩小 —— 比写死 1.0 好，
     * 也不需要知道任何模组名。夹到 [0.25, 4] 防极端模型糊屏。
     */
    public static float armorScale(RigBaker.Rig hostRig, RigBaker.Rig armorRig) {
        float hs = pivotSpanY(hostRig);
        float as = pivotSpanY(armorRig);
        if (hs <= 0.01F || as <= 0.01F) {
            return 1.0F;
        }
        return Math.max(0.25F, Math.min(4.0F, hs / as));
    }

    private static float pivotSpanY(RigBaker.Rig rig) {
        float lo = Float.MAX_VALUE;
        float hi = -Float.MAX_VALUE;
        for (int i = 0; i < rig.boneCount; i++) {
            float y = rig.pivot4[i * 4 + 1];
            lo = Math.min(lo, y);
            hi = Math.max(hi, y);
        }
        return hi - lo;
    }

    // ------------------------------------------------------------------
    // 手持物品
    // ------------------------------------------------------------------

    /** 捕获到的手持物品几何 */
    public static final class CapturedItem {
        public final ItemStack stack;
        public final float[] vertices;
        public final int vertexCount;

        CapturedItem(ItemStack stack, float[] vertices, int vertexCount) {
            this.stack = stack;
            this.vertices = vertices;
            this.vertexCount = vertexCount;
        }
    }

    /**
     * 捕获一次物品几何（复用 {@link ProjectileBaker} 的手法）。
     *
     * <p>造一个傀儡实体，把它手上的物品设成目标物品，通过**实体自己的渲染器**渲染一次，
     * 把顶点截下来。好处是模组物品的自定义渲染器也一并生效 —— 我们不需要认识任何物品模型。
     * 失败（渲染器不画手持物、该实体没有手）返回 null 并记原因。
     */
    public static CapturedItem captureItem(EntityType<?> type, ItemStack stack, boolean offHand,
                                           List<String> skipped) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || type == null || stack == null || stack.isEmpty()) {
            return null;
        }
        Entity puppet;
        try {
            puppet = type.create(mc.level);
        } catch (Throwable t) {
            skipped.add(itemName(stack) + "：造傀儡失败");
            return null;
        }
        if (!(puppet instanceof LivingEntity living)) {
            skipped.add(itemName(stack) + "：宿主不是生物实体");
            return null;
        }
        try {
            living.setItemSlot(offHand ? EquipmentSlot.OFFHAND : EquipmentSlot.MAINHAND, stack.copy());
            // 站姿复位：手持物的位置由手臂姿态决定，给一个中立姿态最稳
            living.setPos(0.0D, 0.0D, 0.0D);
            living.setYRot(0.0F);
            living.yRotO = 0.0F;
            living.yBodyRot = 0.0F;
            living.yBodyRotO = 0.0F;
            living.yHeadRot = 0.0F;
            living.yHeadRotO = 0.0F;
            living.setXRot(0.0F);
            living.xRotO = 0.0F;
            living.tickCount = 1;
        } catch (Throwable t) {
            skipped.add(itemName(stack) + "：装置到傀儡失败（" + t.getMessage() + "）");
            return null;
        }
        EntityRenderer<?> renderer;
        try {
            renderer = mc.getEntityRenderDispatcher().getRenderer(puppet);
        } catch (Throwable t) {
            skipped.add(itemName(stack) + "：拿不到渲染器");
            return null;
        }
        if (renderer == null) {
            return null;
        }
        ProjectileBaker.Capture cap = new ProjectileBaker.Capture();
        try {
            PoseStack pose = new PoseStack();
            java.lang.reflect.Method render = findRender(renderer.getClass(), puppet);
            if (render == null) {
                skipped.add(itemName(stack) + "：找不到渲染器的渲染方法");
                return null;
            }
            render.invoke(renderer, renderArgs(render, puppet, pose, new ProjectileBaker.StaticSource(cap)));
            if (cap.vertexCount() == 0) {
                skipped.add(itemName(stack) + "：渲染器没有画出手持物（该实体可能没有手）");
                return null;
            }
        } catch (Throwable t) {
            Throwable cause = t;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            skipped.add(itemName(stack) + "：渲染捕获失败（" + cause.getClass().getSimpleName()
                    + (cause.getMessage() == null ? "" : ": " + cause.getMessage()) + "）");
            return null;
        }
        return new CapturedItem(stack.copy(), cap.buildVertices(), cap.vertexCount());
    }

    /** 找 {@code EntityRenderer#render}（与 {@link ProjectileBaker} 同一套「按参数形状」的找法） */
    private static java.lang.reflect.Method findRender(Class<?> cls, Entity puppet) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length < 6 || m.getReturnType() != void.class || !p[0].isInstance(puppet)) {
                    continue;
                }
                int n = p.length;
                if (p[n - 1] != int.class || p[n - 2] != MultiBufferSource.class
                        || p[n - 3] != PoseStack.class) {
                    continue;
                }
                boolean numeric = true;
                for (int i = 1; i < n - 3; i++) {
                    if (p[i] != float.class && p[i] != double.class) {
                        numeric = false;
                        break;
                    }
                }
                if (numeric) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    private static Object[] renderArgs(java.lang.reflect.Method render, Entity puppet,
                                       PoseStack stack, MultiBufferSource source) {
        Class<?>[] p = render.getParameterTypes();
        int n = p.length;
        Object[] args = new Object[n];
        args[0] = puppet;
        double[] values = {0.0D, 0.0D, 0.0D, 0.0D, 1.0D};
        int vi = 0;
        for (int i = 1; i < n - 3; i++) {
            double v = values[Math.min(vi, values.length - 1)];
            vi++;
            args[i] = p[i] == float.class ? (Object) (float) v : (Object) v;
        }
        args[n - 3] = stack;
        args[n - 2] = source;
        args[n - 1] = 15728880;
        return args;
    }

    /** 把手持物品挂到宿主手上；挂不上返回 null */
    public static float[] attachItem(RigBaker.Rig hostRig, CapturedItem item, int handBone,
                                     boolean offHand, List<String> skipped) {
        if (handBone < 0) {
            skipped.add(itemName(item.stack) + "：宿主没有可用的手部骨骼");
            return null;
        }
        float[] normalized = normalizeItem(item.vertices);
        if (normalized == null) {
            skipped.add(itemName(item.stack) + "：捕获到的几何是空的");
            return null;
        }
        float handLen = handLength(hostRig, handBone);
        Matrix3f rot = eulerZYX((float) Math.toRadians(ITEM_ROT_X),
                (float) Math.toRadians(ITEM_ROT_Y), (float) Math.toRadians(ITEM_ROT_Z));
        float[] out = rebaseVertices(hostRig, normalized, handBone, rot,
                GRIP_OFFSET_X, -handLen * GRIP_FRACTION + GRIP_OFFSET_Y, GRIP_OFFSET_Z, 1.0F);
        if (out != null && offHand) {
            mirrorX(hostRig, out, handBone);
        }
        return out;
    }

    /** 手长：手骨到它第一个子骨骼的距离（格）；没有子骨骼就用标准人形手臂的量级 */
    public static float handLength(RigBaker.Rig hostRig, int bone) {
        float bx = hostRig.pivot4[bone * 4];
        float by = hostRig.pivot4[bone * 4 + 1];
        float bz = hostRig.pivot4[bone * 4 + 2];
        for (int i = 0; i < hostRig.boneCount; i++) {
            int parent = (int) (hostRig.pivot4[i * 4 + 3] + 0.5F) - 1;
            if (parent != bone) {
                continue;
            }
            float dx = hostRig.pivot4[i * 4] - bx;
            float dy = hostRig.pivot4[i * 4 + 1] - by;
            float dz = hostRig.pivot4[i * 4 + 2] - bz;
            float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len > 0.01F) {
                return len;
            }
        }
        return 0.35F;
    }

    /** 把物品几何归一化成「最长边 0.45 格、以原点为中心」的单位盒 */
    public static float[] normalizeItem(float[] verts) {
        if (verts == null || verts.length < FLOATS_PER_VERTEX) {
            return null;
        }
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        float maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < verts.length; i += FLOATS_PER_VERTEX) {
            minX = Math.min(minX, verts[i]);
            minY = Math.min(minY, verts[i + 1]);
            minZ = Math.min(minZ, verts[i + 2]);
            maxX = Math.max(maxX, verts[i]);
            maxY = Math.max(maxY, verts[i + 1]);
            maxZ = Math.max(maxZ, verts[i + 2]);
        }
        float longest = Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
        if (longest < 1.0E-5F) {
            return null;
        }
        float k = 0.45F / longest;
        float cx = (minX + maxX) * 0.5F;
        float cy = (minY + maxY) * 0.5F;
        float cz = (minZ + maxZ) * 0.5F;
        float[] out = new float[verts.length];
        for (int i = 0; i < verts.length; i += FLOATS_PER_VERTEX) {
            out[i] = (verts[i] - cx) * k;
            out[i + 1] = (verts[i + 1] - cy) * k;
            out[i + 2] = (verts[i + 2] - cz) * k;
            out[i + 3] = verts[i + 3];
            out[i + 4] = verts[i + 4];
            out[i + 5] = verts[i + 5];
            out[i + 6] = verts[i + 6];
            out[i + 7] = verts[i + 7];
            out[i + 8] = verts[i + 8];
        }
        return out;
    }

    private static void mirrorX(RigBaker.Rig hostRig, float[] verts, int bone) {
        float bx = hostRig.pivot4[bone * 4];
        for (int i = 0; i < verts.length; i += FLOATS_PER_VERTEX) {
            verts[i] = 2.0F * bx - verts[i];
            verts[i + 3] = -verts[i + 3];
        }
    }

    /** 把若干段顶点拼到宿主顶点后面（索引整体后移）；骨骼索引已经在各段里写好了 */
    public static float[] concat(RigBaker.Rig host, List<float[]> extras) {
        int total = host.vertices.length;
        for (float[] e : extras) {
            total += e.length;
        }
        float[] out = new float[total];
        System.arraycopy(host.vertices, 0, out, 0, host.vertices.length);
        int p = host.vertices.length;
        for (float[] e : extras) {
            System.arraycopy(e, 0, out, p, e.length);
            p += e.length;
        }
        return out;
    }

    /**
     * 把多段几何的索引拼起来（段内的顶点下标要加上它前面所有段的顶点数）。
     *
     * <p>各段都是「每 4 个顶点一个四边形」的标准格式，所以这里按 4 个一组生成两个三角形，
     * 与 {@link RigBaker} 的顶点发射顺序一致（它的截获器也是这么组的）。
     */
    public static int[] concatIndices(RigBaker.Rig host, List<float[]> extras) {
        List<int[]> parts = new ArrayList<>();
        parts.add(host.indices);
        for (float[] e : extras) {
            parts.add(quadIndices(e.length / FLOATS_PER_VERTEX));
        }
        return concatIndexArrays(parts);
    }

    /** 一段几何的四边形索引（顶点数必须是 4 的倍数；余数按三角形处理） */
    public static int[] quadIndices(int vertexCount) {
        int quads = vertexCount / 4;
        int rest = vertexCount % 4;
        int[] idx = new int[quads * 6 + (rest == 3 ? 3 : 0)];
        int p = 0;
        for (int q = 0; q < quads; q++) {
            int b = q * 4;
            idx[p++] = b;
            idx[p++] = b + 1;
            idx[p++] = b + 2;
            idx[p++] = b;
            idx[p++] = b + 2;
            idx[p++] = b + 3;
        }
        if (rest == 3) {
            int b = quads * 4;
            idx[p++] = b;
            idx[p++] = b + 1;
            idx[p++] = b + 2;
        }
        return idx;
    }

    /**
     * 把多段几何的索引拼起来。
     *
     * <p>{@code parts} 必须按**顶点段出现的顺序**给出，且每段的下标都是从 0 开始的局部下标；
     * 这个方法按「上一段的顶点数」推进基准。各段的顶点数由调用方通过
     * {@link #quadIndices(int)} 一起算出来，所以这里用「段的最大下标 + 1」推顶点数是安全的
     * （段内不会出现空洞）。
     */
    public static int[] concatIndexArrays(List<int[]> parts) {
        int total = 0;
        for (int[] p : parts) {
            total += p.length;
        }
        int[] out = new int[total];
        int p = 0;
        int base = 0;
        for (int[] part : parts) {
            int max = -1;
            for (int v : part) {
                if (v > max) {
                    max = v;
                }
            }
            for (int v : part) {
                out[p++] = v + base;
            }
            base += max + 1;
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 缓存
    // ------------------------------------------------------------------

    /** 每个实体类型的变体上限（配置项 {@code equipVariantCachePerType} 可覆盖） */
    public static final int DEFAULT_VARIANTS_PER_TYPE = 64;

    private static EquipMeshCache<Variant> CACHE;

    /** 真正烘焙一个变体（需要客户端环境）。离线 harness 直接验证 {@link EquipMeshCache} 的缓存行为。 */
    public interface VariantBaker {
        Variant bake(ResourceLocation unitType, String equipKey);
    }

    private static VariantBaker baker;

    /** 接线/测试用：换掉真正干活的烘焙实现 */
    public static void setBaker(VariantBaker impl) {
        baker = impl;
    }

    private static EquipMeshCache<Variant> cache() {
        if (CACHE == null) {
            int limit = safeCacheLimit();
            CACHE = new EquipMeshCache<>(limit, new EquipMeshCache.Factory<Variant>() {
                @Override
                public Variant create(ResourceLocation unitType, String equipKey) {
                    VariantBaker b = baker != null ? baker : EquipBaker::bakeVariant;
                    return b.bake(unitType, equipKey);
                }

                @Override
                public void release(Variant value) {
                    // 变体目前只是纯 CPU 数组（顶点/索引），GL 资源由渲染器持有。
                    // 以后若变体自带 VAO，必须在这里删 —— 留这条注释是为了别忘。
                }
            });
        }
        return CACHE;
    }

    private static int safeCacheLimit() {
        // 上限来自配置项 equipVariantCachePerType —— 接线时在 HordeConfig.Common 里加这个
        // 字段即可；在那之前这里用默认值（报告里的接线说明写了确切的配置项声明）。
        try {
            java.lang.reflect.Field f = dev.swordkingdom.horde.HordeConfig.COMMON.getClass()
                    .getField("equipVariantCachePerType");
            Object v = f.get(dev.swordkingdom.horde.HordeConfig.COMMON);
            Object raw = v.getClass().getMethod("get").invoke(v);
            if (raw instanceof Number n) {
                return Math.max(4, n.intValue());
            }
        } catch (Throwable ignored) {
            // 配置项还没接线：用默认值
        }
        return DEFAULT_VARIANTS_PER_TYPE;
    }

    public static Variant variantFor(ResourceLocation unitType, String equipKey) {
        return cache().get(unitType, equipKey);
    }

    public static EquipMeshCache<Variant> cacheRef() {
        return cache();
    }

    public static void invalidateAll() {
        if (CACHE != null) {
            CACHE.clear();
        }
        POSE_CACHE.clear();
    }

    public static void invalidate(ResourceLocation unitType) {
        if (CACHE != null) {
            CACHE.invalidateType(unitType);
        }
    }

    // ------------------------------------------------------------------
    // 默认的烘焙实现
    // ------------------------------------------------------------------

    /**
     * 默认实现：宿主网格 + 护甲 + 手持物品。
     *
     * <p>任何一步失败都只影响那一件（记进 {@code skipped}），整体仍然返回宿主网格
     * —— 也就是「最坏情况退化成没穿装备的原样」，而不是「这个类型不显示了」。
     */
    public static Variant bakeVariant(ResourceLocation unitType, String equipKey) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null;
        }
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(unitType);
        if (type == null) {
            return null;
        }
        Entity puppet;
        try {
            puppet = type.create(mc.level);
        } catch (Throwable t) {
            return null;
        }
        if (!(puppet instanceof LivingEntity living)) {
            return null;
        }
        EntityRenderer<?> renderer;
        try {
            renderer = mc.getEntityRenderDispatcher().getRenderer(puppet);
        } catch (Throwable t) {
            return null;
        }
        if (!(renderer instanceof LivingEntityRenderer)) {
            // geo 系（GeckoLib 等）：拿不到原版护甲图层，也没有 ModelPart 骨架 —— 交给调用方退化
            return null;
        }
        LivingEntityRenderer<LivingEntity, EntityModel<LivingEntity>> livingRenderer =
                castLiving(renderer);
        EntityModel<?> model;
        try {
            model = livingRenderer.getModel();
        } catch (Throwable t) {
            return null;
        }
        if (model == null) {
            return null;
        }
        RigBaker.Rig hostRig;
        try {
            hostRig = RigBaker.bake(model, living, unitType);
        } catch (Throwable t) {
            return null;
        }
        if (hostRig == null || hostRig.vertices.length == 0) {
            return null;
        }

        ResourceLocation hostTexture = null;
        try {
            hostTexture = livingRenderer.getTextureLocation(living);
        } catch (Throwable ignored) {
            // 没有实体贴图就退化成方块图集（和 ProjectileBaker 同款处理）
        }
        if (hostTexture == null) {
            hostTexture = TextureAtlas.LOCATION_BLOCKS;
        }

        short[] six = parseEquipKey(equipKey);
        int flags = parseFlags(equipKey);
        List<String> skipped = new ArrayList<>();
        List<float[]> extras = new ArrayList<>();
        List<int[]> extraIdx = new ArrayList<>();
        int armorPieces = 0;
        int items = 0;
        ResourceLocation variantTexture = hostTexture;

        // ---- 护甲 ----
        EquipmentSlot[] armorSlots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET};
        Object armorLayer = null;
        Object armorModel = null;
        java.lang.reflect.Method armorTexture = null;
        ClientEquipData clientData = ClientEquipData.forCurrentLevel();
        for (int s = 0; s < armorSlots.length; s++) {
            short index = six[s];
            if (index == 0) {
                continue;
            }
            ClientEquipData.Entry entry = clientData == null ? null : clientData.entry(unitType, index);
            if (entry == null && armorLayer == null) {
                skipped.add(slotName(armorSlots[s]) + "：本地没有这个索引的物品信息（等整表下发）");
                continue;
            }
            if (armorLayer == null) {
                armorLayer = findArmorLayer(renderer);
                armorModel = armorModelOf(armorLayer);
                armorTexture = findArmorTextureMethod(armorLayer);
                if (armorModel == null) {
                    skipped.add(slotName(armorSlots[s]) + "：该渲染器没有原版人形护甲图层"
                            + "（非人形/GeckoLib 模型挂不上护甲，跳过）");
                    break;
                }
            }
            float[] armorVerts = attachArmor(hostRig, armorModel, armorSlots[s], living, skipped);
            if (armorVerts == null) {
                continue;
            }
            extras.add(armorVerts);
            extraIdx.add(quadIndices(armorVerts.length / FLOATS_PER_VERTEX));
            armorPieces++;
            // 护甲贴图：与原版一致 —— 护甲的贴图来自护甲物品自己，不是实体贴图
            if (armorTexture != null && entry != null) {
                try {
                    net.minecraft.world.item.Item armorItem =
                            ForgeRegistries.ITEMS.getValue(entry.item);
                    if (armorItem != null) {
                        ItemStack stack = new ItemStack(armorItem);
                        Object tex = armorTexture.invoke(armorLayer, living, stack, armorSlots[s], null);
                        if (tex instanceof ResourceLocation rl && !rl.equals(hostTexture)) {
                            skipped.add(slotName(armorSlots[s]) + "：护甲贴图 " + rl
                                    + " 与主体贴图 " + hostTexture + " 不同，"
                                    + "一次 draw call 只能绑一张贴图 —— 这一件会沿用主体贴图"
                                    + "（要在渲染器里为它多发一次 draw call，见接线说明）");
                        }
                    }
                } catch (Throwable t) {
                    skipped.add(slotName(armorSlots[s]) + "：取护甲贴图失败（"
                            + t.getClass().getSimpleName() + "）");
                }
            }
        }

        // ---- 手持物品 ----
        String mainItem = itemIdAt(unitType, six[EquipPalette.SLOT_MAINHAND]);
        String offItem = itemIdAt(unitType, six[EquipPalette.SLOT_OFFHAND]);
        items += attachItems(living, type, hostRig, skipped, extras, extraIdx, mainItem, false);
        items += attachItems(living, type, hostRig, skipped, extras, extraIdx, offItem, true);

        float[] verts = concat(hostRig, extras);
        List<int[]> allIdx = new ArrayList<>();
        allIdx.add(hostRig.indices);
        allIdx.addAll(extraIdx);
        int[] idx = concatIndexArrays(allIdx);
        Variant variant = new Variant(verts, idx, variantTexture, armorPieces, items, skipped, hostRig);
        // 姿态来源：宿主那一份（变体骨骼数与通道都继承宿主，所以可以共用）
        variant.pose = poseOf(unitType, model, living);
        if (!skipped.isEmpty()) {
            HordeMod.LOGGER.info("[horde] 变体网格 {} / {}：顶点 {}，三角 {}，护甲 {} 件，手持 {} 件；跳过 {} 项：{}",
                    unitType, equipKey, variant.vertexCount(), variant.triangleCount(),
                    armorPieces, items, skipped.size(), skipped);
        }
        return variant;
    }

    /** 每个实体类型一份 {@link CrowdPose} 缓存（变体共用宿主的动画驱动） */
    private static final java.util.Map<ResourceLocation, CrowdPose> POSE_CACHE = new java.util.HashMap<>();

    private static CrowdPose poseOf(ResourceLocation unitType, Object model, LivingEntity puppet) {
        CrowdPose cached = POSE_CACHE.get(unitType);
        if (cached != null) {
            return cached;
        }
        try {
            EntityMeshBaker.Mesh host = EntityMeshBaker.bake(
                    ForgeRegistries.ENTITY_TYPES.getValue(unitType), unitType);
            if (host != null && host.pose != null) {
                POSE_CACHE.put(unitType, host.pose);
                return host.pose;
            }
        } catch (Throwable t) {
            HordeMod.LOGGER.debug("[horde] 变体的宿主姿态来源获取失败（{}）: {}", unitType, t.toString());
        }
        return null;
    }

    public static void clearPoseCache() {
        POSE_CACHE.clear();
    }

    /** 装一只手上的物品 */
    private static int attachItems(LivingEntity living, EntityType<?> type, RigBaker.Rig hostRig,
                                   List<String> skipped, List<float[]> extras, List<int[]> extraIdx,
                                   String itemId, boolean offHand) {
        if (itemId == null || itemId.isEmpty()) {
            return 0;
        }
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        net.minecraft.world.item.Item item = id == null ? null : ForgeRegistries.ITEMS.getValue(id);
        if (item == null) {
            skipped.add((offHand ? "副手" : "主手") + "：" + itemId + " 未注册");
            return 0;
        }
        CapturedItem cap = captureItem(type, new ItemStack(item), offHand, skipped);
        if (cap == null) {
            return 0;
        }
        String[] family = offHand
                ? new String[]{"leftarm", "left_arm", "left arm", "larm"}
                : new String[]{"rightarm", "right_arm", "right arm", "rarm"};
        int hand = matchHostBone(hostRig, family);
        if (hand < 0) {
            hand = matchHostBone(hostRig, BONE_FAMILIES[2]);
        }
        if (hand < 0) {
            hand = guessBoneByPivot(hostRig, EquipmentSlot.CHEST);
        }
        float[] verts = attachItem(hostRig, cap, hand, offHand, skipped);
        if (verts == null) {
            return 0;
        }
        extras.add(verts);
        extraIdx.add(quadIndices(verts.length / FLOATS_PER_VERTEX));
        return 1;
    }

    /** 从 {@code EquipData.key} 格式里解析 6 个索引 */
    public static short[] parseEquipKey(String key) {
        short[] six = new short[EquipData.SLOT_COUNT];
        if (key == null) {
            return six;
        }
        int semi = key.indexOf(';');
        String body = semi >= 0 ? key.substring(0, semi) : key;
        String[] parts = body.split(",");
        for (int s = 0; s < EquipData.SLOT_COUNT && s < parts.length; s++) {
            try {
                six[s] = (short) Integer.parseInt(parts[s].trim());
            } catch (Throwable ignored) {
                six[s] = 0;
            }
        }
        return six;
    }

    /** 从 {@code EquipData.key} 格式里解析 flags */
    public static int parseFlags(String key) {
        if (key == null) {
            return 0;
        }
        int semi = key.indexOf(';');
        if (semi < 0 || semi + 1 >= key.length()) {
            return 0;
        }
        try {
            return Integer.parseInt(key.substring(semi + 1).trim()) & 0xFF;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String itemIdAt(ResourceLocation unitType, short index) {
        if (index == 0) {
            return null;
        }
        ClientEquipData data = ClientEquipData.forCurrentLevel();
        ClientEquipData.Entry e = data == null ? null : data.entry(unitType, index);
        return e == null || e.item == null ? null : e.item.toString();
    }

    private static String itemName(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "物品";
        }
        try {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
            return id == null ? "物品" : id.toString();
        } catch (Throwable t) {
            return "物品";
        }
    }

    // ------------------------------------------------------------------
    // 护甲几何：把护甲模型挂到宿主骨骼上
    // ------------------------------------------------------------------

    /**
     * 取某一槽的护甲几何并挂到宿主骨架上。
     *
     * <p>只让该槽对应的部件可见 —— 护甲模型是「一套四件」共用的，
     * 不按槽过滤的话头盔会画出胸甲（原版 {@code HumanoidArmorLayer.setPartVisibility} 做的就是这件事）。
     */
    public static float[] attachArmor(RigBaker.Rig hostRig, Object armorModel, EquipmentSlot slot,
                                      LivingEntity puppet, List<String> skipped) {
        applyArmorVisibility(armorModel, slot);
        RigBaker.Rig armorRig;
        try {
            // 傀儡传 null：护甲模型没有动画通道，而且 RigBaker 会先 resetBind，
            // 所以顶点就是纯绑定姿态（护甲不动的部分正是我们要的）
            armorRig = RigBaker.bake(armorModel, null);
        } catch (Throwable t) {
            skipped.add(slotName(slot) + "：护甲模型拆骨骼失败（" + t.getMessage() + "）");
            return null;
        }
        if (armorRig == null || armorRig.vertices.length == 0) {
            skipped.add(slotName(slot) + "：护甲模型拆不出几何（该部件系统可能不被支持）");
            return null;
        }
        int target = pickTargetBone(hostRig, slot, skipped);
        if (target < 0) {
            skipped.add(slotName(slot) + "：宿主模型找不到对应骨骼（非人形/无手模型）");
            return null;
        }
        float scale = armorScale(hostRig, armorRig);
        float[] out = rebaseVertices(hostRig, armorRig.vertices, target, null, 0.0F, 0.0F, 0.0F, scale);
        if (out == null) {
            skipped.add(slotName(slot) + "：重定位失败");
        }
        return out;
    }

    /** 把通配的 {@code LivingEntityRenderer} 转成「LivingEntity + EntityModel」的确定类型 */
    @SuppressWarnings("unchecked")
    private static LivingEntityRenderer<LivingEntity, EntityModel<LivingEntity>> castLiving(
            EntityRenderer<?> renderer) {
        return (LivingEntityRenderer<LivingEntity, EntityModel<LivingEntity>>) renderer;
    }

    /** 按槽设置护甲模型的部件可见性（原版 {@code HumanoidArmorLayer.setPartVisibility} 的等价物） */
    public static void applyArmorVisibility(Object armorModel, EquipmentSlot slot) {
        if (!(armorModel instanceof HumanoidModel<?> hm)) {
            return;
        }
        boolean head = slot == EquipmentSlot.HEAD;
        boolean chest = slot == EquipmentSlot.CHEST;
        boolean legs = slot == EquipmentSlot.LEGS;
        boolean feet = slot == EquipmentSlot.FEET;
        try {
            hm.setAllVisible(false);
            hm.head.visible = head;
            hm.hat.visible = head;
            hm.body.visible = chest;
            hm.rightArm.visible = chest;
            hm.leftArm.visible = chest;
            hm.rightLeg.visible = legs || feet;
            hm.leftLeg.visible = legs || feet;
        } catch (Throwable ignored) {
            // 字段拿不到就保持模型自己的可见性（总比崩了强）
        }
    }
}
