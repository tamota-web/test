package dev.swordkingdom.horde.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;

/**
 * 「什么都认不出来」时的最后一道保险：**顶点捕获**。
 *
 * <h2>为什么需要它</h2>
 * 群集渲染只有两条已知的形状：
 * <ol>
 *   <li>{@code LivingEntityRenderer}（有 {@code getModel()}）；</li>
 *   <li>渲染器字段/取值方法里的 {@code EntityModel}（原版末影龙、部分模组生物）；</li>
 *   <li>GeckoLib 那种 {@code GeoModel}（geo 形状）。</li>
 * </ol>
 * 但模组生态里还有大量「自己在 {@code EntityRenderer#render} 里直接写顶点」的渲染器
 * （自定义巨兽、特殊表现体）。它们没有任何模型对象可拆，于是以前会**静默地什么都画不出来**。
 * 这里改用它们唯一共有的东西：**它们都会往 {@code VertexConsumer} 里写顶点**。
 *
 * <h2>做法</h2>
 * 拿一个能记录顶点的 {@link ProjectileBaker.Capture}（与投掷物/装备链路同一套、已在真机验证过），
 * 让实体**通过它自己的渲染器**渲染一次，把顶点截下来：
 * <pre>
 *   render(T entity, float yaw, float partialTick, PoseStack, MultiBufferSource, int light)
 * </pre>
 * 顶点格式因此与生物网格**完全一致**（位置/法线/UV/骨骼索引），骨骼数 = 1 —— 整块网格就是一根骨骼，
 * 没有父子链、没有逐骨骼动画，但**一定能看见**。
 *
 * <h2>三条硬性保护</h2>
 * <ol>
 *   <li><b>顶点上限</b>（{@link #MAX_VERTICES}）：巨兽的渲染器可能画到几百万顶点，超过就整块丢弃并报 WARN，
 *       绝不把内存打爆（上限写在截获器的写入路径上，见 {@code Capture#setMaxVertices}）。</li>
 *   <li><b>异常必被讲清</b>：渲染器抛的异常被反射包在 {@code InvocationTargetException} 里，
 *       这里剥到最内层原因再交回调用方，绝不静默。</li>
 *   <li><b>不动世界</b>：傀儡实体不入世界、不发光、位置/朝向先复位到中性状态，</li>
 * </ol>
 *
 * <h2>为什么直接调渲染器（而不是走 EntityRenderDispatcher）</h2>
 * 调度器那条路依赖 {@code Minecraft} 客户端单例（离线无法验证），而且它自己会再查一次渲染器、
 * 还会顺手画火焰层。直接调用 {@code renderer.render(...)} 的语义是同一个（调度器最后也是调它），
 * 区别只有一个：调度器会先 {@code translate(pos + getRenderOffset())}。
 * 所以这里在捕获之后用 {@link #renderOffset} 把这一步补回去（符号见该方法的说明），
 * 于是离线验证与线上走的是**同一条代码路径**。
 */
public final class RendererCapture {

    /**
     * 单块捕获网格的顶点上限。
     *
     * <p>200k 顶点 ≈ 7.2MB 顶点数据（9 float/顶点）+ 索引，是个「异常巨大的模组巨兽」也够用的量级；
     * 而原版末影龙这种「看着巨大」的模型其实只有一两千顶点（几十个方块），远低于上限。
     * 真正会撞上限的是失控的自定义渲染器，那时候整块跳过才是对的。
     */
    public static final int MAX_VERTICES = 200_000;

    private RendererCapture() {}

    /** 捕获结果 */
    public static final class Result {
        public final boolean ok;
        /** 失败原因（人话，直接进日志） */
        public final String reason;
        public final float[] vertices;
        public final int[] indices;
        public final ResourceLocation texture;
        /** 贴图是不是退化成了方块图集（渲染器没给出贴图） */
        public final boolean atlasTexture;
        /** 是否触到顶点上限（此时 {@code ok == false}） */
        public final boolean truncated;

