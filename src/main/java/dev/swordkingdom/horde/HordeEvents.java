package dev.swordkingdom.horde;

import dev.swordkingdom.horde.command.HordeCommand;
import dev.swordkingdom.horde.crowd.CrowdManagers;
import dev.swordkingdom.horde.network.CrowdSync;import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 服务端事件挂载点。 */
@Mod.EventBusSubscriber(modid = HordeMod.MODID)
public final class HordeEvents {

    private HordeEvents() {}

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            // tick 开始时清空本 tick 的插桩累加器（与 tick 末的 tick() 成对，见 HordeProfiler.beginTick）
            HordeProfiler.beginTick();
            return;
        }
        MinecraftServer server = event.getServer();
        CrowdManagers.tickAll(server);
        // 投掷物在群集之后 tick：发射发生在群集的动作命中帧，同 tick 立即开始飞
        dev.swordkingdom.horde.crowd.ProjectileManagers.tickAll(server);
        CrowdSync.tick(server);
        dev.swordkingdom.horde.network.ProjectileSync.tick(server);
        // ---- 每秒最多一行的插桩汇总（分层耗时 + 计数）与投掷物发射汇总 ----
        // 放在所有阶段之后：这样这一段里量到的是「本 tick 完整的账」。
        // 同时负责「卡顿归因」：本 tick ≥ 100ms 就单独打一条 WARN（含该 tick 自己的分段与事件）。
        HordeLog.tick();
        HordeProfiler.tick(-1, -1);
    }

    /**
     * 世界保存（自动存档 / {@code /save-all}）—— 「不同会话、不同单位数，却每次都恰好
     * Running 2067ms or 41 ticks behind」的头号嫌疑：它是**周期性**事件，与群集规模无关。
     *
     * <p>这里不干预保存，只给**当前 tick** 打一个事件标记并记下耗时，
     * 于是卡顿归因行里会写 {@code 事件：**世界保存** …}，一眼就能把它和逐 tick 开销分开。
     */
    @SubscribeEvent
    public static void onLevelSave(LevelEvent.Save event) {
        long start = HordeProfiler.begin();
        HordeProfiler.noteWorldSave(start == 0L ? 0L : System.nanoTime() - start);
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            CrowdManagers.remove(serverLevel);
            dev.swordkingdom.horde.crowd.ProjectileManagers.remove(serverLevel);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        CrowdSync.forget(event.getEntity().getUUID());
        dev.swordkingdom.horde.network.ProjectileSync.forget(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        HordeCommand.register(event.getDispatcher());
    }
}
