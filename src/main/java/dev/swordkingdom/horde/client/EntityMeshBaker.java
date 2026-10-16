package dev.swordkingdom.horde.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 通用实体模型烘焙器（客户端单例部分）。
 *
 * 负责「拿到实体 → 模型 → 贴图 → 渲染器自身的局部变换」，并保留一个不会被加入世界的
 * **傀儡实体**，供 {@link CrowdPose} 每帧驱动模型自己的 {@code setupAnim}。
 *
 * 「渲染器自身的局部变换」= 原版 {@code LivingEntityRenderer.render} 里
 * {@code setupRotations} 之后的那两步：
 * <pre>
 *   this.scale(entity, poseStack, partialTicks);          // 很多模组生物在这里放大/缩小
 *   poseStack.translate(0, -1.501, 0);
 * </pre>
 * 外加 {@code EntityRenderer.getRenderOffset()}（公开方法）。这两者都按**方法签名**反射取得，
 * 不看名字（SRG 名问题），拿到的缩放/偏移作为 uniform 下发，于是巨大的恐龙和微小的生物
 * 在群集里也和真实实体一样大、一样偏移。
 *
 * <h2>「不是 LivingEntityRenderer」的那一大类，现在有三条路（顺序固定）</h2>
 * <ol>
 *   <li><b>渲染器实例字段里的 {@code EntityModel}</b>：原版末影龙就是这样 ——
 *       {@code EnderDragonRenderer} 只有 {@code private final DragonModel model} 这个字段，
 *       既不是 {@code LivingEntityRenderer}，也没有 {@code getModel()} 那样的取值方法。
 *       老代码只找「名字像模型的无参方法」，于是判定失败、整个 boss 不显示（本次修的就是它）。</li>
 *   <li><b>geo 模型</b>（GeckoLib 形状）：保持原样，一个字没动。</li>
 *   <li><b>顶点捕获</b>（{@link RendererCapture}）：以上都没有时，让它自己的渲染器画一次、
 *       把顶点截下来做成「1 根骨骼的静态网格」，接进同一套实例化管线。
 *       这条路的唯一目的是**保证永不不可见**（代价是没有骨骼动画）。</li>
 * </ol>
 * 三条都失败时**必须报 WARN 并写清各自的根因**，绝不静默 —— 「实体不显示、日志里什么都没有」
 * 是上一轮排查最大的坑。
 */
public final class EntityMeshBaker {

    /** 顶点结构见 {@link RigBaker#FLOATS_PER_VERTEX} */
    public static final int FLOATS_PER_VERTEX = RigBaker.FLOATS_PER_VERTEX;

    private EntityMeshBaker() {}

    /** 烘焙结果 */
    public static final class Mesh {
        public final float[] vertices;
        public final int[] indices;
        public final ResourceLocation texture;
        public final int boneCount;
        public final float[] bonePivot;
        public final int[] transSlot;
        public final int transCount;
        /** 逐骨骼缩放通道（GeckoLib 的 GeoBone 会做缩放；其它体系为空） */
        public final int[] scaleSlot;
        public final int scaleCount;
        public final int maxChain;
        public final CrowdPose pose;
        public final boolean supported;
        /** 是否走的 GeckoLib(geo) 那条路 —— 用来把日志说准（那条路暂时只出静止姿态） */
        public final boolean geo;
        /** renderer.scale() 施加的缩放 */
        public final float renderScaleX;
        public final float renderScaleY;
        public final float renderScaleZ;
        /** -getRenderOffset() */
        public final float renderOffsetX;
        public final float renderOffsetY;
        public final float renderOffsetZ;

        Mesh(RigBaker.Rig rig, ResourceLocation texture, CrowdPose pose,
             float scaleX, float scaleY, float scaleZ,
             float offsetX, float offsetY, float offsetZ) {
            this(rig, texture, pose, scaleX, scaleY, scaleZ, offsetX, offsetY, offsetZ, false);
        }

        Mesh(RigBaker.Rig rig, ResourceLocation texture, CrowdPose pose,
             float scaleX, float scaleY, float scaleZ,
             float offsetX, float offsetY, float offsetZ, boolean geo) {
            this.vertices = rig.vertices;
            this.indices = rig.indices;
            this.boneCount = rig.boneCount;
            this.bonePivot = rig.pivot4;
            this.transSlot = rig.transSlot;
            this.transCount = rig.transCount;
            this.scaleSlot = rig.scaleSlot;
            this.scaleCount = rig.scaleCount;
            this.maxChain = rig.maxChain;
            this.texture = texture;
            this.pose = pose;
            this.supported = true;
            this.geo = geo;
            this.renderScaleX = scaleX;
            this.renderScaleY = scaleY;
            this.renderScaleZ = scaleZ;
            this.renderOffsetX = offsetX;
            this.renderOffsetY = offsetY;
            this.renderOffsetZ = offsetZ;
        }

        /**
         * **捕获式网格**（{@link RendererCapture} 那条路）：不是骨架，而是「渲染器画出来的那一堆顶点」。
         *
         * <p>所以：骨骼数恒为 1（整块网格），没有位移/缩放通道，最深链 = 1（没有父链），
         * 姿态对象只输出零旋转（见 {@link RendererCapture.StaticBone}）。
         *
         * <p>为什么 {@code geo = true}：捕获下来的顶点**已经带上了渲染器自己的全部变换**
         * （原版那套 {@code scale(-1,-1,1)} + {@code translate(0,-1.501,0)}、朝向、缩放……）。
         * {@code geo} 在着色器里代表「顶点已是最终朝向，不要再翻转、不要再减 1.501」，
         * 正好是捕获网格需要的语义（geo 的翻转/高度偏移默认是「不翻、偏移 0」）。
         */
        Mesh(float[] vertices, int[] indices, ResourceLocation texture, CrowdPose pose,
             float scaleX, float scaleY, float scaleZ,
             float offsetX, float offsetY, float offsetZ) {
            this.vertices = vertices;
            this.indices = indices;
            this.texture = texture;
            this.boneCount = 1;
            this.bonePivot = new float[]{0.0F, 0.0F, 0.0F, 0.0F};
            this.transSlot = new int[]{-1};
            this.transCount = 0;
            this.scaleSlot = new int[]{-1};
            this.scaleCount = 0;
            this.maxChain = 1;
            this.pose = pose;
            this.supported = true;
            this.geo = true;
            this.renderScaleX = scaleX;
            this.renderScaleY = scaleY;
            this.renderScaleZ = scaleZ;
            this.renderOffsetX = offsetX;
            this.renderOffsetY = offsetY;
            this.renderOffsetZ = offsetZ;
        }

