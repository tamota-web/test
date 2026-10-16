package dev.swordkingdom.horde;

/**
 * 分层耗时与计数的**唯一累加器**（服务端与客户端共用）。
 *
 * <h2>为什么不许再有第二个累加器</h2>
 * 上一版把「计时」拆成了两半：调用方拿一个 {@code long[]} 槽位累加，而汇总行读的是本类自己的
 * {@code aiTickNanos/physicsTickNanos/...} 字段 —— 那批字段**只被清零、从来没被写过**。
 * 于是真机日志里出现了一个极具误导性的现象：客户端那半边（走 {@code noteXxx} 累加）数字正常，
 * 服务端那半边**所有分段恒为 0.000ms**，而它恰好是「41 ticks behind 到底卡在哪一段」唯一需要的证据。
 *
 * <p><b>现在的规矩（也是本类存在的意义）</b>：任何一段耗时/计数都只有一条路径 ——
 * 调用方只通过 {@link #noteStageNanos(Stage, long)} / {@code noteXxx} 写进来，
 * 汇总只通过 {@link #snapshot()} 读出去；快照与清零在同一次 {@link #tick()} 里成对完成。
 * 不存在「写给 A、读 B」的可能。
 *
 * <h2>设计约束（避免插桩自己成为瓶颈）</h2>
 * <ul>
 *   <li>每 tick 的累加只是几个 {@code long} 自增；<b>不做除法、不格式化、不分配</b>；</li>
 *   <li>{@link System#nanoTime()} 只在每段的首尾各一次；</li>
 *   <li>只有真的「卡了一下」（≥ {@link #LAG_TICK_NANOS}）才做一次字符串拼接 + WARN；</li>
 *   <li>关掉插桩时只剩一个 volatile 布尔判断。</li>
 * </ul>
 *
 * <h2>「每秒」是怎么定义的</h2>
 * 不用墙钟定时器（会引入线程），而是把 {@link #tick()} 挂在服务端 tick 末（20Hz）——
 * 每 20 tick 汇总一次。离线 harness 因此可以「手动 tick」精确复现每秒一行这件事。
 */
public final class HordeProfiler {

    /** 汇总间隔（tick）：20 tick = 1 秒 @ 20 TPS */
    public static final int FLUSH_TICKS = 20;

    // ------------------------------------------------------------------
    // 阶段定义（唯一的阶段枚举 —— 累加与汇总共用，不可能再对不上）
    // ------------------------------------------------------------------

    /** 服务端 tick 的分段。 */
    public enum Stage {
        /** 索敌（aiTick） */
        AI("ai"),
        /** 物理（physicsTick：重力/摩擦/方块碰撞/台阶） */
        PHYSICS("physics"),
        /** 单位互推（pushEntities -> CrowdSeparation） */
        PUSH("push"),
        /** 战斗结算（resolveCombat：近战 + 远程决策） */
        COMBAT("combat"),
        /** 技能/动作（skillTick：动作推进 + 命中帧） */
        SKILL("skill"),
        /** 虚拟投掷物模拟（ProjectileManager.stepAll，含方块碰撞与命中判定） */
        PROJECTILE("projectile"),
        /** 空间网格重建（grid.build） */
        GRID("grid"),
        /** 流场重算 */
        FLOW("flow"),
        /** 新类型属性解析（含投掷物自动发现；发生在 spawn 的调用方线程上） */
        STATS("stats");

        public final String label;

        Stage(String label) {
            this.label = label;
        }
    }

    private static final Stage[] STAGES = Stage.values();

    // ------------------------------------------------------------------
    // 本 tick 的累加器（唯一写入点）
    // ------------------------------------------------------------------

    private static final long[] tickStage = new long[STAGES.length];
    private static int tickEnemyScans;
    private static int tickHitCalls;
    private static int projThisTick;
    private static int unitsThisTick;

    // ------------------------------------------------------------------
    // 本窗口的累计（汇总行读这里；由快照在 tick() 末尾推进）
    // ------------------------------------------------------------------

    private static long windowTickCount;
    private static final long[] windowStage = new long[STAGES.length];
    private static long windowEnemyScans;
    private static long windowEnemyScansAi;
    private static long windowEnemyScansCombat;
    private static long windowEnemyScanCandidates;
    private static long windowEnemyScanNanos;
    private static long windowHitCalls;
    private static long windowHitCandidates;
    private static long windowSeparationPairs;
    private static int windowMaxEnemyScansPerTick;
    private static int windowMaxHitCallsPerTick;
    private static int windowStatsBuilds;
    private static long windowUnits;
    private static long windowProjectiles;

