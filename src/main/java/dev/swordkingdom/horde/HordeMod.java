package dev.swordkingdom.horde;

import com.mojang.logging.LogUtils;
import dev.swordkingdom.horde.edit.TypeOverride;
import dev.swordkingdom.horde.network.HordeNetwork;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;

/**
 * Horde —— 一套全新的 Minecraft 实体运行逻辑（群集/大规模单位引擎）。
 *
 * 设计目标：在上万单位规模下保持服务端稳定 TPS。
 * 核心手段（对标 3D 引擎的制作逻辑）：
 *   1. 逻辑与表现分离 —— 服务端只跑纯数据模拟，客户端只负责渲染。
 *   2. 数据导向（SoA）—— 所有单位字段存在连续基本类型数组里，缓存友好、零对象分配。
 *   3. 空间哈希         —— 邻居查询 O(n) 而非 O(n^2)。
 *   4. 流场寻路         —— 同阵营共享一张方向场，而非每个单位各跑一次 A*。
 *   5. LOD 降频 tick    —— 远处单位按更低频率结算移动/战斗。
 *   6. 快照同步 + GPU 实例化 —— 服务端量化快照，客户端一次 draw call 画上万僵尸。
 *   7. 通用模型/动作适配 —— 不针对模组：结构化发现部件、按形状发现动作 API。
 *   8. 游戏内实时配置   —— GUI 里改模型/动作/攻击/属性，全局共享、立刻生效。
 */
@Mod(HordeMod.MODID)
public final class HordeMod {

    public static final String MODID = "horde";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** 当前服务端实例（覆盖表要在所有维度上重算时用） */
    public static MinecraftServer server;

    public HordeMod() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, HordeConfig.SPEC);
        HordeNetwork.register();
        TypeOverride.load();
        MinecraftForge.EVENT_BUS.addListener(HordeMod::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(HordeMod::onServerStopping);
        LOGGER.info("[horde] 群集引擎已装载");
    }

    private static void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
        TypeOverride.load();
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        TypeOverride.save();
        server = null;
    }
}
