package dev.swordkingdom.horde.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * GL 验证：在隐藏窗口里建 GL 3.2 Core 上下文，用 {@link CrowdRenderer} 真正使用的那份
 * 顶点/片元着色器源码编译一次，再按同样的顶点属性布局 + 姿态纹理把烘焙好的网格实例化画进 FBO，
 * 回读像素并输出 PNG。
 *
 * 姿态纹理里的骨骼欧拉角由 {@link CrowdPose} 现算（离线用 null 实体，测试模型的 setupAnim 不读实体）。
 */
public final class ShaderHarness {

    private static final int W = 360;
    private static final int H = 260;
    private static final String OUT_DIR = "build/harness-shots";
    private static final int POSE_UNIT = 3;

    private static int failures;

    public static void main(String[] args) throws Exception {
        if (!GLFW.glfwInit()) {
            System.out.println("[skip] glfwInit 失败（无桌面会话），跳过 GL 验证");
            return;
        }
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
        long window = GLFW.glfwCreateWindow(W, H, "horde-shader-harness", 0L, 0L);
        if (window == 0L) {
            System.out.println("[skip] 无法创建 GL 窗口，跳过 GL 验证");
            return;
        }
        try {
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            System.out.println("GL_VERSION  = " + GL11.glGetString(GL11.GL_VERSION));
            System.out.println("GL_RENDERER = " + GL11.glGetString(GL11.GL_RENDERER));
            org.lwjgl.opengl.GLCapabilities caps = GL.getCapabilities();
            System.out.println("OpenGL33=" + caps.OpenGL33
                    + " ARB_instanced_arrays=" + caps.GL_ARB_instanced_arrays
                    + " OpenGL31=" + caps.OpenGL31);
            if (!CrowdRenderer.initDivisorApi()) {
                failures++;
                System.out.println("[FAIL] 本上下文不支持实例化顶点属性");
            } else {
                System.out.println("[ ok ] 实例化顶点属性入口可用（生产代码探测通过）");
            }
            run();
        } finally {
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
        System.out.println(failures == 0 ? "\n== GL PASS ==" : "\n== GL FAILURES: " + failures + " ==");
        if (failures != 0) {
            System.exit(1);
        }
    }

    private static void run() throws Exception {
        int program = createProgram();
        System.out.println("[ ok ] 着色器编译链接成功，program=" + program);

        int fbo = createFbo();
        int texture = makeTexture();
        File dir = new File(OUT_DIR);
        dir.mkdirs();

        // 故意把「像素解包状态」弄脏：真实整合包里别的 mod（如 ImmediatelyFast）会留着不还原。
        // 旧实现（2D 纹理 + glTexSubImage2D + 客户端 FloatBuffer）在这种状态下会让驱动
        // 把小块内存当成大图去读，直接在 atio6axx.dll 里 EXCEPTION_ACCESS_VIOLATION 崩掉 JVM
        // （见 hs_err_pid23964.log）。新实现走缓冲纹理，完全不读这些状态，所以必须仍然正常。
        dirtyUnpackState();
        System.out.println("[ ok ] 已在渲染前弄脏 GL_PIXEL_UNPACK_BUFFER / GL_UNPACK_* 状态");

        renderCreature(program, fbo, texture, dir, "biped",
                new dev.swordkingdom.horde.harness.RigHarness.BipedModel(), 0.55F);
        renderCreature(program, fbo, texture, dir, "quadruped",
                new dev.swordkingdom.horde.harness.RigHarness.QuadrupedModel(), 0.9F);
        renderCreature(program, fbo, texture, dir, "spider",
                new dev.swordkingdom.horde.harness.RigHarness.SpiderModel(), 0.55F);

        GL30.glDeleteFramebuffers(fbo);
        GL11.glDeleteTextures(texture);
        GL20.glDeleteProgram(program);
        System.out.println("[ ok ] PNG 输出目录：" + dir.getAbsolutePath());
    }

    /** 模拟「别的 mod 留下的脏像素解包状态」。 */
    private static void dirtyUnpackState() {
        int pbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, pbo);
        GL15.glBufferData(GL21.GL_PIXEL_UNPACK_BUFFER, 1024L, GL15.GL_STREAM_DRAW);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 4096);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 3);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 5);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 2);
    }

    /**
     * 用一个真实生物模型渲染三个相邻实例，行走相位两两不同，
     * 用来直观核对：多部件层级是否正确、步态是否是模型自己的步态。
     */
    private static void renderCreature(int program, int fbo, int texture, File dir, String name,
                                       EntityModel<Entity> model, float spacing) throws Exception {
        RigBaker.Rig rig;
        try {
            rig = RigBaker.bake(model, null);
        } catch (Throwable t) {
            failures++;
            System.out.println("[FAIL] " + name + " 烘焙异常: " + t);
            return;
        }
        if (rig == null) {
            failures++;
            System.out.println("[FAIL] " + name + " 烘焙失败");
            return;
        }
        System.out.println("[ ok ] " + name + " 烘焙：骨骼 " + rig.boneCount + "，最深链 " + rig.maxChain
                + "，顶点 " + (rig.vertices.length / RigBaker.FLOATS_PER_VERTEX));

        CrowdPose pose = new CrowdPose(model, null, rig.parts);
        int vao = GL30.glGenVertexArrays();
        int instanceVbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, instanceVbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, 16L * ClientCrowdData.FLOATS_PER_UNIT * 4L, GL15.GL_DYNAMIC_DRAW);
        setupVao(vao, instanceVbo, rig);
        int poseBuffer = GL15.glGenBuffers();
        int poseTexture = createPoseBufferTexture(poseBuffer, rig.boneCount * 16);

        Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(48.0), (float) W / H, 0.1F, 100.0F);
        Matrix4f view = new Matrix4f().lookAt(0.0F, 1.5F, 4.2F, 0.0F, 0.8F, 0.0F, 0.0F, 1.0F, 0.0F);

        byte[] px = renderInstances(program, fbo, vao, instanceVbo, texture, poseBuffer, poseTexture,
                rig, pose, proj, view, 0.0F, spacing);
        writePng(px, new File(dir, name + "-a.png"));
        byte[] px2 = renderInstances(program, fbo, vao, instanceVbo, texture, poseBuffer, poseTexture,
                rig, pose, proj, view, 1.6F, spacing);
        writePng(px2, new File(dir, name + "-b.png"));
        check(litPixels(px) > 200, name + " 应画出可见几何（非背景像素 " + litPixels(px) + "）");
        check(diffPixels(px, px2) > 30, name + " 行走相位应改变画面（差异像素 " + diffPixels(px, px2) + "）");

        // 再来一遍「没有位移通道」的形态（原版僵尸这类类型就是这种），确保这条路径也能画出来
        byte[] pxNoTrans = renderInstances(program, fbo, vao, instanceVbo, texture, poseBuffer, poseTexture,
                rig, pose, proj, view, 0.0F, spacing, false);
        check(litPixels(pxNoTrans) > 200, name + " 无位移通道形态也应画出可见几何（非背景像素 "
                + litPixels(pxNoTrans) + "）");
        check(diffPixels(pxNoTrans, px) < W * H * 0.5F, name + " 无位移通道形态应与带位移的形态基本一致（差异像素 "
                + diffPixels(pxNoTrans, px) + "）");

        GL30.glDeleteVertexArrays(vao);
        GL15.glDeleteBuffers(instanceVbo);
        GL15.glDeleteBuffers(poseBuffer);
        GL11.glDeleteTextures(poseTexture);
    }

    /**
     * 画 3 个实例：x 方向间隔 spacing，行走相位依次 +0.9。
     */
    private static byte[] renderInstances(int program, int fbo, int vao, int instanceVbo, int texture,
                                          int poseBuffer, int poseTexture, RigBaker.Rig rig, CrowdPose pose,
                                          Matrix4f proj, Matrix4f view, float walkPhase, float spacing) {
        return renderInstances(program, fbo, vao, instanceVbo, texture, poseBuffer, poseTexture,
                rig, pose, proj, view, walkPhase, spacing, true);
    }

    /**
     * 画 3 个实例：x 方向间隔 spacing，行走相位依次 +0.9。
     *
     * @param withTranslation false 时按「该类型没有位移通道」的形态走一遍
     *                        （stride = 骨骼数、uTransSlot 全 -1）—— 原版僵尸等类型就是这种形态，
     *                        之前正是因为只有带位移通道的用例，才漏掉了相关缺陷。
     */
    private static byte[] renderInstances(int program, int fbo, int vao, int instanceVbo, int texture,
                                          int poseBuffer, int poseTexture, RigBaker.Rig rig, CrowdPose pose,
                                          Matrix4f proj, Matrix4f view, float walkPhase, float spacing,
                                          boolean withTranslation) {
        int bones = rig.boneCount;
        int count = 3;
        int transTexels = withTranslation ? 1 : 0;
        float[] instances = new float[ClientCrowdData.FLOATS_PER_UNIT * count];
        float[] angles = new float[bones * CrowdPose.STRIDE];
        int stride = bones + transTexels;
        FloatBuffer poseBuf = MemoryUtil.memAllocFloat(stride * 4 * count);
        for (int i = 0; i < count; i++) {
            float limbSwing = walkPhase + i * 0.9F;
            float amount = 1.0F;
            float age = 20.0F + i * 7.0F;
            pose.evaluate(limbSwing, amount, age, 0.0F, 0.0F, 0.0F, 0, 0.0D, 0.0D, 0.0D, 0.0F, true, angles);
            for (int k = 0; k < bones; k++) {
                poseBuf.put(angles[k * CrowdPose.STRIDE]);
                poseBuf.put(angles[k * CrowdPose.STRIDE + 1]);
                poseBuf.put(angles[k * CrowdPose.STRIDE + 2]);
                poseBuf.put(0.0F);
            }
            if (withTranslation) {
                // 位移通道：给骨骼 0 一个随相位变化的平移，验证 shader 的位移路径
                poseBuf.put(0.0F);
                poseBuf.put((float) Math.sin(limbSwing) * 0.2F);
                poseBuf.put(0.0F);
                poseBuf.put(0.0F);
            }

            int b = i * ClientCrowdData.FLOATS_PER_UNIT;
            instances[b] = (i - 1) * spacing;    // iPos
            instances[b + 1] = 0.0F;
            instances[b + 2] = 0.0F;
            instances[b + 3] = 0.0F;             // yaw
            instances[b + 4] = i % 2 == 0 ? 0.0F : 1.0F;  // team
            instances[b + 5] = 0.0F;             // hurt
        }
        poseBuf.flip();

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL11.glViewport(0, 0, W, H);
        GL11.glClearColor(0.06F, 0.07F, 0.10F, 1.0F);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_CULL_FACE);

        GL20.glUseProgram(program);
        float[] pm = new float[16];
        float[] vm = new float[16];
        proj.get(pm);
        view.get(vm);
        GL20.glUniformMatrix4fv(uniform(program, "uProj"), false, pm);
        GL20.glUniformMatrix4fv(uniform(program, "uView"), false, vm);
        GL20.glUniform1i(uniform(program, "uTex"), 0);
        GL20.glUniform1i(uniform(program, "uPose"), POSE_UNIT);
        GL20.glUniform1i(uniform(program, "uPoseStride"), stride);
        GL20.glUniform1i(uniform(program, "uTransBase"), bones);
        int[] slots = new int[RigBaker.MAX_BONES];
        java.util.Arrays.fill(slots, -1);
        if (withTranslation) {
            // 合成一个位移通道挂在骨骼 0 上，专门验证着色器的「旋转 + 位移」叠加路径
            slots[0] = 0;
        }
        GL20.glUniform1iv(uniform(program, "uTransSlot"), slots);
        // 缩放通道：这条用例不带缩放（原版/Citadel 模型就是这种形态）
        GL20.glUniform1i(uniform(program, "uScaleBase"), bones + transTexels);
        int[] scaleSlots = new int[RigBaker.MAX_BONES];
        java.util.Arrays.fill(scaleSlots, -1);
        GL20.glUniform1iv(uniform(program, "uScaleSlot"), scaleSlots);
        GL20.glUniform3f(uniform(program, "uRenderScale"), 1.0F, 1.0F, 1.0F);
        GL20.glUniform3f(uniform(program, "uRenderOffset"), 0.0F, 0.0F, 0.0F);
        // 原版模型的朝向链：scale(-1,-1,1) + translate(0,-1.501,0)
        GL20.glUniform2f(uniform(program, "uFlip"), -1.0F, -1.0F);
        GL20.glUniform1f(uniform(program, "uYOffset"), 1.501F);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL20.glUniform4fv(uniform(program, "uBonePivot"), rig.pivot4);

        // 缓冲纹理：完全不依赖像素解包状态
        GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, poseBuffer);
        GL15.glBufferSubData(GL31.GL_TEXTURE_BUFFER, 0L, poseBuf);
        MemoryUtil.memFree(poseBuf);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + POSE_UNIT);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, poseTexture);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);

        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, instanceVbo);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, instances);
        GL31.glDrawElementsInstanced(GL11.GL_TRIANGLES, rig.indices.length, GL11.GL_UNSIGNED_INT, 0L, count);
        GL30.glBindVertexArray(0);
        GL20.glUseProgram(0);

        ByteBuffer buf = MemoryUtil.memAlloc(W * H * 4);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glReadPixels(0, 0, W, H, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
        byte[] out = new byte[W * H * 4];
        buf.get(out);
        MemoryUtil.memFree(buf);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        return out;
    }

    private static int uniform(int program, String name) {
        int loc = GL20.glGetUniformLocation(program, name);
        if (loc < 0) {
            loc = GL20.glGetUniformLocation(program, name + "[0]");
        }
        if (loc < 0) {
            failures++;
            System.out.println("[FAIL] uniform 缺失: " + name);
        }
        return loc;
    }

    // ------------------------------------------------------------------
    // GL 资源
    // ------------------------------------------------------------------

    private static int createProgram() {
        int vs = compile(GL20.GL_VERTEX_SHADER, CrowdRenderer.VERTEX_SHADER);
        int fs = compile(GL20.GL_FRAGMENT_SHADER, CrowdRenderer.FRAGMENT_SHADER);
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
            failures++;
            System.out.println("[FAIL] 链接失败: " + GL20.glGetProgramInfoLog(p));
        }
        return p;
    }

    private static int compile(int type, String source) {
        int s = GL20.glCreateShader(type);
        GL20.glShaderSource(s, source);
        GL20.glCompileShader(s);
        if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            failures++;
            System.out.println("[FAIL] 着色器编译失败: " + GL20.glGetShaderInfoLog(s));
        }
        return s;
    }

    private static void setupVao(int vao, int instanceVbo, RigBaker.Rig rig) {
        GL30.glBindVertexArray(vao);
        int vbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, rig.vertices, GL15.GL_STATIC_DRAW);
        int stride = RigBaker.FLOATS_PER_VERTEX * 4;
        attrib(0, 3, stride, 0);
        attrib(1, 3, stride, 12);
        attrib(2, 2, stride, 24);
        attrib(3, 1, stride, 32);
        for (int i = 0; i < 4; i++) {
            GL20.glEnableVertexAttribArray(i);
        }

        // 实例属性必须在实例 VBO 绑定状态下设置
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, instanceVbo);
        int istride = ClientCrowdData.FLOATS_PER_UNIT * 4;
        attrib(4, 3, istride, 0);
        attrib(5, 3, istride, 12);
        for (int i = 4; i < 6; i++) {
            GL20.glEnableVertexAttribArray(i);
            CrowdRenderer.attribDivisor(i, 1);
        }

        int ibo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ibo);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, rig.indices, GL15.GL_STATIC_DRAW);
        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    /** 创建缓冲纹理（GL_TEXTURE_BUFFER + RGBA32F），生产代码用的就是这条路径。 */
    private static int createPoseBufferTexture(int buffer, int bytes) {
        GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, buffer);
        GL15.glBufferData(GL31.GL_TEXTURE_BUFFER, (long) bytes * 1024L, GL15.GL_DYNAMIC_DRAW);
        int tex = GL11.glGenTextures();
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, tex);
        GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32F, buffer);
        return tex;
    }

    private static void attrib(int index, int size, int stride, long offset) {
        GL20.glVertexAttribPointer(index, size, GL11.GL_FLOAT, false, stride, offset);
    }

    private static int makeTexture() {
        int tex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        ByteBuffer px = MemoryUtil.memAlloc(4);
        px.put((byte) 0xB0).put((byte) 0xB8).put((byte) 0xC8).put((byte) 0xFF);
        px.flip();
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 1, 1, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px);
        MemoryUtil.memFree(px);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        return tex;
    }

    private static int createFbo() {
        int color = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, color);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, W, H, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        int depth = GL30.glGenRenderbuffers();
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depth);
        GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH_COMPONENT24, W, H);

        int fbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, color, 0);
        GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL30.GL_RENDERBUFFER, depth);
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            failures++;
            System.out.println("[FAIL] FBO 不完整: 0x" + Integer.toHexString(status));
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        return fbo;
    }

    // ------------------------------------------------------------------
    // 像素工具
    // ------------------------------------------------------------------

    private static int litPixels(byte[] rgba) {
        int n = 0;
        for (int i = 0; i < rgba.length; i += 4) {
            int r = rgba[i] & 0xFF;
            int g = rgba[i + 1] & 0xFF;
            int b = rgba[i + 2] & 0xFF;
            if (r + g + b > 60) {
                n++;
            }
        }
        return n;
    }

    private static int diffPixels(byte[] a, byte[] b) {
        int n = 0;
        for (int i = 0; i < a.length; i += 4) {
            int d = Math.abs((a[i] & 0xFF) - (b[i] & 0xFF))
                    + Math.abs((a[i + 1] & 0xFF) - (b[i + 1] & 0xFF))
                    + Math.abs((a[i + 2] & 0xFF) - (b[i + 2] & 0xFF));
            if (d > 24) {
                n++;
            }
        }
        return n;
    }

    private static void writePng(byte[] rgba, File file) throws Exception {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int src = ((H - 1 - y) * W + x) * 4;
                int r = rgba[src] & 0xFF;
                int g = rgba[src + 1] & 0xFF;
                int b = rgba[src + 2] & 0xFF;
                img.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        ImageIO.write(img, "png", file);
    }

    private static void check(boolean ok, String message) {
        if (ok) {
            System.out.println("  [ok] " + message);
        } else {
            failures++;
            System.out.println("  [FAIL] " + message);
        }
    }
}
