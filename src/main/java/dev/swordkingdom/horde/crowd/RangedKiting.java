package dev.swordkingdom.horde.crowd;

/**
 * 远程单位的**距离保持（kiting）**规则 —— 全部是纯数学，离线可断言。
 *
 * <h2>为什么要有这个类</h2>
 * 真机反馈：「远程单位冲到目标脸上近战」。根因是 {@code CrowdManager.physicsTick} 里
 * 单位的移动意图只有一条 —— <b>直奔目标</b>（原版 {@code MeleeAttackGoal} 的语义）。
 * 于是骷髅一路顶上去，真机日志里射击距离从 1.98 格到 16 格都有。
 *
 * <p>真实实体的行为是 {@code RangedAttackGoal} + {@code RangedBowAttackGoal}：
 * 目标在射程外 → 靠近；进入射程 → 停下射；被贴到 {@code (attackRadius+2)²} 之内 → 后撤。
 * 这里把同一件事写成「三段式」意图，并且把**理想站位**从投掷物自己的弹道参数推出来
 * （见 {@link #rule}），而不是拍一个魔数。
 *
 * <h2>理想距离怎么推（只用 {@link ProjectileCatalog.Defaults} 里已有的量）</h2>
 * 一枚弹「能打多远」取决于它在飞完这段距离时掉了多少 —— 也就是它的弹道有多平：
 * <pre>
 *   每秒下垂（格/tick²）  drop = gravity            （重力为 0 的火球完全不垂）
 *   飞完全程用时（tick）  t    = 1/(1-drag)          （阻力 1.0 时取弹的寿命）
 *   全程下垂（格）        drop * t² / 2
 * </pre>
 * 把「下垂不超过一个身位（{@link #DROP_BUDGET} 格）」当作有效射程的上限，
 * 于是：
 * <pre>
 *   effectiveRange = min( 寿命内水平位移, sqrt(2 · DROP_BUDGET / max(drop, ε)) · 平均速度 )
 *   idealDistance  = clamp( 0.60 · effectiveRange, retreatMin, min(rangedDistance, effectiveRange) )
 *   retreatMin(最小开火距离) = max( 0.45 · rangedDistance, 近战距离 + SAFE_BUFFER )
 * </pre>
 * 实测三档（默认 rangedDistance = 16 格）：
 * <pre>
 *   箭   初速 3.0 重力 0.05 阻力 0.99 -> 有效射程 15.01，站 7.20~15.01（弹道射程 96 格）
 *   火球 初速 0.6 重力 0.00 阻力 1.00 -> 有效射程 16.00，站 7.20~16.00（弹道射程 48 格）
 *   雪球 初速 1.5 重力 0.03 阻力 0.99 -> 有效射程  9.84，站 7.20~ 9.84（弹道射程 94 格）
 * </pre>
 *
 * <h2>四段式（{@link Move}）</h2>
 * <pre>
 *   d &gt; ballisticRange → APPROACH     弹道射程之外：原地打不到，只能靠近
 *   d &lt; min            → RETREAT      太近：后撤（朝向仍对目标）
 *   d &gt; max            → APPROACH     射程内但站得太远：靠近到理想站位
 *   min &lt;= d &lt;= max   → HOLD         **原地开火**（不产生任何朝目标的位移）
 *   （弹道射程内但没视线 → STRAFE_LOS 侧移找视线，而不是顶上去）
 * </pre>
 * {@code max} 与 {@code min} 之间刻意留出「带宽」，避免在单点上来回抖动
 * （每 tick 在接近/后撤之间切换会让单位原地抽搐，也浪费物理步）。
 *
 * <p><b>站位上限只由弹道与「玩家配的远程射程」决定，不再被 {@code followRange} 夹。</b>
 * 索敌半径是「能不能发现你」的事，不是「够不够得着你」的事 —— 把两者绑在一起，
 * 会逼着索敌半径小的远程怪明明够得着还往前走（真机反馈的「远程跑过去」）。
 */
public final class RangedKiting {

    private RangedKiting() {}