    /** 上一次 {@link #tick()} 输出的两行（给 harness 断言与真机排查抄日志用） */
    private static String lastServerLine = "";
    private static String lastLagLine = "";

    private static boolean profiling = true;
    private static volatile boolean configRead;
    private static int sinceFlush;

    private HordeProfiler() {}

    public static void setEnabled(boolean on) {
        profiling = on;
        configRead = true;
    }

    /**
     * 是否启用插桩（读配置 {@code debug.logTickProfile}，默认开）。
     *
     * <p>只读一次就缓存：线程安全性无关紧要（最坏退化成「多算一次/少算一次」），
     * 而每 tick 查一遍配置对象没有意义。
     */
    public static boolean enabled() {
        if (!configRead) {
            configRead = true;
            profiling = HordeConfig.safeGet(HordeConfig.COMMON.logTickProfile, true);
        }
        return profiling;
    }

    // ------------------------------------------------------------------
    // 累加 API（唯一入口）
    // ------------------------------------------------------------------

    /** 开始一段计时；配合 {@link #end(long, Stage)} 用。关掉插桩时返回 0，调用方无需分支。 */
    public static long begin() {
        return enabled() ? System.nanoTime() : 0L;
    }

    /** 结束一段计时并累加到对应阶段；返回本次耗时（纳秒）。 */
    public static long end(long start, Stage stage) {
        if (start == 0L) {
            return 0L;
        }
        long d = System.nanoTime() - start;
        tickStage[stage.ordinal()] += d;
        return d;
    }

    /** 直接把一段耗时记到某个阶段（调用方自己算好了差值）。 */
    public static void noteStageNanos(Stage stage, long nanos) {
        if (enabled() && nanos > 0L) {
            tickStage[stage.ordinal()] += nanos;
        }
    }

    /** 兼容旧签名：写进 {@code slot[0]}，**并**记到对应阶段（避免再出现两个累加器）。 */
    public static void noteStageNanos(Stage stage, long[] slot, long nanos) {
        if (!enabled() || nanos <= 0L) {
            return;
        }
        tickStage[stage.ordinal()] += nanos;
        if (slot != null) {
            slot[0] += nanos;
        }
    }

    /** 记一次索敌扫描（{@code candidates} 由扫描内部累加比较次数）。 */
    public static void noteEnemyScan() {
        if (enabled()) {
            tickEnemyScans++;
            windowEnemyScans++;

        }
    }

    /** 记一次来自 aiTick 的索敌扫描。 */
    public static void noteAiScan() {
        if (enabled()) {
            tickAiScanCount++;
            windowEnemyScansAi++;
        }
    }

    private static long tickAiScanCount;

    /** 记一次来自 resolveCombat 兜底索敌的扫描。 */
    public static void noteCombatScan() {
        if (enabled()) {
            tickCombatScanCount++;
            windowEnemyScansCombat++;
        }
    }

    private static long tickCombatScanCount;

    /** 记一次索敌扫描里比较过的候选。 */
    public static void noteEnemyCandidates(int n) {
        if (enabled()) {
            tickEnemyScanCandidates += n;
            windowEnemyScanCandidates += n;
        }
    }

    private static long tickEnemyScanCandidates;

    /** 记一次索敌扫描的耗时（纳秒）。 */
    public static void noteEnemyScanNanos(long nanos) {
        if (enabled()) {
            tickEnemyScanNanos += nanos;
            windowEnemyScanNanos += nanos;
        }
    }

    private static long tickEnemyScanNanos;

    /** 记一次命中判定调用。 */
    public static void noteHitCall() {
        if (enabled()) {
            tickHitCalls++;
            windowHitCalls++;
        }
    }

    /** 记一次命中判定里比较过的候选。 */
    public static void noteHitCandidates(int n) {
        if (enabled()) {
            tickHitCandidates += n;
            windowHitCandidates += n;
        }
    }

    private static long tickHitCandidates;

    /** 记一次分离配对比较。 */
    public static void noteSeparationPair() {
        if (enabled()) {
            tickSeparationPairs++;
            windowSeparationPairs++;
        }
    }

    private static long tickSeparationPairs;

    /** 记录当前在场的投掷物数（每维度调用一次，累加得到本 tick 的总数）。 */
    public static void noteProjectiles(int n) {
        if (enabled() && n > 0) {
            projThisTick += n;
        }
    }

    /** 记录当前在场的群集单位数（每维度汇总后调用一次；本 tick 内取最大值）。 */
    public static void noteUnits(int n) {
        if (enabled() && n > unitsThisTick) {
            unitsThisTick = n;
        }
    }

