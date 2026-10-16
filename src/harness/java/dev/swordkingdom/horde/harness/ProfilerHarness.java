package dev.swordkingdom.horde.harness;

import dev.swordkingdom.horde.HordeLog;
import dev.swordkingdom.horde.HordeProfiler;
import dev.swordkingdom.horde.client.PoseScheduler;
import dev.swordkingdom.horde.crowd.CrowdManager;
import net.minecraft.resources.ResourceLocation;

/**
 * 「先测量，再优化」这条链上的硬性质，全部离线钉住：
 *
 * <ol>
 *   <li><b>姿态求值次数 ≤ 单位数 × 帧数</b>（{@link PoseScheduler}）——
 *       同一帧里若有两条路径走到同一单位，第二次起必须沿用缓存。用例用替身帧号跑几百帧，
 *       并**用「关掉守卫」的对照证明这个守卫真的在拦东西**（不是恒真的断言）；</li>
 *   <li><b>日志节流后每秒最多一行汇总</b>（{@link HordeLog.Summary}）；</li>
 *   <li><b>索敌查询有上界</b>（{@code CrowdManager} 的索敌节流）——用**同一份生产代码**跑
 *       「节流开 / 关」两次，断言扫描次数与候选比较次数至少降 3 倍，且模拟结果逐字相同；</li>
 *   <li><b>分层耗时经 CrowdManager 真实路径读回必须非 0</b>——
 *       这一条钉的是上一版的阻塞性 bug：{@code end(start, slot)} 写调用方的 {@code long[]}，
 *       而汇总行读 {@code HordeProfiler} 自己的字段，两套累加器 ⇒ 真机所有分段恒为 0.000ms；</li>
 *   <li><b>卡顿归因行</b>：一个 tick ≥ 100ms 必须独立打一行 WARN，带该 tick 自己的分段与事件。</li>
 * </ol>
 */
public final class ProfilerHarness {

    private static int failures;