    /**
     * 允许的**弹道下垂**（格）—— 用来把弹道折算成有效射程。
     *
     * <p>为什么是 0.6 格：远程判定用的是「瞄准点 → 目标身体中段」的一条直线，
     * 弹丸实际走的是抛物线。下垂越大，瞄准要抬得越高、横向误差被放得越大，
     * 命中率掉得越快。0.6 格 ≈ 一个僵尸肩膀到身体中段的距离，也就是
     * 「再垂下去就不是打身体、而是打脚／打地面」的那条线。
     *
     * <p>这个数字决定了三档弹的站位差异（实测，默认射程 16 格）：
     * 箭（初速 3.0 重力 0.05）有效射程 15.01 → 站 7.20~15.01 格；
     * 火球（初速 0.6 重力 0）有效射程 16.0（被射程夹住）→ 站 7.20~16.00 格；
     * 雪球（初速 1.5 重力 0.03）有效射程 9.84 → 站 7.20~9.84 格。
     */
    public static final float DROP_BUDGET = 0.6F;

    /** 下垂预算的下界（体型差很小时仍要有一定容差，否则雪球会被缩到贴脸） */
    public static final float DROP_BUDGET_MIN = 0.35F;

    /**
     * 由**双方体型**折算出可接受的弹道下垂（格）。
     *
     * <p>为什么不再是一个固定常数：下垂预算是「从出射点到目标身体」的那条竖直线段的容差，
     * 它天然随双方体型变 —— 小体型单位瞄大体型单位，允许的误差更大。
     * 这里取「出射高度（≈ 攻击者眼高 × 0.85）与目标身体中段之差 + 0.3」，
     * 并夹在 [{@link #DROP_BUDGET_MIN}, {@link #DROP_BUDGET}] 内，
     * 保证不会退化成「无限射程」（那会让远程单位站到天边去）。
     */
    public static float dropBudgetFor(float shooterEye, float targetHeight) {
        float muzzle = Math.max(0.2F, shooterEye) * 0.85F;
        float body = Math.max(0.2F, targetHeight) * 0.5F;
        float budget = Math.abs(muzzle - body) + 0.3F;
        return Math.max(DROP_BUDGET_MIN, Math.min(DROP_BUDGET, budget));
    }

    /** 理想站位占有效射程的比例（越平的弹越可以站远） */
    public static final float IDEAL_FRACTION = 0.60F;

    /** 最小开火距离占「远程射程」的比例（贴着射程下限射击没有任何意义） */
    public static final float MIN_FRACTION = 0.45F;

    /**
     * 最小开火距离相对近战距离的安全余量（格）。
     *
     * <p>为什么必须有它：如果「最小开火距离」只按射程比例算，一个射程 5 格的雪傀儡
     * 会得到 2.25 格 —— 已经进了近战距离（≈1.43 格）的判定范围，于是它会在
     * 「想射」和「被贴脸切近战」之间反复横跳。加 1.2 格余量之后，
     * 后撤的目标点始终留在近战距离之外。
     */
    public static final float SAFE_BUFFER = 1.2F;

    /** 单位 id 哈希带来的站位错开幅度（±15%），避免同类型单位全部挤成一层 */
    public static final float JITTER = 0.15F;

    /**
     * 纯弹道射程的绝对上限（格）——**唯一**的安全阀。
     *
     * <p>为什么需要一个绝对上限：箭的物理射程公式给的是 {@code v²/g = 180} 格，
     * 于是「射程内一律原地开火」会变成「站在 180 格外往人堆里撒箭」——
     * 既不会有任何命中率，也让远程单位看起来像在乱射。
     *
     * <p>为什么是 64 格：玩家要的「极远距离也能原地开火」在默认射程 16 格下
     * 指的是十几到几十格；64 格 = 4 倍默认射程，已经覆盖这条需求，
     * 同时远小于「跨半张地图点射」那种荒唐量级。它**不**与 {@code followRange} 挂钩
     * （索敌半径仍然只决定「能不能发现」）。
     */
    public static final float BALLISTIC_RANGE_CAP = 64.0F;

    /** 把纯弹道射程夹到 {@link #BALLISTIC_RANGE_CAP} */
    public static float rangeCapOf(float ballisticRange) {
        return Math.min(ballisticRange, BALLISTIC_RANGE_CAP);
    }