    /** 记一次类型属性解析（含投掷物自动发现）的耗时。 */
    public static void noteStatsBuild(long nanos) {
        if (!enabled()) {
            return;
        }
        tickStatsBuilds++;
        windowStatsBuilds++;
        tickStage[Stage.STATS.ordinal()] += Math.max(0L, nanos);
        if (nanos > tickStatsWorstNanos) {
            tickStatsWorstNanos = nanos;
        }
    }

    private static int tickStatsBuilds;
    /** 上一个窗口里单次最慢的类型属性解析（纳秒） */
    public static long lastStatsWorstNanos;

    // ------------------------------------------------------------------
    // 卡顿归因：这一 tick 里发生了哪些「外部事件」
    // ------------------------------------------------------------------

    private static long lastTickStartNanos;
    private static long lastIntervalNanos;
    public static long lastTickNanos;
    public static long maxTickNanos;
    /** 判为「卡顿 tick」的阈值（100ms = 2 tick 的预算） */
    public static final long LAG_TICK_NANOS = 100_000_000L;

    private static int tickLongCount;
    private static long tickLongWorst;
    private static long tickLongTickNo;
    private static long tickLongWorstTickNo;
    private static boolean tickHadWorldSave;
    private static boolean tickHadStatsBuild;
    private static boolean tickHadPoolScan;
    private static long lastWorldSaveNanos;
    public static long lastWorldSaveTick = -1L;
    public static long lastPoolScanTick = -1L;

    /** 上一次 {@link #tick()} 里发生过卡顿 tick 的数量 / 最差耗时（窗口级，供 harness 与日志） */
    public static int lastWindowsLongTicks;
    public static long lastWindowsWorstNanos;

    /**
     * 一 tick 开始：清空本 tick 的累加器并记下起点。
     *
     * <p>与 {@link #tick()} 成对调用，顺序是
     * {@code beginTick() → （各阶段自己 noteStageNanos） → tick()}。
     * 挂在服务端 tick **最前面**（见 HordeEvents），于是测到的是「这一整个 tick」而不只是群集那几段。
     */
    public static void beginTick() {
        if (!enabled()) {
            return;
        }
        long now = System.nanoTime();
        if (lastTickStartNanos != 0L) {
            lastIntervalNanos = now - lastTickStartNanos;
        }
        lastTickStartNanos = now;
        java.util.Arrays.fill(tickStage, 0L);
        tickEnemyScans = 0;
        tickHitCalls = 0;
        projThisTick = 0;
        unitsThisTick = 0;
        tickAiScanCount = 0L;
        tickCombatScanCount = 0L;
        tickEnemyScanCandidates = 0L;
        tickEnemyScanNanos = 0L;
        tickHitCandidates = 0L;
        tickSeparationPairs = 0L;
        tickStatsBuilds = 0;
        tickStatsWorstNanos = 0L;
        tickHadWorldSave = false;
        tickHadStatsBuild = false;
        tickHadPoolScan = false;
    }

    /** 外层标记「这一 tick 里发生过世界保存」。 */
    public static void noteWorldSave(long nanos) {
        if (!enabled()) {
            return;
        }
        tickHadWorldSave = true;
        lastWorldSaveNanos = nanos;
        lastWorldSaveTick = windowTickCount;
    }

    /** 外层标记「这一 tick 里候选池扫描过」。 */
    public static void notePoolScan() {
        if (!enabled()) {
            return;
        }
        tickHadPoolScan = true;
        lastPoolScanTick = windowTickCount;
    }

    /**
     * 只给离线 harness/自检用：把「这一 tick 的墙钟间隔」设成指定值。
     *
     * <p>为什么需要：卡顿判据是「一 tick ≥ 100ms」，而离线环境里没法让一个 tick 真的花 100ms
     * （测量本身只有微秒级）—— 于是一条本来正确的判据在 harness 里永远触发不了。
     * 这个口子只影响判据的**输入**，不改变任何生产行为。
     */
    public static void injectTickIntervalNanos(long nanos) {
        if (enabled()) {
            lastTickStartNanos = System.nanoTime() - nanos;
        }
    }

