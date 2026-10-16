package dev.swordkingdom.horde.crowd;

import dev.swordkingdom.horde.action.SkillParams;
import net.minecraft.resources.ResourceLocation;

/**
 * 远程攻击模式的**纯数学部分** —— 只做决策与节流，不碰世界、不碰注册表。
 *
 * <h2>为什么单独抽一个类</h2>
 * 真机反馈「骷髅不射箭」时，最难查的不是发射本身，而是**判定链**：
 * 谁算远程？多远算能打？什么条件才允许放？放了之后多久才能再放？
 * 把这些写成纯函数 + 一个可注入的视线回调（{@link Los}），就能在离线 harness 里
 * 用同样一份代码把整条链跑一遍（见 {@code ProjectileHarness} 的「远程攻击模式」用例），
 * 而不是只能靠进游戏看日志。
 *
 * <h2>攻击模式（刻意与真实实体一致的部分）</h2>
 * <ul>
 *   <li><b>远程</b>：目标在「远程距离」内 + 有视线 + 冷却好了 → 朝目标发射投掷物；</li>
 *   <li><b>近战</b>：目标贴脸（近战距离内）→ 走原来的近战结算（原版骷髅被贴近也会近战）；</li>
 *   <li>两者**共用同一个冷却**（{@code CrowdData.attackCd}），所以同一次攻击
 *       只会走其中一条路 —— 不会出现「这一帧又砍又射」造成双倍伤害。</li>
 * </ul>
 */
public final class ProjectileCombat {

    private ProjectileCombat() {}

    /** 提前量求交点的不动点迭代次数（4 次足够收敛到 0.01 tick 以内） */
    private static final int LEAD_ITERATIONS = 4;

    /**
     * 视线回调：两侧都是**眼睛**位置，返回 true = 看得见。
     *
     * 做成回调的理由与 {@link ProjectileManager.Adapter} / {@link ProjectileManager.Hits} 完全一样：
     * 真实实现走 {@link BlockWorld}，离线 harness 注入一个恒定值就能把决策链单独验证。
     */
    @FunctionalInterface
    public interface Los {
        boolean clear(float x0, float y0, float z0, float x1, float y1, float z1);

        /** 「不做视线判定」的实现：恒为可见 */
        Los ALWAYS = (x0, y0, z0, x1, y1, z1) -> true;
        /** 「永远被挡」的实现：用来验证退化行为 */
        Los NEVER = (x0, y0, z0, x1, y1, z1) -> false;
    }

    /** 一次发射瞄准的解算结果（纯数据，复用同一个实例，零分配）。 */
    public static final class Launch {
        /** 目标是被真实视线挡住的（用的是「不要求视线」的退化路径） */
        public boolean losBlocked;
        /** 水平朝向（本模组约定：yaw=0 朝 +Z，yaw=90 朝 -X） */
        public float yaw;
        /** 仰角（度，正 = 抬头发射） */
        public float pitch;
        /** 初速（格/tick） */
        public float speed;
        /** 归一化方向 × 初速 */
        public final float[] vel = new float[3];
        /** 瞄准点到目标的距离（格），仅用于日志/诊断 */
        public float distance;
        /** **真实**（未加提前量）水平距离（格），仅用于日志/诊断 */
        public float trueDistance;
        /** 本次瞄准用的提前量（tick；0 = 目标没动或弹速未知） */
        public float leadTicks;
        /** 提前量把瞄准点推出去的水平距离（格） */
        public float leadShift;
        /**
         * **重力补偿**：为了让弹丸在重力下垂之后仍然落在目标身上，瞄准点被抬高了多少（格）。
         *
         * <p>原版骷髅的 {@code performRangedAttack} 就是同一件事：
         * {@code arrow.shoot(dx, dy + d * 0.2F, dz, speed, inaccuracy)} —— 水平距离每远 1 格就多抬 0.2 格。
         * 这里把那个 0.2 换成「按该弹自己的重力/阻力/初速解出来的」值（见 {@link #gravityCompensation}）。
         */
        public float gravityComp;
        /** 补偿求解失败（目标太高/太低，解出来的抬升量被夹住）——仍然发射，但要在日志里说明 */
        public boolean compClamped;