        private Result(String reason, float[] vertices, int[] indices, ResourceLocation texture,
                       boolean atlasTexture, boolean truncated) {
            this.reason = reason;
            this.vertices = vertices;
            this.indices = indices;
            this.texture = texture;
            this.atlasTexture = atlasTexture;
            this.truncated = truncated;
            this.ok = vertices != null && indices != null && indices.length > 0;
        }

        static Result fail(String reason, boolean truncated) {
            return new Result(reason, null, null, null, false, truncated);
        }

        public int vertexCount() {
            return vertices == null ? 0 : vertices.length / RigBaker.FLOATS_PER_VERTEX;
        }

        public int triangleCount() {
            return indices == null ? 0 : indices.length / 3;
        }
    }

    /**
     * 捕获一次实体渲染。
     *
     * @param renderer    实体自己的渲染器（调用方已经拿到的那一个，不重新查表）
     * @param puppet      傀儡实体（可以为 null：离线验证用替身渲染器）
     * @param maxVertices 顶点数上限（一般传 {@link #MAX_VERTICES}）
     */
    public static Result capture(Object renderer, Entity puppet, int maxVertices) {
        if (renderer == null) {
            return Result.fail("渲染器为 null", false);
        }
        Method render = findRender(renderer.getClass(), puppet);
        if (render == null) {
            return Result.fail("渲染器 " + renderer.getClass().getName()
                    + " 上找不到「第一个参数是实体、尾部是 PoseStack+MultiBufferSource+int」的 render 方法", false);
        }
        neutralise(puppet);

        ProjectileBaker.Capture cap = new ProjectileBaker.Capture();
        cap.setMaxVertices(maxVertices);
        try {
            PoseStack stack = new PoseStack();
            // 所有 RenderType 灌进同一个截获器：捕获式网格只有一张贴图，分层没有意义
            MultiBufferSource source = new ProjectileBaker.StaticSource(cap);
            render.setAccessible(true);
            render.invoke(renderer, renderArgs(render, puppet, stack, source));
        } catch (Throwable t) {
            return Result.fail("渲染器 " + renderer.getClass().getSimpleName() + " 渲染时抛异常（"
                    + root(t) + "）", false);
        }
        if (cap.overflowed()) {
            return Result.fail("顶点数超过上限 " + maxVertices + "，已整块跳过（巨兽/失控渲染器会把内存打爆）", true);
        }
        if (cap.vertexCount() == 0) {
            return Result.fail("渲染器一个顶点都没写（它可能只在真实世界里、或只走 RenderType 特殊路径时才画）", false);
        }
        float[] vertices = cap.buildVertices();
        int[] indices = cap.buildIndices();
        if (indices.length == 0) {
            return Result.fail("截获到 " + cap.vertexCount() + " 个顶点但没能组成三角形（渲染器写的顶点数不是 3/4 的整数倍）", false);
        }
        ResourceLocation texture = textureOf(renderer, puppet);
        boolean atlas = false;
        if (texture == null) {
            // 拿不到贴图也必须能看见东西：退化成方块图集（灰色但形状正确），并在日志里说明
            texture = TextureAtlas.LOCATION_BLOCKS;
            atlas = true;
        }
        return new Result(null, vertices, indices, texture, atlas, false);
    }