        private Mesh() {
            this.vertices = new float[0];
            this.indices = new int[0];
            this.texture = null;
            this.boneCount = 0;
            this.bonePivot = new float[0];
            this.transSlot = new int[0];
            this.transCount = 0;
            this.scaleSlot = new int[0];
            this.scaleCount = 0;
            this.maxChain = 0;
            this.pose = null;
            this.supported = false;
            this.geo = false;
            this.renderScaleX = 1.0F;
            this.renderScaleY = 1.0F;
            this.renderScaleZ = 1.0F;
            this.renderOffsetX = 0.0F;
            this.renderOffsetY = 0.0F;
            this.renderOffsetZ = 0.0F;
        }
    }

    private static final Mesh UNSUPPORTED = new Mesh();

    /**
     * 最近一次 {@link #bakeFromRenderer} 的**逐条说明**。
     *
     * <p>为什么要把它留成一个可读的值，而不是只打日志：日志在真机上会被淹没，而「这个类型为什么
     * 不可见」是需要**结构化的证据**才能查的（拿到哪些候选、各自为何失败）。离线验证
     * （{@code rendererCoverageHarness}）也是直接断言这个列表来保证「失败必须说话」这条约束。
     * 只有渲染线程会写它。
     */
    private static volatile List<String> lastReasons = List.of();

    /** 见 {@link #lastReasons} */
    public static List<String> lastReasons() {
        return lastReasons;
    }

    /** 该实体类型是否可以走群集实例化渲染（仅支持有 LivingEntityRenderer 的生物）。 */
    public static boolean canRender(EntityType<?> type) {
        // 注意：不能用 EntityType#getBaseClass()，它在运行时并不返回实体类，恒为 Entity.class。
        // 以「实际创建一次实体」来判断最可靠。
        try {
            ClientLevel level = Minecraft.getInstance().level;
            if (level == null) {
                return false;
            }
            return type.create(level) instanceof LivingEntity;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 烘焙某类型用于群集渲染的网格；{@code unitTypeId} 是「群集单位自身的类型」，用来查 GUI 覆盖 */
    public static Mesh bake(EntityType<?> type) {
        return bake(type, net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(type));
    }

    public static Mesh bake(EntityType<?> type, net.minecraft.resources.ResourceLocation unitTypeId) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return UNSUPPORTED;
        }

        // 傀儡实体：只用来喂给 setupAnim，绝不加入世界
        Entity entity;
        try {
            entity = type.create(level);
        } catch (Throwable t) {
            return UNSUPPORTED;
        }
        if (!(entity instanceof LivingEntity puppet)) {
            return UNSUPPORTED;
        }

        Object renderer;
        try {
            renderer = mc.getEntityRenderDispatcher().getRenderer(puppet);
        } catch (Throwable t) {
            return UNSUPPORTED;
        }
        if (renderer == null) {
            return UNSUPPORTED;
        }
        return bakeFromRenderer(renderer, puppet, unitTypeId);
    }

    /**
     * 三种渲染器形状的统一入口。
     *
     * <p>之所以把「拿到渲染器之后」的一切单独抽出来，是因为这一段**完全不依赖 Minecraft 客户端单例**：
     * 模型/贴图/变换全靠反射和模型对象本身，于是离线验证（{@code rendererCoverageHarness}）
     * 可以用替身渲染器把每一条分支都真的跑一遍 —— 线上跑的是同一份代码。
     *
     * @param renderer    实体渲染器（真实类型是 {@code EntityRenderer}，离线验证时可以是任何替身对象）
     * @param puppet      傀儡实体（可以为 null：离线的替身渲染器没有真实体可喂）
     * @param unitTypeId  群集单位自身的类型 id（查 GUI 覆盖用）
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Mesh bakeFromRenderer(Object renderer, Entity puppet, ResourceLocation unitTypeId) {
        if (renderer == null) {
            return UNSUPPORTED;
        }
        // ① 主路径：LivingEntityRenderer（原版绝大多数生物 + MobRenderer 系模组生物）
        if (renderer instanceof LivingEntityRenderer livingRenderer) {
            lastReasons = List.of("走了 LivingEntityRenderer 主路径");
            return bakeLivingRenderer(livingRenderer, puppet, unitTypeId);
        }

        // 后面三条路各自把「为什么失败」记下来，最后一次性打出去（失败必须说话）
        List<String> reasons = new ArrayList<>();

        // ② 渲染器实例字段里的 EntityModel（原版末影龙：private final DragonModel model）
        Mesh byField = bakeEntityModelField(renderer, puppet, unitTypeId, reasons);
        if (byField != null) {
            lastReasons = List.copyOf(reasons);
            return byField;
        }
        // ③ geo（GeckoLib）形状 —— 老路径，未改动
        Mesh byGeo = bakeGeoRenderer(renderer, puppet, unitTypeId, reasons);
        if (byGeo != null) {
            lastReasons = List.copyOf(reasons);
            return byGeo;
        }
        // ④ 捕获兜底：让它自己的渲染器画一次，顶点截下来当静态网格（保证永不不可见）
        Mesh byCapture = bakeByCapture(renderer, puppet, unitTypeId, reasons);
        if (byCapture != null) {
            lastReasons = List.copyOf(reasons);
            return byCapture;
        }

        lastReasons = List.copyOf(reasons);
        dev.swordkingdom.horde.HordeMod.LOGGER.warn("[horde] {} 的渲染器 {} 无法烘焙模型："
                        + "既不是 LivingEntityRenderer、实例字段里没有 EntityModel、没有 geo 模型、顶点捕获也失败。"
                        + "逐条原因：{}",
                unitTypeId, renderer.getClass().getName(),
                reasons.isEmpty() ? "（没有任何候选）" : String.join("；", reasons));
        return UNSUPPORTED;
    }

    /** 记一条失败原因（限长，避免把日志刷爆） */
    private static void reason(List<String> reasons, String text) {
        if (reasons.size() < 12) {
            reasons.add(text);
        }
    }