    /**
     * 开火判定的**射程上界**：弹道打得到、而且玩家配的远程距离也允许。
     *
     * <p>取两者较大值而不是较小值：玩家配的 {@code rangedDistance}（默认 16）是
     * 「交战的期望距离」，而弹道射程是「这一发实际能打到哪」。玩家要的
     * 「骷髅可以直接向极远的目标发射箭矢」属于后者 —— 只要弹道真的够得着就该能射。
     */
    public static float fireEnvelope(Rule rule, CrowdManager.TypeStats stats) {
        float configured = stats != null ? stats.rangedDistance : 0.0F;
        float ballistic = rule != null ? rangeCapOf(rule.ballisticRange) : 0.0F;
        return Math.max(configured, ballistic);
    }

    /** 提前量迭代次数（对移动目标求交点的不动点迭代） */
    public static final int LEAD_ITERATIONS = 4;

    /** 提前量上限（tick）：超过它的目标速度（瞬移/被击退）不参与预测 */
    public static final float MAX_LEAD = 40.0F;

    /**
     * 一个类型的站位规则（全部由弹道参数 + 射程上限推导，运行期只读）。
     *
     * @param minDistance 最小开火距离（格）：比这更近就后撤
     * @param maxDistance 理想站位上限（格）：比这更远就靠近
     * @param idealDistance 理想距离（格）——纯日志/诊断用，实际判定用 min/max 组成的带
     * @param effectiveRange 由弹道推出的有效射程（格）
     * @param rangedRange 远程射程上限（格，取 type 自己的 rangedDistance）
     * @param leadTicks 提前量上限（tick；0 = 目标不动或弹速未知时不预测）
     * @param bullets 用来算提前量的弹速（格/tick）
     * @param gravity 弹的重力（格/tick²，0 = 直线）
     * @param drag 弹的阻力（每 tick 乘数）
     * @param life 弹的寿命（tick）
     * @param kind 弹的基类分类（日志用）
     */
    public static final class Rule {
        public final float minDistance;
        public final float maxDistance;
        public final float idealDistance;
        /** 由弹道推出的**有效射程**（含下垂预算）——站位用它 */
        public final float effectiveRange;
        /**
         * **纯弹道射程**（只由初速/重力/寿命/阻力推，不含下垂预算、**不**被 followRange 夹）。
         *
         * <p>它是「这枚弹最远能飞到哪」的上界，用来回答「在原地能不能打到」这个问题：
         * 目标只要在这个距离内，就**原地开火**，绝不位移过去。
         */
        public final float ballisticRange;
        public final float rangedRange;
        public final int leadTicks;
        public final float bullets;
        public final float gravity;
        public final float drag;
        public final int life;
        public final ProjectileCatalog.Kind kind;

        Rule(float minDistance, float maxDistance, float idealDistance, float effectiveRange,
             float ballisticRange, float rangedRange, int leadTicks, float bullets, float gravity,
             float drag, int life, ProjectileCatalog.Kind kind) {
            this.minDistance = minDistance;
            this.maxDistance = maxDistance;
            this.idealDistance = idealDistance;
            this.effectiveRange = effectiveRange;
            this.ballisticRange = ballisticRange;
            this.rangedRange = rangedRange;
            this.leadTicks = leadTicks;
            this.bullets = bullets;
            this.gravity = gravity;
            this.drag = drag;
            this.life = life;
            this.kind = kind;
        }

        /** 该距离下应该怎么走（三段式；{@link #Move#HOLD} 表示「站着射」） */
        public Move moveFor(float distance) {
            if (distance > maxDistance) {
                return Move.APPROACH;
            }
            if (distance < minDistance) {
                return Move.RETREAT;
            }
            return Move.HOLD;
        }

        /**
         * 第 slot 个单位的目标站位（同一类型内部按 id 哈希小幅错开）。
         *
         * <p>错开只影响 {@code max}（「够远了就别再靠近」的那条线）与理想距离，
         * <b>不动</b> {@code min} —— 后撤阈值必须全类型一致，否则近的那一批会先被吃掉。
         */
        public float standoffFor(int slot) {
            float j = jitter(slot);
            float max = maxDistance * (1.0F + j);
            return Math.max(minDistance, max);
        }

        /** {@code ±JITTER} 内的确定性抖动（同一 slot 恒定，不会每 tick 变） */
        public static float jitter(int slot) {
            int h = slot * 374761393 + 668265263;
            h = (h ^ (h >>> 13)) * 1274126177;
            h = h ^ (h >>> 16);
            float u = (h & 0xFFFF) / 65535.0F;          // 0..1
            return (u * 2.0F - 1.0F) * JITTER;
        }