    /**
     * 渲染器「在画模型之前」自己施加的变换（4×4，列主序），拿不到返回 null。
     *
     * <p>手法：{@code MultiBufferSource#getBuffer} 是渲染器**在摆好姿势之后、开始画模型之前**
     * 必然会调的（它得先要到顶点消费者），而它拿不到 PoseStack —— 但我们拿得到：
     * 传进去的 PoseStack 就是我们自己造的那一个。于是在第一次 {@code getBuffer} 时
     * 把 {@code stack.last().pose()} 快照下来，正好就是渲染器的前置变换（不含模型内部各部件）。
     *
     * <p>用途有二：① 日志里把「这个渲染器到底做了什么」说清楚（排查「大小/位置不对」的第一现场）；
     * ② 取出它施加的整体缩放 —— 有些模组渲染器会 {@code scale(3,3,3)} 放大 boss，
     * 不跟着放大，群集里的单位就只有真实实体的三分之一大。
     * 原版约定 {@code scale(-1,-1,1)} 取各列模长后正好是 1，不会改变现有表现。
     */
    public static float[] preludeMatrix(Object renderer, Entity puppet) {
        if (renderer == null) {
            return null;
        }
        try {
            Method render = findRender(renderer.getClass(), puppet);
            if (render == null) {
                return null;
            }
            PoseStack stack = new PoseStack();
            PreludeSource source = new PreludeSource(stack);
            render.setAccessible(true);
            render.invoke(renderer, renderArgs(render, puppet, stack, source));
            return source.matrix;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 前置变换矩阵的三列模长（= 该渲染器施加的整体缩放，与旋转/翻转无关） */
    public static float[] columnLengths(float[] matrix) {
        if (matrix == null || matrix.length < 16) {
            return new float[]{1.0F, 1.0F, 1.0F};
        }
        return new float[]{len(matrix, 0), len(matrix, 4), len(matrix, 8)};
    }

    private static float len(float[] m, int o) {
        return (float) Math.sqrt(m[o] * m[o] + m[o + 1] * m[o + 1] + m[o + 2] * m[o + 2]);
    }

    /** 矩阵压成一行（日志用）：缩放 / 平移 */
    public static String describeMatrix(float[] m) {
        if (m == null) {
            return "拿不到（渲染器没在画模型前要过顶点消费者）";
        }
        float[] s = columnLengths(m);
        return String.format(Locale.ROOT, "缩放(%.3f, %.3f, %.3f)，平移(%.3f, %.3f, %.3f)",
                s[0], s[1], s[2], m[12], m[13], m[14]);
    }

    /**
     * 该实体在调度器里会额外获得的渲染位移。
     *
     * <p>符号说明：{@code EntityRenderDispatcher} 是
     * {@code translate(pos.x + off.x, pos.y + off.y, pos.z + off.z)} 之后再调
     * {@code renderer.render(...)}（见 1.20.1 字节码），也就是说渲染器画出来的顶点
     * 是在「实体位置 **加上** off」这一帧里。我们直接调渲染器，少了这一步，
     * 所以要把 {@code +off} 交给着色器的 {@code uRenderOffset}（它加在世界空间）补回来。
     * 只有极少数渲染器会覆盖 {@code getRenderOffset}（潜影贝、末影人、物品展示框……），
     * 对绝大多数类型这里是 (0,0,0)。
     *
     * <p>注意：{@code LivingEntityRenderer} 那条老路径用的是 {@code -off}（历史行为，本次不动）。
     */
    @SuppressWarnings("rawtypes")
    public static Vec3 renderOffset(Object renderer, Entity puppet) {
        if (!(renderer instanceof net.minecraft.client.renderer.entity.EntityRenderer er)) {
            return Vec3.ZERO;
        }
        try {
            Vec3 off = er.getRenderOffset(puppet, 0.0F);
            return off == null ? Vec3.ZERO : off;
        } catch (Throwable t) {
            return Vec3.ZERO;
        }
    }

    /**
     * 按**签名**找渲染方法，不按名字找（线上是 SRG 名）。
     *
     * <p>形状：{@code [实体, 若干数值(yaw, partialTick…), PoseStack, MultiBufferSource, int]}
     * —— 与 {@link ProjectileBaker}/{@code EquipBaker} 里那套完全一致（那里已有真机验证）。
     * 纯数值段允许 float/double，所以 6 参（渲染器自身）与 9 参（调度器）两种形状都能命中。
     *
     * @param puppet 可以为 null（离线验证用替身渲染器）：此时只按形状匹配，不校验第一个参数
     */
    static Method findRender(Class<?> cls, Entity puppet) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length < 6 || m.getReturnType() != void.class) {
                    continue;
                }
                if (puppet != null && !p[0].isInstance(puppet)) {
                    continue;
                }
                int n = p.length;
                if (p[n - 1] != int.class || p[n - 2] != MultiBufferSource.class || p[n - 3] != PoseStack.class) {
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
                    return m;
                }
            }
        }
        return null;
    }

    /** 按真实参数类型拼实参：数值段按 (yaw, partialTick) 填，其余三段是栈/缓冲/光照。 */
    public static Object[] renderArgs(Method render, Entity puppet, PoseStack stack, MultiBufferSource source) {
        Class<?>[] p = render.getParameterTypes();
        int n = p.length;
        Object[] args = new Object[n];
        args[0] = puppet;
        // 数值段：x, y, z, yaw, partialTick —— 与 ProjectileBaker 同一套填法（多余的位一律用 1.0）
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

    /**
     * 按**签名**找 {@code getTextureLocation(T)} —— 不按名字（线上是 SRG 名），
     * 找不到就退一步用渲染器里的 {@code ResourceLocation} 字段
     * （原版末影龙的贴图就是一个 static final 字段 {@code DRAGON_LOCATION}）。
     */
    public static ResourceLocation textureOf(Object renderer, Entity puppet) {
        if (renderer == null) {
            return null;
        }
        for (Class<?> c = renderer.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() != 1 || m.getReturnType() != ResourceLocation.class) {
                    continue;
                }
                if (puppet != null && !m.getParameterTypes()[0].isInstance(puppet)) {
                    continue;
                }
                try {
                    m.setAccessible(true);
                    Object v = m.invoke(renderer, puppet);
                    if (v instanceof ResourceLocation rl) {
                        return rl;
                    }
                } catch (Throwable ignored) {
                    // 试下一个
                }
            }
        }
        // 字段兜底：先实例字段，再静态字段（static final 的贴图常量很常见）
        ResourceLocation fallback = null;
        for (Class<?> c = renderer.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() != ResourceLocation.class) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object v = f.get(Modifier.isStatic(f.getModifiers()) ? null : renderer);
                    if (v instanceof ResourceLocation rl) {
                        if (!Modifier.isStatic(f.getModifiers())) {
                            return rl;
                        }
                        if (fallback == null) {
                            fallback = rl;
                        }
                    }
                } catch (Throwable ignored) {
                    // 试下一个
                }
            }
        }
        return fallback;
    }

    /**
     * 把傀儡复位到中性状态。
     *
     * <p>为什么要复位：很多渲染器会按实体的运动状态决定画什么（飞行翻滚、受击闪红、
     * 死亡动画、火焰层）。一个刚 {@code create} 出来的傀儡带着未初始化的朝向/计时，
     * 直接渲染可能得到翻转或错位的几何。这里只做「位置原点、朝向 0、计时 1」，
     * 不碰任何业务字段 —— 渲染器自己怎么画仍然由它决定。
     */
    private static void neutralise(Entity puppet) {
        if (puppet == null) {
            return;
        }
        try {
            puppet.setPos(0.0D, 0.0D, 0.0D);
            puppet.setYRot(0.0F);
            puppet.setXRot(0.0F);
            puppet.yRotO = 0.0F;
            puppet.xRotO = 0.0F;
            puppet.setDeltaMovement(0.0D, 0.0D, 0.0D);
            puppet.tickCount = 1;
            if (puppet instanceof LivingEntity living) {
                living.yBodyRot = 0.0F;
                living.yBodyRotO = 0.0F;
                living.yHeadRot = 0.0F;
                living.yHeadRotO = 0.0F;
                living.hurtTime = 0;
                living.deathTime = 0;
                living.setSwimming(false);
                living.setSprinting(false);
            }
        } catch (Throwable ignored) {
            // 复位失败不影响捕获（渲染器自己会决定画什么）
        }
    }

    /** 只剥最内层原因：反射把渲染器抛的异常包了一层，只说「调用失败」等于没说。 */
    static String root(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        String m = c.getClass().getSimpleName() + (c.getMessage() == null ? "" : ": " + c.getMessage());
        return m.length() > 200 ? m.substring(0, 200) : m;
    }

    /**
     * 单缓冲的 {@code MultiBufferSource}：只做两件事 ——
     * 记录第一次 {@code getBuffer} 时的 PoseStack 快照，然后给一个空消费者。
     *
     * <p>它存在的意义是「只摆姿势、不画东西」：我们想知道的只是渲染器在模型之前施加了什么变换。
     */
    static final class PreludeSource implements MultiBufferSource {
        private final PoseStack stack;
        volatile float[] matrix;

        PreludeSource(PoseStack stack) {
            this.stack = stack;
        }

        @Override
        public VertexConsumer getBuffer(RenderType type) {
            if (matrix == null) {
                try {
                    float[] m = new float[16];
                    stack.last().pose().get(m);
                    matrix = m;
                } catch (Throwable ignored) {
                    // 拿不到就算了
                }
            }
            return NoopConsumer.INSTANCE;
        }
    }

    /** 什么都不做的顶点消费者（只要姿势，不要几何） */
    static final class NoopConsumer implements VertexConsumer {
        static final NoopConsumer INSTANCE = new NoopConsumer();

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            return this;
        }

        @Override
        public VertexConsumer color(int red, int green, int blue, int alpha) {
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            return this;
        }

        @Override
        public void endVertex() {
            // no-op：我们只要姿势
        }

        @Override
        public void defaultColor(int red, int green, int blue, int alpha) {
            // no-op
        }

        @Override
        public void unsetDefaultColor() {
            // no-op
        }
    }

    /**
     * 捕获式网格的**唯一一根骨骼**：整块网格，无父链、无动画。
     *
     * <p>为什么不干脆不用姿态对象：{@link CrowdRenderer} 的每条渲染路径都会调
     * {@code mesh.pose.evaluate(...)} 拿每实例的骨骼旋转，捕获网格也得给出一份
     * 「恒为零」的姿态。给一个真实的 {@code Part} 而不是 null，是为了让
     * 编辑界面列部件时不会 NPE，也把「这块网格就是一根骨头」讲明白。
     */
    public static final class StaticBone implements ModelRig.Part {

        @Override
        public String name() {
            return "captured";
        }

        @Override
        public ModelRig.Part parent() {
            return null;
        }

        @Override
        public java.util.List<ModelRig.Part> children() {
            return java.util.List.of();
        }

        @Override
        public float pivotX() {
            return 0.0F;
        }

        @Override
        public float pivotY() {
            return 0.0F;
        }

        @Override
        public float pivotZ() {
            return 0.0F;
        }

        @Override
        public float rotX() {
            return 0.0F;
        }

        @Override
        public float rotY() {
            return 0.0F;
        }

        @Override
        public float rotZ() {
            return 0.0F;
        }

        @Override
        public float offX() {
            return 0.0F;
        }

        @Override
        public float offY() {
            return 0.0F;
        }

        @Override
        public float offZ() {
            return 0.0F;
        }

        @Override
        public boolean visible() {
            return true;
        }

        @Override
        public boolean skipCubes() {
            // 它的几何不是「模型方块」而是整块捕获网格，烘焙器不需要再画它
            return true;
        }

        @Override
        public boolean supportsOffset() {
            return false;
        }

        @Override
        public void translateAndRotate(PoseStack stack) {
            // 无变换
        }

        @Override
        public void renderOwn(PoseStack stack, VertexConsumer buffer, int light, int overlay) {
            // 无自己的几何
        }

        @Override
        public void resetBind() {
            // 无姿态
        }

        @Override
        public void saveState() {
            // 无状态
        }

        @Override
        public void restoreState() {
            // 无状态
        }
    }
}