        void reset() {
            losBlocked = false;
            yaw = 0.0F;
            pitch = 0.0F;
            speed = 0.0F;
            distance = 0.0F;
            trueDistance = 0.0F;
            leadTicks = 0.0F;
            leadShift = 0.0F;
            gravityComp = 0.0F;
            compClamped = false;
            vel[0] = 0.0F;
            vel[1] = 0.0F;
            vel[2] = 0.0F;
        }
    }

    /**
     * 远程攻击的**决策输入**。
     *
     * 用可变对象而不是 record：每个远程单位每 tick 都会问一次，
     * SoA 风格的模拟里不能为此每 tick 分配几万个 record（GC 压力会直接吃掉 LOD 省下来的收益）。
     */
    public static final class Query {
        /** 施法者脚底位置 */
        public float casterX;
        public float casterY;
        public float casterZ;
        /** 施法者眼高（相对脚底） */
        public float casterEye;
        /** 目标脚底位置 */
        public float targetX;
        public float targetY;
        public float targetZ;
        /** 目标眼高（相对脚底） */
        public float targetEye;
        /** 近战距离（格）—— 小于等于它就交给近战那条路 */
        public float meleeRange = 2.0F;
        /** 远程距离（格） */
        public float rangedRange = 16.0F;
        /** 初速（格/tick），只用来算 velocity，不参与判定 */
        public float speed = 3.0F;
        /** 目标每 tick 的水平速度（格/tick）—— 提前量用它外推瞄准点；0 = 不预测 */
        public float targetVelX;
        public float targetVelZ;
        /** 提前量上限（tick；&le; 0 = 不预测）。真实实现取该弹的「射程 / 弹速」 */
        public float leadTicks;
        /** 发射时目标身上的**瞄准偏移**（真实实体瞄的是身体中段而不是脚底） */
        public float aimBodyOffset = 0.8F;
        /**
         * 是否真的做视线判定。
         *
         * <p>默认 true（线上语义）。只有「注入的 {@link Los} 恒为可见」的退化路径才应当关掉它 ——
         * 否则一个没有方块世界的环境（离线 harness）会得到 {@code losBlocked = true} 这个
         * **没有任何证据支撑**的结论，而调用方会据此打一条「视线被挡住」的 WARN 刷屏。
         * 判定本身一个字都没变：它照样只有在 {@code requireLos && los != null} 时才跑。
         */
        public boolean requireLos = true;
        /** 该弹的重力（格/tick²，0 = 直线弹）。用来解**重力补偿** */
        public float gravity;
        /** 该弹的阻力（每 tick 乘数）。用来修正飞行时间与下垂 */
        public float drag = 1.0F;
        /**
         * 是否启用**重力补偿**（默认 true = 与原版 {@code dy + d·0.2} 同形的行为）。
         *
         * <p>关掉它就是**旧行为**（只做水平提前量）：只给离线对照实验用，
         * 用来量出「修前 vs 修后」的远距离命中率差距（见 projectileHarness 的 rangedHitRate 用例）。
         */
        public boolean gravityCompensation = true;
    }