    public static void main(String[] args) {
        poseEvalBoundedPerFrame();
        poseGuardActuallyMatters();
        clientProfileCounters();
        logThrottleOneLinePerSecond();
        scanBoundedAndBehaviorIdentical();
        profileStagesAreFilled();
        lagAttributionLine();

        System.out.println(failures == 0
                ? "\n== PROFILER PASS =="
                : "\n== PROFILER FAILURES: " + failures + " ==");
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------
    // 1. 姿态求值：每单位每帧最多一次
    // ------------------------------------------------------------------

    /**
     * 真机数据的判读：26~28 个单位、每 2.5~3.5 秒涨约 5400 ⇒ 恰是
     * {@code units × frames}（27 × 200 = 5400）。这个用例把这条不变式钉住。
     */
    private static void poseEvalBoundedPerFrame() {
        HordeProfiler.resetAll();
        final int units = 27;
        final int frames = 400;
        PoseScheduler s = new PoseScheduler(true);
        long evals = 0L;
        long reEvaluated = 0L;
        int worstPerFrame = 0;
        int worstUnitsPerFrame = 0;

        // 每帧把每个单位访问两次（模拟「主渲染路径 + 另一条路径也走到同一个单位」），
        // 距离在近/中/远三档之间来回摆，让 LOD 周期 1/2/4/8 都真的被用到。
        for (int f = 0; f < frames; f++) {
            s.frameBegin();
            int tick = f;
            for (int phase = 0; phase < 2; phase++) {
                for (int id = 0; id < units; id++) {
                    float dist = 10.0F + ((id * 37 + f * 11 + phase) % 200);
                    int before = s.evaluatedThisFrame();
                    PoseScheduler.Decision d = s.prepare(id, dist * dist, tick);
                    if (d == PoseScheduler.Decision.EVALUATE) {
                        long st = s.evalBegin();
                        s.evalEnd(st);
                        s.noteEvaluated(id, tick);
                    } else if (d == PoseScheduler.Decision.REUSE) {
                        reEvaluated++;
                    }
                    if (s.evaluatedThisFrame() > before + 1) {
                        check(false, "同一帧内一次 prepare 不该产生超过 1 次求值");
                    }
                }
            }
            int perFrame = s.evaluatedThisFrame();
            int unitsThisFrame = s.visitedThisFrame();
            evals += perFrame;
            if (perFrame > worstPerFrame) {
                worstPerFrame = perFrame;
            }
            if (unitsThisFrame > worstUnitsPerFrame) {
                worstUnitsPerFrame = unitsThisFrame;
            }
            check(perFrame <= unitsThisFrame,
                    "第 " + f + " 帧求值次数（" + perFrame + "）不得超过该帧单位访问数（" + unitsThisFrame + "）");
            s.frameEnd();
        }

        System.out.printf("[ ok ] 姿态求值上界：%d 单位 × %d 帧 × 2 次访问 → 求值 %d 次（峰值/帧 %d ≤ 单位访问 %d），"
                        + "被守卫复用的重复访问 %d 次%n",
                units, frames, evals, worstPerFrame, worstUnitsPerFrame, reEvaluated);
        check(evals <= (long) units * 2 * frames, "求值总次数必须 ≤ 单位访问总次数");
        check(worstPerFrame <= worstUnitsPerFrame, "单帧求值峰值不得超过单帧单位数峰值");
        check(reEvaluated > 0L, "重复访问必须真的被守卫拦下来过（否则这个用例恒真、什么也没验到）");
        HordeProfiler.resetAll();
    }

    /** 对照实验：**关掉守卫**就会复现「同一帧里同一单位被求值多次」。 */
    private static void poseGuardActuallyMatters() {
        final int units = 10;
        final int frames = 60;
        long guardedEvals = runTwoVisitFrames(units, frames, true);
        long guardedReuse = lastReuse;
        long unguardedEvals = runTwoVisitFrames(units, frames, false);
        long unguardedReuse = lastReuse;

        System.out.printf("[ ok ] 守卫有效性对照（%d 单位 / 每帧访问 2 次 / %d 帧）："
                        + "开守卫 求值 %d（复用 %d）｜关守卫 求值 %d（复用 %d）%n",
                units, frames, guardedEvals, guardedReuse, unguardedEvals, unguardedReuse);
        check(guardedReuse > 0L, "开守卫时必须有「同帧重复访问被复用」");
        check(unguardedReuse == 0L, "关守卫时不该有复用（每条路径都自己算）");
        check(guardedEvals < unguardedEvals,
                "守卫必须真的减少求值次数（开 " + guardedEvals + " vs 关 " + unguardedEvals + "）");
        check(guardedEvals <= (long) units * frames, "开守卫时求值次数必须 ≤ 单位数 × 帧数");
    }

    private static long lastReuse;

    private static long runTwoVisitFrames(int units, int frames, boolean guard) {
        PoseScheduler s = new PoseScheduler(guard);
        long evals = 0L;
        for (int f = 0; f < frames; f++) {
            s.frameBegin();
            for (int phase = 0; phase < 2; phase++) {
                for (int id = 0; id < units; id++) {
                    float dist = 10.0F + ((id * 37 + f * 11 + phase) % 200);
                    PoseScheduler.Decision d = s.prepare(id, dist * dist, f);
                    if (d == PoseScheduler.Decision.EVALUATE) {
                        s.noteEvaluated(id, f);
                    }
                }
            }
            evals += s.evaluatedThisFrame();
            s.frameEnd();
        }
        lastReuse = s.totalReused();
        return evals;
    }

    // ------------------------------------------------------------------
    // 2. 客户端插桩计数（含 crowdMs/帧）
    // ------------------------------------------------------------------

    private static void clientProfileCounters() {
        HordeProfiler.resetAll();
        PoseScheduler s = new PoseScheduler(true);
        final int units = 20;
        final int frames = 20;
        for (int f = 0; f < frames; f++) {
            HordeProfiler.frameBegin();
            s.frameBegin();
            for (int id = 0; id < units; id++) {
                PoseScheduler.Decision d = s.prepare(id, 25.0F, f);   // 近处 => period 1
                if (d == PoseScheduler.Decision.EVALUATE) {
                    long st = s.evalBegin();
                    s.evalEnd(st);
                    s.noteEvaluated(id, f);
                }
            }
            s.frameEnd();
            // 实例缓冲：每单位 6 float；姿态 texel：每单位 (骨骼+位移+缩放) × 1 texel × 16 字节
            long bytes = units * 6L * 4L + units * 8L * 16L;
            HordeProfiler.noteCrowdFrameNanos(500_000L);   // 假装我们这一段花了 0.5ms
            HordeProfiler.frameEnd(s.visitedThisFrame(), s.evaluatedThisFrame(), s.evalNanosThisFrame(),
                    2, bytes, s.visitedThisFrame());
        }
        long windowEvals = HordeProfiler.poseEvals();
        long windowUpload = HordeProfiler.uploadBytes();
        int maxPerFrame = HordeProfiler.maxPoseEvalsPerFrame();
        int maxUnits = HordeProfiler.maxUnitsPerFrame();
        HordeProfiler.clientFlush(frames);

        String line = HordeProfiler.lastClientLine();
        System.out.println("[ ok ] 客户端插桩行：" + line);
        check(windowEvals == (long) units * frames,
                "近处（period=1）每帧每单位求值一次：期望 " + ((long) units * frames) + "，实际 " + windowEvals);
        check(maxPerFrame <= maxUnits, "poseEval/帧峰值不得超过单位数/帧峰值");
        check(maxPerFrame == units, "近处每帧都应求值 " + units + " 次，实际峰值 " + maxPerFrame);
        check(windowUpload > 0L, "上传字节必须被统计到");
        check(line.contains("poseEval=") && line.contains("上传="), "客户端行必须含 poseEval 与上传量");
        check(line.contains("crowdMs/帧="), "客户端行必须含 crowdMs/帧（回答「是不是我们拖慢的」）");
        check(line.contains("0.500"), "crowdMs/帧 必须等于实测值 0.500（我们注入了 0.5ms/帧）");
        check(line.contains("fps≈"), "客户端行必须含 fps≈（便于与 crowdMs 并列对比）");
        check(HordeProfiler.controlHintLogged(), "「对照方法」那行必须在第一次 flush 时打出来");
        // 再 flush 两次：client 行照常出，但「对照方法」那一行不该再打（原先是每秒都打，units=0 时刷屏）
        HordeProfiler.clientFlush(frames);
        String second = HordeProfiler.lastClientLine();
        HordeProfiler.clientFlush(frames);
        check(HordeProfiler.controlHintLogged(), "对照方法标志必须保持「已打」（每次会话只打一次）");
        check(!second.isEmpty() && second.contains("crowdMs/帧="),
                "后续 flush 照常产出 client 行（只抑制说明行）：" + second);
        check(HordeProfiler.poseEvals() == 0L, "flush 后窗口必须清零");
        HordeProfiler.resetAll();
        HordeProfiler.resetControlHint();
    }

    // ------------------------------------------------------------------
    // 3. 日志节流：每秒最多一行
    // ------------------------------------------------------------------

    private static void logThrottleOneLinePerSecond() {
        check(!HordeLog.Summary.detailEnabled(), "逐发投掷物日志必须默认关闭（debug.logLaunchDetail=false）");

        HordeLog.Summary sum = new HordeLog.Summary();
        final int ticks = 100;          // 5 秒
        int printedRows = 0;
        int rowsWithArrow = 0;
        String sample = "";
        for (int t = 0; t < ticks; t++) {
            // 一秒 20 tick、20 个骷髅各发一箭 = 每秒 20 次 note（旧行为就是 20 条 INFO）
            for (int k = 0; k < 20; k++) {
                sum.note("minecraft:arrow", 1, 3.0F, 8.4F);
            }
            if (t % HordeProfiler.FLUSH_TICKS == HordeProfiler.FLUSH_TICKS - 1) {
                String line = sum.line();
                if (!line.isEmpty()) {
                    printedRows++;
                    rowsWithArrow += line.contains("minecraft:arrow") ? 1 : 0;
                    sample = line;
                }
                sum.flush();
            }
        }
        System.out.println("[ ok ] 投掷物发射汇总（每秒一行）：" + sample);
        check(printedRows == ticks / HordeProfiler.FLUSH_TICKS,
                "5 秒应恰好刷出 " + (ticks / HordeProfiler.FLUSH_TICKS) + " 行，实际 " + printedRows);
        check(rowsWithArrow == printedRows, "每一行都要按投掷物 id 聚合");
        check(sample.contains("×400"), "汇总行必须把一整个窗口的次数累加起来（20 × 20 = 400）");
        check(sample.contains("共 400 枚"), "汇总行必须给出总数");
        check(sample.contains("3.00"), "汇总行必须带平均初速");
        check(sample.contains("8.40"), "汇总行必须带平均目标距离");

        sum.flush();
        check(sum.line().isEmpty(), "清空后的窗口不该再产出日志行");
        check(!sum.flush(), "空窗口 flush 不该返回「打了一行」");
    }

    // ------------------------------------------------------------------
    // 4. 索敌查询上界 + 行为一致性（同一份生产代码的对照实验）
    // ------------------------------------------------------------------

    /**
     * 索敌查询的上界，以及「节流不改变模拟结果」。
     *
     * <h2>为什么场景要这样摆</h2>
     * {@code aiTick} 与 {@code resolveCombat} 的兜底索敌**共用同一个半径**（followRange = 16 格），
     * 所以只有一种摆法能让「每 tick 白扫」这件事可测：**同一个阵营**的一队单位排成一条
     * 互不重叠的直线（间距 1 格 > 碰撞盒 0.6 格，分离不会推动它们）。
     * 它们扫描时**真的会把另外 23 个友军逐个比较**（candidates 非 0），结论却永远是「没找到敌人」——
     * 这正是真机上「附近没有敌人 / 敌人都在索敌半径之外」那一刻的结构，
     * 也是「每 tick 白扫」最纯粹的形态：位置与血量在两次对照里逐位相同，
     * 唯一的差别只有扫描频率。
     */
    private static void scanBoundedAndBehaviorIdentical() {
        final int units = 24;
        final int ticks = 200;
        final float spacing = 1.0F;

        Run ok = runCombat(units, ticks, 1, 0.0F, 0.0F, spacing, 0.6F, 3.0F, true, true);
        Run noAi = runCombat(units, ticks, 1, 0.0F, 0.0F, spacing, 0.6F, 3.0F, true, false);
        Run old = runCombat(units, ticks, 1, 0.0F, 0.0F, spacing, 0.6F, 3.0F, false, false);

        long unitTicks = (long) units * ticks;
        System.out.printf("[ ok ] 索敌扫描（%d 单位同阵营 / %d tick / 同队间距 %.0f 格 / 附近无敌人）：%n"
                        + "        线上（ai 节流 + 战斗节流）：扫描 %d 次（%.3f 次/单位/tick，ai %d + 战斗 %d）"
                        + "、候选比较 %d 次、扫描耗时 %.3f ms、整场 %.1f ms%n"
                        + "        只关 ai 节流              ：扫描 %d 次（ai %d + 战斗 %d）、候选比较 %d 次、"
                        + "扫描耗时 %.3f ms、整场 %.1f ms%n"
                        + "        两个都关（旧行为）        ：扫描 %d 次（ai %d + 战斗 %d）、候选比较 %d 次、"
                        + "扫描耗时 %.3f ms、整场 %.1f ms%n"
                        + "        总降幅：扫描 %.2f×、候选比较 %.2f×%n",
                units, ticks, spacing,
                ok.scans, (double) ok.scans / unitTicks, ok.aiScans, ok.combatScans,
                ok.candidates, ok.scanNanos / 1.0E6, ok.wallNanos / 1.0E6,
                noAi.scans, noAi.aiScans, noAi.combatScans,
                noAi.candidates, noAi.scanNanos / 1.0E6, noAi.wallNanos / 1.0E6,
                old.scans, old.aiScans, old.combatScans,
                old.candidates, old.scanNanos / 1.0E6, old.wallNanos / 1.0E6,
                ratio(old.scans, ok.scans), ratio(old.candidates, ok.candidates));

        check(ok.candidates > 0L, "这个用例必须真的比较过候选（否则「降 N 倍」没有意义）");
        // 每条扫描的候选数必须是「同队其余单位」量级（24 单位、间距 1 格、半径 16 格）
        check(ok.candidates >= ok.scans * 10L,
                "每次扫描都应比较十几个候选（扫描 " + ok.scans + " 次、候选 " + ok.candidates + " 次）");
        // 复杂度上界：线上每个单位最多每 10 tick 扫一次（aiTick 与战斗兜底共用 retargetCd，不叠加）
        check(ok.scans * 10L <= unitTicks * 2L,
                "线上扫描次数必须满足「每个单位每 10 tick 最多 2 次」的上界（实际 " + ok.scans
                        + "，上界 " + (unitTicks * 2L / 10L) + "）");
        check(ratio(old.scans, ok.scans) >= 3.0,
                "扫描总次数至少降 3 倍（实际 " + ratio(old.scans, ok.scans) + "×）");
        check(ratio(old.candidates, ok.candidates) >= 3.0,
                "候选比较次数至少降 3 倍（实际 " + ratio(old.candidates, ok.candidates) + "×）");
        check(noAi.scans > ok.scans, "关掉 aiTick 节流必须让扫描变多（" + noAi.scans + " vs " + ok.scans + "）");

        // ★ 不许降低功能正确性：两次对照的模拟结果必须逐字相同
        check(ok.damage.equals(old.damage),
                "节流不得改变任何伤害结算（线上：" + ok.damage + " / 旧行为：" + old.damage + "）");
        check(Math.abs(ok.totalHp - old.totalHp) < 1.0E-3F, "节流不得改变总血量");
        check(ok.alive == old.alive, "节流不得改变存活数");
        check(ok.events == 0 && old.events == 0, "只有一个阵营时不该有任何伤害结算");

        // ---- 场景 B：真的打起来的边界条件（两军贴脸）----
        // 只断言「同量级」：目标死亡后的重索敌延迟确实会让两次对照的结算序列不同，
        // 那是节流的**真实代价**，报告里给出确切数字，不在这里假装它不存在。
        final float gap = 1.2F;
        Run nearOk = runCombat(units, ticks, 2, 0.0F, gap, 1.0F, 0.6F, 3.0F, true, true);
        Run nearOld = runCombat(units, ticks, 2, 0.0F, gap, 1.0F, 0.6F, 3.0F, false, false);
        System.out.printf("[ ok ] 有伤害的两军贴脸对照（各 12 单位 / 间距 %.1f 格 / 攻击力 3）：%n"
                        + "        线上  ：扫描 %d、伤害事件 %d 条、存活 %d、总血量 %.1f%n"
                        + "        旧行为：扫描 %d、伤害事件 %d 条、存活 %d、总血量 %.1f%n",
                gap,
                nearOk.scans, nearOk.events, nearOk.alive, nearOk.totalHp,
                nearOld.scans, nearOld.events, nearOld.alive, nearOld.totalHp);
        check(nearOk.events > 0 && nearOld.events > 0, "贴脸场景必须真的产生伤害结算（否则等于没验）");
        check(nearOk.events * 4 >= nearOld.events && nearOk.events <= nearOld.events * 2,
                "贴脸时结算次数必须同量级（线上 " + nearOk.events + " vs 旧行为 " + nearOld.events + "）");

        // ---- 场景 C：扫描窗口的**边界**（回归用例）----
        boundaryScanFindsNeighbour();
    }

    /**
     * 两个单位贴在坐标原点（网格最低角）：它们必须**互相看得见**。
     *
     * <p>这条用例在修掉「{@code cellX(pos ± radius)} 越界夹取」之前是必然失败的
     * （扫描窗口落在 [0, c1x]，而这两个单位在更靠右的格子里）。
     */
    private static void boundaryScanFindsNeighbour() {
        CrowdManager cm = new CrowdManager(null, 8);
        cm.registerTypeStub(ResourceLocation.tryParse("minecraft:test_unit"), 0);
        cm.registerTypeStub(ResourceLocation.tryParse("minecraft:test_enemy"), 1);
        CrowdManager.TypeStats stats = CrowdManager.TypeStats.custom(
                0.6F, 1.95F, 1.62F, 100.0F, 3.0F, 0.0F, 16.0F, 20, 0, 120, -1.0F, false, false);
        cm.setStatsOverride(0, stats);
        cm.setStatsOverride(1, stats);
        cm.putUnitRaw(0, 0, 0.0F, 64.0F, 0.0F, 0, 100.0F);
        cm.putUnitRaw(1, 1, 3.0F, 64.0F, 0.0F, 1, 100.0F);

        HordeProfiler.resetAll();
        HordeProfiler.setEnabled(true);
        for (int t = 0; t < 4; t++) {
            HordeProfiler.beginTick();
            cm.tickWithLevel(null);
            HordeProfiler.tick(-1, -1);
        }
        int target = cm.data().targetId[0];
        long candidates = snapshotCandidates();
        System.out.println("[ ok ] 边界索敌回归（原点处的单位找 3 格外的敌人）：targetId[0]=" + target
                + "，候选比较 " + candidates + " 次");
        check(target == 1,
                "贴着网格最低角的单位必须能看见 3 格外的敌人（实际 targetId=" + target
                        + "）—— 这正是「扫描窗口用边界夹取」造成的漏索敌");
        check(candidates > 0L, "边界处的扫描必须真的比较过候选（实际 " + candidates + " 次）");
        HordeProfiler.resetAll();
    }

    /** 直接读整场的候选比较数（这里刚 resetAll 过，且窗口内不会清零，所以等于累计值）。 */
    private static long snapshotCandidates() {
        return HordeProfiler.enemyScanCandidates();
    }

    private static int countEvents(String s) {
        if (s.isEmpty()) {
            return 0;
        }
        int n = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '|') {
                n++;
            }
        }
        return n;
    }

    private static double ratio(long a, long b) {
        return b <= 0L ? 0.0D : (double) a / (double) b;
    }

    private static final class Run {
        long scans;
        long candidates;
        long scanNanos;
        long hits;
        long wallNanos;
        int aiScans;
        int combatScans;
        int aiMaxPerTick;
        int combatMaxPerTick;
        int maxPerTick;
        String damage = "";
        float totalHp;
        int alive;
        int events;
    }

    /**
     * 用**同一份**生产 {@link CrowdManager#tickWithLevel} 跑一遍，采集扫描/耗时/模拟结果。
     *
     * <p>每 tick 都走 {@code beginTick → tickWithLevel → tick}，与线上完全同一节奏；
     * 计数从每个窗口的快照增量里取（窗口级字段每 20 tick 会被清零，直接读会读到 0）。
     */
    private static Run runCombat(int units, int ticks, int teams, float speed, float gap, float spacing,
                                 float width, float damage,
                                 boolean combatThrottle, boolean aiThrottle) {
        CrowdManager cm = new CrowdManager(null, units + 4);
        cm.setLevel(null);
        cm.setCombatScanThrottled(combatThrottle);
        cm.setAiScanThrottled(aiThrottle);
        cm.registerTypeStub(ResourceLocation.tryParse("minecraft:test_unit"), 0);
        cm.registerTypeStub(ResourceLocation.tryParse("minecraft:test_enemy"), 1);
        CrowdManager.TypeStats stats = CrowdManager.TypeStats.custom(
                width, 1.95F, 1.62F, 100000.0F, damage, speed, 16.0F,
                20, 0, 120, -1.0F, false, false);
        for (int i = 0; i < units; i++) {
            cm.setStatsOverride(i, stats);
        }
        for (int i = 0; i < units; i++) {
            int team = teams <= 1 ? 0 : i % teams;
            int k = teams <= 1 ? i : i / teams;
            float x = team == 0 ? k * spacing : gap + k * spacing;
            cm.putUnitRaw(i, team, x, 64.0F, 0.0F, team, 100000.0F);
        }

        HordeProfiler.resetAll();
        HordeProfiler.setEnabled(true);
        Run r = new Run();
        long wallStart = System.nanoTime();
        for (int t = 0; t < ticks; t++) {
            HordeProfiler.beginTick();
            cm.tickWithLevel(null);
            HordeProfiler.tick(-1, -1);
            // 计数与候选数每 20 tick 会被窗口清零 —— 用**本 tick 的计数**累加，不受窗口边界影响
            r.scans += HordeProfiler.tickEnemyScans();
            r.aiScans += HordeProfiler.tickAiScans();
            r.combatScans += HordeProfiler.tickCombatScans();
            r.candidates += HordeProfiler.tickEnemyCandidates();
            r.hits += HordeProfiler.tickHitCalls();
            r.scanNanos += HordeProfiler.tickEnemyScanNanos();
        }
        r.wallNanos = System.nanoTime() - wallStart;
        r.damage = cm.damageEvents();
        r.alive = cm.aliveCount();
        float hp = 0.0F;
        for (int i = 0; i < cm.data().highWater; i++) {
            if (cm.data().aliveFlag[i]) {
                hp += cm.data().hp[i];
            }
        }
        r.totalHp = hp;
        r.events = countEvents(r.damage);
        return r;
    }

    // ------------------------------------------------------------------
    // 5. 分层耗时：**经 CrowdManager 真实路径读回必须非 0**
    // ------------------------------------------------------------------

    /**
     * 这条用例钉住的正是上一版的**阻塞性 bug**：{@code end(start, slot)} 把耗时写进调用方持有的
     * {@code long[]}，而汇总行读的是 {@code HordeProfiler} 自己的字段 —— 两套累加器，
     * 于是真机日志里服务端所有分段恒为 0.000ms，而它恰好是「41 ticks behind 卡在哪一段」
     * 唯一需要的证据。现在只有一条路径，所以「经 CrowdManager 跑一遍再读回来」必须看得见数字。
     */
    private static void profileStagesAreFilled() {
        CrowdManager cm = new CrowdManager(null, 8);
        cm.registerTypeStub(ResourceLocation.tryParse("minecraft:test_unit"), 0);
        cm.setStatsOverride(0, CrowdManager.TypeStats.custom(
                0.6F, 1.95F, 1.62F, 20.0F, 3.0F, 0.0F, 16.0F, 20, 0, 120, -1.0F, false, false));
        for (int i = 0; i < 6; i++) {
            cm.putUnitRaw(i, 0, i * 0.5F, 64.0F, 0.0F, 0, 20.0F);
        }
        HordeProfiler.resetAll();
        HordeProfiler.setEnabled(true);
        // ★ 走**真实调用路径**（beginTick → tickWithLevel → tick），跑够 20 tick 让汇总行刷出来。
        //   分段耗时每 20 tick 会被窗口清零，所以逐 tick 累加「本 tick 的增量」
        //   （windowStage 是单调累加、只在 flush 时清零 ⇒ 逐 tick 取快照相加即是整场总数）。
        int windows = 0;
        long[] total = new long[HordeProfiler.Stage.values().length];
        long[] last = new long[total.length];
        for (int t = 0; t < 60; t++) {
            HordeProfiler.beginTick();
            cm.tickWithLevel(null);
            if (HordeProfiler.tick(-1, -1)) {
                windows++;
            }
            for (HordeProfiler.Stage st : HordeProfiler.Stage.values()) {
                long cur = HordeProfiler.stageNanos(st);
                total[st.ordinal()] += Math.max(0L, cur - last[st.ordinal()]);
                last[st.ordinal()] = cur;
            }
        }
        // ① 经 CrowdManager 真实路径读回：**至少某些阶段必须 > 0**
        StringBuilder sb = new StringBuilder();
        int nonZero = 0;
        for (HordeProfiler.Stage st : HordeProfiler.Stage.values()) {
            long v = total[st.ordinal()];
            if (v > 0L) {
                nonZero++;
            }
            sb.append(st.label).append('=').append(v).append("ns ");
        }
        System.out.println("[ ok ] 经 CrowdManager 真实路径读回分段（60 tick 累计）：" + sb);
        check(nonZero > 0, "经 CrowdManager 真实调用路径读回的分段耗时必须至少有一段非 0（实际非零段数 "
                + nonZero + "）");
        check(total[HordeProfiler.Stage.GRID.ordinal()] > 0L, "grid.build 段必须被计时到");
        check(total[HordeProfiler.Stage.AI.ordinal()] > 0L, "aiTick 段必须被计时到");
        check(total[HordeProfiler.Stage.PHYSICS.ordinal()] > 0L, "physicsTick 段必须被计时到");
        check(total[HordeProfiler.Stage.PUSH.ordinal()] > 0L, "pushEntities 段必须被计时到");
        check(total[HordeProfiler.Stage.COMBAT.ordinal()] > 0L, "resolveCombat 段必须被计时到");
        check(total[HordeProfiler.Stage.SKILL.ordinal()] > 0L, "skillTick 段必须被计时到");

        // ② 汇总行必须与快照逐段一致（证明「累加 → 快照 → 打印」是同一批值）
        HordeProfiler.Snapshot snap = HordeProfiler.lastServerSnapshot();
        String line = HordeProfiler.lastServerLine();
        System.out.println("[ ok ] 服务端汇总行：" + line);
        check(windows == 3, "60 tick（3 秒）应恰好刷出 3 行，实际 " + windows);
        check(snap.totalNanos() > 0L, "快照的总耗时必须 > 0（实际 " + snap.totalNanos() + "ns）");
        check(snap.stage(HordeProfiler.Stage.AI) > 0L, "快照里 ai 段必须 > 0");
        check(snap.stage(HordeProfiler.Stage.GRID) > 0L, "快照里 grid 段必须 > 0");
        check(line.contains("ai=") && line.contains("grid=") && line.contains("stats="),
                "汇总行必须含全部分段（含 stats），实际：" + line);
        check(line.contains(fmt3(snap.stage(HordeProfiler.Stage.AI) / 1.0E6)),
                "汇总行里的 ai= 必须与快照一致（快照 " + fmt3(snap.stage(HordeProfiler.Stage.AI) / 1.0E6)
                        + "ms），实际行：" + line);
        HordeProfiler.resetAll();
    }

    private static String fmt3(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    // ------------------------------------------------------------------
    // 6. 卡顿归因探针
    // ------------------------------------------------------------------

    /**
     * 一个 tick ≥ 100ms 必须独立打一行 WARN，内容含 tick 号、耗时、units/proj、
     * **该 tick 自己的分段耗时**、以及当时发生了哪些外部事件（世界保存/类型解析/候选池扫描）。
     */
    private static void lagAttributionLine() {
        HordeProfiler.resetAll();
        HordeProfiler.setEnabled(true);
        CrowdManager cm = new CrowdManager(null, 8);
        cm.registerTypeStub(ResourceLocation.tryParse("minecraft:test_unit"), 0);
        cm.setStatsOverride(0, CrowdManager.TypeStats.custom(
                0.6F, 1.95F, 1.62F, 20.0F, 3.0F, 0.0F, 16.0F, 20, 0, 120, -1.0F, false, false));
        for (int i = 0; i < 4; i++) {
            cm.putUnitRaw(i, 0, i * 0.5F, 64.0F, 0.0F, 0, 20.0F);
        }
        for (int t = 0; t < 5; t++) {
            HordeProfiler.beginTick();
            cm.tickWithLevel(null);
            HordeProfiler.tick(-1, -1);
        }
        check(HordeProfiler.lastLagLine().isEmpty(),
                "正常 tick 不该产生卡顿行（实际：" + HordeProfiler.lastLagLine() + "）");

        // 人为制造一个「世界保存 + 118ms 类型解析」的卡顿 tick。
        // 离线环境里一个 tick 的墙钟耗时只有微秒级，所以用 injectTickIntervalNanos 把
        // 「这一 tick 的间隔」设成 130ms —— 它只改判据的输入，不改任何生产行为。
        HordeProfiler.beginTick();
        HordeProfiler.injectTickIntervalNanos(130_000_000L);
        HordeProfiler.noteWorldSave(0L);
        HordeProfiler.noteStatsBuild(118_000_000L);
        HordeProfiler.noteUnits(4);
        cm.tickWithLevel(null);
        HordeProfiler.tick(-1, -1);
        String lag = HordeProfiler.lastLagLine();
        System.out.println("[ ok ] 卡顿归因行：" + lag);
        check(!lag.isEmpty(), "≥100ms 的 tick 必须打出卡顿归因行");
        check(lag.contains("卡顿 tick"), "必须写明是卡顿 tick");
        check(lag.contains("units=") && lag.contains("proj="), "必须带当时的 units/proj");
        check(lag.contains("stats="), "必须带**该 tick 自己的**分段耗时（这里应有 stats=…ms）");
        check(lag.contains("世界保存"), "必须标出这一 tick 发生了世界保存");
        check(lag.contains("新类型属性解析"), "必须标出这一 tick 发生了类型属性解析");

        // 卡顿行必须与 20 tick 窗口无关：窗口刚开始的第 1 tick 卡了也要立刻打出来
        HordeProfiler.resetAll();
        HordeProfiler.setEnabled(true);
        HordeProfiler.beginTick();
        HordeProfiler.injectTickIntervalNanos(150_000_000L);
        HordeProfiler.noteWorldSave(0L);
        HordeProfiler.noteStatsBuild(150_000_000L);
        HordeProfiler.tick(-1, -1);
        check(!HordeProfiler.lastLagLine().isEmpty(), "窗口刚开始的第 1 tick 卡了也必须立刻打出来");
        check(HordeProfiler.lastServerLine().isEmpty(), "但汇总行不该在窗口没满时打出来");
        HordeProfiler.resetAll();
    }

    // ------------------------------------------------------------------

    private static void check(boolean ok, String what) {
        if (ok) {
            return;
        }
        failures++;
        System.out.println("[FAIL] " + what);
    }
}
