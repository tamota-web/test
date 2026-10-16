package dev.swordkingdom.horde.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.swordkingdom.horde.HordeConfig;
import dev.swordkingdom.horde.HordeMod;
import dev.swordkingdom.horde.crowd.ProjectileCatalog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Locale;

/**
 * 投掷物网格的**捕获式烘焙**。
 *
 * <h2>为什么不能用生物那条路</h2>
 * {@link EntityMeshBaker} 的前提是「渲染器是 {@code LivingEntityRenderer}，有 EntityModel + setupAnim」。
 * 投掷物**完全不是**这样：
 * <pre>
 *   原版箭        ArrowRenderer           （直接把贴图四边形推进 buffer，没有模型）
 *   雪球/蛋/珍珠  ThrownItemRenderer      （走物品模型 + 方块图集贴图）
 *   模组火球/弹幕 各种自定义 EntityRenderer（有的还是 GeckoLib 的 GeoEntityRenderer）
 * </pre>
 * 它们唯一共有的东西是：**都会往一个 {@code VertexConsumer} 里写顶点**。
 *
 * <h2>做法：把顶点截下来</h2>
 * 造一个能记录顶点的 {@link VertexConsumer}（见内部类 {@link Capture}，
 * 与 {@link RigBaker} 里那个截获器同类），然后让投掷物通过**它自己的渲染器**渲染一次：
 * <pre>
 *   dispatcher.render(entity, 0,0,0, yaw, partialTick, poseStack, captureSource, light)
 * </pre>
 * 截下来的顶点直接就是「位置/法线/UV/骨骼索引」这套 9 float 格式，
 * 骨骼数 = 1（整枚投掷物就是一个骨骼，不需要骨架，也不需要 setupAnim）。
 * 渲染管线因此完全复用：同一套 VAO + 实例化 + 缓冲纹理姿态。
 *
 * <h2>贴图</h2>
 * 优先 {@code renderer.getTextureLocation(entity)}（按**签名**反射找，不认方法名/SRG）；
 * 拿不到（典型：物品投掷物用物品模型 + 方块图集）就退化为
 * {@link TextureAtlas#LOCATION_BLOCKS}，并在日志里明确说明。
 *
 * <h2>失败退化</h2>
 * 这条链路依赖「渲染器不检查当前线程/世界状态」，在真实客户端上不一定成立
 * （见交付说明里的不确定性）。所以任何一步失败都会退化成**一个按该实体碰撞盒生成的方块网格**，
 * 至少保证「能看见投掷物在飞」，而不是静默不可见。
 */
public final class ProjectileBaker {

    /** 顶点格式与生物网格一致：位置 3 + 法线 3 + UV 2 + 骨骼索引 1 */
    public static final int FLOATS_PER_VERTEX = RigBaker.FLOATS_PER_VERTEX;

    private ProjectileBaker() {}

    /** 烘焙结果（沿用 {@link EntityMeshBaker.Mesh} 的字段语义，方便渲染器一视同仁地处理） */
    public static final class Mesh {
        public final float[] vertices;
        public final int[] indices;
        public final ResourceLocation texture;
        public final int boneCount = 1;
        public final float[] bonePivot = new float[]{0.0F, 0.0F, 0.0F, 0.0F};
        public final int[] transSlot = new int[0];
        public final int transCount = 0;
        public final int[] scaleSlot = new int[0];
        public final int scaleCount = 0;
        public final int maxChain = 1;
        public final boolean supported;
        /** 顶点是不是「它自己的渲染器画出来的」（false = 退化成了方块） */
        public final boolean captured;
        /** 贴图是不是退化成了方块图集 */
        public final boolean atlasTexture;
        /** 该网格的主轴（0=X/1=Y/2=Z）：用来把模型自己的朝向对齐到速度方向 */
        public final int principalAxis;
        /** 主轴指向 +轴还是 -轴 */
        public final boolean principalPositive;
        /** 模型包围盒（格），用于诊断与自动缩放判断 */
        public final float sizeX;
        public final float sizeY;
        public final float sizeZ;
        /** 顶点/三角形数（日志用） */
        public final int vertexCount;
        public final int triangleCount;