    // ------------------------------------------------------------------
    // ① 主路径：LivingEntityRenderer
    // ------------------------------------------------------------------

    /**
     * {@code LivingEntityRenderer} 主路径：模型与贴图都有公开方法，直接取。
     *
     * <p>以前这一支失败时是**静默返回**的（只有渲染器那边一句「模型烘焙失败，已跳过」），
     * 排查「某个原版飞行生物不显示」时根本看不出是哪一步断的。现在失败也把根因打出来。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Mesh bakeLivingRenderer(LivingEntityRenderer livingRenderer, Entity puppet,
                                           ResourceLocation unitTypeId) {
        // bake() 那条线上傀儡一定是生物；这里挡住的是「离线验证拿不到实体」这类情况
        if (!(puppet instanceof LivingEntity livingPuppet)) {
            dev.swordkingdom.horde.HordeMod.LOGGER.warn("[horde] {} 的渲染器 {} 是 LivingEntityRenderer，"
                    + "但替身不是生物实体，无法烘焙", unitTypeId, livingRenderer.getClass().getName());
            return UNSUPPORTED;
        }
        EntityModel model;
        ResourceLocation texture;
        try {
            model = livingRenderer.getModel();
            texture = livingRenderer.getTextureLocation(livingPuppet);
        } catch (Throwable t) {
            dev.swordkingdom.horde.HordeMod.LOGGER.warn("[horde] {} 的 LivingEntityRenderer {} 取模型/贴图失败：{}",
                    unitTypeId, livingRenderer.getClass().getName(), RendererCapture.root(t));
            return UNSUPPORTED;
        }
        if (model == null) {
            dev.swordkingdom.horde.HordeMod.LOGGER.warn("[horde] {} 的 LivingEntityRenderer {} 的 getModel() 返回 null",
                    unitTypeId, livingRenderer.getClass().getName());
            return UNSUPPORTED;
        }

        float[] scale = new float[]{1.0F, 1.0F, 1.0F};
        float[] offset = new float[]{0.0F, 0.0F, 0.0F};
        captureRenderTransform(livingRenderer, livingPuppet, scale, offset);
        applyOverride(unitTypeId, scale, offset);

        RigBaker.Rig rig;
        try {
            rig = RigBaker.bake(model, livingPuppet, unitTypeId);
        } catch (Throwable t) {
            dev.swordkingdom.horde.HordeMod.LOGGER.warn("[horde] {} 的模型 {} 烘焙抛异常：{}",
                    unitTypeId, model.getClass().getName(), RendererCapture.root(t));
            return UNSUPPORTED;
        }
        if (rig == null || rig.indices.length == 0 || rig.vertices.length == 0) {
            dev.swordkingdom.horde.HordeMod.LOGGER.warn("[horde] {} 的模型 {} 用 RigBaker 烘焙不出几何"
                            + "（部件系统未识别，或该模型一个部件都不可见）",
                    unitTypeId, model.getClass().getName());
            return UNSUPPORTED;
        }

        // 动作/技能动画：按「形状」发现该实体的动画状态 API（对任何模组通用）
        dev.swordkingdom.horde.action.ActionDriver driver =
                dev.swordkingdom.horde.action.ActionDriver.of(livingPuppet.getClass());
        if (driver != null && driver.usable()) {
            dev.swordkingdom.horde.HordeMod.LOGGER.info("[horde] {} 支持 {} 种动作/技能动画，"
                    + "群集单位会像真实实体一样播放", puppet.getType().getDescription().getString(), driver.actionCount());
        }

        return new Mesh(rig, texture, new CrowdPose(model, livingPuppet, rig.parts, driver),
                scale[0], scale[1], scale[2], offset[0], offset[1], offset[2]);
    }

    // ------------------------------------------------------------------
    // ② 非 LivingEntityRenderer：渲染器实例字段里的 EntityModel
    // ------------------------------------------------------------------

    /**
     * 「模型藏在渲染器实例字段里」那一路（原版末影龙 {@code EnderDragonRenderer} 就是它）。
     *
     * <p>原版 {@code EnderDragonRenderer}（javap 确认）：
     * <pre>
     *   public class EnderDragonRenderer extends EntityRenderer&lt;EnderDragon&gt; {
     *       private final EnderDragonRenderer$DragonModel model;   // ← 模型只在字段里
     *       public ResourceLocation getTextureLocation(EnderDragon);   // ← 贴图有方法
     *   }
     *   class DragonModel extends EntityModel&lt;EnderDragon&gt; { ... }
     * </pre>
     * 也就是说「不是 LivingEntityRenderer」+「没有 getModel()」两件事同时成立，老代码两条路都认不出来。
     *
     * <p>做法：扫**实例字段（含父类、含 private，setAccessible）**，只要字段的**值**可以赋给
     * {@code EntityModel} 就当作模型候选，然后真的交给 {@link RigBaker} 试烘焙 ——
     * 能拆出几何的才算数（避免被不相干的字段带偏）。贴图优先「单参返回 {@code ResourceLocation} 的方法」，
     * 其次该类里的 {@code ResourceLocation} 字段。
     *
     * <p>另外：字段里的模型可能其实是 geo（Bedrock）形状的（有模组把 GeckoLib 模型塞在渲染器字段里）。
     * 那种情况按 geo 的朝向约定处理，并且能拿到采样表就照样出动画（见 {@link #poseFor}）。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Mesh bakeEntityModelField(Object renderer, Entity puppet, ResourceLocation unitTypeId,
                                            List<String> reasons) {
        List<Field> fields = new ArrayList<>();
        List<EntityModel<?>> models = new ArrayList<>();
        List<Integer> scores = new ArrayList<>();
        int scanned = 0;
        for (Class<?> c = renderer.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) {
                    continue;
                }
                scanned++;
                Object value;
                try {
                    f.setAccessible(true);
                    value = f.get(renderer);
                } catch (Throwable ignored) {
                    continue;
                }
                if (!(value instanceof EntityModel<?> em)) {
                    continue;
                }
                // 声明类型就是 EntityModel（子类）的更可信；字段名里带 model 的再加一档
                int score = EntityModel.class.isAssignableFrom(f.getType()) ? 2 : 0;
                if (f.getName().toLowerCase(Locale.ROOT).contains("model")) {
                    score++;
                }
                fields.add(f);
                models.add(em);
                scores.add(score);
            }
        }
        if (models.isEmpty()) {
            reason(reasons, "实例字段里没有 EntityModel（扫了 " + scanned + " 个字段）");
            return null;
        }
        // 稳定排序：分高的在前（不改变同分候选的相对顺序）
        for (int i = 1; i < models.size(); i++) {
            EntityModel<?> cm = models.get(i);
            Field cf = fields.get(i);
            int cs = scores.get(i);
            int j = i - 1;
            while (j >= 0 && scores.get(j) < cs) {
                models.set(j + 1, models.get(j));
                fields.set(j + 1, fields.get(j));
                scores.set(j + 1, scores.get(j));
                j--;
            }
            models.set(j + 1, cm);
            fields.set(j + 1, cf);
            scores.set(j + 1, cs);
        }

        ResourceLocation texture = RendererCapture.textureOf(renderer, puppet);
        LivingEntity living = puppet instanceof LivingEntity le ? le : null;
        dev.swordkingdom.horde.action.ActionDriver driver =
                living == null ? null : dev.swordkingdom.horde.action.ActionDriver.of(living.getClass());
        StringBuilder tried = new StringBuilder();
        for (int i = 0; i < models.size(); i++) {
            EntityModel<?> model = models.get(i);
            String where = fields.get(i).getDeclaringClass().getSimpleName() + "." + fields.get(i).getName();
            // geo 形状的模型（GeckoLib 系）走 geo 的朝向约定与采样表
            ModelRig geoParts;
            boolean geoShaped;
            try {
                geoParts = GeoRig.tryCreate(model, puppet);
                geoShaped = geoParts != null;
            } catch (Throwable t) {
                geoParts = null;
                geoShaped = false;
            }
            GeoPoseTable table = geoShaped ? geoTable(renderer, living, model, geoParts, driver) : null;
            RigBaker.Rig rig;
            try {
                rig = RigBaker.bake(model, puppet, unitTypeId, table);
            } catch (Throwable t) {
                if (tried.length() < 300) {
                    tried.append(where).append('(').append(RendererCapture.root(t)).append(") ");
                }
                continue;
            }
            if (rig == null || rig.indices.length == 0 || rig.vertices.length == 0) {
                if (tried.length() < 300) {
                    tried.append(where).append(rig == null ? "(部件系统未识别)" : "(无几何)").append(' ');
                }
                continue;
            }

            float[] scale = new float[]{1.0F, 1.0F, 1.0F};
            float[] offset = new float[]{0.0F, 0.0F, 0.0F};
            // 渲染器在画模型之前自己施加的整体缩放：不跟着放大，模组 boss 在群集里会缩水
            float[] prelude = RendererCapture.preludeMatrix(renderer, puppet);
            float[] columnLen = RendererCapture.columnLengths(prelude);
            scale[0] = columnLen[0];
            scale[1] = columnLen[1];
            scale[2] = columnLen[2];
            // 前置变换里除去「着色器已经固定施加的那一套」之外剩下的位移，直接烘进顶点
            float[] folded = foldPreludeTranslation(rig.vertices, prelude);
            applyRenderOffset(renderer, puppet, offset);
            applyOverride(unitTypeId, scale, offset);

            dev.swordkingdom.horde.HordeMod.LOGGER.info("[horde] {} 从渲染器字段拿到模型：{} 的字段 {} = {}，"
                            + "骨骼 {} 根（最深链 {} 层），顶点 {}，贴图 {}，渲染器前置变换 {}，残余位移已烘进顶点 ({}, {}, {})",
                    unitTypeId, renderer.getClass().getSimpleName(), where, model.getClass().getSimpleName(),
                    rig.boneCount, rig.maxChain, rig.vertices.length / FLOATS_PER_VERTEX,
                    texture == null ? "无（着色器会保留上一张贴图）" : texture,
                    RendererCapture.describeMatrix(prelude),
                    trim(folded[0]), trim(folded[1]), trim(folded[2]));
            CrowdPose pose = poseFor(model, living, geoParts, rig, driver, table, geoShaped, unitTypeId);
            return new Mesh(rig, texture, pose, scale[0], scale[1], scale[2],
                    offset[0], offset[1], offset[2], geoShaped);
        }
        reason(reasons, "实例字段里有 " + models.size() + " 个 EntityModel 候选，但都烘焙不出几何："
                + (tried.length() == 0 ? "（无）" : tried));
        return null;
    }

    // ------------------------------------------------------------------
    // ③ 非 LivingEntityRenderer：geo（GeckoLib）形状
    // ------------------------------------------------------------------

    /**
     * 渲染器不是 {@code LivingEntityRenderer} 时的兜底：**按形状**找它暴露的 geo 模型与贴图。
     *
     * GeckoLib 的 {@code GeoEntityRenderer} 有 {@code getGeoModel()} 与
     * {@code getTextureLocation(entity)}，但我们不认这个类名 —— 只认「有一个无参方法返回模型对象」
     * 与「有一个单参方法返回贴图路径」，所以任何长这样的渲染器都能进来。
     * 模型交给 {@link GeoRig} 拆骨骼；A 档先出绑定姿态（动画驱动见后续 B 档）。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Mesh bakeGeoRenderer(Object renderer, Entity puppet, ResourceLocation unitTypeId,
                                        List<String> reasons) {
        LivingEntity living = puppet instanceof LivingEntity le ? le : null;
        // 按「名字像不像模型」排序候选：geomodel / getModel 优先，其它含 model 的兜底。
        // 关键：**先命中的候选不一定是模型**（渲染器上可能有别的无参 model 相关 getter），
        // 所以这里把候选全收集起来，后面逐个真的交给 RigBaker 试，能烘焙出几何的才算。
        List<Object> candidates = new ArrayList<>();
        List<Integer> scores = new ArrayList<>();
        for (Class<?> c = renderer.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() != 0 || m.getReturnType().isPrimitive()
                        || m.getReturnType() == String.class || m.getReturnType().isArray()) {
                    continue;
                }
                String n = m.getName().toLowerCase(Locale.ROOT);
                int score = n.contains("geomodel") ? 3
                        : (n.contains("getmodel") || n.equals("model") ? 2
                        : (n.contains("model") ? 1 : 0));
                if (score == 0) {
                    continue;
                }
                try {
                    m.setAccessible(true);
                    Object v = m.invoke(renderer);
                    if (v != null && v != renderer && !candidates.contains(v)) {
                        candidates.add(v);
                        scores.add(score);
                    }
                } catch (Throwable ignored) {
                    // 试下一个
                }
            }
        }
        if (candidates.isEmpty()) {
            reason(reasons, "没有「名字像模型的无参取值方法」（geo 渲染器的 getGeoModel() 就是这种）");
            return null;
        }
        // 稳定排序：分数高的在前
        for (int i = 1; i < candidates.size(); i++) {
            Object cv = candidates.get(i);
            int cs = scores.get(i);
            int j = i - 1;
            while (j >= 0 && scores.get(j) < cs) {
                candidates.set(j + 1, candidates.get(j));
                scores.set(j + 1, scores.get(j));
                j--;
            }
            candidates.set(j + 1, cv);
            scores.set(j + 1, cs);
        }
        ResourceLocation texture = RendererCapture.textureOf(renderer, puppet);
        dev.swordkingdom.horde.action.ActionDriver driver =
                living == null ? null : dev.swordkingdom.horde.action.ActionDriver.of(living.getClass());

        // 逐个候选真的试烘焙：能拆出几何的才算模型（避免被不相干的无参 getter 带偏）
        StringBuilder tried = new StringBuilder();
        for (Object candidate : candidates) {
            // 骨骼表：优先独立识别一次（GeoRig 内部按类缓存，重复调用很便宜），
            // 识别不出来就等 RigBaker 成功后用它烘焙出来的部件表 —— 两条路的顺序一定一致
            ModelRig geoParts = GeoRig.tryCreate(candidate, living);
            // B 档第 1 步：先把姿态采成表（geo 模型没有 setupAnim，姿态只能是采出来的）。
            // 放在 RigBaker 之前是为了让 RigBaker 能拿它探测「哪些骨骼会被平移/缩放」。
            GeoPoseTable table = geoTable(renderer, living, candidate, geoParts, driver);
            RigBaker.Rig rig;
            try {
                rig = RigBaker.bake(candidate, living, unitTypeId, table);
            } catch (Throwable t) {
                if (tried.length() < 300) {
                    tried.append(candidate.getClass().getSimpleName())
                            .append("(").append(RendererCapture.root(t)).append(") ");
                }
                continue;
            }
            if (rig == null || rig.indices.length == 0 || rig.vertices.length == 0) {
                if (tried.length() < 300) {
                    tried.append(candidate.getClass().getSimpleName())
                            .append(rig == null ? "(未识别)" : "(无几何)").append(' ');
                }
                continue;
            }
            dev.swordkingdom.horde.HordeMod.LOGGER.info("[horde] {} 走 geo 形状识别成功：模型 {}，骨骼 {} 根，顶点 {}，"
                            + "位移通道 {} 个，缩放通道 {} 个",
                    unitTypeId, candidate.getClass().getSimpleName(), rig.boneCount,
                    rig.vertices.length / RigBaker.FLOATS_PER_VERTEX, rig.transCount, rig.scaleCount);
            CrowdPose pose = poseFor(candidate, living, geoParts, rig, driver, table, true, unitTypeId);
            return new Mesh(rig, texture, pose, 1.0F, 1.0F, 1.0F, 0.0F, 0.0F, 0.0F, true);
        }
        // 全都失败：把「渲染器、候选模型、贴图、各自失败原因」记下来（最后统一打 WARN）
        reason(reasons, "按 geo 形状识别失败。候选模型 " + candidates.size() + " 个："
                + candidates.stream().map(o -> o.getClass().getName()).toList()
                + "；贴图 " + (texture == null ? "无" : texture)
                + "；尝试结果：" + (tried.length() == 0 ? "（无）" : tried));
        return null;
    }

    /**
     * 组装「模型 → 每帧姿态」这一层。
     *
     * <p>geo 系（GeckoLib）没有 {@code setupAnim}，姿态只能来自烘焙期的采样表，所以它走查表；
     * 其余（原版 / Citadel / 反射适配出来的部件系统）直接喂傀儡实体跑模型自己的 {@code setupAnim}。
     *
     * @param geoShaped 该模型是不是 geo（Bedrock）形状 —— 这决定了朝向约定（{@code Mesh.geo}）
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static CrowdPose poseFor(Object model, LivingEntity living, ModelRig geoParts, RigBaker.Rig rig,
                                     dev.swordkingdom.horde.action.ActionDriver driver, GeoPoseTable table,
                                     boolean geoShaped, ResourceLocation unitTypeId) {
        if (geoShaped) {
            ModelRig.Part[] parts = partsOf(geoParts, rig);
            if (table != null && table.usable()) {
                // 这句日志是排查「群集单位一动不动」的第一现场：必须能看出
                // 「待机是时间表还是常量」「走路是否二维」「各表字节数」（见 GeoPoseTable#describe）
                dev.swordkingdom.horde.HordeMod.LOGGER.info("[horde] {} 的 geo 动画采样表：{}（该类型有 {} 种动作动画，"
                                + "骨骼 {} 根，位移通道 {} 个，缩放通道 {} 个）",
                        unitTypeId, table.describe(), driver == null ? 0 : driver.actionCount(),
                        rig.boneCount, rig.transCount, rig.scaleCount);
                // ★ 决定性分叉：表**自身**在两个维度上到底有多少变化 ——
                // 这一行把「表是废的」与「运行时把结果乘没了/查错表」一次分开。
                dev.swordkingdom.horde.HordeMod.LOGGER.info("[horde] geo 采样表自审 {}：{}",
                        unitTypeId, table.audit());
                return CrowdPose.ofGeo(model, living, parts, driver, living, table);
            }
            // 退化：保留 A 档行为（只出绑定姿态），但把原因讲清楚
            dev.swordkingdom.horde.HordeMod.LOGGER.warn("[horde] {} 的 geo 动画采样失败，"
                            + "该类型退回绑定姿态（群集里会是静止的）：{}", unitTypeId,
                    table == null ? "没建表（渲染器不是 EntityRenderer，或拿不到替身实体）" : table.reason());
            return CrowdPose.ofGeo(model, living, parts, driver, living, null);
        }
        if (driver != null && driver.usable()) {
            dev.swordkingdom.horde.HordeMod.LOGGER.info("[horde] {} 支持 {} 种动作/技能动画，"
                    + "群集单位会像真实实体一样播放", unitTypeId, driver.actionCount());
        }
        return new CrowdPose(model, living, rig.parts, driver);
    }

    /**
     * 取这次烘焙要用的骨骼表。
     *
     * 优先用独立识别出来的（{@link GeoRig#tryCreate} 按类缓存，重复调用很便宜）；
     * 万一独立识别失败就退回 {@code RigBaker} 烘焙出来的部件表 —— 两者顺序一定一致
     * （RigBaker 内部也是走 {@code GeoRig}），确保「采样读骨骼」与「顶点骨骼下标」对得上。
     */
    private static ModelRig.Part[] partsOf(ModelRig geoParts, RigBaker.Rig rig) {
        if (geoParts != null && geoParts.allParts().size() == rig.boneCount) {
            return geoParts.allParts().toArray(new ModelRig.Part[0]);
        }
        return rig.parts;
    }