    /**
     * **重力补偿**：按该弹自己的弹道参数解出「为了让弹丸在重力下垂后仍然落在目标身上，
     * 瞄准点需要抬高多少格」。
     *
     * <h2>与原版的关系</h2>
     * 原版骷髅的 {@code performRangedAttack} 写的是
     * {@code arrow.shoot(dx, dy + d * 0.2F, dz, speed, inaccuracy)} —— 水平距离 {@code d} 每远 1 格
     * 就多抬 {@code 0.2} 格。那个 0.2 是**手感常数**：对箭大致够用，换个弹种（雪球/火球）就完全不对。
     * 这里把它换成**由该弹自己的初速/重力/阻力解出来的量**。
     *
     * <h2>解法（为什么不能用一个闭式近似）</h2>
     * 第一版用 {@code drop = g·t²/2}（{@code t = d/v}）—— 实测**打高了约 1 格**
     * （22 格处瞄准点被抬 2.15 格，而真实下垂只有 1.77 格），远距离命中率归零。
     * 根因是「飞行时间」与「下垂」在这个积分器里不是同一个 t：
     * <pre>
     *   水平：vx ← vx · drag            每 tick（drag = 0.99）
     *   垂直：vy ← (vy - g) · drag      每 tick
     * </pre>
     * 所以这里按**与 {@link SkillParams#integrate} 完全同形的离散积分**逐 tick 解到水平距离 {@code d}，
     * 同时累加垂直位移 —— 得到的就是这个积分器真实的下垂量：
     *
     * <pre>
     *   x = 0; y = 0; vx = speed; vy = 0
     *   while (x &lt; distance) { x += vx; y -= vy; vx *= drag; vy = (vy - g) * drag; }
     *   下垂 = -y（负值 = 抬升量）
     * </pre>
     *
     * <p>对原版箭（v=3.0、重力=0.05、阻力=0.99）这个解法给：10 格 ≈ 0.31、22 格 ≈ 1.77、30 格 ≈ 3.5 格。
     * 原版常数 {@code d·0.2} 给的是 2 / 4.4 / 6 格 —— 本模组的弹道下垂更小（原版箭另有 0.05 摩擦），
     * 所以**只有按本模组自己的积分器解才对得上**。
     *
     * @param distance 水平距离（格）
     * @param speed    初速（格/tick）
     * @param gravity  重力（格/tick²；0 ⇒ 直线弹，不需要补偿）
     * @param drag     阻力（每 tick 乘数）
     */
    public static float gravityCompensation(float distance, float speed, float gravity, float drag) {
        if (gravity <= 1.0E-6F || speed <= 1.0E-4F || distance <= 0.0F) {
            return 0.0F;
        }
        float dd = Math.max(0.01F, Math.min(1.0F, drag));
        float x = 0.0F;
        float y = 0.0F;
        float vx = speed;
        float vy = 0.0F;
        int guard = 0;
        while (x < distance && guard++ < COMP_MAX_TICKS) {
            x += vx;
            y -= vy;
            vx *= dd;
            vy = (vy - gravity) * dd;
        }
        return Math.max(0.0F, y);
    }

    /** 重力补偿积分的时间上限（tick；与投掷物寿命同量级，防极端参数死循环） */
    public static final int COMP_MAX_TICKS = 600;

    /** 重力补偿的绝对上限（格）：超过它说明这个几何解不出来（目标极高/极低），夹住并标注 */
    public static final float COMP_MAX = 24.0F;

