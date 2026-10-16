package dev.swordkingdom.horde.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.swordkingdom.horde.HordeMod;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
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
import java.util.List;
import java.util.Map;

/**
 * 装备变体的实例化渲染：**每种 (实体类型, 装备组合) 一次 draw call**。
 *
 * <h2>为什么单独一个渲染器</h2>
 * 群集主体渲染是「每种实体类型一次 draw call」（{@link CrowdRenderer}），
 * 而装备让「同一个类型」分裂成若干**变体**：铁甲僵尸 / 钻甲僵尸 / 拿剑的僵尸……
 * 变体的骨骼数、姿态通道与宿主**完全一致**（这是 {@link EquipBaker} 的硬约束），
 * 所以这里只是「换一份 VAO + 换一张贴图，其余一字不改」的又一次实例化绘制。
 *
 * <h2>为什么不做成 {@code CrowdRenderer} 的一个分支</h2>
 * {@code CrowdRenderer} 被本任务的硬性契约冻结了（并行开发正在改它），
 * 所以这里**自己持有一份 GL 状态**（VAO/IBO/实例缓冲/姿态缓冲纹理/程序），
 * 并复用 {@link CrowdRenderer#VERTEX_SHADER} / {@link CrowdRenderer#FRAGMENT_SHADER}
 * 那两份**同一个字符串**（着色器一个字符都没改，`shaderHarness` 继续测同一份源码）。
 *
 * <h2>接线（一行）</h2>
 * 在 {@code CrowdRenderer.onRenderLevelStage} 里，主体那轮绘制结束之后加：
 * <pre>
 *   EquipRenderer.render(event.getCamera().getPosition());
 * </pre>
 * 放在 {@code AFTER_ENTITIES} 阶段内、{@link CrowdRenderer} 已经绑好程序之后最省事；
 * 放在它之后（本方法自己绑程序）也一样正确。
 *
 * <h2>失败退化</h2>
 * 任何异常都会把本渲染器**整块停用**并打一条 ERROR，主体渲染完全不受影响
 * （和 {@link ProjectileRenderer} 的处理方式一致）。
 */
public final class EquipRenderer {

    /** 每实例 float 数：位置 3 + (朝向, 阵营, 受击) 3 —— 与群集完全一致 */
    private static final int FLOATS_PER_INSTANCE = ClientCrowdData.FLOATS_PER_UNIT;
    private static final float RENDER_DISTANCE = 256.0F;
    private static final int POSE_TEXTURE_UNIT = 3;

    private static final float[] PROJ_MAT = new float[16];
    private static final float[] VIEW_MAT = new float[16];
    private static final int[] transSlotUpload = new int[RigBaker.MAX_BONES];
    private static final int[] scaleSlotUpload = new int[RigBaker.MAX_BONES];
    private static final float[] poseScratch = new float[RigBaker.MAX_BONES * CrowdPose.STRIDE];
    private static final int[] uBone = new int[1];

    private static boolean initialized;
    private static boolean broken;

    private static int program;
    private static int vboInstance;
    private static int uProj;
    private static int uView;
    private static int uTex;
    private static int uPose;
    private static int uPoseStride;
    private static int uTransBase;
    private static int uTransSlot;
    private static int uScaleBase;
    private static int uScaleSlot;
    private static int uRenderScale;
    private static int uFlip;
    private static int uYOffset;
    private static int uRenderOffset;

    private static float[] instanceData = new float[0];
    private static FloatBuffer instanceBuffer = MemoryUtil.memAllocFloat(0);
    private static int instanceCapacity;

    /** 单位 -> 姿态（按单位 id 索引，与 {@link CrowdRenderer} 同样的做法，互不干扰） */
    private static float[] poseCache = new float[0];
    private static float[] transCache = new float[0];
    private static float[] scaleCache = new float[0];
    private static float[] poseUpload = new float[0];
    private static int[] poseStamp = new int[0];

    private static int debugCounter;
    private static long lastVariantCount;