    /**
     * 建 geo 姿态采样表。
     *
     * <p>采样必须真的调该渲染器渲染几帧（GeckoLib 的动画器就是这样被推动的），所以它需要
     * 一个真实的 {@code EntityRenderer} 与一只真实的生物替身。离线验证用的替身渲染器两者都没有，
     * 于是这里返回 null —— 后果只是退化成绑定姿态（静止），几何/骨骼数照样正确。
     *
     * <p><b>{@code model} 必须传下去（真机事故②的修复点）</b>：GeckoLib 的每帧动画有
     * 「动画时间没变 + 同一个 instanceId 就整帧 return」的节流，而采样是同一个模型实例连续几千帧，
     * 于是 2048 个样本逐位相同（真机日志：「行走样本塌缩」）。修法是采样时**显式驱动动画器**
     * （{@link GeoAnimator}），而驱动需要模型对象 —— 它就是这里已经拿在手上的那个候选模型。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static GeoPoseTable geoTable(Object renderer, LivingEntity living, Object model, ModelRig geoParts,
                                        dev.swordkingdom.horde.action.ActionDriver driver) {
        if (living == null || !(renderer instanceof EntityRenderer<?> entityRenderer)) {
            return null;
        }
        try {
            return GeoPoseTable.fromRenderer(entityRenderer, living, model,
                    geoParts == null ? null : geoParts.allParts().toArray(new ModelRig.Part[0]), driver);
        } catch (Throwable t) {
            dev.swordkingdom.horde.HordeMod.LOGGER.warn("[horde] geo 姿态采样建表失败：{}", RendererCapture.root(t));
            return null;
        }
    }

    // ------------------------------------------------------------------
    // ④ 非 LivingEntityRenderer：顶点捕获（静态网格，保证永不不可见）
    // ------------------------------------------------------------------

    /**
     * 最后一道保险：**顶点捕获**。
     *
     * <p>走到这里说明「渲染器不是 LivingEntityRenderer、字段里没有 EntityModel、也没有 geo 模型」，
     * 但那个渲染器仍然会往 {@code VertexConsumer} 里写顶点。于是让它通过自己渲染一次，
     * 把顶点截下来做成 **1 根骨骼的静态网格**，接进现有的实例化管线（渲染器那边完全不用改：
     * 顶点格式一致、骨骼数 1、无位移/缩放通道）。
     *
     * <p>代价说清楚：**没有骨骼动画**（整块网格是死的），而且顶点数会随模型大小增长 ——
     * 所以有 {@link RendererCapture#MAX_VERTICES} 上限保护，超限就整块跳过并 WARN。
     */
    private static Mesh bakeByCapture(Object renderer, Entity puppet, ResourceLocation unitTypeId,
                                     List<String> reasons) {
        RendererCapture.Result result = RendererCapture.capture(renderer, puppet, RendererCapture.MAX_VERTICES);
        if (!result.ok) {
            reason(reasons, result.reason);
            return null;
        }
        float[] scale = new float[]{1.0F, 1.0F, 1.0F};
        float[] offset = new float[]{0.0F, 0.0F, 0.0F};
        // ★ 真机事故（本轮）：「蝙蝠 / 末影龙 模型组件错位」—— 这里以前是 **+getRenderOffset()**，
        //   而主路径（字段烘焙，见 applyRenderOffset）是 **−getRenderOffset()**（原版
        //   EntityRenderer.render 里就是 translate(−offset.x, −offset.y, −offset.z)）。
        //   两条烘焙路径符号相反 ⇒ 走捕获路径的类型整体位移 2×offset，而且蝙蝠飞行的
        //   getRenderOffset 是变化的 ⇒ 看起来就是「部件位置不对、还在抖」。
        //   捕获路径同样是「直调渲染器」，所以必须与主路径取同一个符号。
        Vec3 off = RendererCapture.renderOffset(renderer, puppet);
        offset[0] = -(float) off.x;
        offset[1] = -(float) off.y;
        offset[2] = -(float) off.z;
        applyOverride(unitTypeId, scale, offset);

        LivingEntity living = puppet instanceof LivingEntity le ? le : null;
        CrowdPose pose = new CrowdPose(null, living,
                new ModelRig.Part[]{new RendererCapture.StaticBone()}, null, living);

        float[] bounds = new float[6];
        measure(result.vertices, bounds);
        dev.swordkingdom.horde.HordeMod.LOGGER.info("[horde] {} 按捕获式烘焙（静态、无骨骼动画）：捕获自渲染器 {}，"
                        + "顶点 {}、三角 {}、贴图 {}（{}）、尺寸 {}×{}×{}",
                unitTypeId, renderer.getClass().getSimpleName(), result.vertexCount(), result.triangleCount(),
                result.texture, result.atlasTexture ? "图集退化（渲染器没给贴图）" : "来自渲染器",
                trim(bounds[3] - bounds[0]), trim(bounds[4] - bounds[1]), trim(bounds[5] - bounds[2]));
        return new Mesh(result.vertices, result.indices, result.texture, pose,
                scale[0], scale[1], scale[2], offset[0], offset[1], offset[2]);
    }