    /**
     * 服务端 tick 末调用一次：推进窗口、必要时打「卡顿归因」行、到点打汇总行。
     *
     * @param unitsNow       &lt; 0 = 用本 tick 里 {@code noteUnits} 记的值
     * @param projectilesNow &lt; 0 = 用本 tick 里 {@code noteProjectiles} 累加的值
     * @return 本 tick 是否打了汇总行（供 harness 断言「每秒最多一行」）
     */
    public static boolean tick(int unitsNow, int projectilesNow) {
        if (!enabled()) {
            return false;
        }
        long now = System.nanoTime();
        long wall = lastTickStartNanos == 0L ? 0L : now - lastTickStartNanos;
        if (lastTickStartNanos == 0L) {
            lastIntervalNanos = 0L;
        } else {
            lastIntervalNanos = wall;
        }
        // 「这一 tick 用了多久」取「墙钟间隔」与「各阶段实测之和」的较大值：
        // 后者能把「在 tick 内同步做掉的活」（世界保存/类型解析）算进来，
        // 前者把「谁都没插桩的活」（整合包其它 mod、GC）算进来。
        long stageSum = 0L;
        for (long v : tickStage) {
            stageSum += v;
        }
        lastTickNanos = Math.max(wall, stageSum);
        if (lastTickNanos > maxTickNanos) {
            maxTickNanos = lastTickNanos;
        }
        // 本 tick 的计数 → 窗口累计（计数类由各自的 noteXxx 直接写窗口，这里只补「每 tick 峰值」）
        for (int k = 0; k < STAGES.length; k++) {
            windowStage[k] += tickStage[k];
        }
        windowTickCount++;
        if (tickEnemyScans > windowMaxEnemyScansPerTick) {
            windowMaxEnemyScansPerTick = tickEnemyScans;
        }
        if (tickHitCalls > windowMaxHitCallsPerTick) {
            windowMaxHitCallsPerTick = tickHitCalls;
        }
        if (tickStatsBuilds > 0) {
            tickHadStatsBuild = true;
        }
        if (tickStatsWorstNanos > lastStatsWorstNanos) {
            lastStatsWorstNanos = tickStatsWorstNanos;
        }
        windowUnits = unitsThisTick > 0 ? unitsThisTick : (unitsNow >= 0 ? unitsNow : windowUnits);
        windowProjectiles = projectilesNow >= 0 ? projectilesNow : projThisTick;

        // ---- 卡顿归因：这一 tick ≥ 100ms 就独立打一行（不看窗口） ----
        boolean isLong = lastTickNanos >= LAG_TICK_NANOS;
        if (isLong) {
            tickLongCount++;
            if (lastTickNanos > tickLongWorst) {
                tickLongWorst = lastTickNanos;
                tickLongWorstTickNo = windowTickCount;
            }
            tickLongTickNo = windowTickCount;
            lastLagLine = lagLine(tickLongTickNo, lastTickNanos, 1, lastTickNanos);
            logWarn(lastLagLine);
        }

        sinceFlush++;
        if (sinceFlush < FLUSH_TICKS) {
            return false;
        }
        sinceFlush = 0;
        serverSnapshot = snapshot();
        lastServerLine = serverLine(serverSnapshot);
        logInfo(lastServerLine);
        // 窗口级卡顿总结（有卡顿才打；与上面那条逐 tick 的 WARN 互补）
        lastWindowsLongTicks = tickLongCount;
        lastWindowsWorstNanos = tickLongWorst;
        if (tickLongCount > 0) {
            logWarn(lagLine(tickLongWorstTickNo, tickLongWorst, tickLongCount,
                    tickLongWorst));
        }
        tickLongCount = 0;
        tickLongWorst = 0L;
        tickLongTickNo = 0L;
        tickLongWorstTickNo = 0L;
        lastStatsWorstNanos = 0L;
        resetWindow();
        return true;
    }

    // ------------------------------------------------------------------
    // 快照：**同一实例上的原子读**
    // ------------------------------------------------------------------

    /**
     * 一个窗口的不可变快照。
     *
     * <p>这就是「累加 → 快照 → 打印」里的第二步：{@link #tick()} 先 {@code snapshot()} 再
     * {@code resetWindow()}，打印用的是快照里的值 —— 于是**打印的内容与窗口里累加的内容一定是同一批**。
     */
    public static final class Snapshot {
        public final long tickCount;
        public final long[] stageNanos;
        public final long units;
        public final long projectiles;
        public final long enemyScans;
        public final long enemyScansAi;
        public final long enemyScansCombat;
        public final long enemyScanCandidates;
        public final long enemyScanNanos;
        public final long hitCalls;
        public final long hitCandidates;
        public final long separationPairs;
        public final int maxEnemyScansPerTick;
        public final int maxHitCallsPerTick;
        public final int statsBuilds;