    /**
     * **沿真实弹道做视线判定**：把「炮口 → 补偿后的瞄准点」这条直线按固定步长采样，
     * 用实际弹道（重力 + 阻力）算出的高度去问视线回调。
     *
     * <h2>为什么不能只测直线</h2>
     * 加了重力补偿之后，瞄准点比目标**高**出 {@code comp} 格（30 格处约 2.7 格）。
     * 这时「炮口 → 瞄准点」是一条**向上**的直线，而弹丸实际走的是先平后垂的抛物线，
     * 整条路径都在这条直线**下方** —— 一段挡住那条直线的矮墙会被误判成「看不见」，
     * 于是远程单位对着空气反复侧移。反过来，只测「炮口 → 目标」也不行：
     * 抛物线最高点高于它，一道贴着目标的高墙会被漏判。
     *
     * <p>所以这里按真实弹道采样。代价是每次开火尝试多几条射线（步长 2 格时 30 格约 15 条），
     * 而开火尝试本身已被冷却节流（默认 40 tick 一次），相比每 tick 一次的模拟可以忽略。
     *
     * @param distance 水平距离（格）
     * @param dy       瞄准点相对炮口的**垂直差**（已含重力补偿）
     * @param horizX/horizZ 水平单位方向
     * @param muzzleX/muzzleY/muzzleZ 炮口
     */
    public static boolean losAlongBallistic(float distance, float dy,
                                            float horizX, float horizZ,
                                            float muzzleX, float muzzleY, float muzzleZ,
                                            float speed, float gravity, float drag, Los los) {
        if (los == null || distance <= 0.0F) {
            return true;
        }
        int steps = Math.max(2, Math.min(MAX_LOS_SAMPLES, (int) (distance / LOS_SAMPLE_STEP) + 1));
        for (int k = 0; k <= steps; k++) {
            float f = (float) k / (float) steps;
            float t = f * distance / Math.max(0.05F, speed);
            if (drag < 1.0F) {
                float avg = (1.0F - (float) Math.pow(Math.max(0.01F, drag), Math.max(0.0F, t)))
                        / (1.0F - drag) / Math.max(1.0E-4F, t);
                t = t / Math.max(0.2F, avg);
            }
            float x = muzzleX + horizX * distance * f;
            float z = muzzleZ + horizZ * distance * f;
            float y = muzzleY + dy * f - gravity * t * t * 0.5F;
            if (!los.clear(muzzleX, muzzleY, muzzleZ, x, y, z)) {
                return false;
            }
        }
        return true;
    }

    /** 弹道视线采样的步长（格） */
    public static final float LOS_SAMPLE_STEP = 2.0F;
    /** 弹道视线采样的点数上限（防御性：极端距离下也不会打几百条射线） */
    public static final int MAX_LOS_SAMPLES = 40;

    /**
     * **线段 × AABB** 相交检测（slab 法）——命中判定的精确判据。
     *
     * <h2>为什么不能用「线段到球心」</h2>
     * 上一版用 {@code SkillParams.sweepHit}：它测的是**线段到球心**的距离 ≤ hitRadius = 0.3。
     * 而一个原版怪是 0.6×1.95×0.6 的**长方体** —— 用它自己的半宽当球半径，
     * 竖直方向的判定范围就只有 0.3 格（真实半高 0.975）。
     * 后果是极其隐蔽的**按距离分段的命中/落空**：
     * <pre>
     *   近距离（≈6 格）：箭还在上升段，弹道比目标中心高约 0.5 格 ⇒ **打不到**
     *   远距离（≥12 格）：箭开始下垂，接近目标中心高度 ⇒ 又能打到了
     * </pre>
     * 真机表现就是「同样是箭，近的打不中、远的反而中」。改成 AABB 之后，
     * 判定范围就是这个单位真实的碰撞箱（水平 攻击者半径 + 目标半宽，竖直 ±身高/2）。
     *
     * @param halfX/halfY/halfZ 目标碰撞箱的半尺寸
     * @param pad               额外外扩（= 投掷物的命中半径）
     */
    public static boolean segmentHitsBox(float x0, float y0, float z0,
                                         float x1, float y1, float z1,
                                         float cx, float cy, float cz,
                                         float halfX, float halfY, float halfZ, float pad) {
        float minX = cx - halfX - pad;
        float maxX = cx + halfX + pad;
        float minY = cy - halfY - pad;
        float maxY = cy + halfY + pad;
        float minZ = cz - halfZ - pad;
        float maxZ = cz + halfZ + pad;

        float dx = x1 - x0;
        float dy = y1 - y0;
        float dz = z1 - z0;
        float t0 = 0.0F;
        float t1 = 1.0F;
        // slab 法：逐个轴求参数区间，取交集
        float[] r = slab(x0, dx, minX, maxX, t0, t1);
        if (r == null) {
            return false;
        }
        r = slab(y0, dy, minY, maxY, r[0], r[1]);
        if (r == null) {
            return false;
        }
        r = slab(z0, dz, minZ, maxZ, r[0], r[1]);
        return r != null;
    }