    /**
     * 把渲染器前置变换里的**残余平移**烘进顶点，返回实际烘进去的位移（无残余时是 (0,0,0)）。
     *
     * <p>背景：非 geo 网格进着色器时，着色器会**固定**施加原版那一套
     * {@code pos.y -= 1.501; pos *= uRenderScale; pos.x *= -1; pos.y *= -1;}
     * （见 {@link CrowdRenderer} 的顶点着色器），也就是 {@code scale(-1,-1,1)·translate(0,-1.501,0)}。
     * 但渲染器完全可能在这之前还有别的平移 —— 原版末影龙就是：
     * <pre>
     *   poseStack.translate(0, 0, 1);          // ← 只有末影龙有这一步
     *   poseStack.scale(-1, -1, 1);
     *   poseStack.translate(0, -1.501, 0);
     * </pre>
     * 漏掉这一步，龙就会整体偏一格（对这种体型的 boss 是看得出来的）。
     *
     * <p>为什么烘进顶点而不是用 uniform：{@code uRenderOffset} 加在**世界空间**（朝向旋转之后），
     * 而这个位移必须跟着单位朝向一起转，用 uniform 表达不了。
     *
     * <p>为什么只处理「原版约定」的前置变换：只有 x/y 取反（{@code scale(-1,-1,1)}）时，
     * 着色器那套固定变换才和渲染器一致，公式 {@code Δ = D⁻¹·(t − shader 自身的平移)} 才有意义
     * （{@code D = diag(-|ax|, -|ay|, |az|)}）。不是这个约定就原样不动，交给 GUI 里的模型偏移去修。
     *
     * <p>已知代价：位移是烘进顶点的，所以**会随该骨骼的旋转一起转**（着色器是
     * {@code R·(v+Δ−pivot)+pivot}）。对绑定姿态/无骨骼动画的模型（末影龙的 {@code setupAnim}
     * 是空实现，动画全在 {@code renderToBuffer} 里）这是精确的；对真正会逐骨骼旋转的模型，
     * 残余位移本身已经很小（绝大多数渲染器就是 0），影响可忽略。
     */
    private static float[] foldPreludeTranslation(float[] vertices, float[] prelude) {
        float[] applied = new float[]{0.0F, 0.0F, 0.0F};
        if (vertices == null || vertices.length == 0 || prelude == null || prelude.length < 16) {
            return applied;
        }
        // 必须是「接近对角」的线性部分（旋转分量大的时候，下面的公式不再成立）
        float off = Math.abs(prelude[1]) + Math.abs(prelude[2]) + Math.abs(prelude[4])
                + Math.abs(prelude[6]) + Math.abs(prelude[8]) + Math.abs(prelude[9]);
        if (off > 0.01F) {
            return applied;
        }
        float ax = prelude[0];
        float ay = prelude[5];
        float az = prelude[10];
        // 只认原版约定：x、y 反向（scale(-1,-1,1) 之后），z 不反向
        if (ax > -0.01F || ay > -0.01F || az < 0.01F) {
            return applied;
        }
        float sx = Math.abs(ax);
        float sy = Math.abs(ay);
        float sz = Math.abs(az);
        if (sy < 1.0E-6F || sx < 1.0E-6F || sz < 1.0E-6F) {
            return applied;
        }
        // 着色器对非 geo 网格固定用的高度偏移（CrowdRenderer 里那个写死的 1.501，改那里要同步改这里）
        float shaderYOffset = 1.501F;
        float dx = -prelude[12] / sx;
        float dy = -(prelude[13] - shaderYOffset * sy) / sy;
        float dz = prelude[14] / sz;
        if (Math.abs(dx) < 1.0E-3F && Math.abs(dy) < 1.0E-3F && Math.abs(dz) < 1.0E-3F) {
            return applied;
        }
        for (int i = 0; i + FLOATS_PER_VERTEX <= vertices.length; i += FLOATS_PER_VERTEX) {
            vertices[i] += dx;
            vertices[i + 1] += dy;
            vertices[i + 2] += dz;
        }
        applied[0] = dx;
        applied[1] = dy;
        applied[2] = dz;
        return applied;
    }