        Snapshot(long tickCount, long[] stageNanos, long units, long projectiles,
                 long enemyScans, long enemyScansAi, long enemyScansCombat, long enemyScanCandidates,
                 long enemyScanNanos, long hitCalls, long hitCandidates, long separationPairs,
                 int maxEnemyScansPerTick, int maxHitCallsPerTick, int statsBuilds) {
            this.tickCount = tickCount;
            this.stageNanos = stageNanos;
            this.units = units;
            this.projectiles = projectiles;
            this.enemyScans = enemyScans;
            this.enemyScansAi = enemyScansAi;
            this.enemyScansCombat = enemyScansCombat;
            this.enemyScanCandidates = enemyScanCandidates;
            this.enemyScanNanos = enemyScanNanos;
            this.hitCalls = hitCalls;
            this.hitCandidates = hitCandidates;
            this.separationPairs = separationPairs;
            this.maxEnemyScansPerTick = maxEnemyScansPerTick;
            this.maxHitCallsPerTick = maxHitCallsPerTick;
            this.statsBuilds = statsBuilds;
        }

        public long stage(Stage s) {
            return stageNanos[s.ordinal()];
        }

        /** 所有阶段之和（纳秒）。 */
        public long totalNanos() {
            long t = 0L;
            for (long v : stageNanos) {
                t += v;
            }
            return t;
        }

        /** 阶段耗时（毫秒），harness / 命令用。 */
        public double stageMs(Stage s) {
            return stage(s) / 1.0E6;
        }
    }

    /** 本窗口的实时快照（不清零；harness 与命令用）。 */
    public static Snapshot snapshot() {
        return new Snapshot(windowTickCount, windowStage.clone(), windowUnits, windowProjectiles,
                windowEnemyScans, windowEnemyScansAi, windowEnemyScansCombat, windowEnemyScanCandidates,
                windowEnemyScanNanos, windowHitCalls, windowHitCandidates, windowSeparationPairs,
                windowMaxEnemyScansPerTick, windowMaxHitCallsPerTick, windowStatsBuilds);
    }

    /** 上一次 {@link #tick()} 刷出汇总行时用的那份快照。 */
    private static Snapshot serverSnapshot = snapshot();

    public static Snapshot lastServerSnapshot() {
        return serverSnapshot;
    }

    /** 一次性读回所有阶段的纳秒值（顺序 = {@link Stage#values()}）。 */
    public static long[] windowStageNanos() {
        return windowStage.clone();
    }

    private static void resetWindow() {
        java.util.Arrays.fill(windowStage, 0L);
        windowTickCount = 0L;
        windowEnemyScans = 0L;
        windowEnemyScansAi = 0L;
        windowEnemyScansCombat = 0L;
        windowEnemyScanCandidates = 0L;
        windowEnemyScanNanos = 0L;
        windowHitCalls = 0L;
        windowHitCandidates = 0L;
        windowSeparationPairs = 0L;
        windowMaxEnemyScansPerTick = 0;
        windowMaxHitCallsPerTick = 0;
        windowStatsBuilds = 0;
        windowUnits = 0L;
        windowProjectiles = 0L;
    }

    // ------------------------------------------------------------------
    // 输出格式
    // ------------------------------------------------------------------