        /** 一行中文说明（限频日志用；逐发日志走 debug.logLaunchDetail，默认关） */
        public String describe() {
            return String.format(java.util.Locale.ROOT,
                    "攻击模式：原地开火（距离保持 [%.2f,%.2f] 格，弹道射程 %.2f 格，有效射程 %.2f），"
                            + "间隔 %d tick，提前量 %d tick（弹速 %.2f 格/tick，重力 %.3f，寿命 %d，类别 %s）",
                    minDistance, maxDistance, ballisticRange, effectiveRange,
                    leadTicks, leadTicks, bullets, gravity, life, kind);
        }
    }

    /** 三段式移动意图 */
    public enum Move {
        /** 目标在理想带之外：靠近到理想距离 */
        APPROACH,
        /** 在理想带内：驻留并射击 */
        HOLD,
        /** 目标太近（进了最小开火距离）：后撤 */
        RETREAT,
        /**
         * 距离已经够（弹道射程内），但**没有视线**：侧移找视线。
         *
         * <p>刻意与 {@link #APPROACH} 分开：玩家要的是「站在原地能打就原地打」，
         * 没有视线时也不该一路顶上去贴脸 —— 侧移一步绕开遮挡更像真实实体的走位。
         */
        STRAFE_LOS
    }

    /**
     * 当前位置下该怎么走（**最终裁决**）。
     *
     * <p>四条规则（顺序即优先级）：
     * <ol>
     *   <li>{@code distance > ballisticRange}：目标在**弹道射程之外**，原地打不到 ——
     *       只能靠近（站位规则在这里没有发言权，因为没有位移就永远打不到）；</li>
     *   <li>{@code distance < minDistance}：太近 —— 后撤（朝向仍对目标）；</li>
     *   <li>{@code distance > standoff(slot)}：在射程内但站得太远 —— 靠近到理想站位；</li>
     *   <li>否则：**原地开火**（{@link Move#HOLD}）。</li>
     * </ol>
     *
     * <p><b>followRange 不参与</b>：索敌半径是「能不能发现你」的事，不是「够不够得着你」的事。
     * 以前把站位上限也拿 followRange 夹一次，于是索敌半径小的远程怪明明弹道够得着，
     * 却被逼着往前走 —— 那正是「远程跑过去」的一半成因。
     *
     * @param slot 单位槽位（同类型内部按 id 哈希小幅错开站位，避免全挤在同一距离）
     */
    public static Move decide(Rule rule, CrowdManager.TypeStats stats, int slot, float distance) {
        if (rule == null) {
            return Move.APPROACH;
        }
        // ① 弹道射程之内 ⇒ **原地开火**（这是玩家的硬要求：
        //    「远程攻击需要在原地就能向远距离的目标释放对应的攻击，不会自己跑过去」）。
        //    刻意**不**再拿「理想站位」当开火先决条件：站位带只描述命中率最优，
        //    而为了命中率去挪动一步，正是「远程单位自己跑过去」的来源。
        // ② 太近 ⇒ 后撤（朝向仍对目标，倒着走）。
        // ③ 弹道射程之外 ⇒ 只能靠近（原地打不到）。
        if (distance <= rule.ballisticRange) {
            return distance < rule.minDistance ? Move.RETREAT : Move.HOLD;
        }
        return Move.APPROACH;
    }

    /**
     * 「值得为命中率走两步」的余量（格）。
     *
     * <p><b>当前不参与决策</b>（2026-10：玩家要求「原地开火」，所以射程内一律不挪动）。
     * 保留它，是给「以后要恢复『顺路靠近到理想站位』」留一个显式参数：
     * 那时的规则应当是「超过 standoff + 余量才值得走」，避免只超一点就顶着走。
     */
    public static final float APPROACH_SLACK = 8.0F;

