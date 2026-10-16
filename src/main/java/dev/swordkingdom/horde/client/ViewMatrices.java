package dev.swordkingdom.horde.client;

import dev.swordkingdom.horde.HordeMod;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * ★ 视图/投影矩阵的**唯一来源**（群集与投掷物两条路径都必须调这里）。
 *
 * <h2>真机事故（本轮）：「投掷物被粘在玩家视角上」</h2>
 * 两条路径以前各取一份：
 * <ul>
 *   <li>群集：{@code event.getPoseStack().last().pose()} —— 关卡渲染阶段这一份**只有旋转、没有平移**
 *       （原版是把世界坐标在 CPU 端减掉摄像机再进来）；</li>
 *   <li>投掷物：{@code RenderSystem.getModelViewMatrix()} —— 这是**完整的模型视图矩阵**，
 *       <b>含摄像机平移</b> {@code T(−C)·R}。</li>
 * </ul>
 * 而两条路径上传的实例位置 {@code iPos} 都是「世界 − 摄像机」⇒ 投掷物那边摄像机被减了**两次**
 * （CPU 一次 + 矩阵一次），于是它相对镜头几乎不动、看起来就「粘在视角上」，而群集一切正常。
 *
 * <h2>契约（离线 harness 会断言）</h2>
 * <pre>
 *   iPos  = 世界 − 摄像机（相机相对，CPU 端减好）
 *   VIEW  = 只有旋转（平移分量必须 ≈ 0）
 *   world = 模型空间算完的坐标（含 iPos）
 *   gl_Position = PROJ · VIEW · vec4(world, 1)
 * </pre>
 * 只要 {@code VIEW} 带平移，就必然出现「跟着镜头跑」；这里的 {@link #capture} 会把平移分量记下来并
 * 在非零时告警 —— 下次装包前就能看见。
 */
public final class ViewMatrices {

    /** 投影矩阵（列主序 16 个 float，与 {@code Matrix4f.get(float[])} 一致） */
    public static final float[] PROJ = new float[16];
    /** 视图矩阵（列主序 16 个 float）——**只有旋转**才算对 */
    public static final float[] VIEW = new float[16];

    /** 两条路径各自最近一次的平移分量（诊断用；列主序下就是下标 12/13/14） */
    private static final float[] CROWD_TRANS = new float[3];
    private static final float[] PROJ_TRANS = new float[3];
    private static final boolean[] LOGGED = new boolean[2];

    /** 平移分量的容差：超过它就算「视图矩阵带平移」（那就是粘视角） */
    public static final float TRANSLATION_EPS = 1.0E-3F;

    private ViewMatrices() {
    }

    /**
     * 从渲染事件里捕获 VIEW/PROJ（两条路径**共用这一份实现**）。
     *
     * @param event 关卡渲染事件（群集与投掷物都在 {@code AFTER_ENTITIES} 阶段里被调用）
     * @param path  0 = 群集、1 = 投掷物（只为日志/诊断区分）
     * @return 视图矩阵的平移分量（列主序下标 12/13/14）
     */
    public static float[] capture(RenderLevelStageEvent event, int path) {
        Matrix4f proj = com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix();
        Matrix4f view = event.getPoseStack().last().pose();
        proj.get(PROJ);
        view.get(VIEW);
        float[] trans = path == 0 ? CROWD_TRANS : PROJ_TRANS;
        trans[0] = VIEW[12];
        trans[1] = VIEW[13];
        trans[2] = VIEW[14];
        if (!LOGGED[path]) {
            LOGGED[path] = true;
            report(path, event.getStage());
        }
        return trans;
    }

    /** 初始化时每类型一行：来源 / 调用阶段 / 平移分量（群集与投掷物各一行，一比就知道） */
    private static void report(int path, Object stage) {
        float[] trans = path == 0 ? CROWD_TRANS : PROJ_TRANS;
        String name = path == 0 ? "群集" : "投掷物";
        String mag = String.format(java.util.Locale.ROOT, "(%.4f, %.4f, %.4f)", trans[0], trans[1], trans[2]);
        if (Math.abs(trans[0]) > TRANSLATION_EPS || Math.abs(trans[1]) > TRANSLATION_EPS
                || Math.abs(trans[2]) > TRANSLATION_EPS) {
            HordeMod.LOGGER.warn("[horde] 视图矩阵来源：ViewMatrices.capture（{}），"
                            + "调用阶段={}，**平移分量={} 非零** —— iPos 已经是相机相对，"
                            + "再叠加这份平移就会出现「物体粘在视角上」",
                    name, stage, mag);
        } else {
            HordeMod.LOGGER.info("[horde] 视图矩阵来源：ViewMatrices.capture（{}），"
                            + "调用阶段={}，平移分量={}（≈0 = 只有旋转，契约成立）",
                    name, stage, mag);
        }
    }

    /** 某个路径最近一次的平移分量（诊断/离线断言用） */
    public static float[] translation(int path) {
        return path == 0 ? CROWD_TRANS : PROJ_TRANS;
    }

    /** 视图矩阵是否满足「只有旋转」的契约 */
    public static boolean isRotationOnly() {
        for (int i = 12; i <= 14; i++) {
            if (Math.abs(VIEW[i]) > TRANSLATION_EPS) {
                return false;
            }
        }
        return true;
    }

    /**
     * 按**着色器同一条公式**算一个实例位置的裁剪空间坐标（一次性诊断用）。
     *
     * @param iPos 上传的实例位置（相机相对）
     * @return {@code [viewX, viewY, viewZ, clipX, clipY, clipZ, clipW]}
     */
    public static float[] project(float[] iPos) {
        float vx = VIEW[0] * iPos[0] + VIEW[4] * iPos[1] + VIEW[8] * iPos[2] + VIEW[12];
        float vy = VIEW[1] * iPos[0] + VIEW[5] * iPos[1] + VIEW[9] * iPos[2] + VIEW[13];
        float vz = VIEW[2] * iPos[0] + VIEW[6] * iPos[1] + VIEW[10] * iPos[2] + VIEW[14];
        float cx = PROJ[0] * vx + PROJ[4] * vy + PROJ[8] * vz + PROJ[12];
        float cy = PROJ[1] * vx + PROJ[5] * vy + PROJ[9] * vz + PROJ[13];
        float cz = PROJ[2] * vx + PROJ[6] * vy + PROJ[10] * vz + PROJ[14];
        float cw = PROJ[3] * vx + PROJ[7] * vy + PROJ[11] * vz + PROJ[15];
        return new float[]{vx, vy, vz, cx, cy, cz, cw};
    }

    /** 视图矩阵的 3×3（诊断打印用） */
    public static String describeRotation() {
        return String.format(java.util.Locale.ROOT,
                "[%.3f %.3f %.3f; %.3f %.3f %.3f; %.3f %.3f %.3f]",
                VIEW[0], VIEW[4], VIEW[8], VIEW[1], VIEW[5], VIEW[9], VIEW[2], VIEW[6], VIEW[10]);
    }

    // ------------------------------------------------------------------
    // 离线断言用的注入点（生产不调；见 projectileHarness 的「视图矩阵契约」用例）
    // ------------------------------------------------------------------

    /**
     * 直接塞一份视图矩阵（列主序 16 float）——**只给离线用例**，用来断言
     * 「带平移的矩阵会被判违约」「投影公式与着色器一致」。生产路径一律走 {@link #capture}。
     */
    public static void setViewForTest(float[] colMajor16) {
        System.arraycopy(colMajor16, 0, VIEW, 0, 16);
    }

    /** 直接塞一份投影矩阵（列主序 16 float）——只给离线用例 */
    public static void setProjForTest(float[] colMajor16) {
        System.arraycopy(colMajor16, 0, PROJ, 0, 16);
    }

    /** 复位成「只有旋转」的默认视图矩阵（离线用例之间互不影响） */
    public static void resetForTest() {
        java.util.Arrays.fill(VIEW, 0.0F);
        VIEW[0] = 1.0F;
        VIEW[5] = 1.0F;
        VIEW[10] = 1.0F;
        VIEW[15] = 1.0F;
        java.util.Arrays.fill(PROJ, 0.0F);
        PROJ[0] = 1.0F;
        PROJ[5] = 1.0F;
        PROJ[10] = 1.0F;
        PROJ[15] = 1.0F;
    }
}