    /** 变体的 GL 资源（每个 (类型, 装备组合) 一份 VAO + 姿态缓冲纹理） */
    private static final Map<String, VariantGpu> GPU = new HashMap<>();

    private EquipRenderer() {}

    private static final class VariantGpu {
        String key;
        int vao;
        int ibo;
        int indexCount;
        ResourceLocation texture;
        int boneCount;
        int transCount;
        int scaleCount;
        float[] pivot;
        int[] transSlot;
        int[] scaleSlot;
        float renderScaleX = 1.0F;
        float renderScaleY = 1.0F;
        float renderScaleZ = 1.0F;
        float renderOffsetX;
        float renderOffsetY;
        float renderOffsetZ;
        CrowdPose pose;
        int poseBuffer;
        int poseTexture;
        int poseTexels;
    }

    /** 覆盖表/调色板变了 -> 全部作废（GL 资源在渲染线程释放） */
    public static void invalidateAll() {
        EquipBaker.invalidateAll();
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) {
            releaseAll();
        } else {
            mc.execute(EquipRenderer::releaseAll);
        }
    }

    private static void releaseAll() {
        for (VariantGpu g : GPU.values()) {
            if (g == null) {
                continue;
            }
            if (g.vao != 0) {
                GL30.glDeleteVertexArrays(g.vao);
            }
            if (g.ibo != 0) {
                GL15.glDeleteBuffers(g.ibo);
            }
            if (g.poseBuffer != 0) {
                GL15.glDeleteBuffers(g.poseBuffer);
            }
            if (g.poseTexture != 0) {
                GL11.glDeleteTextures(g.poseTexture);
            }
        }
        GPU.clear();
    }

    /** 由 {@link CrowdRenderer} 在同一个渲染阶段调用 */
    public static void render(Vec3 camera) {
        if (broken) {
            return;
        }
        ClientEquipData data = ClientEquipData.forCurrentLevel();
        if (data == null || data.count <= 0) {
            return;
        }
        try {
            renderInner(data, camera);
        } catch (Throwable t) {
            broken = true;
            HordeMod.LOGGER.error("[horde] 装备变体渲染失败，已自动停用（主体群集渲染不受影响）", t);
        }
    }

    private static void renderInner(ClientEquipData data, Vec3 camera) {
        ClientCrowdData crowd = ClientCrowdData.forCurrentLevel();
        if (crowd == null || crowd.count <= 0) {
            return;
        }
        // 本单位没有装备时整块跳过（绝大多数场景下这就是 0 开销）
        boolean any = false;
        for (int i = 0; i < crowd.count; i++) {
            if (!data.isEmpty(crowd.ids[i])) {
                any = true;
                break;
            }
        }
        if (!any) {
            return;
        }
        if (!initialized) {
            init();
            initialized = true;
        }

        float camX = (float) camera.x;
        float camY = (float) camera.y;
        float camZ = (float) camera.z;
        float maxD2 = RENDER_DISTANCE * RENDER_DISTANCE;
        float alpha = crowd.alpha();
        ensureInstance(crowd.count);
        ensurePose(crowd.count + 64);

        Matrix4f proj = RenderSystem.getProjectionMatrix();
        Matrix4f view = RenderSystem.getModelViewMatrix();
        proj.get(PROJ_MAT);
        view.get(VIEW_MAT);

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

        int tick = (int) (Minecraft.getInstance().level == null ? 0L
                : Minecraft.getInstance().level.getGameTime() & 0x7FFFFFFF);
        int drawn = 0;
        long variants = 0;

        // 先按 (类型, 装备组合) 分组：同一组的单位一次性提交
        Map<String, List<Integer>> groups = new java.util.LinkedHashMap<>();
        for (int i = 0; i < crowd.count; i++) {
            int id = crowd.ids[i];
            if (data.isEmpty(id)) {
                continue;   // 没装备的单位由主体渲染器负责，这里不能重复画
            }
            int typeIndex = crowd.curType[id];
            if (typeIndex < 0 || typeIndex >= crowd.types.length) {
                continue;
            }
            ResourceLocation unitType = crowd.types[typeIndex];
            String key = unitType + "#" + data.keyOf(id);
            groups.computeIfAbsent(key, k -> new ArrayList<>(4)).add(id);
        }

        for (Map.Entry<String, List<Integer>> e : groups.entrySet()) {
            int hash = e.getKey().indexOf('#');
            ResourceLocation unitType = ResourceLocation.tryParse(e.getKey().substring(0, hash));
            String equipKey = e.getKey().substring(hash + 1);
            if (unitType == null) {
                continue;
            }
            EquipBaker.Variant variant = EquipBaker.variantFor(unitType, equipKey);
            if (variant == null) {
                continue;
            }
            VariantGpu gpu = gpuFor(unitType, equipKey, variant);
            if (gpu == null) {
                continue;
            }
            variants++;
            int written = 0;
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboInstance);
            for (int id : e.getValue()) {
                written = fillInstance(gpu, data, crowd, id, written, camX, camY, camZ, maxD2,
                        alpha, tick);
            }
            if (written == 0) {
                continue;
            }
            drawGroup(gpu, written);
            drawn += written;
        }

        GL13.glActiveTexture(GL13.GL_TEXTURE0 + POSE_TEXTURE_UNIT);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL30.glBindVertexArray(0);
        GL20.glUseProgram(prevProgram);

        lastVariantCount = variants;
        if ((debugCounter++ % 200) == 0) {
            HordeMod.LOGGER.info("[horde] 装备变体渲染：drawn={} variants={} 缓存的组合={}（命中 {} / 未命中 {} / 淘汰 {}）",
                    drawn, variants, EquipBaker.cacheRef().size(),
                    EquipBaker.cacheRef().hits(), EquipBaker.cacheRef().misses(),
                    EquipBaker.cacheRef().evictions());
        }
    }

    /** 把一个单位写进实例缓冲 + 姿态缓存；超出视距返回原 written */
    private static int fillInstance(VariantGpu gpu, ClientEquipData data, ClientCrowdData crowd,
                                    int id, int written, float camX, float camY, float camZ,
                                    float maxD2, float alpha, int tick) {
        float pX = crowd.prvX(id);
        float pY = crowd.prvY(id);
        float pZ = crowd.prvZ(id);
        float cX = crowd.curX[id];
        float cY = crowd.curY[id];
        float cZ = crowd.curZ[id];
        float tdx = cX - pX;
        float tdy = cY - pY;
        float tdz = cZ - pZ;
        float a = (tdx * tdx + tdy * tdy + tdz * tdz > 16.0F) ? 1.0F : alpha;
        float dx = pX + tdx * a - camX;
        float dy = pY + tdy * a - camY;
        float dz = pZ + tdz * a - camZ;
        if (dx * dx + dy * dy + dz * dz > maxD2 || written >= 65536) {
            return written;
        }

        int bones = gpu.boneCount;
        int base = id * RigBaker.MAX_BONES * 3;
        int tbase = id * RigBaker.MAX_TRANS * 3;
        int sbase = id * RigBaker.MAX_TRANS * 3;
        int stamp = poseStamp[id];
        if (stamp == 0 || (tick + id) % 2 == 0) {
            CrowdPose pose = gpu.pose;
            boolean ok = false;
            if (pose != null) {
                float attack = lerp(crowd.prvAttack(id), crowd.curAttack[id], a);
                ok = pose.evaluate(
                        lerp(crowd.prvWalkPos(id), crowd.curWalkPos[id], a),
                        lerp(crowd.prvWalkSpeed(id), crowd.curWalkSpeed[id], a),
                        lerp(crowd.prvAge(id), crowd.curAge[id], a),
                        lerp(crowd.prvHeadYaw(id), crowd.curHeadYaw[id], a),
                        lerp(crowd.prvHeadPitch(id), crowd.curHeadPitch[id], a),
                        attack,
                        Math.round(lerp(crowd.prvHurt(id), crowd.curHurt[id], a) * 10.0F),
                        pX + tdx * a, pY + tdy * a, pZ + tdz * a,
                        lerpAngle(crowd.prvYaw(id), crowd.curYaw[id], a),
                        crowd.curAggro[id] > 0.5F,
                        (int) crowd.curAction[id], (int) crowd.curActionTick[id], poseScratch);
            }
            if (ok) {
                for (int k = 0; k < bones; k++) {
                    int src = k * CrowdPose.STRIDE;
                    int d = base + k * 3;
                    poseCache[d] = poseScratch[src];
                    poseCache[d + 1] = poseScratch[src + 1];
                    poseCache[d + 2] = poseScratch[src + 2];
                    int slot = gpu.transSlot != null && k < gpu.transSlot.length ? gpu.transSlot[k] : -1;
                    if (slot >= 0 && slot < gpu.transCount) {
                        int td = tbase + slot * 3;
                        transCache[td] = poseScratch[src + 3];
                        transCache[td + 1] = poseScratch[src + 4];
                        transCache[td + 2] = poseScratch[src + 5];
                    }
                    int sslot = gpu.scaleSlot != null && k < gpu.scaleSlot.length ? gpu.scaleSlot[k] : -1;
                    if (sslot >= 0 && sslot < gpu.scaleCount) {
                        int sd = sbase + sslot * 3;
                        scaleCache[sd] = poseScratch[src + 6];
                        scaleCache[sd + 1] = poseScratch[src + 7];
                        scaleCache[sd + 2] = poseScratch[src + 8];
                    }
                }
                poseStamp[id] = tick;
            } else if (stamp == 0) {
                java.util.Arrays.fill(poseCache, base, base + bones * 3, 0.0F);
                if (gpu.transCount > 0) {
                    java.util.Arrays.fill(transCache, tbase, tbase + gpu.transCount * 3, 0.0F);
                }
                if (gpu.scaleCount > 0) {
                    java.util.Arrays.fill(scaleCache, sbase, sbase + gpu.scaleCount * 3, 1.0F);
                }
                poseStamp[id] = tick;
            }
        }

        // 姿态行（与 CrowdRenderer 完全同样的布局：旋转 texel + 位移 texel + 缩放 texel）
        int stride = bones + gpu.transCount + gpu.scaleCount;
        ensurePoseUpload((written + 1) * stride * 3);
        int poseDst = written * stride * 3;
        System.arraycopy(poseCache, base, poseUpload, poseDst, bones * 3);
        int cursor = poseDst + bones * 3;
        if (gpu.transCount > 0) {
            System.arraycopy(transCache, tbase, poseUpload, cursor, gpu.transCount * 3);
            cursor += gpu.transCount * 3;
        }
        if (gpu.scaleCount > 0) {
            System.arraycopy(scaleCache, sbase, poseUpload, cursor, gpu.scaleCount * 3);
        }

        int b = written * FLOATS_PER_INSTANCE;
        instanceData[b] = dx;
        instanceData[b + 1] = dy;
        instanceData[b + 2] = dz;
        instanceData[b + 3] = lerpAngle(crowd.prvYaw(id), crowd.curYaw[id], a);
        instanceData[b + 4] = crowd.curTeam[id];
        instanceData[b + 5] = lerp(crowd.prvHurt(id), crowd.curHurt[id], a);
        return written + 1;
    }

    private static void drawGroup(VariantGpu gpu, int written) {
        instanceBuffer.clear();
        instanceBuffer.put(instanceData, 0, written * FLOATS_PER_INSTANCE);
        instanceBuffer.flip();
        RenderSystem.setShaderTexture(0, gpu.texture);
        if (uploadPose(gpu, written) <= 0) {
            return;
        }
        GL30.glBindVertexArray(gpu.vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboInstance);
        GL20.glUniform4fv(uBone[0], gpu.pivot);
        GL20.glUniform1i(uPoseStride, gpu.boneCount + gpu.transCount + gpu.scaleCount);
        GL20.glUniform1i(uTransBase, gpu.boneCount);
        GL20.glUniform1i(uScaleBase, gpu.boneCount + gpu.transCount);
        java.util.Arrays.fill(transSlotUpload, -1);
        for (int i = 0; i < gpu.boneCount && i < transSlotUpload.length; i++) {
            transSlotUpload[i] = gpu.transSlot == null || i >= gpu.transSlot.length ? -1 : gpu.transSlot[i];
        }
        GL20.glUniform1iv(uTransSlot, transSlotUpload);
        java.util.Arrays.fill(scaleSlotUpload, -1);
        for (int i = 0; i < gpu.boneCount && i < scaleSlotUpload.length; i++) {
            scaleSlotUpload[i] = gpu.scaleSlot == null || i >= gpu.scaleSlot.length ? -1 : gpu.scaleSlot[i];
        }
        GL20.glUniform1iv(uScaleSlot, scaleSlotUpload);
        GL20.glUniform3f(uRenderScale, gpu.renderScaleX, gpu.renderScaleY, gpu.renderScaleZ);
        GL20.glUniform3f(uRenderOffset, gpu.renderOffsetX, gpu.renderOffsetY, gpu.renderOffsetZ);
        GL20.glUniform2f(uFlip, -1.0F, -1.0F);
        GL20.glUniform1f(uYOffset, 1.501F);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, instanceBuffer);
        GL31.glDrawElementsInstanced(GL11.GL_TRIANGLES, gpu.indexCount, GL11.GL_UNSIGNED_INT, 0L, written);
    }

    // ------------------------------------------------------------------

    private static VariantGpu gpuFor(ResourceLocation unitType, String equipKey,
                                     EquipBaker.Variant variant) {
        String key = unitType + "#" + equipKey;
        VariantGpu gpu = GPU.get(key);
        if (gpu != null) {
            return gpu;
        }
        gpu = new VariantGpu();
        gpu.key = key;
        gpu.texture = variant.texture;
        gpu.indexCount = variant.indices.length;
        gpu.boneCount = variant.hostRig.boneCount;
        gpu.pivot = variant.hostRig.pivot4;
        gpu.transSlot = variant.hostRig.transSlot;
        gpu.transCount = variant.hostRig.transCount;
        gpu.scaleSlot = variant.hostRig.scaleSlot;
        gpu.scaleCount = variant.hostRig.scaleCount;
        gpu.pose = variant.pose;
        gpu.renderScaleX = 1.0F;
        gpu.renderScaleY = 1.0F;
        gpu.renderScaleZ = 1.0F;
        createVao(gpu, variant);
        GPU.put(key, gpu);
        return gpu;
    }

    private static void createVao(VariantGpu gpu, EquipBaker.Variant variant) {
        gpu.vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(gpu.vao);

        int stride = EquipBaker.FLOATS_PER_VERTEX * 4;
        int vboModel = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboModel);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, variant.vertices, GL15.GL_STATIC_DRAW);
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

        gpu.ibo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, gpu.ibo);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, variant.indices, GL15.GL_STATIC_DRAW);
        GL30.glBindVertexArray(0);
    }

    private static int uploadPose(VariantGpu gpu, int rows) {
        if (rows <= 0) {
            return 0;
        }
        int stride = gpu.boneCount + gpu.transCount + gpu.scaleCount;
        int needFloats = rows * stride * 3;
        if (gpu.poseBuffer == 0) {
            gpu.poseBuffer = GL15.glGenBuffers();
            GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, gpu.poseBuffer);
            GL15.glBufferData(GL31.GL_TEXTURE_BUFFER, (long) Math.max(4096, needFloats) * 4L,
                    GL15.GL_DYNAMIC_DRAW);
            gpu.poseTexture = GL11.glGenTextures();
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, gpu.poseTexture);
            GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32F, gpu.poseBuffer);
            gpu.poseTexels = Math.max(4096, needFloats) / 3;
        } else if (needFloats > gpu.poseTexels * 3) {
            GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, gpu.poseBuffer);
            GL15.glBufferData(GL31.GL_TEXTURE_BUFFER, (long) needFloats * 4L, GL15.GL_DYNAMIC_DRAW);
            gpu.poseTexels = needFloats / 3;
        }
        FloatBuffer buf = MemoryUtil.memAllocFloat(needFloats);
        try {
            buf.put(poseUpload, 0, needFloats);
            buf.flip();
            GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, gpu.poseBuffer);
            GL15.glBufferSubData(GL31.GL_TEXTURE_BUFFER, 0L, buf);
        } finally {
            MemoryUtil.memFree(buf);
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + POSE_TEXTURE_UNIT);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, gpu.poseTexture);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        return rows;
    }

    private static void ensureInstance(int units) {
        int need = units * FLOATS_PER_INSTANCE;
        if (instanceData.length < need) {
            instanceData = new float[need];
        }
        if (instanceBuffer.capacity() < need) {
            MemoryUtil.memFree(instanceBuffer);
            instanceBuffer = MemoryUtil.memAllocFloat(Math.max(need, instanceBuffer.capacity() * 2));
        }
        if (instanceCapacity < units) {
            int cap = Math.max(units, instanceCapacity * 2);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboInstance);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) cap * FLOATS_PER_INSTANCE * 4L,
                    GL15.GL_DYNAMIC_DRAW);
            instanceCapacity = cap;
        }
    }

    private static void ensurePose(int units) {
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

    // ------------------------------------------------------------------
    // GL 初始化
    // ------------------------------------------------------------------

    private static void init() {
        program = createProgram();
        uProj = uniform("uProj");
        uView = uniform("uView");
        uTex = uniform("uTex");
        uPose = uniform("uPose");
        uPoseStride = uniform("uPoseStride");
        uTransBase = uniform("uTransBase");
        uTransSlot = uniform("uTransSlot");
        uScaleBase = uniform("uScaleBase");
        uScaleSlot = uniform("uScaleSlot");
        uRenderScale = uniform("uRenderScale");
        uFlip = uniform("uFlip");
        uYOffset = uniform("uYOffset");
        uRenderOffset = uniform("uRenderOffset");
        uBone[0] = uniform("uBonePivot");
        if (uProj < 0 || uView < 0 || uTex < 0 || uPose < 0 || uPoseStride < 0
                || uTransBase < 0 || uTransSlot < 0 || uScaleBase < 0 || uScaleSlot < 0
                || uRenderScale < 0 || uFlip < 0 || uYOffset < 0 || uRenderOffset < 0
                || uBone[0] < 0) {
            throw new IllegalStateException("装备变体着色器 uniform 缺失（与群集着色器不一致？）");
        }
        if (!initDivisorApi()) {
            throw new IllegalStateException("当前 GL 上下文不支持实例化顶点属性");
        }
        vboInstance = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboInstance);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, 1024L * FLOATS_PER_INSTANCE * 4L, GL15.GL_DYNAMIC_DRAW);
        instanceCapacity = 1024;
        HordeMod.LOGGER.info("[horde] 装备变体实例化渲染已初始化（复用群集着色器源码）");
    }

    private static int uniform(String name) {
        int loc = GL20.glGetUniformLocation(program, name);
        if (loc < 0) {
            loc = GL20.glGetUniformLocation(program, name + "[0]");
        }
        return loc;
    }

    private static int divisorApi;

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
        int vs = compileShader(GL20.GL_VERTEX_SHADER, CrowdRenderer.VERTEX_SHADER);
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
            throw new IllegalStateException("装备变体着色器链接失败: " + GL20.glGetProgramInfoLog(p));
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
            throw new IllegalStateException("装备变体着色器编译失败: " + log);
        }
        return shader;
    }

    /** 诊断：当前驻留 GPU 的变体数 / 缓存的组合数 */
    public static long variantsOnGpu() {
        return GPU.size();
    }

    public static long lastDrawnVariants() {
        return lastVariantCount;
    }

    /** 关屏/换维度时彻底清空（GL 资源 + 缓存） */
    public static void shutdown() {
        releaseAll();
        EquipBaker.invalidateAll();
    }
}