    /**
     * 由**投掷物自己的参数**推导站位规则。
     *
     * <p>站位上限**只**由弹道决定：
     * <pre>
     *   effectiveRange = min(寿命水平位移, 垂到下垂预算的时刻的水平位移) + 下垂预算
     *   ballisticRange = 寿命耗尽时的水平位移（纯弹道上界，不夹任何配置）
     *   min            = max(0.45 · rangedRange, 近战距离 + 1.2)
     *   max            = min(rangedRange, max(min, effectiveRange))
     * </pre>
     * 注意 {@code max} 仍受玩家配的 {@code rangedRange} 约束（那是玩家的显式意图：
     * 「这个类型的射程就是 16 格」），但**不再**受 {@code followRange} 约束。
     *
     * @param bullets       弹的初速（格/tick）
     * @param gravity       弹的重力（格/tick²）
     * @param drag          弹的阻力（每 tick 乘数）
     * @param life          弹的寿命（tick）
     * @param kind          弹的基类分类
     * @param rangedRange   远程射程上限（格，= {@code TypeStats.rangedDistance}）
     * @param meleeReach    该类型的近战距离（格）
     * @param followRange   该类型的索敌半径（格）—— **只用于日志**，不夹站位
     * @param dropBudget    可接受的弹道下垂（格，由双方体型推，见 {@link #dropBudgetFor}）
     */
    public static Rule rule(float bullets, float gravity, float drag, int life,
                            ProjectileCatalog.Kind kind, float rangedRange,
                            float meleeReach, float followRange, float dropBudget) {
        float v = Math.max(0.05F, bullets);
        float g = Math.max(0.0F, gravity);
        float d = Math.max(0.01F, Math.min(1.0F, drag));
        int l = Math.max(1, life);
        float range = Math.max(1.0F, rangedRange);
        float budget = Math.max(DROP_BUDGET_MIN, Math.min(DROP_BUDGET, dropBudget));

        float effective = effectiveRange(v, g, d, l, budget);
        // 纯弹道射程**不夹任何配置**：它回答的是「这一发最远能落在哪」这个物理问题，
        // 玩家要的「极远距离也能原地开火」就靠它（见 decide 与开火判定的射程上界）。
        // 唯一的安全阀是 {@link #rangeCapOf}（64 格），见那里的注释。
        float ballistic = rangeCapOf(ballisticRange(v, g, d, l));
        // 最小开火距离：射程比例 与 「近战距离 + 安全余量」取较大者
        float min = Math.max(MIN_FRACTION * range, meleeReach + SAFE_BUFFER);
        // 站位上限：① 不超出弹道有效射程（超了就是白白浪费命中率）
        //           ② 不超出玩家配的远程射程（超了就永远进不了「能射」的状态）
        float cap = Math.min(range, Math.max(min, effective));
        float ideal = Math.min(cap, Math.max(min, IDEAL_FRACTION * effective));
        // 提前量：目标一 tick 走过的距离 × 弹飞行所需的 tick。用「弹速」而不做完整弹道积分，
        // 因为提前量的作用是「别既瞄准点差得离谱」，不是求弹道精确解。
        int lead = (int) Math.min(MAX_LEAD, Math.max(1.0F, Math.min(cap, ballistic) / v));
        return new Rule(min, cap, ideal, effective, ballistic, range, lead, v, g, d, l, kind);
    }

    /** 兼容旧签名（下垂预算取上界 {@link #DROP_BUDGET}） */
    public static Rule rule(float bullets, float gravity, float drag, int life,
                            ProjectileCatalog.Kind kind, float rangedRange,
                            float meleeReach, float followRange) {
        return rule(bullets, gravity, drag, life, kind, rangedRange, meleeReach, followRange, DROP_BUDGET);
    }

    /**
     * **纯弹道射程**：这枚弹在「打平射」时最远能落到哪里。
     *
     * <pre>
     *   g &gt; 0（有重力）：平射最大射程 = v² / g      ← 经典的抛体射程公式（45° 出手）
     *   g = 0（直线弹）  ：只受寿命与阻力限制 = v·(1-d^life)/(1-d)（drag=1 时 v·life）
     *   再与「寿命耗尽时的水平位移」取小
     * </pre>
     *
     * <p>为什么不用「寿命耗尽时的水平位移」当射程：那是箭在**完全不失高**的假设下能飞的距离
     * （箭 100 tick × 3.0 ≈ 96 格），而现实中它早落到地上了。用 {@code v²/g} 才是
     * 「站着不动、一箭能覆盖多远」的物理答案（箭 3²/0.05 = 180 格，仍然很远，
     * 但那是初速与重力的真实关系；游戏里由「远程射程」这一配置约束到合理范围）。
     *
     * <p>它**不**被 {@code followRange} 夹：索敌半径是「能不能发现」，射程是「能不能打到」。
     */
    public static float ballisticRange(float bullets, float gravity, float drag, int life) {
        float v = Math.max(0.05F, bullets);
        float g = Math.max(0.0F, gravity);
        float d = Math.max(0.01F, Math.min(1.0F, drag));
        int l = Math.max(1, life);
        float byLife = d >= 1.0F ? v * l : v * (1.0F - (float) Math.pow(d, l)) / (1.0F - d);
        float byGravity = g > 1.0E-6F ? v * v / g : byLife;
        return Math.max(1.0F, Math.min(byLife, byGravity));
    }