    /** 单轴 slab 裁剪；返回新的 [t0, t1]（复用长度为 2 的数组，null = 无交集） */
    private static float[] slab(float origin, float dir, float min, float max, float t0, float t1) {
        if (Math.abs(dir) < 1.0E-9F) {
            // 平行于该轴：origin 必须已经在 slab 内，否则永不进入
            if (origin < min || origin > max) {
                return null;
            }
        } else {
            float a = (min - origin) / dir;
            float b = (max - origin) / dir;
            if (a > b) {
                float tmp = a;
                a = b;
                b = tmp;
            }
            if (a > t0) {
                t0 = a;
            }
            if (b < t1) {
                t1 = b;
            }
            if (t0 > t1) {
                return null;
            }
        }
        slabOut[0] = t0;
        slabOut[1] = t1;
        return slabOut;
    }

    /** {@link #slab} 的复用缓冲（单线程服务端模拟，不做并发保护） */
    private static final float[] slabOut = new float[2];

    /**
     * 远程模式的一次攻击决策。
     *
     * @param cooldown 冷却剩余 tick（{@code CrowdData.attackCd} 的值）
     * @param los      视线回调（null = 不做视线判定）
     * @param out      解算结果（会被原地覆盖）
     * @return 是否**本 tick 真的发射了**（true 时调用方必须把冷却设回去，否则每 tick 连发）
     */
    public static boolean decide(Query q, int cooldown, Los los, Launch out) {
        return decide(q, cooldown, los, q.casterX, q.casterY, q.casterZ, out);
    }

