package dev.swordkingdom.horde.crowd;

import dev.swordkingdom.horde.HordeConfig;
import dev.swordkingdom.horde.HordeProfiler;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;

/** 维度 -> 虚拟投掷物管理器 的注册表（与 {@link CrowdManagers} 同样的组织方式）。 */
public final class ProjectileManagers {

    private static final Map<ResourceKey<Level>, ProjectileManager> MANAGERS = new HashMap<>();
    /** 每维度一份共享的方块碰撞世界（投掷物与群集单位共用，缓存命中率更高） */
    private static final Map<ResourceKey<Level>, BlockWorld> BLOCKS = new HashMap<>();

    private ProjectileManagers() {}

    public static ProjectileManager get(ServerLevel level) {
        ResourceKey<Level> key = level.dimension();
        ProjectileManager m = MANAGERS.get(key);
        if (m == null) {
            m = new ProjectileManager(key.location());
            MANAGERS.put(key, m);
        }
        if (m.level() == null) {
            m.setLevel(level);
        }
        return m;
    }

    /** 该维度共享的方块碰撞世界（优先复用群集管理器已有的那份，避免建两张表） */
    public static BlockWorld blocks(ServerLevel level) {
        BlockWorld shared = BLOCKS.get(level.dimension());
        if (shared != null) {
            return shared;
        }
        CrowdManager cm = CrowdManagers.getIfPresent(level.dimension());
        if (cm != null && cm.blockWorld() != null) {
            BLOCKS.put(level.dimension(), cm.blockWorld());
            return cm.blockWorld();
        }
        return BLOCKS.computeIfAbsent(level.dimension(), k -> new BlockWorld(level, 1 << 17));
    }

    public static ProjectileManager getIfPresent(ResourceKey<Level> key) {
        return MANAGERS.get(key);
    }

    /**
     * 每维度 tick 一次投掷物。
     *
     * 接线（方块碰撞 + 群集命中判定）放在这里而不是 {@link CrowdManager#tick}：
     * 投掷物必须在群集单位**全死光之后**还能继续飞完（否则最后那波箭会凭空消失），
     * 而 {@code CrowdManager.tick} 在没有活单位时会提前返回。
     *
     * 传 {@link ProjectileManager.Sink#NONE} 表示「真实实体伤害由投掷物管理器按配置自己接」，
     * 群集命中走 {@link CrowdManager#crowdHitResolver()} 注入的空间哈希路径。
     */
    public static void tickAll(MinecraftServer server) {
        int alive = 0;
        for (ServerLevel level : server.getAllLevels()) {
            ProjectileManager m = MANAGERS.get(level.dimension());
            if (m == null || m.aliveCount() == 0) {
                continue;
            }
            // 方块缓存每 tick 清一次（与 CrowdManager 同一套「按 tick 复用」策略）。
            // 注意：这里**不能**用「先 get 再判空」—— 群集单位一个都没有时这个缓存还没被创建，
            // 而那时投掷物照样在飞（最后那一波箭），必须给它一个可用的碰撞世界。
            BlockWorld world = blocks(level);
            world.beginTick();
            m.setAdapter(ProjectileManager.blockAdapter(world, 0.09F));
            CrowdManager cm = CrowdManagers.getIfPresent(level.dimension());
            if (cm != null) {
                m.setHits(cm.crowdHitResolver());
            }
            alive += m.aliveCount();
            // 分层计时：投掷物这一段（积分 + 方块碰撞 + 命中判定）单独量。
            // 走 HordeProfiler 自己的累加器（见 HordeProfiler 类注释：不许再有第二个累加器）。
            long t = HordeProfiler.begin();
            m.tick(level, ProjectileManager.Sink.NONE);
            HordeProfiler.end(t, HordeProfiler.Stage.PROJECTILE);
        }
        if (alive > 0) {
            HordeProfiler.noteProjectiles(alive);
        }
    }

    /** 玩家配的「同时存在的投掷物上限」改了以后重建容量 */
    public static void reconfigure(ServerLevel level) {
        ResourceKey<Level> key = level.dimension();
        ProjectileManager old = MANAGERS.get(key);
        if (old == null) {
            return;
        }
        int want = Math.min(ProjectileManager.HARD_CAP,
                Math.max(256, HordeConfig.safeGet(HordeConfig.COMMON.maxProjectilesPerLevel, 2048)));
        if (want > old.data().capacity) {
            ProjectileManager fresh = new ProjectileManager(key.location());
            MANAGERS.put(key, fresh);
        }
    }

    public static void remove(ServerLevel level) {
        MANAGERS.remove(level.dimension());
        BLOCKS.remove(level.dimension());
    }
}