    /**
     * 弹道有效射程：在「至少还落在目标身上」的前提下能打多远。
     *
     * <pre>
     *   阻力 &lt; 1：水平位移 s(t) = v · (1 - d^t) / (1 - d)，总位移上限 v / (1 - d)
     *   阻力 = 1：s(t) = v · t
     *   下垂     h(t) = g · t² / 2   （重力为 0 的火球不垂）
     * </pre>
     * 取「寿命耗尽」与「垂到下垂预算」两个时刻中较早的那个，换算成水平距离。
     *
     * <p>为什么拿下垂当判据而不是「能不能够到」：够不够得到只取决于初速与方块碰撞，
     * 那是几百格的量级（箭 96 格、火球 48 格），对站位毫无约束力；
     * 真正让「站得越远越打不中」的是重力 —— 射程越远，弹道下垂越大，
     * 瞄准要抬得越高、横向误差被放得越大。下垂预算就是这条可接受的误差。
     */
    public static float effectiveRange(float bullets, float gravity, float drag, int life, float dropBudget) {
        float v = Math.max(0.05F, bullets);
        float g = Math.max(0.0F, gravity);
        float d = Math.max(0.01F, Math.min(1.0F, drag));
        int l = Math.max(1, life);
        float budget = Math.max(1.0E-3F, dropBudget);

        float sLife = ballisticRange(v, g, d, l);

        // 垂到下垂预算的时刻（重力为 0 时不存在：射程只受寿命限制）
        float sDrop = sLife;
        if (g > 1.0E-6F) {
            float tDrop = (float) Math.sqrt(2.0F * budget / g);
            if (tDrop < l) {
                sDrop = d >= 1.0F ? v * tDrop
                        : v * (1.0F - (float) Math.pow(d, tDrop)) / (1.0F - d);
            }
        }
        return Math.max(1.0F, Math.min(sLife, sDrop) + budget);
    }

    /** 兼容旧签名（下垂预算取上界 {@link #DROP_BUDGET}） */
    public static float effectiveRange(float bullets, float gravity, float drag, int life) {
        return effectiveRange(bullets, gravity, drag, life, DROP_BUDGET);
    }

    /**
     * 对移动目标求**提前量**（不动点迭代，与原版 {@code ProjectileUtil} 的近似同形）。
     *
     * <p>先用直线距离估一次飞行时间，再按目标速度外推瞄准点、重算距离，迭代
     * {@link #LEAD_ITERATIONS} 次。目标速度取「上一 tick 位移」（SoA 里就是
     * {@code pos - prev}），没有历史时（刚生成 / 离线直接摆放）速度为 0 ⇒ 退化成瞄当前位置。
     *
     * @param bullets 弹速（格/tick）；&le; 0 时不预测
     * @param out     输出瞄准点（长度 &ge; 3，原地覆盖）
     */
    public static void lead(float bullets, int maxLeadTicks,
                            float casterX, float casterY, float casterZ,
                            float targetX, float targetY, float targetZ,
                            float velX, float velY, float velZ,
                            float[] out) {
        out[0] = targetX;
        out[1] = targetY;
        out[2] = targetZ;
        if (bullets <= 1.0E-4F) {
            return;
        }
        int cap = maxLeadTicks > 0 ? maxLeadTicks : (int) MAX_LEAD;
        float t = 0.0F;
        for (int k = 0; k < LEAD_ITERATIONS; k++) {
            float px = targetX + velX * t;
            float py = targetY + velY * t;
            float pz = targetZ + velZ * t;
            float dx = px - casterX;
            float dy = py - casterY;
            float dz = pz - casterZ;
            float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            float nt = Math.min(cap, dist / bullets);
            if (Math.abs(nt - t) < 0.01F) {
                t = nt;
                break;
            }
            t = nt;
        }
        out[0] = targetX + velX * t;
        out[1] = targetY + velY * t;
        out[2] = targetZ + velZ * t;
    }
}