    /**
     * 同 {@link #decide(Query, int, Los, Launch)}，但显式给出**这一 tick 扫掠线段的起点**。
     *
     * <h2>为什么需要它</h2>
     * 瞄准点带提前量时，真正的弹道是 {@code [炮口, 提前点]} 这一整段，而不是「炮口到提前点」的一条
     * 从头开始的直线：视线如果只测「炮口 → 提前点」的**终点**，一个刚从掩体后面探出半个身位、
     * 正在横移的目标就会被判成「看得见」（而箭其实是擦着掩体飞过去的）。
     * 把线段起点交给调用方（真实实现传的就是炮口），视线判定与瞄准解算用的是**同一段几何**。
     *
     * @param segX/segY/segZ 扫掠线段起点（= 本次瞄准的出发点；不传则等于施法者位置）
     */
    public static boolean decide(Query q, int cooldown, Los los,
                                 float segX, float segY, float segZ, Launch out) {
        out.reset();
        float dx0 = q.targetX - q.casterX;
        float dz0 = q.targetZ - q.casterZ;
        out.trueDistance = (float) Math.sqrt(dx0 * dx0 + dz0 * dz0);
        out.distance = out.trueDistance;

        float min = Math.max(0.05F, q.meleeRange);
        float max = Math.max(min, q.rangedRange);
        // 太长/太近都不射：太近交给近战（原版骷髅被贴近也会抡弓），太远够不着
        if (out.distance < min || out.distance > max) {
            return false;
        }
        if (cooldown > 0) {
            return false;   // 节流：冷却没走完绝不发射（否则会每 tick 连发把服务器刷爆）
        }

        // ---- 提前量（对移动目标按弹速求交点）----
        // 迭代解出的 t 就是「弹飞到瞄准点所需的 tick」；位移为 0 的目标（离线直接摆放的替身）
        // 会得到 t = 距离/弹速，但瞄准点仍等于目标位置 —— 语义与旧代码**完全一致**。
        float aimX = q.targetX;
        float aimZ = q.targetZ;
        float leadT = 0.0F;
        boolean moving = q.targetVelX != 0.0F || q.targetVelZ != 0.0F;
        if (q.leadTicks > 0 && moving && q.speed > 1.0E-4F) {
            float t = out.trueDistance / q.speed;
            for (int k = 0; k < LEAD_ITERATIONS; k++) {
                float px = q.targetX + q.targetVelX * t;
                float pz = q.targetZ + q.targetVelZ * t;
                float ddx = px - q.casterX;
                float ddz = pz - q.casterZ;
                float d = (float) Math.sqrt(ddx * ddx + ddz * ddz);
                float nt = Math.min(q.leadTicks, d / q.speed);
                if (Math.abs(nt - t) < 0.01F) {
                    t = nt;
                    break;
                }
                t = nt;
            }
            if (t > 0.0F) {
                aimX = q.targetX + q.targetVelX * t;
                aimZ = q.targetZ + q.targetVelZ * t;
                leadT = t;
                out.leadTicks = t;
                float sx = aimX - q.targetX;
                float sz = aimZ - q.targetZ;
                out.leadShift = (float) Math.sqrt(sx * sx + sz * sz);
            }
        }

        float dx = aimX - q.casterX;
        float dz = aimZ - q.casterZ;
        float horiz = (float) Math.sqrt(dx * dx + dz * dz);
        out.distance = horiz;
        // 提前量把瞄准点推到射程之外（目标正在全速逃）时不再发射：这一发只会掉在空地上
        if (horiz > max) {
            return false;
        }

        // 瞄准点：真实实体瞄的是目标身体中段偏上，不是脚底
        float aimY = q.targetY + q.targetEye * q.aimBodyOffset;
        float muzzleY = q.casterY + q.casterEye * 0.85F;

        // ---- 重力补偿（与原版 `dy + d·0.2` 同形，但系数按该弹自己的弹道解出来）----
        // 为什么必须有它：只瞄直线的话，弹丸在飞行途中被重力拽下去，
        // 20 格开外就会从目标头顶掉到脚下（真机表现：箭飞过去、穿过人堆、什么都不掉血）。
        // 抬升量加在**瞄准点的高度**上，于是 pitch / 视线线段 / 速度向量三者用的是同一个瞄准点。
        if (q.gravityCompensation && q.gravity > 1.0E-6F) {
            float comp = gravityCompensation(horiz, q.speed, q.gravity, q.drag);
            if (comp > COMP_MAX) {
                comp = COMP_MAX;
                out.compClamped = true;
            }
            out.gravityComp = comp;
            aimY += comp;
        }
        float dy = aimY - muzzleY;
        if (q.requireLos && los != null) {
            // ★ 沿**真实弹道**采样，而不是「炮口 → 瞄准点」的直线：
            //   补偿把瞄准点抬高之后，那条直线整条都在弹道上方，矮墙会被误判成「看不见」。
            float invHoriz = horiz > 1.0E-4F ? 1.0F / horiz : 0.0F;
            if (!losAlongBallistic(horiz, dy, dx * invHoriz, dz * invHoriz,
                    segX, segY, segZ, Math.max(0.05F, q.speed), Math.max(0.0F, q.gravity), q.drag, los)) {
                out.losBlocked = true;
                return false;
            }
        }
        out.yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        out.pitch = (float) Math.toDegrees(Math.atan2(dy, Math.max(horiz, 1.0E-4F)));
        out.speed = Math.max(0.0F, q.speed);
        SkillParams.launchVelocity(out.yaw, out.pitch, out.speed, out.vel);
        return true;
    }