    /** 服务端每秒汇总行（**只读快照**，绝不在这里读任何会被清零的字段）。 */
    public static String serverLine(Snapshot s) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(String.format(java.util.Locale.ROOT,
                "[horde] tick: units=%d proj=%d total=%.3fms |", s.units, s.projectiles,
                s.totalNanos() / 1.0E6));
        for (Stage st : STAGES) {
            sb.append(' ').append(st.label).append('=')
                    .append(String.format(java.util.Locale.ROOT, "%.3f", s.stage(st) / 1.0E6));
        }
        String stats = s.statsBuilds == 0 ? "无"
                : (s.statsBuilds + " 次/" + String.format(java.util.Locale.ROOT, "%.3f",
                s.stage(Stage.STATS) / 1.0E6) + "ms");
        return sb.append(String.format(java.util.Locale.ROOT,
                " | 索敌扫描=%d(候选 %d, 峰值/tick=%d) 命中=%d(候选 %d, 峰值/tick=%d) 分离配对=%d"
                        + " | 新类型属性解析=%s | 最慢 tick=%.1fms(第 %d tick)",
                s.enemyScans, s.enemyScanCandidates, s.maxEnemyScansPerTick,
                s.hitCalls, s.hitCandidates, s.maxHitCallsPerTick,
                s.separationPairs, stats, maxTickNanos / 1.0E6, tickLongWorstTickNo)).toString();
    }

    /**
     * 「卡顿归因」行：一个 tick ≥ 100ms 时独立打一条 WARN。
     *
     * <p>格式刻意把「谁干的」摆在最前面：耗时、当时的 units/proj、该 tick 自己的分段耗时、
     * 以及这一 tick 内是否发生了世界保存 / 类型解析 / 候选池扫描。
     */
    public static String lagLine(long tickNo, long tickNanos, int longCount, long worstNanos) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(String.format(java.util.Locale.ROOT,
                "[horde] 卡顿 tick：第 %d tick 耗时 %.1fms（阈值 %.0fms，本秒共 %d 个卡顿 tick，最差 %.1fms）"
                        + " units=%d proj=%d | 本 tick 分段：",
                tickNo, tickNanos / 1.0E6, LAG_TICK_NANOS / 1.0E6, longCount, worstNanos / 1.0E6,
                unitsThisTick, projThisTick));
        for (Stage st : STAGES) {
            long v = tickStage[st.ordinal()];
            if (v > 0L) {
                sb.append(' ').append(st.label).append('=')
                        .append(String.format(java.util.Locale.ROOT, "%.3fms", v / 1.0E6));
            }
        }
        sb.append(" | 事件：");
        sb.append(tickHadWorldSave ? "**世界保存** " : "");
        sb.append(tickHadStatsBuild ? "**新类型属性解析** " : "");
        sb.append(tickHadPoolScan ? "**候选池扫描** " : "");
        sb.append("（其余为逐 tick 模拟 + 整合包其它 mod）");
        sb.append(" | 上一 tick 间隔=")
                .append(String.format(java.util.Locale.ROOT, "%.1fms", lastIntervalNanos / 1.0E6));
        return sb.toString();
    }

    private static long tickStatsWorstNanos;

    // ------------------------------------------------------------------
    // 客户端
    // ------------------------------------------------------------------
    public static long frames;
    public static long poseEvals;
    public static long poseReEvals;
    public static int maxPoseEvalsPerFrame;
    public static int maxUnitsPerFrame;
    public static long poseEvalNanos;
    public static long uploadBytes;
    public static long drawnInstances;
    public static int meshes;
    /** 我们这一段渲染的耗时（纳秒）：fillInstance + 姿态上传 + draw call + 投掷物 */
    public static long crowdFrameNanos;
    public static long crowdWorstFrameNanos;
    public static int crowdProfileFrames;
    private static String lastClientLine = "";

    public static void frameBegin() {
        if (enabled()) {
            frames++;
        }
    }

    /** 记下本帧我们这一段（群集+投掷物渲染）的耗时。 */
    public static void noteCrowdFrameNanos(long nanos) {
        if (!enabled() || nanos <= 0L) {
            return;
        }
        crowdFrameNanos += nanos;
        crowdProfileFrames++;
        if (nanos > crowdWorstFrameNanos) {
            crowdWorstFrameNanos = nanos;
        }
    }

    public static void frameEnd(int unitsThisFrame, int evalsThisFrame, long evalNanosThisFrame,
                               int meshesThisFrame, long uploadBytesThisFrame, int drawnThisFrame) {
        if (!enabled()) {
            return;
        }
        poseEvals += evalsThisFrame;
        poseEvalNanos += evalNanosThisFrame;
        meshes = meshesThisFrame;
        uploadBytes += uploadBytesThisFrame;
        drawnInstances += drawnThisFrame;
        if (evalsThisFrame > maxPoseEvalsPerFrame) {
            maxPoseEvalsPerFrame = evalsThisFrame;
        }
        if (unitsThisFrame > maxUnitsPerFrame) {
            maxUnitsPerFrame = unitsThisFrame;
        }
    }

    public static void notePoseReEval() {
        if (enabled()) {
            poseReEvals++;
        }
    }

    public static boolean shouldFlushClient(int framesInWindow) {
        return enabled() && framesInWindow >= FLUSH_TICKS;
    }

    /**
     * 客户端汇总行（每秒一行）。
     *
     * <p>把 {@code crowdMs/帧}（我们这一段）与 {@code fps≈} 并列 —— 一句话回答「是不是我们拖慢的」。
     * {@code crowdMs} 即使 {@code units=0} 也会打（便于「清空前 vs 清空后」对照）。
     */
    public static String clientLine(int framesInWindow) {
        double perFrame = framesInWindow <= 0 ? 0.0D : crowdFrameNanos / 1.0E6 / framesInWindow;
        double evalPerUnitPerFrame = (maxUnitsPerFrame <= 0 || framesInWindow <= 0)
                ? 0.0D : (double) poseEvals / framesInWindow / maxUnitsPerFrame;
        return String.format(java.util.Locale.ROOT,
                "[horde] client: units=%d/帧(峰值) poseEval=%d/秒(%.2f/单位/帧, 峰值/帧=%d)"
                        + " 复用跳过=%d 求值耗时=%.3fms/秒 crowdMs/帧=%.3f(峰值 %.3f)"
                        + " 网格=%d 上传=%.1fKB/秒 drawn=%d | fps≈%d",
                maxUnitsPerFrame, poseEvals, evalPerUnitPerFrame, maxPoseEvalsPerFrame,
                poseReEvals, poseEvalNanos / 1.0E6, perFrame, crowdWorstFrameNanos / 1.0E6,
                meshes, uploadBytes / 1024.0D, drawnInstances,
                framesInWindow <= 0 ? 0 : framesInWindow * 20 / Math.max(1, framesInWindow));
    }

    /** 「对照方法」那行只打一次（每次会话一次） */
    private static final java.util.concurrent.atomic.AtomicBoolean controlHintLogged =
            new java.util.concurrent.atomic.AtomicBoolean();

    public static boolean controlHintLogged() {
        return controlHintLogged.get();
    }

    /** 只给离线 harness 用：重置「对照方法已打」标志。 */
    public static void resetControlHint() {
        controlHintLogged.set(false);
    }

    /** 打客户端汇总行并把窗口清零（含 crowdMs 的三个量）。 */
    public static void clientFlush(int framesInWindow) {
        if (!enabled()) {
            return;
        }
        lastClientLine = clientLine(framesInWindow);
        logInfo(lastClientLine);
        // 对照方法的说明**每次会话只打一次**（原先是每秒都打，units=0 时刷屏）。
        // 用一次性标志而不是「跟随 debug 开关」：排查时最需要的正是这一行，
        // 而它本身每会话只出现一次，不会构成噪声。
        if (controlHintLogged.compareAndSet(false, true)) {
            logInfo("[horde] 对照方法（判定「是我们还是整合包本身」）：用 /horde clear 清空群集 → 看 fps 与 crowdMs/帧；"
                    + "再生成 25 → 看；再生成 100 → 看。判断口径：crowdMs/帧 明显上升且与 fps 下降幅度一致 ⇒ 是我们；"
                    + "crowdMs/帧 只有零点几毫秒而 fps 依旧很低 ⇒ 是整合包/其它 mod。"
                    + "（units=0 时 client 行照常输出，便于对比；本行只打一次）");
        }
        frames = 0L;
        poseEvals = 0L;
        poseEvalNanos = 0L;
        poseReEvals = 0L;
        maxPoseEvalsPerFrame = 0;
        maxUnitsPerFrame = 0;
        uploadBytes = 0L;
        drawnInstances = 0L;
        meshes = 0;
        crowdFrameNanos = 0L;
        crowdWorstFrameNanos = 0L;
        crowdProfileFrames = 0;
    }

    // ------------------------------------------------------------------
    // 只读访问（harness / 命令 / 诊断）
    // ------------------------------------------------------------------

    public static String lastServerLine() {
        return lastServerLine;
    }

    public static String lastClientLine() {
        return lastClientLine;
    }

    public static String lastLagLine() {
        return lastLagLine;
    }

    public static long maxTickNanos() {
        return maxTickNanos;
    }

    /** 只读：本窗口内某一段的耗时（不清零）。 */
    public static long stageNanos(Stage s) {
        return windowStage[s.ordinal()];
    }

    // ---- 只读访问器（客户端窗口与若干个服务端计数）----

    public static long poseEvals() {
        return poseEvals;
    }

    public static long poseReEvals() {
        return poseReEvals;
    }

    public static long poseEvalNanos() {
        return poseEvalNanos;
    }

    public static int maxPoseEvalsPerFrame() {
        return maxPoseEvalsPerFrame;
    }

    public static int maxUnitsPerFrame() {
        return maxUnitsPerFrame;
    }

    public static long uploadBytes() {
        return uploadBytes;
    }

    public static int meshes() {
        return meshes;
    }

    public static long frames() {
        return frames;
    }

    /** 只读：**本窗口内**（不是整场）的候选比较次数 —— 每 20 tick 会被清零。 */
    public static long enemyScanCandidates() {
        return windowEnemyScanCandidates;
    }

    public static long enemyScans() {
        return windowEnemyScans;
    }

    public static long enemyScansAi() {
        return windowEnemyScansAi;
    }

    public static long enemyScansCombat() {
        return windowEnemyScansCombat;
    }

    public static long enemyScanNanos() {
        return windowEnemyScanNanos;
    }

    public static long hitResolverCalls() {
        return windowHitCalls;
    }

    public static long separationPairs() {
        return windowSeparationPairs;
    }

    public static int maxEnemyScansPerTick() {
        return windowMaxEnemyScansPerTick;
    }

    /** 只读：**本 tick 内**新增的计数（每 tick 开始时清零，不受 20 tick 窗口影响）。 */
    public static int tickEnemyScans() {
        return tickEnemyScans;
    }

    public static int tickHitCalls() {
        return tickHitCalls;
    }

    public static int tickAiScans() {
        return (int) tickAiScanCount;
    }

    public static int tickCombatScans() {
        return (int) tickCombatScanCount;
    }

    public static long tickEnemyCandidates() {
        return tickEnemyScanCandidates;
    }

    public static long tickEnemyScanNanos() {
        return tickEnemyScanNanos;
    }

    /** 只读：**本 tick 内**某一段的耗时（二级分段 / 归因诊断用）。 */
    public static long tickStageNanos(Stage s) {
        return tickStage[s.ordinal()];
    }

    /** 只读：本 tick 内比较过的分离配对数。 */
    public static long tickSeparationPairs() {
        return tickSeparationPairs;
    }

    public static long tickHitCandidates() {
        return tickHitCandidates;
    }

    /** 只读：本窗口（可能只有一部分 tick）里累计的量。 */
    public static long windowEnemyScans() {
        return windowEnemyScans;
    }

    public static long windowEnemyScanCandidates() {
        return windowEnemyScanCandidates;
    }

    public static long windowEnemyScanNanos() {
        return windowEnemyScanNanos;
    }

    public static long windowHitCalls() {
        return windowHitCalls;
    }

    public static long windowSeparationPairs() {
        return windowSeparationPairs;
    }

    public static long windowMaxEnemyScansPerTick() {
        return windowMaxEnemyScansPerTick;
    }

    public static long windowMaxHitCallsPerTick() {
        return windowMaxHitCallsPerTick;
    }

    /** 只读：本窗口的 tick 数。 */
    public static long windowTickCount() {
        return windowTickCount;
    }

    /** 只读：本窗口的群集单位数 / 投掷物数。 */
    public static long windowUnits() {
        return windowUnits;
    }

    public static long windowProjectiles() {
        return windowProjectiles;
    }

    /** 离线 harness 用：清空全部累计（每个用例互不影响）。 */
    public static void resetAll() {
        java.util.Arrays.fill(tickStage, 0L);
        java.util.Arrays.fill(windowStage, 0L);
        windowTickCount = 0L;
        tickEnemyScans = tickHitCalls = projThisTick = unitsThisTick = 0;
        tickAiScanCount = tickCombatScanCount = 0L;
        tickEnemyScanCandidates = tickEnemyScanNanos = 0L;
        tickHitCandidates = tickSeparationPairs = 0L;
        tickStatsBuilds = 0;
        tickStatsWorstNanos = 0L;
        lastStatsWorstNanos = 0L;
        tickHadWorldSave = tickHadStatsBuild = tickHadPoolScan = false;
        lastWorldSaveNanos = 0L;
        lastWorldSaveTick = -1L;
        lastPoolScanTick = -1L;
        tickLongCount = 0;
        tickLongWorst = 0L;
        tickLongTickNo = 0L;
        tickLongWorstTickNo = 0L;
        lastWindowsLongTicks = 0;
        lastWindowsWorstNanos = 0L;
        lastTickStartNanos = 0L;
        lastIntervalNanos = 0L;
        lastTickNanos = 0L;
        maxTickNanos = 0L;
        sinceFlush = 0;
        lastServerLine = "";
        lastClientLine = "";
        lastLagLine = "";
        frames = 0L;
        poseEvals = poseReEvals = 0L;
        poseEvalNanos = 0L;
        maxPoseEvalsPerFrame = maxUnitsPerFrame = 0;
        uploadBytes = 0L;
        drawnInstances = 0L;
        meshes = 0;
        crowdFrameNanos = 0L;
        crowdWorstFrameNanos = 0L;
        crowdProfileFrames = 0;
        profiling = true;
        configRead = true;
    }

    /**
     * 安全的日志出口。
     *
     * <p>为什么包一层 try/catch：离线 harness 里日志后端可能根本不可用
     * （{@code LogUtils.getLogger()} 会抛），而插桩绝不能把模拟/渲染带崩。
     */
    public static void logInfo(String line) {
        try {
            HordeMod.LOGGER.info(line);
        } catch (Throwable ignored) {
            // 没有日志后端：忽略
        }
    }

    public static void logWarn(String line) {
        try {
            HordeMod.LOGGER.warn(line);
        } catch (Throwable ignored) {
            // 没有日志后端：忽略
        }
    }
}