    /** 顶点包围盒（格）—— 只用于日志（「这个网格到底多大」） */
    private static void measure(float[] verts, float[] out) {
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        float maxZ = -Float.MAX_VALUE;
        for (int i = 0; i + FLOATS_PER_VERTEX <= verts.length; i += FLOATS_PER_VERTEX) {
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

    private static String trim(float v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    // ------------------------------------------------------------------
    // 渲染器自身的局部变换
    // ------------------------------------------------------------------

    /**
     * 把 GUI 里给该**群集类型**配的「整体模型变换」乘/加到渲染器自身变换之上。
     *
     * <p>三条路都要过这一关：玩家在 GUI 里调的就是「最终看起来对不对」，
     * 不该因为走了捕获兜底就调不动。
     */
    private static void applyOverride(ResourceLocation unitTypeId, float[] scale, float[] offset) {
        if (unitTypeId == null) {
            return;
        }
        dev.swordkingdom.horde.edit.TypeOverride ov =
                dev.swordkingdom.horde.edit.TypeOverride.get(unitTypeId);
        if (ov == null) {
            return;
        }
        if (ov.modelScaleX >= 0.0F) {
            scale[0] *= ov.modelScaleX;
        }
        if (ov.modelScaleY >= 0.0F) {
            scale[1] *= ov.modelScaleY;
        }
        if (ov.modelScaleZ >= 0.0F) {
            scale[2] *= ov.modelScaleZ;
        }
        if (ov.modelOffsetX != dev.swordkingdom.horde.edit.TypeOverride.OFFSET_UNSET) {
            offset[0] += ov.modelOffsetX;
        }
        if (ov.modelOffsetY != dev.swordkingdom.horde.edit.TypeOverride.OFFSET_UNSET) {
            offset[1] += ov.modelOffsetY;
        }
        if (ov.modelOffsetZ != dev.swordkingdom.horde.edit.TypeOverride.OFFSET_UNSET) {
            offset[2] += ov.modelOffsetZ;
        }
    }

    /**
     * 取「该实体在调度器里会得到的渲染位移」，与主路径同一个约定（{@code -getRenderOffset}）。
     *
     * <p>与 {@link #captureRenderTransform} 的区别：这里不碰 {@code scale()} ——
     * 非 {@code LivingEntityRenderer} 的渲染器没有那个方法，它的整体缩放由
     * {@link RendererCapture#preludeMatrix} 从渲染器的前置变换里取。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void applyRenderOffset(Object renderer, Entity puppet, float[] offset) {
        if (!(renderer instanceof EntityRenderer raw) || puppet == null) {
            return;
        }
        try {
            Vec3 off = raw.getRenderOffset(puppet, 0.0F);
            if (off != null) {
                // 原版 EntityRenderer.render 里是 translate(-offset.x, -offset.y, -offset.z)
                offset[0] -= (float) off.x;
                offset[1] -= (float) off.y;
                offset[2] -= (float) off.z;
            }
        } catch (Throwable ignored) {
            // 没有额外偏移
        }
    }

    /**
     * 取该渲染器自己的局部变换。
     *
     * {@code scale(T, PoseStack, float)} 是 protected 方法，只能靠**签名**找（不能按名字，线上是 SRG 名）。
     * {@code getRenderOffset(T, float)} 是公开的，直接调用。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void captureRenderTransform(LivingEntityRenderer renderer, LivingEntity puppet,
                                               float[] scale, float[] offset) {
        try {
            PoseStack stack = new PoseStack();
            Method scaleMethod = findScale(renderer.getClass());
            if (scaleMethod != null) {
                scaleMethod.invoke(renderer, puppet, stack, 0.0F);
                org.joml.Matrix4f m = stack.last().pose();
                scale[0] = m.m00();
                scale[1] = m.m11();
                scale[2] = m.m22();
                // 有的渲染器在 scale() 里顺便平移，这里一并取出（在缩放后的坐标系里）
                float tx = m.m30();
                float ty = m.m31();
                float tz = m.m32();
                if (Math.abs(tx) > 1.0E-4F || Math.abs(ty) > 1.0E-4F || Math.abs(tz) > 1.0E-4F) {
                    offset[0] -= tx;
                    offset[1] -= ty;
                    offset[2] -= tz;
                }
            }
        } catch (Throwable t) {
            scale[0] = 1.0F;
            scale[1] = 1.0F;
            scale[2] = 1.0F;
        }
        try {
            Vec3 off = renderer.getRenderOffset(puppet, 0.0F);
            if (off != null) {
                // 原版 EntityRenderer.render 里是 translate(-offset.x, -offset.y, -offset.z)
                offset[0] -= (float) off.x;
                offset[1] -= (float) off.y;
                offset[2] -= (float) off.z;
            }
        } catch (Throwable ignored) {
            // 没有额外偏移
        }
    }

    /** 按参数类型找 {@code scale(T, PoseStack, float)}（名字在线上是 SRG，不能按名字找）。 */
    private static Method findScale(Class<?> rendererClass) {
        for (Class<?> c = rendererClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 3 && p[1] == PoseStack.class && p[2] == float.class
                        && m.getReturnType() == void.class
                        && LivingEntity.class.isAssignableFrom(p[0])) {
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
