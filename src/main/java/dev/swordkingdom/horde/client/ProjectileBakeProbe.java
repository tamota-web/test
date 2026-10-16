package dev.swordkingdom.horde.client;

import dev.swordkingdom.horde.HordeMod;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * 投掷物**捕获式烘焙**的在线自检。
 *
 * <h2>为什么需要它</h2>
 * {@link ProjectileBaker} 靠「让投掷物通过它自己的渲染器渲染一次，把顶点截下来」拿网格。
 * 这条路依赖真实渲染器在当前线程/世界状态下的行为，**离线验证不了**
 * （离线 harness 里没有 ClientLevel、没有 EntityRenderDispatcher、没有贴图管理器）。
 * 所以这里提供一个「跑进真客户端、真渲染器里做一次」的自检入口：
 *
 * <pre>
 *   gradlew runClient   （环境变量 HORDE_PROBE=arrow,spectral_arrow,snowball,fireball,small_fireball）
 * </pre>
 *
 * 用**环境变量**而不是 -D 系统属性：ForgeGradle 的 run 配置不会把宿主 JVM 的 -D 透传给
 * 子进程，而环境变量一定会被子进程继承。
 *
 * 它只做三件事：逐个把投掷物类型烘焙一遍、把结果（顶点数/三角数/贴图来源/主轴）打进日志，
 * 然后（带 {@code HORDE_PROBE_EXIT_MS} 时）自动退出，方便脚本化。
 *
 * 不做任何游戏内改动：不生成实体、不改世界、不注册任何东西，
 * 只读一遍渲染器。生产环境里这个类永远不会跑（没有该环境变量就立刻返回）。
 */
@Mod.EventBusSubscriber(modid = HordeMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ProjectileBakeProbe {

    /** 只跑一次 */
    private static boolean done;
    private static long startedAt;

    private ProjectileBakeProbe() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        String probe = System.getenv("HORDE_PROBE");
        if (probe == null || probe.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || done) {
            return;
        }
        done = true;
        startedAt = System.currentTimeMillis();
        run(probe.split(","));
    }

    private static void run(String[] ids) {
        HordeMod.LOGGER.info("=========== [horde] 投掷物捕获式烘焙自检开始（{} 个类型）===========", ids.length);
        List<String> lines = new ArrayList<>();
        int captured = 0;
        int fallback = 0;
        int atlas = 0;
        for (String raw : ids) {
            String id = raw.trim();
            if (id.isEmpty()) {
                continue;
            }
            if (!id.contains(":")) {
                id = "minecraft:" + id;
            }
            ResourceLocation rl = ResourceLocation.tryParse(id);
            EntityType<?> type = rl == null ? null : ForgeRegistries.ENTITY_TYPES.getValue(rl);
            if (type == null) {
                lines.add("  " + id + " -> 未注册，跳过");
                continue;
            }
            long t0 = System.nanoTime();
            ProjectileBaker.Mesh mesh;
            try {
                mesh = ProjectileBaker.bake(type, "");
            } catch (Throwable t) {
                lines.add("  " + id + " -> 异常 " + t);
                continue;
            }
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            if (mesh == null || !mesh.supported) {
                lines.add("  " + id + " -> 不支持（" + ms + " ms）");
                continue;
            }
            if (mesh.captured) {
                captured++;
            } else {
                fallback++;
            }
            if (mesh.atlasTexture) {
                atlas++;
            }
            lines.add("  " + id + " -> " + (mesh.captured ? "捕获成功" : "退化成方块")
                    + "：顶点 " + mesh.vertexCount + "，三角 " + mesh.triangleCount
                    + "，贴图 " + mesh.texture + (mesh.atlasTexture ? "（方块图集）" : "")
                    + "，主轴 " + "XYZ".charAt(mesh.principalAxis)
                    + (mesh.principalPositive ? "+" : "-")
                    + "，尺寸 " + fmt(mesh.sizeX) + "×" + fmt(mesh.sizeY) + "×" + fmt(mesh.sizeZ)
                    + "（" + ms + " ms）");
        }
        for (String l : lines) {
            HordeMod.LOGGER.info(l);
        }
        HordeMod.LOGGER.info("=========== [horde] 自检汇总：捕获 {} / 退化 {} / 图集贴图 {} ===========",
                captured, fallback, atlas);

        // 需要脚本化时自动退出（默认不退出，方便手动看画面）
        String exitMs = System.getenv("HORDE_PROBE_EXIT_MS");
        if (exitMs != null) {
            try {
                long ms = Long.parseLong(exitMs);
                HordeMod.LOGGER.info("[horde] 自检完成（{} ms），{} ms 后自动退出",
                        System.currentTimeMillis() - startedAt, ms);
                Thread t = new Thread(() -> {
                    try {
                        Thread.sleep(ms);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    Minecraft.getInstance().stop();
                }, "horde-probe-exit");
                t.setDaemon(true);
                t.start();
            } catch (Throwable ignored) {
                // 参数写错就不退出
            }
        }
    }

    private static String fmt(float v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
