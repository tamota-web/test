package dev.swordkingdom.horde.crowd;

import dev.swordkingdom.horde.HordeConfig;
import dev.swordkingdom.horde.HordeProfiler;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;

/** 维度 -> 群集管理器 的注册表。 */
public final class CrowdManagers {

    private static final Map<ResourceKey<Level>, CrowdManager> MANAGERS = new HashMap<>();

    private CrowdManagers() {}

    public static CrowdManager get(ServerLevel level) {
        ResourceKey<Level> key = level.dimension();
        CrowdManager m = MANAGERS.get(key);
        if (m == null) {
            m = new CrowdManager(key, HordeConfig.COMMON.maxUnitsPerLevel.get());
            MANAGERS.put(key, m);
        }
        return m;
    }

    public static CrowdManager getIfPresent(ResourceKey<Level> key) {
        return MANAGERS.get(key);
    }

    public static void tickAll(MinecraftServer server) {
        int units = 0;
        boolean any = false;
        for (ServerLevel level : server.getAllLevels()) {
            CrowdManager m = MANAGERS.get(level.dimension());
            if (m == null || m.aliveCount() == 0) {
                continue;
            }
            any = true;
            units += m.aliveCount();
            m.tick(level);
        }
        if (any) {
            HordeProfiler.noteUnits(units);
        }
    }

    public static void remove(ServerLevel level) {
        MANAGERS.remove(level.dimension());
    }
}
