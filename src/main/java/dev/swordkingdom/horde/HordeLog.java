package dev.swordkingdom.horde;

import java.util.ArrayList;
import java.util.List;

/**
 * 投掷物发射日志的**每秒汇总**（替代「每发一支箭一条 INFO」）。
 *
 * <h2>为什么要改</h2>
 * 真机日志里 20 个骷髅每秒刷约 20 条：
 * <pre>
 *   [horde] minecraft:skeleton 的单位 #52，投掷物 minecraft:arrow：发射 ×1（远程攻击模式…）
 * </pre>
 * 这些 INFO 走的是同步 appender（写盘 + 控制台），20 条/秒本身不致命，
 * 但它是「一万单位」规模下唯一会**线性放大**的外部 IO —— 而且同样的信息
 * 一行汇总就能说清。逐发日志保留为调试开关（默认关）。
 *
 * <h2>为什么做成可 tick 的实例</h2>
 * 「每秒最多一行」这条性质必须能在离线 harness 里断言（见 ProfilerHarness）。
 * 把累计与刷出放进一个普通对象、由调用方显式 {@link Summary#flush()}，
 * 时间就不再依赖墙钟 —— 测试里 tick 20 次就等于 1 秒，断言精确可复现。
 */
public final class HordeLog {

    private HordeLog() {}

    /**
     * 一个窗口（默认 1 秒 = 20 tick）内的发射累计。
     *
     * <p>同一枚投掷物按 id 聚合成一行：{@code ×N（平均初速 x.xx，平均目标距离 y.yy）}。
     * 「平均目标距离」只在发射时确实有目标（远程模式）时统计；动作命中帧那条路没有目标，
     * 就不参与这个平均，但参与总数 —— 这是刻意的，避免把 0 混进平均里把数字拉低。
     */
    public static final class Summary {

        private final List<ResourceLocationKey> order = new ArrayList<>();
        private final java.util.Map<String, Bucket> buckets = new java.util.HashMap<>();
        private int totalShots;
        private String lastLine = "";

        private static final class Bucket {
            int shots;
            double speedSum;
            int speedShots;
            double distSum;
            int distShots;
        }

        /** 投掷物 id 用 String 做键（避免依赖 Minecraft 的 ResourceLocation 才能离线跑） */
        private static final class ResourceLocationKey {
            final String id;

            ResourceLocationKey(String id) {
                this.id = id;
            }
        }

        /**
         * 记一次发射。
         *
         * @param projectileId 投掷物 id（{@code minecraft:arrow}）
         * @param shots        本次发射了几枚
         * @param speed        初速（格/tick）；&lt; 0 = 未知，不参与平均
         * @param targetDist   到目标的距离（格）；&lt; 0 = 无目标，不参与平均
         */
        public void note(String projectileId, int shots, float speed, float targetDist) {
            if (shots <= 0) {
                return;
            }
            String key = projectileId == null ? "（未知）" : projectileId;
            Bucket b = buckets.get(key);
            if (b == null) {
                b = new Bucket();
                buckets.put(key, b);
                order.add(new ResourceLocationKey(key));
            }
            b.shots += shots;
            if (speed >= 0.0F) {
                b.speedSum += speed;
                b.speedShots++;
            }
            if (targetDist >= 0.0F) {
                b.distSum += targetDist;
                b.distShots++;
            }
            totalShots += shots;
        }

        /** 逐发调试日志是不是该打（配置默认关）。 */
        public static boolean detailEnabled() {
            return HordeConfig.safeGet(HordeConfig.COMMON.logLaunchDetail, false);
        }

        /**
         * 生成汇总行（不打日志）。
         *
         * 格式：{@code [horde] 投掷物发射汇总：minecraft:arrow ×N（平均初速 3.00，平均目标距离 8.40）}
         * 多种投掷物时用 {@code ｜} 分隔。这一行就是「每秒最多一行」的全部内容。
         */
        public String line() {
            if (totalShots <= 0) {
                return "";
            }
            StringBuilder sb = new StringBuilder(96);
            sb.append("[horde] 投掷物发射汇总：共 ").append(totalShots).append(" 枚");
            for (int i = 0; i < order.size(); i++) {
                String id = order.get(i).id;
                Bucket b = buckets.get(id);
                sb.append(i == 0 ? " —— " : " ｜ ").append(id).append(" ×").append(b.shots);
                if (b.speedShots > 0) {
                    sb.append("（平均初速 ")
                            .append(String.format(java.util.Locale.ROOT, "%.2f", b.speedSum / b.speedShots));
                    if (b.distShots > 0) {
                        sb.append("，平均目标距离 ")
                                .append(String.format(java.util.Locale.ROOT, "%.2f", b.distSum / b.distShots));
                    }
                    sb.append('）');
                } else if (b.distShots > 0) {
                    sb.append("（平均目标距离 ")
                            .append(String.format(java.util.Locale.ROOT, "%.2f", b.distSum / b.distShots))
                            .append('）');
                }
            }
            return sb.toString();
        }

        /**
         * 打一行汇总（若有内容）并清空窗口。
         *
         * @return 是否真的打了一行 —— harness 用它断言「每秒最多一行」
         */
        public boolean flush() {
            String line = line();
            boolean printed = !line.isEmpty();
            if (printed) {
                lastLine = line;
                HordeProfiler.logInfo(line);
            }
            clear();
            return printed;
        }

        /** 丢弃当前窗口（不打印）。 */
        public void clear() {
            order.clear();
            buckets.clear();
            totalShots = 0;
        }

        public int totalShots() {
            return totalShots;
        }

        /** 上一次刷出来的行（空 = 还没刷过 / 当前窗口没内容）。 */
        public String lastLine() {
            return lastLine;
        }
    }

    /** 生产环境用的全局汇总（服务端 tick 末刷出）。 */
    public static final Summary INSTANCE = new Summary();

    /**
     * 服务端 tick 末调用：刷出投掷物发射汇总。
     *
     * @return 是否打了一行
     */
    public static boolean tick() {
        return INSTANCE.flush();
    }

    /**
     * 逐发调试日志（默认关）。
     *
     * 打开方式：配置 {@code debug.logLaunchDetail = true}
     * （对应 {@link HordeConfig.Common#logLaunchDetail}）。
     */
    public static void launchDetail(String format, Object... args) {
        if (!Summary.detailEnabled()) {
            return;
        }
        try {
            HordeMod.LOGGER.info(format, args);
        } catch (Throwable ignored) {
            // 没有日志后端：忽略
        }
    }
}