        Mesh(float[] vertices, int[] indices, ResourceLocation texture, boolean supported,
             boolean captured, boolean atlasTexture, int principalAxis, boolean principalPositive,
             float sizeX, float sizeY, float sizeZ) {
            this.vertices = vertices;
            this.indices = indices;
            this.texture = texture;
            this.supported = supported;
            this.captured = captured;
            this.atlasTexture = atlasTexture;
            this.principalAxis = principalAxis;
            this.principalPositive = principalPositive;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.vertexCount = vertices.length / FLOATS_PER_VERTEX;
            this.triangleCount = indices.length / 3;
        }
    }

    private static final Mesh UNSUPPORTED = new Mesh(new float[0], new int[0], null,
            false, false, false, 0, true, 0, 0, 0);

    /** 某个类型「有物品投掷物渲染器但拿不到物品」时只提示一次 */
    private static final java.util.Set<ResourceLocation> LOGGED = new java.util.HashSet<>();

    /**
     * 烘焙某投掷物类型的网格。
     *
     * @param typeId     投掷物实体类型
     * @param renderItem GUI 里指定的「发射用物品」（空 = 不设置）
     */
    public static Mesh bake(EntityType<?> typeId, String renderItem) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return UNSUPPORTED;
        }
        Entity puppet;
        try {
            puppet = typeId.create(mc.level);
        } catch (Throwable t) {
            return UNSUPPORTED;
        }
        if (puppet == null) {
            return UNSUPPORTED;
        }
        EntityRenderer<?> renderer;
        try {
            renderer = mc.getEntityRenderDispatcher().getRenderer(puppet);
        } catch (Throwable t) {
            return UNSUPPORTED;
        }
        if (renderer == null) {
            return UNSUPPORTED;
        }

        // 物品投掷物（雪球/蛋/珍珠……）的渲染器是从实体身上的 ItemStack 取模型的。
        // 优先级：GUI 里指定 > 按「同 id 的物品」自动配一个 > 空（渲染器可能抛异常，随后退化成方块）。
        applyItem(puppet, renderItem, ForgeRegistries.ENTITY_TYPES.getKey(typeId));

        ResourceLocation texture = textureOf(renderer, puppet);
        boolean atlas = false;
        if (texture == null) {
            if (!dev.swordkingdom.horde.HordeConfig.safeGet(
                    HordeConfig.COMMON.projectileAtlasFallback, true)) {
                HordeMod.LOGGER.warn("[horde] 投掷物 {} 拿不到贴图且已关闭图集退化，改用合成方块", typeId);
            }
            texture = TextureAtlas.LOCATION_BLOCKS;
            atlas = true;
        }

        // ---- 捕获式烘焙：让它自己的渲染器画一次，把顶点截下来 ----
        Capture cap = new Capture();
        boolean captured = false;
        try {
            PoseStack stack = new PoseStack();
            // 所有 RenderType 都灌进同一个截获器（投掷物很小，一次 draw call 更划算）
            MultiBufferSource source = new StaticSource(cap);
            EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
            renderPuppet(dispatcher, puppet, stack, source);
            captured = cap.vertexCount() > 0;
        } catch (Throwable t) {
            captured = false;
            // 打印**根因**：渲染器抛的异常被反射包在 InvocationTargetException 里，
            // 只说「调用失败」等于没说（上一次排查就卡在这里）
            Throwable cause = t;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            HordeMod.LOGGER.warn("[horde] 投掷物 {} 的渲染器顶点捕获失败（{}: {}），改用合成方块",
                    typeId, cause.getClass().getSimpleName(), cause.getMessage());
        }

        AABB box = puppet.getBoundingBox();
        float sizeX = (float) Math.max(0.05D, box.getXsize());
        float sizeY = (float) Math.max(0.05D, box.getYsize());
        float sizeZ = (float) Math.max(0.05D, box.getZsize());

        if (!captured) {
            // 退化：按该实体自己的碰撞盒造一个方块（属性来自实体本身，不是写死的数字）
            float[] verts = boxVertices(sizeX, sizeY, sizeZ);
            int[] idx = boxIndices();
            ResourceLocation log = ForgeRegistries.ENTITY_TYPES.getKey(typeId);
            if (log != null && LOGGED.add(log)) {
                HordeMod.LOGGER.warn("[horde] 投掷物 {} 无法从它自己的渲染器捕获顶点，"
                                + "已退化成 {}×{}×{} 的方块网格（能看见在飞，但形状不是它本来的样子）",
                        log, trim(Math.max(sizeX, Math.max(sizeY, sizeZ))),
                        trim(Math.max(sizeX, Math.max(sizeY, sizeZ))),
                        trim(Math.max(sizeX, Math.max(sizeY, sizeZ))));
            }
            return new Mesh(verts, idx, texture, true, false, atlas,
                    1, true, sizeX, sizeY, sizeZ);
        }

        float[] verts = cap.buildVertices();
        int[] idx = cap.buildIndices();
        float[] bounds = new float[6];
        measure(verts, bounds);
        int axis = principalAxis(bounds);
        boolean positive = principalPositive(bounds, axis);
        ResourceLocation log = ForgeRegistries.ENTITY_TYPES.getKey(typeId);
        HordeMod.LOGGER.info("[horde] 投掷物 {} 捕获式烘焙成功：顶点 {}、三角 {}、贴图 {}（{}）、"
                        + "主轴 {}、尺寸 {}×{}×{}",
                log == null ? typeId : log, verts.length / FLOATS_PER_VERTEX, idx.length / 3,
                texture, atlas ? "图集退化（渲染器没给贴图）"
                        : (TextureAtlas.LOCATION_BLOCKS.equals(texture)
                        ? "渲染器自己给的就是方块图集（物品投掷物就是这样）"
                        : "来自渲染器 getTextureLocation"),
                "XYZ".charAt(axis), trim(bounds[3] - bounds[0]), trim(bounds[4] - bounds[1]), trim(bounds[5] - bounds[2]));
        return new Mesh(verts, idx, texture, true, true, atlas, axis, positive,
                bounds[3] - bounds[0], bounds[4] - bounds[1], bounds[5] - bounds[2]);
    }

    /**
     * 调渲染器的 {@code render(...)}。
     *
     * {@code EntityRenderer#render} 是**泛型**方法 {@code render(T, ...)}，
     * 在线上环境名字是 SRG，所以这里按**参数形状**反射找：
     * 「第一个参数是实体、第 5 个是 PoseStack、第 6 个是 MultiBufferSource、最后一个是 int」。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void renderPuppet(EntityRenderDispatcher dispatcher, Entity puppet,
                                     PoseStack stack, MultiBufferSource source) throws Exception {
        java.lang.reflect.Method render = findRender(dispatcher.getClass(), puppet);
        if (render == null) {
            // 打印一次真实签名，便于定位（线上名是 SRG，不能按名字找）
            StringBuilder sb = new StringBuilder();
            for (Class<?> c = dispatcher.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    if (m.getName().toLowerCase(java.util.Locale.ROOT).contains("render")
                            && m.getParameterCount() >= 5) {
                        sb.append(m.getName()).append('(');
                        for (Class<?> p : m.getParameterTypes()) {
                            sb.append(p.getSimpleName()).append(',');
                        }
                        sb.append(") ");
                    }
                }
            }
            throw new NoSuchMethodException("找不到 EntityRenderDispatcher 的渲染方法。候选：" + sb);
        }
        render.setAccessible(true);
        render.invoke(dispatcher, renderArgs(render, puppet, stack, source));
    }

    /**
     * 按**参数形状**找 {@code EntityRenderDispatcher#render}。
     *
     * 线上方法名是 SRG，所以不能按名字找；参数个数在不同版本/映射下可能是 6 或 7
     * （有的版本省略了 partialTick）。这里按「尾部固定三段」匹配：
     * <pre>
     *   [实体, 若干数值(posX,posY,posZ,yaw[,partialTick]), PoseStack, MultiBufferSource, int]
     * </pre>
     */
    private static java.lang.reflect.Method findRender(Class<?> dispatcherClass, Entity puppet) {
        for (Class<?> c = dispatcherClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length < 6 || m.getReturnType() != void.class) {
                    continue;
                }
                if (!p[0].isInstance(puppet)) {
                    continue;
                }
                int n = p.length;
                if (p[n - 1] != int.class
                        || p[n - 2] != MultiBufferSource.class
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
                    return m;
                }
            }
        }
        return null;
    }

    /**
     * 按方法的真实参数类型拼实参：数值段按顺序填 (x, y, z, yaw)，多出来的那个位置填 partialTick。
     *
     * 这样 6 参和 7 参两种形状都能对上，不会因为「多/少一个 float」而 invoke 失败。
     */
    private static Object[] renderArgs(java.lang.reflect.Method render, Entity puppet,
                                       PoseStack stack, MultiBufferSource source) {
        Class<?>[] p = render.getParameterTypes();
        int n = p.length;
        Object[] args = new Object[n];
        args[0] = puppet;
        // 数值段：x, y, z, yaw, [partialTick]
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
     * 按**签名**找 {@code getTextureLocation(T)} —— 不按名字找（线上是 SRG 名），
     * 也不限定参数类型必须正好是 Entity（原版是 {@code getTextureLocation(T extends Entity)}）。
     */
    private static ResourceLocation textureOf(EntityRenderer<?> renderer, Entity puppet) {
        for (Class<?> c = renderer.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() != 1 || m.getReturnType() != ResourceLocation.class) {
                    continue;
                }
                if (!m.getParameterTypes()[0].isInstance(puppet)) {
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
        return null;
    }

    /**
     * 把「发射用物品」装到傀儡实体身上。
     *
     * 认的是「实体上有一个 {@code ItemStack} 字段」（原版 {@code ThrowableItemProjectile#setItem}），
     * 不认类名。玩家没指定时，再试一次**自动配对**：找一个与实体 id 同名的物品
     * （{@code minecraft:snowball} 投射物 ←→ {@code minecraft:snowball} 物品）——
     * 物品投掷物基本都这样命名，所以绝大多数情况不用玩家操心；
     * 两条都不成就留空（渲染器会画出空物品/抛异常，我们随后退化成方块）。
     */
    private static void applyItem(Entity puppet, String renderItem, ResourceLocation typeId) {
        net.minecraft.world.item.Item item = null;
        if (renderItem != null && !renderItem.isEmpty()) {
            ResourceLocation id = ResourceLocation.tryParse(renderItem);
            if (id != null) {
                item = ForgeRegistries.ITEMS.getValue(id);
            }
        }
        if (item == null && typeId != null) {
            item = ForgeRegistries.ITEMS.getValue(typeId);
        }
        if (item == null) {
            return;
        }
        // 原版路径：ThrowableItemProjectile（雪球/蛋/珍珠/药水……）有 setItem
        if (puppet instanceof net.minecraft.world.entity.projectile.ThrowableItemProjectile itemProj) {
            try {
                itemProj.setItem(new ItemStack(item));
                return;
            } catch (Throwable ignored) {
                // 落到下面的通用反射路径
            }
        }
        // 通用路径：任何实体只要身上有 ItemStack 字段就试一次（不限原版基类，模组投掷物也吃这一条）
        for (Class<?> c = puppet.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (f.getType() != ItemStack.class
                        || java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    f.set(puppet, new ItemStack(item));
                    return;
                } catch (Throwable ignored) {
                    // 试下一个
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 几何分析
    // ------------------------------------------------------------------

    /** 顶点包围盒（格） */
    private static void measure(float[] verts, float[] out) {
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        float maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < verts.length; i += FLOATS_PER_VERTEX) {
            float x = verts[i];
            float y = verts[i + 1];
            float z = verts[i + 2];
            if (x < minX) minX = x;
            if (y < minY) minY = y;
            if (z < minZ) minZ = z;
            if (x > maxX) maxX = x;
            if (y > maxY) maxY = y;
            if (z > maxZ) maxZ = z;
        }
        out[0] = minX;
        out[1] = minY;
        out[2] = minZ;
        out[3] = maxX;
        out[4] = maxY;
        out[5] = maxZ;
    }

    /** 主轴：包围盒里最长的那一维（箭是长条，所以主轴就是箭头方向） */
    public static int principalAxis(float[] bounds) {
        float sx = bounds[3] - bounds[0];
        float sy = bounds[4] - bounds[1];
        float sz = bounds[5] - bounds[2];
        if (sx >= sy && sx >= sz) {
            return 0;
        }
        return sy >= sz ? 1 : 2;
    }

    /** 主轴指向 + 还是 -（包围盒中心相对原点） */
    public static boolean principalPositive(float[] bounds, int axis) {
        float c = (bounds[axis] + bounds[axis + 3]) * 0.5F;
        return c >= 0.0F;
    }

    private static String trim(float v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    // ------------------------------------------------------------------
    // 合成方块（退化路径）
    // ------------------------------------------------------------------

    /** 按给定尺寸造一个以原点为中心的方块（法线朝外） */
    private static float[] boxVertices(float sx, float sy, float sz) {
        float x0 = -sx * 0.5F;
        float y0 = -sy * 0.5F;
        float z0 = -sz * 0.5F;
        float x1 = sx * 0.5F;
        float y1 = sy * 0.5F;
        float z1 = sz * 0.5F;
        // 6 面 × 4 顶点，每顶点 (pos3, normal3, uv2, bone1)
        float[][] faces = {
                {x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 0, 0, 1},
                {x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, 0, 0, -1},
                {x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, 1, 0, 0},
                {x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, -1, 0, 0},
                {x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, 0, 1, 0},
                {x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, 0, -1, 0},
        };
        float[][] uvs = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        float[] out = new float[6 * 4 * FLOATS_PER_VERTEX];
        int p = 0;
        for (float[] f : faces) {
            for (int k = 0; k < 4; k++) {
                out[p++] = f[k * 3];
                out[p++] = f[k * 3 + 1];
                out[p++] = f[k * 3 + 2];
                out[p++] = f[12];
                out[p++] = f[13];
                out[p++] = f[14];
                out[p++] = uvs[k][0];
                out[p++] = uvs[k][1];
                out[p++] = 0.0F;
            }
        }
        return out;
    }

    private static int[] boxIndices() {
        int[] idx = new int[6 * 6];
        for (int f = 0; f < 6; f++) {
            int b = f * 4;
            int o = f * 6;
            idx[o] = b;
            idx[o + 1] = b + 1;
            idx[o + 2] = b + 2;
            idx[o + 3] = b;
            idx[o + 4] = b + 2;
            idx[o + 5] = b + 3;
        }
        return idx;
    }

    // ------------------------------------------------------------------
    // 顶点截获
    // ------------------------------------------------------------------

    /**
     * 顶点截获器。
     *
     * 与 {@link RigBaker} 里那个私有截获器是同一套思路，但这里有两点不同：
     *   1. 它必须能当 {@code MultiBufferSource} 的 buffer 用，所以把
     *      {@code VertexConsumer} 的 7 个基础方法全部原样实现（不依赖任何默认实现）；
     *   2. 它会顺便**按法线修正三角形绕序** —— 有些渲染器对某些面是反向写的，
     *      直接照抄在开了背面剔除的管线里会出现「缺面」。
     */
    public static final class Capture implements VertexConsumer {

        private float[] verts = new float[4096];
        private int vn;
        private int[] idx = new int[2048];
        private int in;

        private float px;
        private float py;
        private float pz;
        private float nx;
        private float ny;
        private float nz;
        private float u;
        private float v;
        private boolean hasNormal;

        private final int[] quad = new int[4];
        private int quadSize;

        /**
         * 一个「面」由几个顶点组成：四边形（原版 {@code GL_QUADS}）= 4，三角形 = 3。
         *
         * <p>为什么必须是**外部告诉**它的：{@code VertexConsumer} 只有 {@code endVertex()}，
         * 没有任何「本图元结束」的信号 —— 写到第 3 个顶点时，我们无法知道后面还有没有第 4 个。
         * 唯一的真实依据是绘制模式，而它写在 {@code RenderType} 上
         * （{@link StaticSource#getBuffer} 会读 {@code mode()} 转告它）。
         *
         * <p>默认 4：生物/物品/GeckoLib 模型的方块面**全是四边形**，实体渲染器的 RenderType
         * 也都是 {@code QUADS}。以前这里是「先按 4 个一组、又在第 3 个顶点提前收口」的写法，
         * 结果是**每个四边形只出一个三角形**，第 4 个顶点还会跟下一个面的前两个顶点拼成
         * 跨面垃圾三角形（{@code rendererCoverageHarness} 的用例 c 就是抓这个的）。
         */
        private int primitiveSize = 4;

        /** 顶点数上限（默认无上限，行为与以前完全一致）。见 {@link #setMaxVertices} */
        private int maxVertices = Integer.MAX_VALUE;
        /** 是否已经因为触到上限而丢掉过顶点 */
        private boolean overflow;

        public int vertexCount() {
            return vn / FLOATS_PER_VERTEX;
        }

        /**
         * 设置顶点数上限。
         *
         * <p>为什么截获器自己要有限制：实体渲染器是**别人写的代码**，它想画多少顶点我们事先不知道。
         * 一只巨兽/一个失控的渲染器可以轻易画到几百万顶点，而截获器是「来一个存一个」——
         * 内存会在我们有机会检查之前就爆掉。所以上限必须写在写入路径上：
         * 到顶之后 {@link #endVertex()} 变成空操作（不再记顶点、也不再组三角形），
         * 于是内存有界、索引仍然自洽，调用方事后读 {@link #overflowed()} 决定是否丢弃整个网格。
         *
         * <p>默认无上限 —— 原有的投掷物/装备链路行为一字不变。
         */
        public void setMaxVertices(int max) {
            this.maxVertices = Math.max(1, max);
        }

        /** 本次截获是否触到了顶点上限（触到了就说明网格不完整，必须整块丢弃） */
        public boolean overflowed() {
            return overflow;
        }

        /**
         * 按绘制模式确定「几个顶点组成一个面」。
         *
         * @param mode 该 RenderType 的 {@code VertexFormat.Mode}（{@code QUADS} / {@code TRIANGLES}）；
         *             其它模式（线段之类）不支持，保持默认
         */
        public void setPrimitiveMode(com.mojang.blaze3d.vertex.VertexFormat.Mode mode) {
            if (mode == com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLES) {
                primitiveSize = 3;
            } else if (mode == com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS) {
                primitiveSize = 4;
            }
        }

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            this.px = (float) x;
            this.py = (float) y;
            this.pz = (float) z;
            return this;
        }

        @Override
        public VertexConsumer color(int red, int green, int blue, int alpha) {
            return this;
        }

        @Override
        public VertexConsumer uv(float uu, float vv) {
            this.u = uu;
            this.v = vv;
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int uu, int vv) {
            return this;
        }

        @Override
        public VertexConsumer uv2(int uu, int vv) {
            return this;
        }

        @Override
        public VertexConsumer normal(float xx, float yy, float zz) {
            this.nx = xx;
            this.ny = yy;
            this.nz = zz;
            this.hasNormal = true;
            return this;
        }

        @Override
        public void endVertex() {
            // 上限保护：到顶之后直接忽略后续顶点（内存有界、索引仍自洽，由调用方判定失败）
            if (vn / FLOATS_PER_VERTEX >= maxVertices) {
                overflow = true;
                return;
            }
            ensure(1);
            int vi = vn / FLOATS_PER_VERTEX;
            verts[vn++] = px;
            verts[vn++] = py;
            verts[vn++] = pz;
            verts[vn++] = hasNormal ? nx : 0.0F;
            verts[vn++] = hasNormal ? ny : 1.0F;
            verts[vn++] = hasNormal ? nz : 0.0F;
            verts[vn++] = u;
            verts[vn++] = v;
            verts[vn++] = 0.0F;   // 骨骼索引：整枚投掷物 = 1 个骨骼
            hasNormal = false;

            quad[quadSize++] = vi;
            if (quadSize == primitiveSize) {
                if (primitiveSize == 4) {
                    // 四边形：两个三角形
                    emitTriangle(quad[0], quad[1], quad[2]);
                    emitTriangle(quad[0], quad[2], quad[3]);
                } else {
                    // 三角形（该 RenderType 的绘制模式就是 GL_TRIANGLES）
                    emitTriangle(quad[0], quad[1], quad[2]);
                }
                quadSize = 0;
            }
        }

        /** 按顶点法线修正绕序：绕序法线与顶点法线相反时翻转，避免开剔除后缺面 */
        private void emitTriangle(int a, int b, int c) {
            float ax = verts[a * FLOATS_PER_VERTEX];
            float ay = verts[a * FLOATS_PER_VERTEX + 1];
            float az = verts[a * FLOATS_PER_VERTEX + 2];
            float bx = verts[b * FLOATS_PER_VERTEX];
            float by = verts[b * FLOATS_PER_VERTEX + 1];
            float bz = verts[b * FLOATS_PER_VERTEX + 2];
            float cx = verts[c * FLOATS_PER_VERTEX];
            float cy = verts[c * FLOATS_PER_VERTEX + 1];
            float cz = verts[c * FLOATS_PER_VERTEX + 2];
            float e1x = bx - ax;
            float e1y = by - ay;
            float e1z = bz - az;
            float e2x = cx - ax;
            float e2y = cy - ay;
            float e2z = cz - az;
            float fx = e1y * e2z - e1z * e2y;
            float fy = e1z * e2x - e1x * e2z;
            float fz = e1x * e2y - e1y * e2x;
            float nxs = verts[a * FLOATS_PER_VERTEX + 3] + verts[b * FLOATS_PER_VERTEX + 3]
                    + verts[c * FLOATS_PER_VERTEX + 3];
            float nys = verts[a * FLOATS_PER_VERTEX + 4] + verts[b * FLOATS_PER_VERTEX + 4]
                    + verts[c * FLOATS_PER_VERTEX + 4];
            float nzs = verts[a * FLOATS_PER_VERTEX + 5] + verts[b * FLOATS_PER_VERTEX + 5]
                    + verts[c * FLOATS_PER_VERTEX + 5];
            boolean flip = (fx * nxs + fy * nys + fz * nzs) < 0.0F;
            if (idx.length < in + 3) {
                int[] bigger = new int[Math.max(in + 3, idx.length * 2)];
                System.arraycopy(idx, 0, bigger, 0, in);
                idx = bigger;
            }
            if (flip) {
                idx[in++] = a;
                idx[in++] = c;
                idx[in++] = b;
            } else {
                idx[in++] = a;
                idx[in++] = b;
                idx[in++] = c;
            }
        }

        @Override
        public void defaultColor(int red, int green, int blue, int alpha) {
            // no-op
        }

        @Override
        public void unsetDefaultColor() {
            // no-op
        }

        private void ensure(int extraVerts) {
            int need = vn + extraVerts * FLOATS_PER_VERTEX;
            if (verts.length < need) {
                float[] bigger = new float[Math.max(need, verts.length * 2)];
                System.arraycopy(verts, 0, bigger, 0, vn);
                verts = bigger;
            }
        }

        /** 顶点数组（只保留实际写过的部分） */
        public float[] buildVertices() {
            float[] out = new float[vn];
            System.arraycopy(verts, 0, out, 0, vn);
            return out;
        }

        public int[] buildIndices() {
            int[] out = new int[in];
            System.arraycopy(idx, 0, out, 0, in);
            return out;
        }
    }

    /**
     * 单缓冲的 {@code MultiBufferSource}：所有 RenderType 都灌进同一个截获器。
     *
     * 这里保留它是为了**离线验证**（harness 里模拟「渲染器画了两批顶点」的情形），
     * 真实烘焙用的是原版现成的 {@code MultiBufferSource.immediate}。
     *
     * 为什么可以合并：投掷物很小、材质通常只有一层，顶点量在几百~几千级别，
     * 合并后一次 draw call 反而更划算（代价是丢掉「按 RenderType 分组/混合」的差别，
     * 对箭/火球/雪球这种不透明或简单半透明的模型可以接受）。
     */
    public static final class StaticSource implements MultiBufferSource {
        private final VertexConsumer capture;

        public StaticSource(VertexConsumer capture) {
            this.capture = capture;
        }

        @Override
        public VertexConsumer getBuffer(RenderType type) {
            // 把绘制模式转告截获器：一个「面」是四边形还是三角形，只有 RenderType 知道，
            // 而截获器光靠顶点序列是分不出来的（见 Capture#primitiveSize 的说明）。
            if (type != null && capture instanceof Capture cap) {
                try {
                    cap.setPrimitiveMode(type.mode());
                } catch (Throwable ignored) {
                    // 拿不到模式就保持默认（四边形）—— 实体渲染器基本都是它
                }
            }
            return capture;
        }
    }
}
