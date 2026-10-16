package dev.swordkingdom.horde.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.swordkingdom.horde.HordeConfig;
import dev.swordkingdom.horde.HordeMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Matrix4f;
import org.lwjgl.opengl.ARBInstancedArrays;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 虚拟投掷物的实例化渲染器 —— 每种投掷物类型一次 draw call。
 *
 * <h2>为什么可以复用群集那套管线</h2>
 * 投掷物的网格是用「捕获它自己渲染器画出来的顶点」得到的（见 {@link ProjectileBaker}），
 * 顶点格式与群集单位**完全一致**（位置/法线/UV/骨骼索引），骨骼数 = 1。
 * 于是这里就是 {@link CrowdRenderer} 的一个特例：
 *   - 姿态纹理每实例只有 1 个 texel（恒为 0 旋转），位移/缩放通道数为 0；
 *   - 多一个 uniform：{@code uYawOffset}，把「模型自己的朝向」对到速度方向
 *     （原版箭的模型指向 +X，而发射方向是 +Z，所以要 +90°，这个值可按配置调）。
 *
 * <h2>着色器一行没改</h2>
 * 顶点/片元着色器源码就是 {@link CrowdRenderer#VERTEX_SHADER}/{@link CrowdRenderer#FRAGMENT_SHADER}
 * 里那两份，只是额外注入一段 {@code uniform float uYawOffset;} 参与朝向计算 ——
 * 这是**新程序**，不影响已经验证过的群集着色器（shaderHarness 继续测同一份字符串）。
 *
 * <h2>真机事故（务必保留这些注释）：投掷物整类「完全看不见」</h2>
 * 真机日志只有一句
 * <pre>
 *   [horde] 投掷物实例化渲染失败，已自动停用（群集渲染不受影响）
 *   java.lang.IllegalStateException: 投掷物着色器 uniform 缺失
 *       at ... ProjectileRenderer.init(ProjectileRenderer.java:490)
 * </pre>
 * 根因是<b>字符串注入静默落空</b>：{@link #vertexShader()} 用「替换原文片段」的方式给群集
 * 着色器加 {@code uYawOffset} 与俯仰旋转，而上一轮改群集着色器时把被替换的那段原文
 * （朝向计算那两行）顺手改了（改成 {@code rotY(radians(180.0 - iMisc.x))}、并插进了
 * {@code uFlip}），于是 {@code String.replace} 什么都没换到：
 * GLSL 里从来没声明 {@code uYawOffset} → {@code glGetUniformLocation} 返回 -1 → 整类停用。
 * 教训有两条，都已落实：
 * <ol>
 *   <li><b>注入必须可验证</b>：{@link #replaceOnce} 要求锚点文本**恰好出现一次**，
 *       否则直接抛异常并把锚点原文打出来 —— 绝不允许「替换没生效但继续跑」；</li>
 *   <li><b>失败必须指名道姓</b>：uniform 逐个定位，缺失的名字逐条列出，
 *       并且区分「必需」（缺一个就不能画）与「可选」（缺了用默认值继续画）。</li>
 * </ol>
 */
public final class ProjectileRenderer {

    /** 每实例 float 数：位置 3 + (朝向, 阵营, 俯仰) 3 */
    private static final int FLOATS_PER_INSTANCE = 6;
    private static final float RENDER_DISTANCE = 256.0F;
    private static final int POSE_TEXTURE_UNIT = 3;

    private static final float[] PROJ_MAT = new float[16];
    private static final float[] VIEW_MAT = new float[16];
    /** 每骨骼位移通道下标（投掷物恒为 -1：整枚弹就是一个骨骼，没有位移动画） */
    private static final int[] transSlotUpload = new int[RigBaker.MAX_BONES];
    /** 每骨骼缩放通道下标（投掷物恒为 -1：不做缩放动画） */
    private static final int[] scaleSlotUpload = new int[RigBaker.MAX_BONES];
    private static final float[] dummyPivot = new float[4];
    /** 每实例 1 个骨骼 × 4 float 的姿态上传缓冲 */
    private static float[] poseUpload = new float[0];
    private static FloatBuffer poseBuffer;
    /** uBonePivot[0] 的 uniform 位置（数组 uniform 用基准名定位） */
    private static int uBonePivot;

    private static boolean initialized;
    private static boolean broken;
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
    private static int uYawOffset;
    // 下面这三个与群集共用同一份 GLSL，所以必须与 CrowdRenderer 一样被显式赋值：
    // 不赋值时它们的默认值是 0，而 0 在着色器里是**合法但错误**的语义
    // （uPoseStride=0 会让所有实例都读第 0 个 texel、uTransSlot[0]=0 会去读位移通道 0）。
    private static int uPoseStride;
    private static int uTransBase;
    private static int uTransSlot;
    private static int uScaleBase;
    private static int uScaleSlot;

    /**
     * 着色器**必需**的 uniform（缺任何一个都不能正确绘制，只能停用并逐条报名字）。
     *
     * 为什么用集合常量而不是散落的局部变量：{@link dev.swordkingdom.horde.harness.ProjectileHarness}
     * 要用反射比对「这里声明的名字集合」与「GLSL 真正声明/使用的名字集合」——
     * 这一次的事故正是两者悄悄脱节（GLSL 改了、Java 清单没跟上）。
     */
    public static final Set<String> REQUIRED_UNIFORMS = Set.of(
            "uProj", "uView", "uTex", "uPose",
            "uBonePivot", "uPoseStride", "uTransBase", "uTransSlot",
            "uScaleBase", "uScaleSlot",
            "uRenderScale", "uRenderOffset", "uFlip", "uYOffset",
            // 投掷物自己注入的那一个（把模型朝向对到速度方向）
            "uYawOffset");

    /**
     * 允许缺失的 uniform（缺了就用默认值继续画，不停用渲染）。
     *
     * 为什么要有这一档：GLSL 编译器会把「被优化掉」的 uniform 剔除，
     * 于是 {@code glGetUniformLocation} 返回 -1 是**正常现象**（上一版删掉 {@code uBoneStride}
     * 就是因为这个）。把「可选」与「必需」分开，才能做到「只在真的必需时才停用」。
     */
    public static final Set<String> OPTIONAL_UNIFORMS = Set.of();

    private static float[] instanceData = new float[0];
    private static FloatBuffer instanceBuffer = MemoryUtil.memAllocFloat(0);
    private static int instanceCapacity;

    private static final Map<ResourceLocation, TypeMesh> MESHES = new HashMap<>();
    private static int debugCounter;

    private ProjectileRenderer() {}

    /** 每种投掷物类型一份 GL 资源 */
    private static final class TypeMesh {
        ResourceLocation typeId;
        int vao;
        int ibo;
        int indexCount;
        ResourceLocation texture;
        boolean failed;
        /** 烘焙出来的主轴/朝向偏移（度）：按配置覆盖或自动判断 */
        float autoYawOffset;
        int poseBuffer;
        int poseTexture;
    }

    /** 覆盖表/类型表变了 -> 作废网格（GL 资源在渲染线程释放） */
    public static void invalidateAll() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) {
            releaseMeshes();
        } else {
            mc.execute(ProjectileRenderer::releaseMeshes);
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

    /**
     * 由 {@link CrowdRenderer} 在**同一个**渲染阶段调用。
     *
     * <p>★ 真机事故（本轮）：这里以前从 {@code RenderSystem.getModelViewMatrix()} 取视图矩阵，
     * 那一份**含摄像机平移**，而 {@code iPos} 已经是「世界 − 摄像机」⇒ 摄像机被减两次，
     * 投掷物就「粘在玩家视角上」。现在改成与群集**完全同一个来源**：
     * 事件自己的 PoseStack（{@link ViewMatrices#capture}）。
     */
    static void render(net.minecraftforge.client.event.RenderLevelStageEvent event) {
        if (broken) {
            return;
        }
        ClientProjectileData data = ClientProjectileData.forCurrentLevel();
        if (data == null || data.count <= 0 || data.types.length == 0) {
            return;
        }
        try {
            renderInner(data, event);
        } catch (Throwable t) {
            broken = true;
            HordeMod.LOGGER.error("[horde] 投掷物实例化渲染失败，已自动停用（群集渲染不受影响）", t);
        }
    }

    private static void renderInner(ClientProjectileData data,
                                    net.minecraftforge.client.event.RenderLevelStageEvent event) {
        if (!initialized) {
            init();
            initialized = true;
        }
        Vec3 camera = event.getCamera().getPosition();
        float camX = (float) camera.x;
        float camY = (float) camera.y;
        float camZ = (float) camera.z;
        float maxD2 = RENDER_DISTANCE * RENDER_DISTANCE;
        float alpha = data.alpha();

        ensureCapacity(data.count);
        float[] buf = instanceData;
        int[] ids = data.ids;
        int total = data.count;
        int[] types = data.curType;

        // ★ 与群集**同一份**视图矩阵（同一次捕获、同一个来源；见 ViewMatrices 的契约）
        ViewMatrices.capture(event, 1);
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
        for (int t = 0; t < data.types.length; t++) {
            TypeMesh mesh = meshFor(data.types[t]);
            if (mesh == null) {
                continue;
            }
            int written = 0;
            for (int i = 0; i < total; i++) {
                int id = ids[i];
                if (types[id] != t) {
                    continue;
                }
                float pX = data.prvX(id);
                float pY = data.prvY(id);
                float pZ = data.prvZ(id);
                float cX = data.curX[id];
                float cY = data.curY[id];
                float cZ = data.curZ[id];
                float tdx = cX - pX;
                float tdy = cY - pY;
                float tdz = cZ - pZ;
                // 一帧位移超过 4 格视为「传送/新出现」：不插值，避免拉出一条长线
                float a = (tdx * tdx + tdy * tdy + tdz * tdz > 16.0F) ? 1.0F : alpha;
                float x = pX + tdx * a;
                float y = pY + tdy * a;
                float z = pZ + tdz * a;
                float dx = x - camX;
                float dy = y - camY;
                float dz = z - camZ;
                if (dx * dx + dy * dy + dz * dz > maxD2 || written >= 65536) {
                    continue;
                }
                int b = written * FLOATS_PER_INSTANCE;
                buf[b] = dx;
                buf[b + 1] = dy;
                buf[b + 2] = dz;
                // ★ 前 3 帧的一次性探针：把「世界坐标 / 摄像机 / 上传的实例位置 / 视图矩阵 /
                //   按着色器公式算出的相机相对量与裁剪空间坐标」全打出来（之后自动关，不刷屏）
                if (probeFrames > 0 && written == 0) {
                    float[] ip = new float[]{dx, dy, dz};
                    float[] clip = ViewMatrices.project(ip);
                    HordeMod.LOGGER.info("[horde] 投掷物位置诊断（第 {} 帧）：世界 P=({}, {}, {})，"
                                    + "摄像机 C=({}, {}, {})，上传 iPos=({}, {}, {})｜视图 3×3={} 平移=({}, {}, {})"
                                    + "｜相机相对=(同上 iPos)｜裁剪 xy/z/w=({}, {}, {}, {})",
                            probeFrames, trim(x), trim(y), trim(z), trim(camX), trim(camY), trim(camZ),
                            trim(dx), trim(dy), trim(dz), ViewMatrices.describeRotation(),
                            trim(ViewMatrices.VIEW[12]), trim(ViewMatrices.VIEW[13]), trim(ViewMatrices.VIEW[14]),
                            trim(clip[3]), trim(clip[4]), trim(clip[5]), trim(clip[6]));
                }
                buf[b + 3] = lerpAngle(data.prvYaw(id), data.curYawOf(id), a);
                buf[b + 4] = data.curTeam[id];
                // iMisc.z 复用为**俯仰**（群集那边是受击闪红；投掷物不做闪红，改成俯仰更有用）
                buf[b + 5] = lerp(data.prvPitch(id), data.curPitchOf(id), a) / 90.0F;
                written++;
            }
            if (written == 0) {
                continue;
            }
            uploadAndDraw(mesh, buf, written);
            drawn += written;
        }

        GL13.glActiveTexture(GL13.GL_TEXTURE0 + POSE_TEXTURE_UNIT);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL30.glBindVertexArray(0);
        GL20.glUseProgram(prevProgram);
        RenderSystem.setShader(GameRenderer::getRendertypeEntityCutoutShader);

        if ((debugCounter++ % 200) == 0 && drawn > 0) {
            HordeMod.LOGGER.info("[horde] 投掷物渲染：clientProjectiles={} drawn={} types={}",
                    total, drawn, data.types.length);
        }
        if (probeFrames > 0) {
            probeFrames--;   // 前 3 帧打完自动关（一次性插桩，不刷屏）
        }
    }

    /** 位置诊断的剩余帧数（默认 3：够看清「世界坐标 vs 上传值」，之后自动关） */
    private static int probeFrames = 3;

    /** 诊断里的数值格式 */
    private static String trim(float v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    private static void uploadAndDraw(TypeMesh mesh, float[] buf, int written) {
        instanceBuffer.clear();
        instanceBuffer.put(buf, 0, written * FLOATS_PER_INSTANCE);
        instanceBuffer.flip();

        RenderSystem.setShaderTexture(0, mesh.texture);
        if (!uploadPose(mesh, written)) {
            return;
        }

        GL30.glBindVertexArray(mesh.vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboInstance);
        // 骨骼数 1、枢轴 0、父 = -1（整枚投掷物就是一个骨骼，没有父链）
        dummyPivot[0] = 0.0F;
        dummyPivot[1] = 0.0F;
        dummyPivot[2] = 0.0F;
        dummyPivot[3] = 0.0F;
        GL20.glUniform4fv(uBonePivot, dummyPivot);
        GL20.glUniform3f(uRenderScale, 1.0F, 1.0F, 1.0F);
        GL20.glUniform3f(uRenderOffset, 0.0F, 0.0F, 0.0F);
        float flipX = dev.swordkingdom.horde.HordeConfig.safeGet(
                HordeConfig.COMMON.projectileFlipX, true) ? -1.0F : 1.0F;
        float flipY = dev.swordkingdom.horde.HordeConfig.safeGet(
                HordeConfig.COMMON.projectileFlipY, true) ? -1.0F : 1.0F;
        GL20.glUniform2f(uFlip, flipX, flipY);
        GL20.glUniform1f(uYOffset, dev.swordkingdom.horde.HordeConfig.safeGet(
                HordeConfig.COMMON.projectileYOffset, 0.0F));
        GL20.glUniform1f(uYawOffset, mesh.autoYawOffset);
        // ---- 姿态通道：与 CrowdRenderer.uploadAndDraw 的语义**逐项一致** ----
        // 投掷物每实例只有 1 个旋转 texel（恒为 0 旋转）、0 个位移通道、0 个缩放通道：
        //   uPoseStride = 1   -> 每个实例恰好占 1 个 texel
        //   uTransBase / uScaleBase = 1（都在第 0 个 texel 之后；通道数 0，槽位恒 -1，不会被读到）
        // 这几个 uniform 与群集共用同一份 GLSL，所以**必须显式赋值**：不赋值时它们默认是 0，
        // 而 0 是「合法但错误」的语义（uPoseStride=0 会让所有实例都读第 0 个 texel）。
        uploadPoseChannelUniforms(1, 0, 0);
        java.util.Arrays.fill(transSlotUpload, -1);
        java.util.Arrays.fill(scaleSlotUpload, -1);
        GL20.glUniform1iv(uTransSlot, transSlotUpload);
        GL20.glUniform1iv(uScaleSlot, scaleSlotUpload);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, instanceBuffer);
        GL31.glDrawElementsInstanced(GL11.GL_TRIANGLES, mesh.indexCount, GL11.GL_UNSIGNED_INT, 0L, written);
    }

    /**
     * 下发「姿态纹理布局」这一组 uniform。
     *
     * 与 {@link CrowdRenderer#uploadAndDraw} 里那一段是同一套公式（源码里逐字对应，便于人工比对）：
     * <pre>
     *   uPoseStride = 骨骼数 + 位移通道数 + 缩放通道数
     *   uTransBase  = 骨骼数
     *   uScaleBase  = 骨骼数 + 位移通道数
     * </pre>
     * 投掷物的通道数恒为 0（整枚弹就是一个骨骼、无位移/缩放动画），但**仍然要下发** ——
     * 它们和群集共用同一份 GLSL，默认值 0 在那里是合法但错误的语义。
     */
    private static void uploadPoseChannelUniforms(int boneCount, int transCount, int scaleCount) {
        GL20.glUniform1i(uPoseStride, boneCount + transCount + scaleCount);
        GL20.glUniform1i(uTransBase, boneCount);
        GL20.glUniform1i(uScaleBase, boneCount + transCount);
    }

    /**
     * 上传姿态纹理。
     *
     * 每实例只有 1 个纹理单元（RGBA32F）：旋转恒为 0 ——
     * 投掷物的朝向完全由实例属性（yaw/pitch）决定，没有骨架动画。
     * 之所以仍然走「缓冲纹理」这条路，是为了和 {@link CrowdRenderer} 共用同一套
     * 顶点着色器（它无条件 texelFetch 姿态缓冲），而不是为了传姿态数据。
     *
     * @return 是否可继续绘制
     */
    private static boolean uploadPose(TypeMesh mesh, int rows) {
        if (rows <= 0) {
            return false;
        }
        if (mesh.poseBuffer == 0) {
            mesh.poseBuffer = GL15.glGenBuffers();
            GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, mesh.poseBuffer);
            GL15.glBufferData(GL31.GL_TEXTURE_BUFFER, (long) Math.max(4096, rows) * 16L, GL15.GL_DYNAMIC_DRAW);
            mesh.poseTexture = GL11.glGenTextures();
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, mesh.poseTexture);
            GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32F, mesh.poseBuffer);
        }
        int needFloats = rows * 4;
        if (poseBuffer == null || poseBuffer.capacity() < needFloats) {
            if (poseBuffer != null) {
                MemoryUtil.memFree(poseBuffer);
            }
            poseBuffer = MemoryUtil.memAllocFloat(Math.max(needFloats, 4096));
        }
        if (poseUpload.length < needFloats) {
            poseUpload = new float[Math.max(needFloats, 4096)];
        }
        FloatBuffer pb = poseBuffer;
        pb.clear();
        for (int i = 0; i < rows; i++) {
            pb.put(poseUpload[i * 4]);
            pb.put(poseUpload[i * 4 + 1]);
            pb.put(poseUpload[i * 4 + 2]);
            pb.put(poseUpload[i * 4 + 3]);
        }
        pb.flip();
        GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, mesh.poseBuffer);
        GL15.glBufferSubData(GL31.GL_TEXTURE_BUFFER, 0L, pb);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + POSE_TEXTURE_UNIT);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, mesh.poseTexture);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        return true;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

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

    private static void ensureCapacity(int count) {
        int needFloats = count * FLOATS_PER_INSTANCE;
        if (instanceData.length < needFloats) {
            instanceData = new float[needFloats];
        }
        if (instanceBuffer.capacity() < needFloats) {
            MemoryUtil.memFree(instanceBuffer);
            instanceBuffer = MemoryUtil.memAllocFloat(Math.max(needFloats, instanceBuffer.capacity() * 2));
        }
        if (instanceCapacity < count) {
            int newCap = Math.max(count, instanceCapacity * 2);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboInstance);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) newCap * FLOATS_PER_INSTANCE * 4L, GL15.GL_DYNAMIC_DRAW);
            instanceCapacity = newCap;
        }
    }

    // ------------------------------------------------------------------
    // 网格（按需烘焙）
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
            if (type == null) {
                mesh.failed = true;
            } else {
                ProjectileBaker.Mesh baked = ProjectileBaker.bake(type, renderItemFor(typeId));
                if (!baked.supported || baked.indices.length == 0) {
                    mesh.failed = true;
                    HordeMod.LOGGER.warn("[horde] 投掷物类型 {} 烘焙失败，已跳过", typeId);
                } else {
                    mesh.texture = baked.texture;
                    mesh.indexCount = baked.indices.length;
                    mesh.autoYawOffset = yawOffsetFor(baked);
                    createVao(mesh, baked);
                }
            }
        } catch (Throwable t) {
            mesh.failed = true;
            HordeMod.LOGGER.warn("[horde] 投掷物类型 {} 烘焙异常，已跳过", typeId, t);
        }
        MESHES.put(typeId, mesh);
        return mesh.failed ? null : mesh;
    }

    /**
     * 该投掷物类型「发射用物品」。
     *
     * 只有**物品投掷物**（雪球/蛋/珍珠以及模组里同类的东西）的渲染器需要它 ——
     * 它从实体身上的 ItemStack 取模型。玩家在技能页签里给任意一个动作配了物品就用它；
     * 没配则为空（渲染器会画出空物品，随后 {@link ProjectileBaker} 退化成方块网格）。
     */
    static String renderItemFor(ResourceLocation typeId) {
        dev.swordkingdom.horde.edit.TypeOverride self =
                dev.swordkingdom.horde.edit.TypeOverride.get(typeId);
        if (self == null || self.actions == null) {
            return "";
        }
        for (dev.swordkingdom.horde.edit.ProjectileSkill s : self.actions.values()) {
            if (s != null && s.renderItem != null && !s.renderItem.isEmpty()) {
                return s.renderItem;
            }
        }
        return "";
    }

    /**
     * 把「模型自己的朝向」对齐到速度方向所需的额外偏航。
     *
     * 渲染里的 yaw 约定是 {@code (-sin, ·, cos)}，也就是 yaw=0 时朝 +Z、yaw=90 时朝 +X。
     * 而烘焙出来的网格朝哪个方向由它的**主轴**决定（箭是长条，主轴即箭头方向）：
     * <pre>
     *   主轴 +X -> 模型朝 +X -> 需要总的 90°，所以偏移 +90
     *   主轴 -X -> 偏移 -90
     *   主轴 +Z -> 已经对上 -> 0
     *   主轴 -Z -> 180
     * </pre>
     * 再加上玩家在配置里填的固定偏移（原版箭的模型与朝向差 90°，就靠它补）。
     */
    static float yawOffsetFor(ProjectileBaker.Mesh baked) {
        float base;
        if (baked.principalAxis == 0) {
            base = baked.principalPositive ? 90.0F : -90.0F;
        } else if (baked.principalAxis == 2) {
            base = baked.principalPositive ? 0.0F : 180.0F;
        } else {
            // 主轴是 Y（上下长条）：没有可用的朝向信息，直接用配置值
            base = 0.0F;
        }
        return base + dev.swordkingdom.horde.HordeConfig.safeGet(
                HordeConfig.COMMON.projectileYawOffset, 0.0F);
    }

    private static void createVao(TypeMesh mesh, ProjectileBaker.Mesh baked) {
        mesh.vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(mesh.vao);

        int stride = ProjectileBaker.FLOATS_PER_VERTEX * 4;
        int vboModel = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboModel);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, baked.vertices, GL15.GL_STATIC_DRAW);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, stride, 0L);
        GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, stride, 12L);
        GL20.glVertexAttribPointer(2, 2, GL11.GL_FLOAT, false, stride, 24L);
        GL20.glVertexAttribPointer(3, 1, GL11.GL_FLOAT, false, stride, 32L);
        for (int i = 0; i < 4; i++) {
            GL20.glEnableVertexAttribArray(i);
        }

        int istride = FLOATS_PER_INSTANCE * 4;
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboInstance);
        GL20.glVertexAttribPointer(4, 3, GL11.GL_FLOAT, false, istride, 0L);
        GL20.glVertexAttribPointer(5, 3, GL11.GL_FLOAT, false, istride, 12L);
        for (int i = 4; i < 6; i++) {
            GL20.glEnableVertexAttribArray(i);
            attribDivisor(i, 1);
        }

        mesh.ibo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, mesh.ibo);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, baked.indices, GL15.GL_STATIC_DRAW);
        GL30.glBindVertexArray(0);
    }

    // ------------------------------------------------------------------
    // GL 初始化
    // ------------------------------------------------------------------

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
        uYawOffset = uniform("uYawOffset");
        uPoseStride = uniform("uPoseStride");
        uTransBase = uniform("uTransBase");
        uTransSlot = uniform("uTransSlot");
        uScaleBase = uniform("uScaleBase");
        uScaleSlot = uniform("uScaleSlot");
        uBonePivot = uniform("uBonePivot");
        // ---- 防呆：逐条列出缺失的 uniform，并且只在**真的必需**时才停用 ----
        // 上一版只报一句「投掷物着色器 uniform 缺失」，于是真机上只知道「失败了」，
        // 完全不知道缺的是哪一个（实际缺的是注入落空导致的 uYawOffset）。
        List<String> missing = new ArrayList<>(2);
        if (uYawOffset < 0) {
            missing.add("uYawOffset");
        }
        checkRequired("uProj", uProj, missing);
        checkRequired("uView", uView, missing);
        checkRequired("uTex", uTex, missing);
        checkRequired("uPose", uPose, missing);
        checkRequired("uBonePivot", uBonePivot, missing);
        checkRequired("uPoseStride", uPoseStride, missing);
        checkRequired("uTransBase", uTransBase, missing);
        checkRequired("uTransSlot", uTransSlot, missing);
        checkRequired("uScaleBase", uScaleBase, missing);
        checkRequired("uScaleSlot", uScaleSlot, missing);
        checkRequired("uRenderScale", uRenderScale, missing);
        checkRequired("uRenderOffset", uRenderOffset, missing);
        checkRequired("uFlip", uFlip, missing);
        checkRequired("uYOffset", uYOffset, missing);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("投掷物着色器 uniform 缺失（共 " + missing.size() + " 个）："
                    + String.join("、", missing)
                    + "；需要 " + REQUIRED_UNIFORMS.size() + " 个，实际缺 " + missing.size()
                    + " 个。"
                    + (missing.contains("uYawOffset")
                    ? "uYawOffset 是**注入**进来的：缺它说明 vertexShader() 的字符串注入没匹配上"
                    + "（群集着色器源码改了、注入锚点没跟着改）—— 见 ProjectileRenderer.vertexShader()。"
                    : ""));
        }
        // 可选 uniform：缺了只是用默认值，**不能**因此停用整条渲染
        for (String name : OPTIONAL_UNIFORMS) {
            if (uniform(name) < 0) {
                HordeMod.LOGGER.info("[horde] 投掷物着色器可选 uniform {} 不存在（被 GLSL 优化掉是正常的），用默认值继续", name);
            }
        }
        if (!initDivisorApi()) {
            throw new IllegalStateException("当前 GL 上下文不支持实例化顶点属性");
        }
        vboInstance = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboInstance);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, 1024L * FLOATS_PER_INSTANCE * 4L, GL15.GL_DYNAMIC_DRAW);
        instanceCapacity = 1024;
        HordeMod.LOGGER.info("[horde] 投掷物实例化渲染已初始化（1 骨骼/枚，共用群集着色器；"
                + "uniform {} 个全部就位：{}）", REQUIRED_UNIFORMS.size(),
                String.join("、", new java.util.TreeSet<>(REQUIRED_UNIFORMS)));
    }

    /** 某个必需 uniform 定位失败就记名字（由调用方汇总成一条可读异常） */
    private static void checkRequired(String name, int location, List<String> missing) {
        if (location < 0) {
            missing.add(name);
        }
    }

    private static int uniform(String name) {
        int loc = GL20.glGetUniformLocation(program, name);
        if (loc < 0) {
            loc = GL20.glGetUniformLocation(program, name + "[0]");
        }
        return loc;
    }

    private static boolean initDivisorApi() {
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

    private static void attribDivisor(int index, int divisor) {
        if (divisorApi == 1) {
            GL33C.glVertexAttribDivisor(index, divisor);
        } else if (divisorApi == 2) {
            ARBInstancedArrays.glVertexAttribDivisorARB(index, divisor);
        } else {
            throw new IllegalStateException("当前 GL 上下文不支持实例化顶点属性");
        }
    }

    private static int createProgram() {
        int vs = compileShader(GL20.GL_VERTEX_SHADER, vertexShader());
        int fs = compileShader(GL20.GL_FRAGMENT_SHADER, CrowdRenderer.FRAGMENT_SHADER);
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
            throw new IllegalStateException("投掷物着色器链接失败: " + GL20.glGetProgramInfoLog(p));
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
            throw new IllegalStateException("投掷物着色器编译失败: " + log);
        }
        return shader;
    }

    /**
     * 投掷物的顶点着色器 = 群集的顶点着色器 + 两处改动：
     *   1. 多一个 {@code uniform float uYawOffset;}（把模型朝向对到速度方向）；
     *   2. {@code iMisc.z} 解释成**俯仰**（[-1,1]），绕侧向轴转一下，让箭/火球跟着抛物线低头抬头。
     *
     * 还是那句话：这是**新程序**，{@link CrowdRenderer} 那份着色器字符串一个字符都没动。
     *
     * <h2>为什么不能再用「随手 String.replace」</h2>
     * 真机事故的根因就是这里：上一轮改群集着色器时把被替换的原文改了
     * （朝向计算改成 {@code rotY(radians(180.0 - iMisc.x))}、并插进了 {@code uFlip}），
     * 而 {@code replace} 的锚点没跟着改 —— 替换<b>静默落空</b>，GLSL 里没有 {@code uYawOffset}，
     * 客户端渲染器初始化时报「uniform 缺失」并自我停用，于是上百枚箭一枚都看不见。
     * 现在两处注入都走 {@link #replaceOnce}：锚点必须**恰好出现一次**，否则立刻抛异常
     * 并把锚点原文与着色器现状打出来 —— 绝不接受「没换到但继续跑」。
     */
    public static String vertexShader() {
        String base = CrowdRenderer.VERTEX_SHADER;
        // 注入 uniform 声明（锚点里的空白与着色器不完全一致时按逐行去缩进兜一次）
        base = replaceOnceLoose(base, UNIFORM_ANCHOR,
                UNIFORM_ANCHOR + "\n            uniform float uYawOffset;   // 模型朝向 -> 速度方向的额外偏航（度）");
        // 替换朝向计算：加上 yawOffset，并按 iMisc.z 做俯仰
        base = replaceOnceLoose(base, ORIENT_ANCHOR, ORIENT_REPLACEMENT);
        return base;
    }

    /** 注入 {@code uYawOffset} 声明用的锚点（必须与 {@link CrowdRenderer#VERTEX_SHADER} 里那行**逐字**一致） */
    public static final String UNIFORM_ANCHOR = "uniform float uYOffset;        // 高度偏移（原版 1.501；geo 用配置值）";

    // ------------------------------------------------------------------
    // uniform 清单审计（离线可测 —— 这次事故的防呆）
    // ------------------------------------------------------------------

    /** GLSL 里 {@code uniform 类型 名字;} 的声明形式（类型段不含分号） */
    private static final java.util.regex.Pattern UNIFORM_DECL =
            java.util.regex.Pattern.compile("uniform\\s+[A-Za-z0-9_]+\\s+([A-Za-z_][A-Za-z0-9_]*)");

    /**
     * 一份着色器源码里**声明**的 uniform 名字集合（数组取基准名，与
     * {@code glGetUniformLocation(program, "uScaleSlot")} 的用法一致）。
     */
    public static Set<String> declaredUniforms(String source) {
        Set<String> out = new LinkedHashSet<>();
        java.util.regex.Matcher m = UNIFORM_DECL.matcher(source);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /**
     * 一份着色器源码里**真的被用到**的 uniform 名字集合（声明行之外还出现过）。
     *
     * 为什么要单独算「用到的」：GLSL 编译器会把没被用到的 uniform 优化掉，
     * {@code glGetUniformLocation} 于是返回 -1 —— 那只说明「用不到」，不是缺失。
     * 把「用到」与「声明了」分开，才能做到「只在真的必需时才停用渲染」。
     */
    public static Set<String> usedUniforms(String source) {
        Set<String> declared = declaredUniforms(source);
        Set<String> used = new LinkedHashSet<>();
        for (String line : source.split("\n", -1)) {
            if (UNIFORM_DECL.matcher(line).find()) {
                continue;   // 跳过声明行本身
            }
            for (String name : declared) {
                if (containsWord(line, name)) {
                    used.add(name);
                }
            }
        }
        return used;
    }

    /** 词边界包含（避免 {@code uScale} 命中 {@code uScaleBase}） */
    private static boolean containsWord(String text, String word) {
        int from = 0;
        while (true) {
            int i = text.indexOf(word, from);
            if (i < 0) {
                return false;
            }
            boolean left = i == 0 || !isIdent(text.charAt(i - 1));
            int end = i + word.length();
            boolean right = end >= text.length() || !isIdent(text.charAt(end));
            if (left && right) {
                return true;
            }
            from = i + 1;
        }
    }

    private static boolean isIdent(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /**
     * 把 {@link #REQUIRED_UNIFORMS} 与着色器源码逐条对上，返回**可读的审计结果**（空串 = 全部对上）。
     *
     * 这是这次事故的防呆核心：真机上「投掷物完全看不见」的根因是 GLSL 里少了一个 uniform
     * （注入静默落空），而**离线**只能靠这份逐条比对发现。`projectileHarness` 调用它，
     * 一旦脱节就会把缺的名字逐条打出来。
     *
     * <p>只报**真问题**（3 类）：着色器用了但没声明、着色器用了但清单没登记、清单要求但着色器没声明。
     * 「声明了但没用到」是正常现象（会被 GLSL 优化掉），由 {@link #uniformHints()} 单独给提示。
     */
    public static String auditUniforms() {
        String vs = vertexShader();
        Set<String> declared = declaredUniforms(vs);
        Set<String> used = usedUniforms(vs);
        StringBuilder sb = new StringBuilder();
        for (String name : new java.util.TreeSet<>(used)) {
            if (!declared.contains(name)) {
                sb.append("着色器用了但没声明：").append(name).append("；");
            }
            if (!REQUIRED_UNIFORMS.contains(name) && !OPTIONAL_UNIFORMS.contains(name)) {
                sb.append("着色器用到但清单里没登记：").append(name).append("；");
            }
        }
        // 片元着色器那一份（uTex 就在那里）也算「声明过」：投掷物直接复用群集的片元着色器
        Set<String> fsDeclared = declaredUniforms(CrowdRenderer.FRAGMENT_SHADER);
        for (String name : new java.util.TreeSet<>(REQUIRED_UNIFORMS)) {
            if (!declared.contains(name) && !fsDeclared.contains(name)) {
                sb.append("清单要求但着色器没声明：").append(name).append("；");
            }
        }
        return sb.toString();
    }

    /**
     * 「声明了但没被用到」的 uniform —— 不是缺失，只是会被 GLSL 优化掉。
     *
     * <p>为什么要单独列出来：投掷物是「单骨骼 + 零位移/缩放通道」的用法，
     * 姿态布局那几个 uniform（{@code uPoseStride}/{@code uTransSlot}/{@code uScaleSlot} …）
     * 在常量折叠后确实可能被判为未使用。把它们与「真的缺失」混在一起，
     * 就会出现「日志说缺了三个，其实一个都不缺」这种误导 —— 上一版排查困难的来源之一。
     */
    public static String uniformHints() {
        String vs = vertexShader();
        Set<String> declared = declaredUniforms(vs);
        declared.addAll(declaredUniforms(CrowdRenderer.FRAGMENT_SHADER));
        // 「用到了」要把两份源码合起来算：uTex 在片元着色器里用，单独看顶点着色器会误报
        Set<String> used = usedUniforms(vs + "\n" + CrowdRenderer.FRAGMENT_SHADER);
        StringBuilder sb = new StringBuilder();
        for (String name : new java.util.TreeSet<>(REQUIRED_UNIFORMS)) {
            if (declared.contains(name) && !used.contains(name)) {
                sb.append(name).append("、");
            }
        }
        return sb.toString();
    }

    /** 清单的**可读形式**（日志/GUI 用：真机排查时一眼看出「该有哪几个」） */
    public static String uniformManifest() {
        return "必需 " + REQUIRED_UNIFORMS.size() + " 个："
                + String.join("、", new java.util.TreeSet<>(REQUIRED_UNIFORMS))
                + (OPTIONAL_UNIFORMS.isEmpty() ? "" : "；可选：" + String.join("、", OPTIONAL_UNIFORMS));
    }

    /**
     * 替换「朝向计算」用的锚点 —— **必须与 {@link CrowdRenderer#VERTEX_SHADER} 里那一段逐字一致**。
     *
     * <p>上一版这里写的是更老的原文（只有 {@code yawRot * pos} 两行、没有 {@code uRenderScale}/
     * {@code uFlip} 那几步的前置处理），群集着色器一改就再也匹配不上 —— 这就是真机上
     * {@code uYawOffset} 从未被声明、投掷物整类不可见的根因。现在 {@link #replaceOnce}
     * 会在匹配不上时直接抛异常（带锚点原文），所以这种漂移再也不可能静默通过。
     */
    /**
     * GLSL 里那段代码的缩进（与 {@link CrowdRenderer#VERTEX_SHADER} 的实际缩进一致）。
     *
     * <p><b>这个值必须用「运行时实测」的口径来理解</b>：源文件里的文本块缩进是 16 空格，
     * 但文本块的「附带缩进」规则会把共同的最小缩进（12）剥掉，于是运行时字符串里只剩 4 空格。
     * 用文本块常量当锚点会**看起来对、实际差 12 个空格**——这正是真机上注入静默落空、
     * 投掷物整类不可见的同一个坑（见类注释）。
     */
    private static final String IND = " ".repeat(4);

    /**
     * 替换「朝向计算」用的锚点 —— 必须与 {@link CrowdRenderer#VERTEX_SHADER} 里那一段逐字一致。
     *
     * <p>用字符串拼接而不是文本块：拼接里的空白是**显式**的，一眼能数清，也不会被编译器
     * 的「附带缩进」规则改写。{@link #replaceOnceLoose} 还会在字面匹配不上时按「逐行去缩进」
     * 再试一次，于是群集着色器将来换缩进也不会把投掷物整类打瞎。
     */
    public static final String ORIENT_ANCHOR = IND + "mat3 yawRot = rotY(radians(180.0 - iMisc.x));\n"
            + IND + "vec3 world = yawRot * pos + iPos + uRenderOffset;\n"
            + IND + "vec3 n = yawRot * nrm;\n";

    /** 替换后的朝向计算（多一个 uYawOffset 偏航 + 一个 iMisc.z 俯仰） */
    public static final String ORIENT_REPLACEMENT = IND + "mat3 yawRot = rotY(radians(180.0 + uYawOffset - iMisc.x));\n"
            + IND + "// 俯仰：iMisc.z 在群集里是受击量，投掷物这边复用为俯仰 [-1,1]。\n"
            + IND + "// 绕模型自己的侧向轴（rotX）转，正好让「前方 +Z」抬头/低头\n"
            + IND + "mat3 pitchRot = rotX(radians(-iMisc.z * 90.0));\n"
            + IND + "vec3 world = yawRot * (pitchRot * pos) + iPos + uRenderOffset;\n"
            + IND + "vec3 n = yawRot * (pitchRot * nrm);\n";


    /**
     * 「锚点必须恰好出现一次」的字符串替换。
     *
     * 为什么把它做成唯一的注入手段：{@code String.replace} 在锚点不存在时**什么都不做、也不报错**，
     * 而这里的后果是「GLSL 少一个 uniform → 整类投掷物不可见」，属于最难查的那种故障
     * （真机上只留下一句笼统的「uniform 缺失」，见类注释）。
     *
     * @throws IllegalStateException 锚点不存在或出现多次（说明群集着色器改了、这里没跟上）
     */
    public static String replaceOnce(String source, String anchor, String replacement) {
        int first = source.indexOf(anchor);
        if (first < 0) {
            throw new IllegalStateException("[horde] 投掷物着色器注入失败：锚点文本在群集着色器里不存在。"
                    + "锚点原文：\n" + anchor
                    + "\n（群集着色器改过而注入没跟上？这正是真机上「投掷物完全看不见」的根因）");
        }
        if (source.indexOf(anchor, first + 1) >= 0) {
            throw new IllegalStateException("[horde] 投掷物着色器注入失败：锚点文本在群集着色器里出现多次，"
                    + "无法确定替换哪一处。锚点原文：\n" + anchor);
        }
        return source.substring(0, first) + replacement + source.substring(first + anchor.length());
    }

    /**
     * 与 {@link #replaceOnce} 同样严格，但**按「逐行去缩进」再试一次** —— 缩进漂移不该把整类投掷物打瞎。
     *
     * <p>为什么需要它：真机事故的根因就是「锚点与着色器源码差了几个空格」这种**看不见的漂移**
     * （文本块的附带缩进规则会让源文件里的 16 空格在运行时字符串里变成 4 空格）。
     * 字面匹配失败时按行去缩进重试，可以让注入对「缩进变化」免疫；
     * 而**行内容**变化仍然会抛异常（那才是真的改了代码，必须人工确认）。
     */
    public static String replaceOnceLoose(String source, String anchor, String replacement) {
        try {
            return replaceOnce(source, anchor, replacement);
        } catch (IllegalStateException strict) {
            int[] span = findLoose(source, anchor);
            if (span == null) {
                throw strict;   // 真的找不到：把严格版的报错（含锚点原文）原样抛出去
            }
            HordeMod.LOGGER.warn("[horde] 投掷物着色器注入：锚点的空白与群集着色器不完全一致"
                    + "（已按逐行去缩进匹配，注入仍然成功）—— 建议把 ProjectileRenderer 里的锚点对齐到最新源码");
            return source.substring(0, span[0]) + replacement + source.substring(span[1]);
        }
    }

    /**
     * 按「逐行 trim」在源码里找锚点，返回它在源码里的 {@code [起, 止)} 区间；找不到返回 null。
     *
     * <p>做法：把源码切成行（记录每行起点），对每个候选起点逐行比较
     * {@code sourceLine.trim().equals(anchorLine.trim())}；行数一致且每行都相等就命中。
     */
    private static int[] findLoose(String source, String anchor) {
        List<String> anchorLines = trimmedLines(anchor);
        List<int[]> lineSpans = lineSpans(source);
        int n = anchorLines.size();
        for (int start = 0; start + n <= lineSpans.size(); start++) {
            boolean ok = true;
            for (int k = 0; k < n; k++) {
                String src = source.substring(lineSpans.get(start + k)[0], lineSpans.get(start + k)[1]);
                if (!src.trim().equals(anchorLines.get(k))) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return new int[]{lineSpans.get(start)[0], lineSpans.get(start + n - 1)[1]};
            }
        }
        return null;
    }

    /** 按行切分并逐行 {@code trim()}（空行保留为空串，用来对齐行数） */
    private static List<String> trimmedLines(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            out.add(line.trim());
        }
        return out;
    }

    /** 每行在字符串里的 {@code [起, 止)} 区间（不含行尾换行符） */
    private static List<int[]> lineSpans(String text) {
        List<int[]> out = new ArrayList<>();
        int i = 0;
        while (i <= text.length()) {
            int nl = text.indexOf('\n', i);
            if (nl < 0) {
                out.add(new int[]{i, text.length()});
                break;
            }
            out.add(new int[]{i, nl});
            i = nl + 1;
        }
        return out;
    }
}