    /**
     * 「这个类型算不算远程单位」的**唯一判据**（与 {@link ProjectileCatalog} 的解析链一致）：
     *
     * <ol>
     *   <li>玩家在技能页签里给这个类型配了投掷物（{@code TypeOverride.hasProjectileSkills()}）→ 是远程
     *       （<b>优先级最高</b>：玩家显式指定时连门槛都不看）；</li>
     *   <li>否则过**自动解析门槛** {@link ProjectileCatalog#rangedGate}：实体必须「真的会远程攻击」——
     *       实现原版 {@code RangedAttackMob}／{@code CrossbowAttackMob} 接口，或者它自己的字节码里
     *       真的 new 过某枚候选投掷物（烈焰人/恶魂/潜影贝就是后者，它们不实现任何接口）。
     *       僵尸/苦力怕/蜘蛛三条都不成立 → 保持近战；</li>
     *   <li>门槛通过后再看自动发现：给这个类型挑出来过一枚按原版基类判定的投掷物 → 是远程；</li>
     *   <li>否则不是远程（保持纯近战）。</li>
     * </ol>
     *
     * <p>「先门槛、后打分」这条顺序**只有一处实现**（{@link ProjectileCatalog#autoAttack}）——
     * 真机上末影龙被自动配了箭，正是因为类型登记那条路绕过了门槛直接打分，
     * 两条路结论不一致。现在这里也走同一个入口，不可能再分叉。
     *
     * 注意这里**完全不看** {@code ActionDriver} 的动作数：原版骷髅没有那套动画 API，
     * 但它一样是远程单位 —— 这正是上一轮「一个条件挡住整条链」的病根。
     */
    public static boolean isRangedAttacker(boolean hasConfiguredProjectile, String casterNs, String casterPath) {
        return isRangedFor(hasConfiguredProjectile, casterNs, casterPath) != null;
    }

    /**
     * 同上，但把「这个类型用哪一枚投掷物」也返回出来。
     *
     * <p>抽出来是为了让调用方（类型登记、GUI 预览、发射点判定）**共用同一条链**：
     * 只要都走这里，就不可能出现「登记时算远程、发射时算近战」这种自相矛盾的状态。
     *
     * @param hasConfiguredProjectile 玩家在 GUI 里显式配过投掷物：非 null 返回（哨兵
     *                                {@link #EXPLICIT}，因为具体是哪一枚由技能配置决定），
     *                                且**不看门槛**
     * @return null = 近战（没有远程证据 或 门槛过了但挑不出候选）
     */
    public static ResourceLocation isRangedFor(
            boolean hasConfiguredProjectile, String casterNs, String casterPath) {
        if (hasConfiguredProjectile) {
            // 玩家意图最高：显式配了就一律远程（连门槛都不看）
            return EXPLICIT;
        }
        // 唯一入口：先门槛、后打分（见 ProjectileCatalog.autoAttack 的注释 —— 真机上末影龙射箭
        // 就是「类型登记那条路直接打分、绕过门槛」造成的）
        return autoAttack(casterNs, casterPath);
    }

    /**
     * 「自动解析链」的唯一入口：门槛在前、打分在后。
     *
     * <p>它只是把 ns/path 拼成 id 再交给 {@link ProjectileCatalog#autoAttack} —— 之所以单独留一层，
     * 是因为调用方手上往往只有 (namespace, path) 两个字符串，而门槛/打分的实现都在 ProjectileCatalog。
     */
    public static ResourceLocation autoAttack(String casterNs, String casterPath) {
        if (casterNs == null || casterPath == null) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(casterNs + ":" + casterPath);
        if (id == null) {
            return null;
        }
        ProjectileCatalog.Candidate picked = ProjectileCatalog.autoAttack(id);
        return picked == null ? null : picked.id;
    }

    /** {@link #isRangedFor} 里「玩家显式配置」那一支的哨兵返回值（调用方只判 null / 非 null） */
    private static final ResourceLocation EXPLICIT = ResourceLocation.tryParse("horde:explicit_gui_config");

    /**
     * 一条**可读的**瞄准描述，用于限频日志。
     *
     * 为什么连速度与方向都要打出来：这次排查的痛点是「什么都没发生，日志里也什么都没有」；
     * 有了这一行就能一眼看出「发射了但方向不对」「初速是 0」这类问题，
     * 不用再靠猜（真机上最容易错的恰恰是这两个）。
     */
    public static String describeLaunch(String projectileId, Launch l) {
        return String.format(java.util.Locale.ROOT,
                "%s 初速 %.2f 格/tick，yaw %.1f° pitch %.1f°，速度向量 (%.2f, %.2f, %.2f)，距离 %.1f 格",
                projectileId == null ? "（自动挑）" : projectileId,
                l.speed, l.yaw, l.pitch, l.vel[0], l.vel[1], l.vel[2], l.distance);
    }
}
