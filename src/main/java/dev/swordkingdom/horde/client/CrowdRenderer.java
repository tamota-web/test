package dev.swordkingdom.horde.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.swordkingdom.horde.HordeConfig;
import dev.swordkingdom.horde.HordeMod;
import dev.swordkingdom.horde.HordeProfiler;
import dev.swordkingdom.horde.crowd.CrowdManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Matrix4f;
import org.lwjgl.opengl.ARBInstancedArrays;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * 客户端群集渲染器 —— 每种实体类型一次 draw call 画出全部单位。
 *
 * 骨骼动画与真实实体**完全一致**：每个单位都在 CPU 上被 {@link CrowdPose} 驱动一次
 * 模型自己的 {@code setupAnim}（同一份模型代码、同一套渲染输入），
 * 逐骨骼的欧拉角写进一张「姿态纹理」，顶点着色器只负责沿父链旋转。
 * 因此不再有任何拟合/公式近似：原版怎么动，群集单位就怎么动。
 *
 * 开销控制：姿态刷新跟随服务端同样的 LOD 节奏（近处每帧、远处每 2/4/8 帧），
 * 未刷新的单位沿用上一帧姿态（透视上完全看不出差别）。
 */
@Mod.EventBusSubscriber(modid = HordeMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CrowdRenderer {

    private static final int FLOATS_PER_INSTANCE = ClientCrowdData.FLOATS_PER_UNIT;
    /** 视距（格）：超出则不渲染 */
    private static final float RENDER_DISTANCE = 256.0F;
    /** 动画诊断的最小间隔（1 秒）—— 每个类型每秒最多一行 */
    private static final long DIAG_INTERVAL_NANOS = 1_000_000_000L;
    /** 姿态纹理绑定的纹理单元（原版只用 0/1/2，3 号安全） */
    private static final int POSE_TEXTURE_UNIT = 3;
    /** 姿态缓冲纹理的 texel 上限（运行时读 GL_MAX_TEXTURE_BUFFER_SIZE 覆盖） */
    private static int maxPoseTexels = 65536;

    private static final float[] PROJ_MAT = new float[16];
    private static final float[] VIEW_MAT = new float[16];
    /** 上传给 uTransSlot 的每类型位移通道表（不足补 -1） */
    private static final int[] transSlotUpload = new int[RigBaker.MAX_BONES];

    private static boolean initialized;
    private static boolean broken;
    /** -1 = 不支持实例化，1 = 核心 3.3 入口，2 = ARB 扩展入口 */
    private static int divisorApi;

    private static int program;
    private static int vboInstance;
    private static int uProj;
    private static int uView;
    private static int uTex;
    private static int uPose;
    private static int uRenderScale;
    private static int uFlip;
    private static int uYOffset;
    private static int uRenderOffset;
    private static int uPoseStride;
    private static int uTransBase;
    private static int uTransSlot;
    private static int uScaleBase;
    private static int uScaleSlot;
    private static final int[] uBone = new int[1];

    private static float[] instanceData = new float[0];
    private static FloatBuffer instanceBuffer = MemoryUtil.memAllocFloat(0);
    private static FloatBuffer poseBuffer;
    private static int instanceCapacity;

    /** 每单位上一次算出的骨骼姿态（固定按 MAX_BONES 步长，与类型无关） */
    private static float[] poseCache = new float[0];
    /** 每单位上一次算出的位移（按 MAX_TRANS 步长） */
    private static float[] transCache = new float[0];
    /** 每骨骼缩放通道（同样按 MAX_TRANS 预留；只为真的会缩放的骨骼占用） */
    private static float[] scaleCache = new float[0];
    private static final int[] scaleSlotUpload = new int[RigBaker.MAX_BONES];
    /** 每单位上一次刷新姿态的 tick（0 = 还没算过） */
    private static int[] poseStamp = new int[0];
    /** 单次 setupAnim 结果的暂存（成功后才拷进 cache，避免半成品） */
    private static final float[] poseScratch = new float[RigBaker.MAX_BONES * CrowdPose.STRIDE];
    /** 姿态缓冲上传缓冲（每实例一行：旋转 texel + 位移 texel） */
    private static float[] poseUpload = new float[0];

    private static int debugCounter;
    /** 本帧投掷物是否已经画过（防止「没有群集单位」那条分支与主路径重复绘制） */
    private static boolean renderedProjectiles;
    /** 上一帧的维度：切维度时姿态缓存要作废 */
    private static ResourceLocation lastDimension;

    /**
     * 「这一帧这个单位要不要重新求值姿态」的唯一决策点。
     *
     * 它保证**每个单位每帧最多求值一次**：同一帧里若有两条路径都走到同一个单位
     * （主渲染路径 / 诊断 / 其它订阅者），第二次起直接沿用缓存。
     * 姿态 texel 的布局与写入仍然完全由本类负责，这里只管「求不求值」。
     */
    private static final PoseScheduler POSE_SCHEDULE = new PoseScheduler();
    /** 客户端插桩的窗口帧计数（每 20 帧 = 1 秒汇总一行） */
    private static int profileFrames;
    /** 本帧上传字节（实例缓冲 + 姿态缓冲），窗口末汇总 */
    private static long frameUploadBytes;
    /** 本帧激活的类型网格数 */
    private static int frameMeshes;

    private static final Map<ResourceLocation, TypeMesh> MESHES = new HashMap<>();

    /**
     * 覆盖表变了（改了模型/动作）→ 作废所有网格，下一帧按新模型重新烘焙。
     * GL 资源在渲染线程释放，避免跨线程删除。
     */
    public static void invalidateAll() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) {
            releaseMeshes();
        } else {
            mc.execute(CrowdRenderer::releaseMeshes);
        }
    }

    private static void releaseMeshes() {
        for (TypeMesh mesh : MESHES.values()) {
            if (mesh == null) {
                continue;
            }
            if (mesh.vao != 0) {
                GL30.glDeleteVertexArrays(mesh.vao);
            }
            if (mesh.ibo != 0) {
                GL15.glDeleteBuffers(mesh.ibo);
            }
            if (mesh.poseBuffer != 0) {
                GL15.glDeleteBuffers(mesh.poseBuffer);
            }
            if (mesh.poseTexture != 0) {
                GL11.glDeleteTextures(mesh.poseTexture);
            }
        }
        MESHES.clear();
    }

    private CrowdRenderer() {}

    /** 每种实体类型一份 GL 资源 */
    private static final class TypeMesh {
        ResourceLocation typeId;
        int vao;
        int ibo;
        int indexCount;
        ResourceLocation texture;
        boolean failed;
        int boneCount;
        float[] pivot;
        int[] transSlot;
        int transCount;
        /** 逐骨骼缩放通道（GeckoLib 会用到） */
        int[] scaleSlot;
        int scaleCount;
        /** 是否 GeckoLib(geo) 模型：决定朝向翻转与高度偏移（由 Mesh 自动搬运） */
        boolean geo;
        float renderScaleX = 1.0F;
        float renderScaleY = 1.0F;
        float renderScaleZ = 1.0F;
        float renderOffsetX;
        float renderOffsetY;
        float renderOffsetZ;
        CrowdPose pose;
        int poseBuffer;
        int poseTexture;
        /** 已分配的 texel 容量 */
        int poseTexels;
        // ---- 动画诊断（每类型每秒最多一行）----
        /** 上次打印诊断的时刻（0 = 还没打过） */
        long diagNanos;
        /** 已经打过几行（同类最多 3 行，之后只在「动静切换」时补一条） */
        int diagReports;
        /** 上一次诊断时是不是「静止」（用来识别动静切换） */
        boolean diagWasStatic;
        /** 上次诊断时**全部骨骼**的旋转快照（用来按「变化最大」挑骨骼，避免被静止骨骼误导） */
        final float[] diagPose = new float[RigBaker.MAX_BONES * 3];
        int lastEvaluated;
        boolean poseFailed;
        boolean poseFailedLogged;
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (broken || event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        // 帧界：本帧的姿态求值/复用/上传计数从这里归零（每帧一次，且必然成对），
        // 同时记下帧起点 —— 用来算 `crowdMs/帧`（我们这一段占了多少帧时间）。
        frameBegin();
        long frameStart = HordeProfiler.begin();
        try {
            render(event, frameStart);
        } catch (Throwable t) {
            broken = true;
            HordeMod.LOGGER.error("[horde] 群集实例化渲染失败，已自动停用（其余功能不受影响）", t);
        } finally {
            frameEnd(frameStart);
        }
    }

    /** 一帧开始：清本帧计数。 */
    private static void frameBegin() {
        POSE_SCHEDULE.frameBegin();
        frameUploadBytes = 0L;
        frameMeshes = 0;
        frameFinalized = false;
        HordeProfiler.frameBegin();
    }

    /**
     * 一帧结束：把本帧的数字并进插桩统计，并按 20 帧（≈1 秒）汇总一行。
     *
     * <p>顺便量出**我们自己占了这一帧多少时间**（{@code crowdMs/帧}）——
     * 它与 {@code fps≈} 并列输出，于是「是不是我们拖慢的」不用猜：
     * 把群集清空（units=0）与 25 / 100 单位三种情况下的两组数字一比就有结论。
     *
     * <p>关键断言就在这一行数字里：{@code poseEval/帧(峰值)} **不可能**超过
     * {@code units/帧(峰值)} —— 一旦超过说明有单位在一帧里被求值了两次。
     */
    private static void frameEnd(long frameStartNanos) {
        if (frameFinalized) {
            return;   // 本帧已经收过尾（例如 units=0 的分支先收了一次）
        }
        frameFinalized = true;
        POSE_SCHEDULE.frameEnd();
        HordeProfiler.noteCrowdFrameNanos(
                frameStartNanos == 0L ? 0L : System.nanoTime() - frameStartNanos);
        HordeProfiler.frameEnd(POSE_SCHEDULE.visitedThisFrame(), POSE_SCHEDULE.evaluatedThisFrame(),
                POSE_SCHEDULE.evalNanosThisFrame(), frameMeshes, frameUploadBytes,
                POSE_SCHEDULE.visitedThisFrame());
        profileFrames++;
        if (HordeProfiler.shouldFlushClient(profileFrames)) {
            HordeProfiler.clientFlush(profileFrames);
            profileFrames = 0;
        }
    }

    /** 本帧是否已经收过尾（保证「每帧恰好一次」的计数与 crowdMs 累加） */
    private static boolean frameFinalized;

    private static void render(RenderLevelStageEvent event, long frameStart) {
        ClientCrowdData data = ClientCrowdData.forCurrentLevel();
        if (data == null || data.count <= 0 || data.types.length == 0) {
            // 没有群集单位时**不能**提前 return：投掷物可能还在飞（最后那一波箭），
            // 它有自己的类型表，不依赖群集的在场列表
            // 另外记一次 units=0：这样「清空群集」那种对照实验里 client 行照样会打
            // （不然恰好是玩家最需要对比 fps 的那一刻反而没有输出）。
            // frameEnd 有一次性守卫，所以这里的提前收尾 + 外层 finally 只会生效一次。
            frameEnd(frameStart);
            if (!renderedProjectiles) {
                renderedProjectiles = true;
                // ★ 与下面主路径**同一个事件**（同一个 PoseStack 来源）——见 ViewMatrices 的契约
                ProjectileRenderer.render(event);
            }
            return;
        }
        renderedProjectiles = false;
        if (!initialized) {
            init();
            initialized = true;
        }

        Vec3 cam = event.getCamera().getPosition();
        float camX = (float) cam.x;
        float camY = (float) cam.y;
        float camZ = (float) cam.z;
        float maxD2 = RENDER_DISTANCE * RENDER_DISTANCE;

        float alpha = data.alpha();
        ensureCapacity(data.count);
        ensurePoseCache(data.count + 64);
        if (!data.dimension.equals(lastDimension)) {
            // 切维度：单位 id 会撞车，姿态缓存整批作废
            lastDimension = data.dimension;
            java.util.Arrays.fill(poseStamp, 0);
            POSE_SCHEDULE.invalidateAll();
        }
        // LOD 阈值：姿态刷新节奏（1/2/4/8）只由 PoseScheduler 裁定 ——
        // 它同时是「每个单位每帧最多求值一次」那条守卫的实现处。
        // 本类不再自己算周期（以前两个地方各算一份，口径很容易走偏）。
        POSE_SCHEDULE.refreshLod();
        float[] buf = instanceData;
        int[] ids = data.ids;
        int total = data.count;
        int[] types = data.curType;
        int tick = (int) (Minecraft.getInstance().level == null ? 0L
                : Minecraft.getInstance().level.getGameTime() & 0x7FFFFFFF);

        // ★ 视图/投影矩阵走**唯一来源**（投掷物那条路径调的是同一个助手、同一个事件）：
        //   以前投掷物用 RenderSystem.getModelViewMatrix()（含摄像机平移），而 iPos 已经是相机相对，
        //   于是摄像机被减两次 ⇒ 投掷物「粘在视角上」。
        ViewMatrices.capture(event, 0);
        System.arraycopy(ViewMatrices.PROJ, 0, PROJ_MAT, 0, 16);
        System.arraycopy(ViewMatrices.VIEW, 0, VIEW_MAT, 0, 16);

        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.enableCull();

        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        GL20.glUseProgram(program);
        GL20.glUniformMatrix4fv(uProj, false, PROJ_MAT);
        GL20.glUniformMatrix4fv(uView, false, VIEW_MAT);
        GL20.glUniform1i(uTex, 0);
        GL20.glUniform1i(uPose, POSE_TEXTURE_UNIT);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboInstance);

        int drawn = 0;
        int evaluated = 0;
        // 按类型分组：每种类型一次提交
        for (int t = 0; t < data.types.length; t++) {
            TypeMesh mesh = meshFor(data.types[t]);
            if (mesh == null) {
                continue;
            }
            int written = 0;
            int firstId = -1;
            for (int i = 0; i < total; i++) {
                int id = ids[i];
                if (types[id] != t) {
                    continue;
                }
                int before = written;
                written = fillInstance(mesh, buf, written, data, id,
                        camX, camY, camZ, maxD2, alpha, tick);
                if (before == 0 && written > 0) {
                    firstId = id;
                }
            }
            if (written == 0) {
                continue;
            }
            // ★ 与投掷物同一组量的一次性对照探针（前 3 帧、每类型一帧一次，之后自动关）：
            //   两组一起看就能判定「差异落在哪一步」（世界坐标 / 上传值 / 视图矩阵 / 裁剪空间）。
            probeCrowdInstance(data, t, firstId, camX, camY, camZ);
            // ★ 动画诊断（每类型每秒最多一行）：直接回答「用了哪条路 + 姿态到底变没变」
            diagnoseAnimation(mesh, data, t, firstId, written);
            uploadAndDraw(mesh, buf, written);
            drawn += written;
            evaluated += mesh.lastEvaluated;
            frameMeshes++;
        }

        // 姿态缓冲纹理解绑（我们只用了 3 号单元的 TEXTURE_BUFFER 目标，不影响原版的 2D 绑定）
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + POSE_TEXTURE_UNIT);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);

        GL30.glBindVertexArray(0);
        GL20.glUseProgram(prevProgram);
        // 让 RenderSystem 的着色器缓存失效，避免后续绘制沿用我们的程序
        RenderSystem.setShader(GameRenderer::getRendertypeEntityCutoutShader);

        if ((debugCounter++ % 200) == 0) {
            HordeMod.LOGGER.info("[horde] render: clientUnits={} drawn={} types={} poseEval={}",
                    total, drawn, data.types.length, evaluated);
        }

        // ---- 虚拟投掷物：同一个渲染阶段、同一套实例化管线（见 ProjectileRenderer）----
        // 放在这里而不是单独 @SubscribeEvent，是为了复用同一次 GL 状态存取，
        // 也避免同一个阶段被两个订阅者交叉影响。
        ProjectileRenderer.render(event);
        renderedProjectiles = true;
    }

    /**
     * ★ 动画诊断：**每类型每秒最多一行**，直接回答「这个类型的姿态到底用了哪条路、动没动」。
     *
     * <p>为什么必须要有它：真机反馈「鸭子/鹅完全不动」时，日志里同时写着
     * 「采样表：行走 64 相位 × 时间 32」「驱动 2088/2088 次」和「动画 = 静止姿态」——
     * 光看日志无法区分「表没被用到」与「表被用了但相位/时间没变」。这一行把两件事都写出来：
     * <ol>
     *   <li><b>来源</b>：geo 采样表（带上相位/时间输入）还是绑定姿态（带上原因）；</li>
     *   <li><b>某个会动的骨骼</b>的旋转/位移**当前值**，以及**1 秒前**的值 —— 两者相同就说明「没接上」，
     *       不用再猜；</li>
     *   <li>只在「这个类型真的有单位被画出来」时打（{@code written > 0}），并且每类型每秒最多一行；
     *       正常播放的类型只报前几次，之后只有**仍然没变**（疑似静止）才继续报，避免刷屏。</li>
     * </ol>
     */
    private static void diagnoseAnimation(TypeMesh mesh, ClientCrowdData data, int typeIdx,
                                          int firstId, int written) {
        if (firstId < 0 || mesh.boneCount <= 0) {
            return;
        }
        // ★ 默认**关闭**：真机上「姿态一直不变」时这条每秒一条 WARN，连续几十秒会把日志和帧时间都拖坏。
        //   打开：config/horde-common.toml → [debug] logAnimationDiagnostics = true
        if (!debugDiagnostics()) {
            return;
        }
        long now = System.nanoTime();
        long elapsed = mesh.diagNanos == 0 ? 0L : now - mesh.diagNanos;
        boolean first = mesh.diagNanos == 0;
        if (!first && elapsed < DIAG_INTERVAL_NANOS) {
            return;   // 每类型每秒最多一行
        }
        int bones = mesh.boneCount;
        int base = firstId * RigBaker.MAX_BONES * 3;
        int tbase = firstId * RigBaker.MAX_TRANS * 3;
        // 逐骨骼与「上次诊断」比：挑**变化最大**的那一根来展示。
        // （上一版挑的是「旋转绝对值最大」的那根 —— 真机上那恰好是一根静止的骨骼，
        //   于是日志看起来「完全没动」，而其实别的骨骼在动。这个坑必须避开。）
        int changedBone = 0;
        float worstChange = -1.0F;
        int loudBone = 0;
        float loudMag = -1.0F;
        for (int b = 0; b < bones; b++) {
            float x = poseCache[base + b * 3];
            float y = poseCache[base + b * 3 + 1];
            float z = poseCache[base + b * 3 + 2];
            float mag = Math.abs(x) + Math.abs(y) + Math.abs(z);
            if (mag > loudMag) {
                loudMag = mag;
                loudBone = b;
            }
            float d = first ? (b == 0 ? 1.0F : 0.0F)
                    : Math.abs(x - mesh.diagPose[b * 3])
                    + Math.abs(y - mesh.diagPose[b * 3 + 1])
                    + Math.abs(z - mesh.diagPose[b * 3 + 2]);
            if (d > worstChange) {
                worstChange = d;
                changedBone = b;
            }
        }
        // 快照**全部**骨骼（不只一根）：下次要按「变化最大」挑
        for (int b = 0; b < bones; b++) {
            mesh.diagPose[b * 3] = poseCache[base + b * 3];
            mesh.diagPose[b * 3 + 1] = poseCache[base + b * 3 + 1];
            mesh.diagPose[b * 3 + 2] = poseCache[base + b * 3 + 2];
        }
        boolean unchanged = !first && worstChange < 1.0E-4F;
        // 上限：**同类最多重复打 3 次**；之后再打只允许两种情况
        //   ① 状态**切换**（由动变静 / 由静变动）——那正是排查要看的转折点；
        //   ② 还没打满 3 次。
        // 这样即使开关打开，也绝不会每秒刷屏（真机那次刷屏就是这里没有上限）。
        boolean switched = !first && (unchanged != mesh.diagWasStatic);
        mesh.diagReports++;
        mesh.diagWasStatic = unchanged;
        if (!first && mesh.diagReports > 3 && !switched) {
            mesh.diagNanos = now;
            return;
        }
        mesh.diagNanos = now;

        float amount = data.curWalkSpeed[firstId];
        String source;
        if (mesh.pose == null) {
            source = "无姿态对象";
        } else if (mesh.pose.usesPoseTable()) {
            source = "geo 采样表[" + mesh.pose.tableBranch(amount) + "]（相位 "
                    + trim3(data.curWalkPos[firstId]) + "、幅度 " + trim3(amount)
                    + "、t=" + trim3(data.curAge[firstId]) + "）";
        } else {
            source = "绑定姿态（原因：" + mesh.pose.poseTableReason() + "）";
        }
        StringBuilder trans = new StringBuilder(48);
        if (mesh.transCount > 0) {
            trans.append("｜位移通道 ").append(mesh.transCount).append(" 个");
            for (int s = 0; s < mesh.transCount && s < 3; s++) {
                trans.append(s == 0 ? "（" : "，")
                        .append(trim3(transCache[tbase + s * 3])).append(',')
                        .append(trim3(transCache[tbase + s * 3 + 1])).append(',')
                        .append(trim3(transCache[tbase + s * 3 + 2]));
            }
            trans.append('）');
        }
        String text = "[horde] 动画诊断 " + data.types[typeIdx] + "：来源=" + source
                + "；1 秒内变化最大的骨骼[" + changedBone + "] 旋转=("
                + trim3(poseCache[base + changedBone * 3]) + ", "
                + trim3(poseCache[base + changedBone * 3 + 1]) + ", "
                + trim3(poseCache[base + changedBone * 3 + 2]) + ") 总变化=" + trim3(worstChange)
                + "；转角最大的骨骼[" + loudBone + "]=" + trim3(loudMag)
                + trans + "｜本帧实例 " + written;
        if (unchanged) {
            HordeMod.LOGGER.warn("{}（**所有骨骼与 1 秒前完全相同**：这条就是「看起来不动」的直接证据）", text);
        } else {
            HordeMod.LOGGER.info("{}", text);
        }
    }

    /** 诊断里的数值格式（3 位小数，省日志体积） */
    private static String trim3(float v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    /** 群集侧对照探针的剩余帧数（默认 3，与投掷物那组一一对应） */
    private static int probeFrames = 3;

    /**
     * ★ 与投掷物**同一组量**的对照探针（前 3 帧、每类型最多一行，之后自动关）。
     *
     * <p>两组日志一起看，就能定量指出「粘视角」的差异落在哪一步：
     * 世界坐标、上传的实例位置、视图矩阵（3×3 + 平移）、按着色器公式算出的相机相对量与裁剪空间坐标。
     * 群集是渲染正常的对照组 —— 它的裁剪空间坐标必须落在视锥内、且随世界位置变化。
     */
    private static void probeCrowdInstance(ClientCrowdData data, int typeIdx, int id,
                                           float camX, float camY, float camZ) {
        if (probeFrames <= 0 || id < 0) {
            return;
        }
        float ipx = data.prvX(id) - camX;
        float ipy = data.prvY(id) - camY;
        float ipz = data.prvZ(id) - camZ;
        float[] clip = ViewMatrices.project(new float[]{ipx, ipy, ipz});
        HordeMod.LOGGER.info("[horde] 群集位置诊断（对照，第 {} 帧）：类型={}，"
                        + "世界 P=({}, {}, {})，摄像机 C=({}, {}, {})，上传 iPos=({}, {}, {})"
                        + "｜视图 3×3={} 平移=({}, {}, {})｜裁剪 xy/z/w=({}, {}, {}, {})",
                probeFrames, data.types[typeIdx],
                trim3(data.prvX(id)), trim3(data.prvY(id)), trim3(data.prvZ(id)),
                trim3(camX), trim3(camY), trim3(camZ), trim3(ipx), trim3(ipy), trim3(ipz),
                ViewMatrices.describeRotation(),
                trim3(ViewMatrices.VIEW[12]), trim3(ViewMatrices.VIEW[13]), trim3(ViewMatrices.VIEW[14]),
                trim3(clip[3]), trim3(clip[4]), trim3(clip[5]), trim3(clip[6]));
        probeFrames--;
    }

    /**
     * 动画诊断开关：**默认关**（真机上它曾每秒刷一条 WARN）。
     *
     * <p>打开方式：{@code config/horde-common.toml} → {@code [debug] logAnimationDiagnostics = true}。
     * 配置读不到时（离线/极早加载阶段）一律当作关闭。
     */
    private static boolean debugDiagnostics() {
        try {
            return HordeConfig.COMMON.logAnimationDiagnostics.get();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 把一个单位写进实例缓冲 + 姿态纹理行；超出视距返回原 written。 */
    private static int fillInstance(TypeMesh mesh, float[] buf, int written, ClientCrowdData data, int id,
                                    float camX, float camY, float camZ, float maxD2, float alpha, int tick) {
        float pX = data.prvX(id);
        float pY = data.prvY(id);
        float pZ = data.prvZ(id);
        float cX = data.curX[id];
        float cY = data.curY[id];
        float cZ = data.curZ[id];
        float tdx = cX - pX;
        float tdy = cY - pY;
        float tdz = cZ - pZ;
        float a = (tdx * tdx + tdy * tdy + tdz * tdz > 16.0F) ? 1.0F : alpha;

        float x = pX + tdx * a;
        float y = pY + tdy * a;
        float z = pZ + tdz * a;
        float dx = x - camX;
        float dy = y - camY;
        float dz = z - camZ;
        float dist2 = dx * dx + dy * dy + dz * dz;
        if (dist2 > maxD2 || written >= 65536) {
            return written;
        }
        ensurePoseCache(id + 1);

        // ---- 骨骼姿态：按 LOD 节奏驱动模型自己的 setupAnim ----
        int bones = mesh.boneCount;
        int base = id * RigBaker.MAX_BONES * 3;
        int tbase = id * RigBaker.MAX_TRANS * 3;
        int sbase = id * RigBaker.MAX_TRANS * 3;
        // 「这一帧要不要重新求值」交给唯一决策点：它保证**每个单位每帧最多求值一次**
        // （同一帧里重复走到这里时返回 REUSE，直接沿用缓存）。
        // LOD 节奏公式与原实现逐位一致：未求值过 || (tick + id) % period == 0。
        PoseScheduler.Decision decision = POSE_SCHEDULE.prepare(id, dist2, tick);
        int stamp = poseStamp[id];
        if (decision == PoseScheduler.Decision.EVALUATE) {
            float attack = lerp(data.prvAttack(id), data.curAttack[id], a);
            // 动作/技能动画：用服务端模拟出来的动作状态（与伤害结算同一个动作），
            // 保证「看到的招式」和「打出来的效果」是同一件事
            int actionIndex = (int) data.curAction[id];
            int actionTick = (int) data.curActionTick[id];
            if (actionIndex <= 0 && mesh.pose.actionCount() > 0 && attack > 0.0F) {
                // 兜底：服务端没给动作时，按攻击窗口播放它的动作动画
                actionIndex = 1 + (id % mesh.pose.actionCount());
                actionTick = Math.round(attack * HordeConfig.COMMON.actionDurationTicks.get());
            }
            long evalStart = POSE_SCHEDULE.evalBegin();
            boolean ok = mesh.pose.evaluate(
                    lerp(data.prvWalkPos(id), data.curWalkPos[id], a),
                    lerp(data.prvWalkSpeed(id), data.curWalkSpeed[id], a),
                    lerp(data.prvAge(id), data.curAge[id], a),
                    lerp(data.prvHeadYaw(id), data.curHeadYaw[id], a),
                    lerp(data.prvHeadPitch(id), data.curHeadPitch[id], a),
                    attack,
                    Math.round(lerp(data.prvHurt(id), data.curHurt[id], a) * 10.0F),
                    x, y, z, lerpAngle(data.prvYaw(id), data.curYaw[id], a),
                    data.curAggro[id] > 0.5F, actionIndex, actionTick, poseScratch);
            POSE_SCHEDULE.evalEnd(evalStart);
            if (ok) {
                int[] slots = mesh.transSlot;
                int[] sslots = mesh.scaleSlot;
                for (int k = 0; k < bones; k++) {
                    int src = k * CrowdPose.STRIDE;
                    int d = base + k * 3;
                    poseCache[d] = poseScratch[src];
                    poseCache[d + 1] = poseScratch[src + 1];
                    poseCache[d + 2] = poseScratch[src + 2];
                    // slots 为空数组（该类型没有位移通道）时视为「全部不平移」
                    int slot = slots != null && k < slots.length ? slots[k] : -1;
                    if (slot >= 0 && slot < mesh.transCount) {
                        int td = tbase + slot * 3;
                        transCache[td] = poseScratch[src + 3];
                        transCache[td + 1] = poseScratch[src + 4];
                        transCache[td + 2] = poseScratch[src + 5];
                    }
                    int sslot = sslots != null && k < sslots.length ? sslots[k] : -1;
                    if (sslot >= 0 && sslot < mesh.scaleCount) {
                        int sd = sbase + sslot * 3;
                        scaleCache[sd] = poseScratch[src + 6];
                        scaleCache[sd + 1] = poseScratch[src + 7];
                        scaleCache[sd + 2] = poseScratch[src + 8];
                    }
                }
                poseStamp[id] = tick;
                POSE_SCHEDULE.noteEvaluated(id, tick);
                mesh.lastEvaluated++;
            } else if (stamp == 0) {
                // 首次就失败：留绑定姿态，并只报一次
                java.util.Arrays.fill(poseCache, base, base + bones * 3, 0.0F);
                if (mesh.transCount > 0) {
                    java.util.Arrays.fill(transCache, tbase, tbase + mesh.transCount * 3, 0.0F);
                }
                if (mesh.scaleCount > 0) {
                    java.util.Arrays.fill(scaleCache, sbase, sbase + mesh.scaleCount * 3, 1.0F);
                }
                poseStamp[id] = tick;
                POSE_SCHEDULE.noteFailed(id, tick);
                mesh.poseFailed = true;
            }
        }

        // ---- 姿态行：按 [骨骼][位移][缩放] 的 texel 顺序写进第 written 行 ----
        // 布局由 packPoseRow **唯一实现**（与着色器的 uPoseStride/uTransBase/uScaleBase 同源），
        // 且直接写成 texel 布局（4 float/texel）⇒ 写入端下标与着色器 texelFetch 是同一个公式。
        int stride = poseStride(bones, mesh.transCount, mesh.scaleCount);
        ensurePoseUpload((written + 1) * stride * 4);
        packPoseRow(poseUpload, written, bones, mesh.transCount, mesh.scaleCount,
                poseCache, base, transCache, tbase, scaleCache, sbase);

        int b = written * FLOATS_PER_INSTANCE;
        buf[b] = dx;
        buf[b + 1] = dy;
        buf[b + 2] = dz;
        buf[b + 3] = lerpAngle(data.prvYaw(id), data.curYaw[id], a);
        buf[b + 4] = data.curTeam[id];
        buf[b + 5] = lerp(data.prvHurt(id), data.curHurt[id], a);
        return written + 1;
    }

    /** 姿态缓存按单位 id 索引，步长固定为 MAX_BONES，与实体类型无关。 */
    private static void ensurePoseCache(int units) {
        int need = units * RigBaker.MAX_BONES * 3;
        if (poseCache.length < need) {
            poseCache = java.util.Arrays.copyOf(poseCache, Math.max(need, poseCache.length * 2));
        }
        int tneed = units * RigBaker.MAX_TRANS * 3;
        if (transCache.length < tneed) {
            transCache = java.util.Arrays.copyOf(transCache, Math.max(tneed, transCache.length * 2));
        }
        if (scaleCache.length < tneed) {
            scaleCache = java.util.Arrays.copyOf(scaleCache, Math.max(tneed, scaleCache.length * 2));
        }
        if (poseStamp.length < units) {
            poseStamp = java.util.Arrays.copyOf(poseStamp, Math.max(units, poseStamp.length * 2));
        }
    }

    private static void ensurePoseUpload(int need) {
        if (poseUpload.length < need) {
            poseUpload = java.util.Arrays.copyOf(poseUpload, Math.max(need, poseUpload.length * 2));
        }
    }

    private static void uploadAndDraw(TypeMesh mesh, float[] buf, int written) {
        instanceBuffer.clear();
        instanceBuffer.put(buf, 0, written * FLOATS_PER_INSTANCE);
        instanceBuffer.flip();

        RenderSystem.setShaderTexture(0, mesh.texture);
        int drawable = uploadPoseBuffer(mesh, written);
        if (drawable <= 0) {
            return;
        }

        GL30.glBindVertexArray(mesh.vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboInstance);
        GL20.glUniform4fv(uBone[0], mesh.pivot);
        GL20.glUniform3f(uRenderScale, mesh.renderScaleX, mesh.renderScaleY, mesh.renderScaleZ);
        GL20.glUniform3f(uRenderOffset, mesh.renderOffsetX, mesh.renderOffsetY, mesh.renderOffsetZ);
        if (mesh.geo) {
            // geo(Bedrock) 顶点已是最终朝向：默认不翻转，按配置微调（改配置重启即可）
            GL20.glUniform2f(uFlip, HordeConfig.COMMON.geoFlipX.get() ? -1.0F : 1.0F,
                    HordeConfig.COMMON.geoFlipY.get() ? -1.0F : 1.0F);
            GL20.glUniform1f(uYOffset, HordeConfig.COMMON.geoYOffset.get().floatValue());
        } else {
            GL20.glUniform2f(uFlip, -1.0F, -1.0F);
            GL20.glUniform1f(uYOffset, 1.501F);
        }
        GL20.glUniform1i(uPoseStride, mesh.boneCount + mesh.transCount + mesh.scaleCount);
        GL20.glUniform1i(uTransBase, mesh.boneCount);
        GL20.glUniform1i(uScaleBase, mesh.boneCount + mesh.transCount);
        java.util.Arrays.fill(transSlotUpload, -1);
        if (mesh.transSlot != null) {
            System.arraycopy(mesh.transSlot, 0, transSlotUpload, 0,
                    Math.min(transSlotUpload.length, mesh.transSlot.length));
        }
        GL20.glUniform1iv(uTransSlot, transSlotUpload);
        java.util.Arrays.fill(scaleSlotUpload, -1);
        if (mesh.scaleSlot != null) {
            System.arraycopy(mesh.scaleSlot, 0, scaleSlotUpload, 0,
                    Math.min(scaleSlotUpload.length, mesh.scaleSlot.length));
        }
        GL20.glUniform1iv(uScaleSlot, scaleSlotUpload);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, instanceBuffer);
        GL31.glDrawElementsInstanced(GL11.GL_TRIANGLES, mesh.indexCount, GL11.GL_UNSIGNED_INT, 0L, drawable);

        // 插桩：这一帧真正上传了多少字节（实例缓冲 + 姿态缓冲），用来回答
        // 「慢是慢在 CPU 姿态求值，还是慢在上传带宽」。上传量是纯计数，不影响任何 GL 状态。
        frameUploadBytes += (long) drawable * FLOATS_PER_INSTANCE * 4L
                + (long) drawable * (mesh.boneCount + mesh.transCount + mesh.scaleCount) * 16L;

        if (mesh.poseFailed && !mesh.poseFailedLogged) {
            mesh.poseFailedLogged = true;
            HordeMod.LOGGER.warn("[horde] 类型 {} 的 setupAnim 驱动失败，该类型将保持绑定姿态", mesh.typeId);
        }
    }

    /**
     * 每实例的姿态 texel 步长 = 骨骼数 + 位移通道数 + 缩放通道数。
     *
     * <p><b>写入端与着色器必须用同一个公式</b>（{@code uPoseStride} 就是这个值）：
     * 着色器里 {@code texelBase = gl_InstanceID * uPoseStride}，
     * 旋转在 {@code texelBase + b}、位移在 {@code texelBase + uTransBase + slot}、
     * 缩放在 {@code texelBase + uScaleBase + slot}。三个数任何一处不一致都会让**部位错位**。
     */
    public static int poseStride(int bones, int transCount, int scaleCount) {
        return bones + transCount + scaleCount;
    }

    /** 位移通道起始 texel（= 骨骼数；与 {@code uTransBase} 同一个值） */
    public static int poseTransBase(int bones) {
        return bones;
    }

    /** 缩放通道起始 texel（= 骨骼数 + 位移通道数；与 {@code uScaleBase} 同一个值） */
    public static int poseScaleBase(int bones, int transCount) {
        return bones + transCount;
    }

    /**
     * 按**着色器同一条公式**算 texel 下标（离线往返校验用；GPU 上由 GLSL 自己算）。
     *
     * @param instance 实例序号（= 写入时的行号 = {@code gl_InstanceID}）
     * @param base     通道起始：旋转 0、位移 {@link #poseTransBase}、缩放 {@link #poseScaleBase}
     * @param slot     骨骼用 {@code b}；通道用 {@code uTransSlot[b]} / {@code uScaleSlot[b]}（-1 = 不占用）
     */
    public static int poseTexelAt(int instance, int stride, int base, int slot) {
        return instance * stride + base + slot;
    }

    /**
     * 把一行的姿态按 {@code [骨骼][位移][缩放]} 的 texel 顺序写进上传缓冲的第 {@code row} 行。
     *
     * <p><b>直接写成 texel 布局</b>（每 texel 4 个 float：xyz + 未用的 w）——因为着色器就是
     * {@code texelFetch(uPose, texelBase + …)}，这样「写入端的下标」与「着色器的下标」是**同一个公式**
     * （{@code float 下标 = texel × 4}），中间不再有一次容易写错的 3-float↔4-float 转换。
     *
     * <p>抽成**纯函数**是为了让离线用例按着色器的读法**逐 texel 读回**校验（哨兵往返）——
     * 写入端与校验端共用这一份实现，不再各写一份。
     *
     * @param rotSrc   骨骼源数组（每骨骼 3 个 float），从 {@code rotOff} 开始读
     * @param trans    位移通道源数组（**相对绑定姿态的增量**，方块），从 {@code transOff} 开始
     * @param scale    缩放通道源数组（1 = 不缩放），从 {@code scaleOff} 开始
     */
    public static void packPoseRow(float[] dst, int row, int bones, int transCount, int scaleCount,
                                   float[] rotSrc, int rotOff,
                                   float[] trans, int transOff,
                                   float[] scale, int scaleOff) {
        int texel = row * poseStride(bones, transCount, scaleCount);
        for (int b = 0; b < bones; b++) {
            putTexelAt(dst, texel++, rotSrc, rotOff + b * 3);
        }
        for (int t = 0; t < transCount; t++) {
            putTexelAt(dst, texel++, trans, transOff + t * 3);
        }
        for (int s = 0; s < scaleCount; s++) {
            putTexelAt(dst, texel++, scale, scaleOff + s * 3);
        }
    }

    /** 便捷重载：三个源数组都从 0 开始（离线用例/一次性路径用） */
    public static void packPoseRow(float[] dst, int row, int bones, int transCount, int scaleCount,
                                   float[] rot, float[] trans, float[] scale) {
        packPoseRow(dst, row, bones, transCount, scaleCount, rot, 0,
                trans == null ? EMPTY3 : trans, 0, scale == null ? EMPTY3 : scale, 0);
    }

    /** 写一个 texel 到 texel 下标 {@code texel}（4 float：xyz + 0） */
    private static void putTexelAt(float[] dst, int texel, float[] src, int at) {
        int o = texel * 4;
        dst[o] = src[at];
        dst[o + 1] = src[at + 1];
        dst[o + 2] = src[at + 2];
        dst[o + 3] = 0.0F;
    }

    private static final float[] EMPTY3 = new float[3];

    /**
     * 把每实例的骨骼欧拉角传上去。
     *
     * 用**缓冲纹理（TBO）**而不是 2D 纹理 + {@code glTexSubImage2D}：
     * 后者的数据指针解释方式受 {@code GL_PIXEL_UNPACK_BUFFER} / {@code GL_UNPACK_ROW_LENGTH} 等
     * 全局像素解包状态影响，而整合包里别的 mod（如 ImmediatelyFast）会把这些状态留着不还原 ——
     * 实测会导致驱动把小块客户端内存当成大图去读，直接在 atio6axx.dll 里
     * {@code EXCEPTION_ACCESS_VIOLATION} 崩掉 JVM。{@code glBufferSubData} 完全不走那条路径。
     *
     * @return 可以安全绘制的实例数（缓冲上限内）
     */
    private static int uploadPoseBuffer(TypeMesh mesh, int rows) {
        int bones = mesh.boneCount;
        int scaleCount = mesh.scaleCount;
        // 每实例占用的 texel 数 = 骨骼 + 位移通道 + 缩放通道（与 fillInstance / 着色器口径一致）。
        // 这里曾经漏掉 scaleCount（写死 boneCount+transCount），于是「有缩放通道的类型」
        // 行偏移整体错位、缩放 texel 也从来没被写过 —— 着色器读到的是上一行的数据。
        int stride = poseStride(bones, mesh.transCount, scaleCount);
        int need = rows * stride;
        if (need <= 0 || bones <= 0) {
            return 0;
        }
        if (mesh.poseBuffer == 0) {
            mesh.poseBuffer = GL15.glGenBuffers();
        }
        GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, mesh.poseBuffer);
        if (mesh.poseTexels < need) {
            int newTexels = Math.max(need, Math.max(4096, mesh.poseTexels * 2));
            if (newTexels > maxPoseTexels) {
                newTexels = maxPoseTexels;
            }
            if (newTexels < stride) {
                return 0;
            }
            rows = Math.min(rows, newTexels / stride);
            need = rows * stride;
            GL15.glBufferData(GL31.GL_TEXTURE_BUFFER, (long) newTexels * 16L, GL15.GL_DYNAMIC_DRAW);
            mesh.poseTexels = newTexels;
            if (mesh.poseTexture == 0) {
                mesh.poseTexture = GL11.glGenTextures();
            }
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, mesh.poseTexture);
            GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32F, mesh.poseBuffer);
        }

        FloatBuffer pb = poseBuffer;
        int floatsPerInstance = stride * 4;
        int needFloats = rows * floatsPerInstance;
        if (pb == null || pb.capacity() < needFloats) {
            if (pb != null) {
                MemoryUtil.memFree(pb);
            }
            pb = MemoryUtil.memAllocFloat(Math.max(needFloats, 4096));
            poseBuffer = pb;
        }
        pb.clear();
        // poseUpload 已经是 texel 布局（4 float/texel、[骨骼][位移][缩放]）——直接整段拷过去，
        // 与着色器的 texelFetch 下标完全一致（这里不再做任何 3↔4 的重排）。
        int perRow = stride * 4;
        pb.put(poseUpload, 0, rows * perRow);
        pb.flip();
        GL15.glBufferSubData(GL31.GL_TEXTURE_BUFFER, 0L, pb);

        GL13.glActiveTexture(GL13.GL_TEXTURE0 + POSE_TEXTURE_UNIT);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, mesh.poseTexture);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        return rows;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** 角度插值，走最短弧。 */
    private static float lerpAngle(float a, float b, float t) {
        float delta = (b - a) % 360.0F;
        if (delta >= 180.0F) {
            delta -= 360.0F;
        }
        if (delta < -180.0F) {
            delta += 360.0F;
        }
        return a + delta * t;
    }

    private static void ensureCapacity(int units) {
        int needFloats = units * FLOATS_PER_INSTANCE;
        if (instanceData.length < needFloats) {
            instanceData = new float[needFloats];
        }
        if (instanceBuffer.capacity() < needFloats) {
            int newFloats = Math.max(needFloats, instanceBuffer.capacity() * 2);
            MemoryUtil.memFree(instanceBuffer);
            instanceBuffer = MemoryUtil.memAllocFloat(newFloats);
        }
        if (instanceCapacity < units) {
            int newCap = Math.max(units, instanceCapacity * 2);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboInstance);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) newCap * FLOATS_PER_INSTANCE * 4L, GL15.GL_DYNAMIC_DRAW);
            instanceCapacity = newCap;
        }
    }

    // ------------------------------------------------------------------
    // 每种类型的 GL 资源（按需烘焙）
    // ------------------------------------------------------------------

    private static TypeMesh meshFor(ResourceLocation typeId) {
        TypeMesh cached = MESHES.get(typeId);
        if (cached != null) {
            return cached.failed ? null : cached;
        }
        TypeMesh mesh = new TypeMesh();
        mesh.typeId = typeId;
        try {
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(typeId);
            if (type == null || !EntityMeshBaker.canRender(type)) {
                mesh.failed = true;
                HordeMod.LOGGER.warn("[horde] 实体类型 {} 不支持群集实例化渲染（仅支持生物），已跳过", typeId);
            } else {
                // 覆盖表可以把「渲染用的模型」换成别的实体类型 —— 直接用模组自己的模型
                ResourceLocation modelId = dev.swordkingdom.horde.edit.TypeOverride.modelFor(typeId);
                EntityType<?> modelType = modelId.equals(typeId) ? type
                        : ForgeRegistries.ENTITY_TYPES.getValue(modelId);
                if (modelType == null || !EntityMeshBaker.canRender(modelType)) {
                    modelType = type;
                }
                EntityMeshBaker.Mesh baked = EntityMeshBaker.bake(modelType, typeId);
                if (!baked.supported || baked.indices.length == 0) {
                    mesh.failed = true;
                    HordeMod.LOGGER.warn("[horde] 实体类型 {} 模型烘焙失败，已跳过", typeId);
                } else {
                    mesh.texture = baked.texture;
                    mesh.indexCount = baked.indices.length;
                    mesh.boneCount = baked.boneCount;
                    mesh.pivot = baked.bonePivot;
                    // 其余字段按名字自动搬过来（含位移通道、渲染器局部变换），漏一个都会在上面报警
                    mesh.transSlot = baked.transSlot != null ? baked.transSlot : new int[0];
                    mesh.transCount = baked.transCount;
                    mesh.renderScaleX = baked.renderScaleX;
                    mesh.renderScaleY = baked.renderScaleY;
                    mesh.renderScaleZ = baked.renderScaleZ;
                    mesh.renderOffsetX = baked.renderOffsetX;
                    mesh.renderOffsetY = baked.renderOffsetY;
                    mesh.renderOffsetZ = baked.renderOffsetZ;
                    mesh.pose = baked.pose;
                    copyMeshFields(baked, mesh);
                    createVao(mesh, baked);
                    HordeMod.LOGGER.info("[horde] 已烘焙 {}：骨骼 {} 根（最深链 {} 层，上限 {}），顶点 {}，三角 {}，"
                                    + "动画 = {}",
                            typeId, baked.boneCount, baked.maxChain, RigBaker.MAX_CHAIN,
                            baked.vertices.length / EntityMeshBaker.FLOATS_PER_VERTEX,
                            baked.indices.length / 3,
                            // 按**事实**写：真有表就说表（这一版之前这里写死「A 档：静止姿态」，
                            // 明明建出 864 KiB 的采样表却告诉玩家「静止」，直接把排查带偏）
                            !baked.geo ? "模型自身 setupAnim（与真实实体一致）"
                                    : (baked.pose != null && baked.pose.usesPoseTable()
                                    ? "该模组自己的骨骼（GeoLib 显式驱动，采样表：" + baked.pose.poseTableSummary() + "）"
                                    : "绑定姿态（该类型不支持动画采样："
                                    + (baked.pose == null ? "没有姿态对象" : baked.pose.poseTableReason()) + "）"));
                }
            }
        } catch (Throwable t) {
            mesh.failed = true;
            HordeMod.LOGGER.warn("[horde] 实体类型 {} 烘焙异常，已跳过", typeId, t);
        }
        MESHES.put(typeId, mesh);
        return mesh.failed ? null : mesh;
    }

    private static final java.util.Set<String> HANDLED_MESH_FIELDS = java.util.Set.of(
            "vertices", "indices", "bonePivot", "maxChain", "supported", "pose", "texture", "boneCount");

    /**
     * 把 {@link EntityMeshBaker.Mesh} 里与 {@link TypeMesh} **同名字段**逐一搬过来。
     *
     * 这不是「图省事」：之前就是因为手工逐字段拷贝时漏了新增的 {@code transSlot}，
     * 上线后直接 NPE 导致群集渲染被自动停用。所以这里改成按名字自动拷贝，
     * 并在发现「Mesh 有、TypeMesh 没有」的字段时**大声报警**，
     * 以后给 Mesh 加字段不会再出现「忘了搬」这类静默故障。
     */
    private static void copyMeshFields(EntityMeshBaker.Mesh src, TypeMesh dst) {        for (java.lang.reflect.Field sf : EntityMeshBaker.Mesh.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(sf.getModifiers())) {
                continue;
            }
            String name = sf.getName();
            try {
                java.lang.reflect.Field df = TypeMesh.class.getDeclaredField(name);
                if (df.getType() != sf.getType()) {
                    HordeMod.LOGGER.warn("[horde] 内部字段 {} 类型不一致（{} vs {}），请检查渲染器实现",
                            name, sf.getType().getSimpleName(), df.getType().getSimpleName());
                    continue;
                }
                sf.setAccessible(true);
                df.setAccessible(true);
                df.set(dst, sf.get(src));
            } catch (NoSuchFieldException e) {
                // 这几个字段在上面已经显式搬过（名字不同或语义不同），不算“漏搬”
                if (!HANDLED_MESH_FIELDS.contains(name)) {
                    HordeMod.LOGGER.warn("[horde] Mesh 的字段 {} 在渲染器里没有对应项，"
                            + "该字段不会生效（群集渲染可能缺少某个特性）", name);
                }
            } catch (Throwable t) {
                HordeMod.LOGGER.warn("[horde] 字段 {} 拷贝失败: {}", name, t.toString());
            }
        }
    }

    private static void createVao(TypeMesh mesh, EntityMeshBaker.Mesh baked) {        mesh.vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(mesh.vao);

        int stride = EntityMeshBaker.FLOATS_PER_VERTEX * 4;
        int vboModel = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboModel);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, baked.vertices, GL15.GL_STATIC_DRAW);
        attrib(0, 3, stride, 0);    // aPos
        attrib(1, 3, stride, 12);   // aNormal
        attrib(2, 2, stride, 24);   // aUV
        attrib(3, 1, stride, 32);   // aBone
        for (int i = 0; i < 4; i++) {
            GL20.glEnableVertexAttribArray(i);
        }

        int istride = FLOATS_PER_INSTANCE * 4;
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboInstance);
        attrib(4, 3, istride, 0);    // iPos
        attrib(5, 3, istride, 12);   // iMisc = (yaw, team, hurt)
        for (int i = 4; i < 6; i++) {
            GL20.glEnableVertexAttribArray(i);
            attribDivisor(i, 1);
        }

        mesh.ibo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, mesh.ibo);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, baked.indices, GL15.GL_STATIC_DRAW);

        GL30.glBindVertexArray(0);
    }

    private static void init() {
        program = createProgram();
        uProj = uniform("uProj");
        uView = uniform("uView");
        uTex = uniform("uTex");
        uPose = uniform("uPose");
        uRenderScale = uniform("uRenderScale");
        uFlip = uniform("uFlip");
        uYOffset = uniform("uYOffset");
        uRenderOffset = uniform("uRenderOffset");
        uPoseStride = uniform("uPoseStride");
        uTransBase = uniform("uTransBase");
        uTransSlot = uniform("uTransSlot");
        uScaleBase = uniform("uScaleBase");
        uScaleSlot = uniform("uScaleSlot");
        uBone[0] = uniform("uBonePivot");
        if (uProj < 0 || uView < 0 || uTex < 0 || uPose < 0
                || uRenderScale < 0 || uRenderOffset < 0 || uBone[0] < 0
                || uFlip < 0 || uYOffset < 0
                || uPoseStride < 0 || uTransBase < 0 || uTransSlot < 0
                || uScaleBase < 0 || uScaleSlot < 0) {
            throw new IllegalStateException("shader uniform 缺失");
        }
        maxPoseTexels = GL11.glGetInteger(GL31.GL_MAX_TEXTURE_BUFFER_SIZE);
        if (maxPoseTexels < 4096) {
            maxPoseTexels = 4096;
        }
        HordeMod.LOGGER.info("[horde] 姿态缓冲纹理上限 {} texel（约 {} 个单位 × {} 骨骼）",
                maxPoseTexels, maxPoseTexels / RigBaker.MAX_BONES, RigBaker.MAX_BONES);
        if (!initDivisorApi()) {
            throw new IllegalStateException("当前 GL 上下文不支持实例化顶点属性"
                    + "（OpenGL 3.3 与 ARB_instanced_arrays 都不可用）");
        }
        HordeMod.LOGGER.info("[horde] 群集渲染已初始化（实例化顶点属性入口: {}）",
                divisorApi == 1 ? "GL 3.3 core" : "ARB_instanced_arrays");

        vboInstance = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboInstance);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, 1024L * FLOATS_PER_INSTANCE * 4L, GL15.GL_DYNAMIC_DRAW);
        instanceCapacity = 1024;
    }

    private static int uniform(String name) {
        int loc = GL20.glGetUniformLocation(program, name);
        if (loc < 0) {
            loc = GL20.glGetUniformLocation(program, name + "[0]");
        }
        return loc;
    }

    /**
     * 实例化顶点属性除数的可用入口。
     *
     * Minecraft 1.20.1 申请的是 OpenGL 3.2 Core 上下文，而 glVertexAttribDivisor 是 3.3 才进核心；
     * 部分驱动（实测 AMD）在 3.2 上下文里并不会填好 GL33C 的函数指针，
     * 直接调用会以 "No context is current / function not available" **中止 JVM**（不可捕获）。
     * 所以这里先探测：能用 3.3 用 3.3，否则退回 ARB_instanced_arrays 扩展入口。
     */
    static boolean initDivisorApi() {
        org.lwjgl.opengl.GLCapabilities caps = GL.getCapabilities();
        if (caps.OpenGL33) {
            divisorApi = 1;
        } else if (caps.GL_ARB_instanced_arrays) {
            divisorApi = 2;
        } else {
            divisorApi = -1;
        }
        return divisorApi > 0;
    }

    static void attribDivisor(int index, int divisor) {
        if (divisorApi == 1) {
            GL33C.glVertexAttribDivisor(index, divisor);
        } else if (divisorApi == 2) {
            ARBInstancedArrays.glVertexAttribDivisorARB(index, divisor);
        } else {
            throw new IllegalStateException("当前 GL 上下文不支持实例化顶点属性"
                    + "（OpenGL 3.3 与 ARB_instanced_arrays 都不可用）");
        }
    }

    private static void attrib(int index, int size, int stride, long offset) {
        GL20.glVertexAttribPointer(index, size, GL11.GL_FLOAT, false, stride, offset);
    }

    private static int createProgram() {
        int vs = compileShader(GL20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fs = compileShader(GL20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, vs);
        GL20.glAttachShader(p, fs);
        GL20.glBindAttribLocation(p, 0, "aPos");
        GL20.glBindAttribLocation(p, 1, "aNormal");
        GL20.glBindAttribLocation(p, 2, "aUV");
        GL20.glBindAttribLocation(p, 3, "aBone");
        GL20.glBindAttribLocation(p, 4, "iPos");
        GL20.glBindAttribLocation(p, 5, "iMisc");
        GL20.glLinkProgram(p);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            throw new IllegalStateException("着色器链接失败: " + GL20.glGetProgramInfoLog(p));
        }
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        return p;
    }

    private static int compileShader(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException("着色器编译失败: " + log);
        }
        return shader;
    }

    // ------------------------------------------------------------------
    // 着色器
    // ------------------------------------------------------------------

    /**
     * 群集的顶点着色器源码。
     *
     * <p><b>它是「共用着色器」的源头</b>：{@link ProjectileRenderer} 的顶点着色器就是这份字符串
     * 加两处注入得到的。所以它必须能被离线用例读到（{@code projectileHarness} 会逐条比对
     * 两边的 uniform 清单）—— 真机事故「投掷物完全看不见」的根因正是
     * 「这里改了、那边的注入锚点没跟上」，而那种脱节只有比对才能发现。
     */
    public static final String VERTEX_SHADER = """
            #version 150

            in vec3 aPos;
            in vec3 aNormal;
            in vec2 aUV;
            in float aBone;

            in vec3 iPos;
            in vec3 iMisc;   // x=朝向(度) y=阵营 z=受击[0,1]

            uniform mat4 uProj;
            uniform mat4 uView;
            uniform vec4 uBonePivot[64];   // xyz 绑定姿态枢轴，w = 父骨骼 + 1
            uniform samplerBuffer uPose;   // 每实例：boneCount 个旋转 texel + 位移 texel + 缩放 texel
            uniform int uPoseStride;       // = 骨骼数 + 位移通道数 + 缩放通道数
            uniform int uTransBase;        // 位移 texel 起始下标（= 骨骼数）
            uniform int uTransSlot[64];    // 每骨骼的位移通道下标；-1 = 不平移
            uniform int uScaleBase;        // 缩放 texel 起始下标
            uniform int uScaleSlot[64];    // 每骨骼的缩放通道下标；-1 = 不缩放（恒为 1）
            uniform vec3 uRenderScale;     // 该类型 renderer.scale() 施加的缩放
            uniform vec3 uRenderOffset;    // -getRenderOffset()
            uniform vec2 uFlip;            // X/Y 翻转（原版 = (-1,-1)；geo = 按配置，默认 (1,1)）
            uniform float uYOffset;        // 高度偏移（原版 1.501；geo 用配置值）

            out vec2 vUV;
            out float vShade;
            out vec3 vColor;
            out float vHurt;

            mat3 rotX(float a) {
                float c = cos(a);
                float s = sin(a);
                return mat3(1.0, 0.0, 0.0,
                            0.0, c, s,
                            0.0, -s, c);
            }

            mat3 rotY(float a) {
                float c = cos(a);
                float s = sin(a);
                return mat3(c, 0.0, -s,
                            0.0, 1.0, 0.0,
                            s, 0.0, c);
            }

            mat3 rotZ(float a) {
                float c = cos(a);
                float s = sin(a);
                return mat3(c, s, 0.0,
                            -s, c, 0.0,
                            0.0, 0.0, 1.0);
            }

            // 与原版 ModelPart.translateAndRotate 的 rotationZYX(z, y, x) 一致
            mat3 rotZYX(float x, float y, float z) {
                return rotZ(z) * rotY(y) * rotX(x);
            }

            void main() {
                // 沿父链逐级旋转：自己 -> 父 -> 祖父 ...；角度是 CPU 上 setupAnim 算出来的原样结果
                vec3 pos = aPos;
                vec3 nrm = aNormal;
                int b = int(aBone + 0.5);
                for (int i = 0; i < 16; i++) {
                    if (b < 0) {
                        break;
                    }
                    int texelBase = gl_InstanceID * uPoseStride;
                    vec4 pose = texelFetch(uPose, texelBase + b);
                    vec3 trans = vec3(0.0);
                    int tslot = uTransSlot[b];
                    if (tslot >= 0) {
                        trans = texelFetch(uPose, texelBase + uTransBase + tslot).xyz;
                    }
                    // 逐骨骼缩放：与原版 ModelPart.translateAndRotate 一致 —— 先缩放、再旋转
                    vec3 scale = vec3(1.0);
                    int sslot = uScaleSlot[b];
                    if (sslot >= 0) {
                        scale = texelFetch(uPose, texelBase + uScaleBase + sslot).xyz;
                    }
                    mat3 R = rotZYX(pose.x, pose.y, pose.z);
                    vec4 piv = uBonePivot[b];
                    vec3 local = (pos - piv.xyz) * scale;
                    pos = R * local + piv.xyz + trans;
                    nrm = R * (nrm / max(scale, vec3(0.0001)));
                    b = int(piv.w + 0.5) - 1;
                }

                // 原版 LivingEntityRenderer 的模型变换链：
                //   translate(0, -1.501, 0) -> renderer.scale() -> scale(-1, -1, 1) -> 朝向
                // 但 geo(Bedrock) 模型的方块顶点**已经是最终朝向**，再翻转就会倒立，
                // 所以这三项（Y 偏移、X/Y 翻转）按后端区分，并可由配置微调。
                pos.y -= uYOffset;
                pos *= uRenderScale;
                pos.x *= uFlip.x;
                pos.y *= uFlip.y;
                nrm = vec3(nrm.x * uFlip.x, nrm.y * uFlip.y, nrm.z);

                mat3 yawRot = rotY(radians(180.0 - iMisc.x));
                vec3 world = yawRot * pos + iPos + uRenderOffset;
                vec3 n = yawRot * nrm;

                vec3 lightDir = normalize(vec3(0.45, 1.0, 0.35));
                float diff = max(dot(normalize(n), lightDir), 0.0);
                vShade = 0.62 + 0.38 * diff;

                vec3 teamTint = (iMisc.y < 0.5) ? vec3(0.80, 0.92, 1.00) : vec3(1.00, 0.86, 0.82);
                vColor = teamTint;
                vHurt = iMisc.z;

                vUV = aUV;
                gl_Position = uProj * uView * vec4(world, 1.0);
            }
            """;

    /** 片元着色器源码（投掷物**直接复用**这一份，不做任何注入） */
    public static final String FRAGMENT_SHADER = """
            #version 150

            uniform sampler2D uTex;

            in vec2 vUV;
            in float vShade;
            in vec3 vColor;
            in float vHurt;

            out vec4 fragColor;

            void main() {
                vec4 c = texture(uTex, vUV);
                if (c.a < 0.1) {
                    discard;
                }
                vec3 rgb = c.rgb * vColor * vShade;
                rgb = mix(rgb, vec3(1.0, 0.0, 0.0), 0.3 * vHurt);
                fragColor = vec4(rgb, c.a);
            }
            """;
}
