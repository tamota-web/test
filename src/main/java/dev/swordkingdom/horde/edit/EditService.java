package dev.swordkingdom.horde.edit;

import dev.swordkingdom.horde.HordeMod;
import dev.swordkingdom.horde.crowd.CrowdManager;
import dev.swordkingdom.horde.crowd.CrowdManagers;
import dev.swordkingdom.horde.network.EditPackets;
import dev.swordkingdom.horde.network.HordeNetwork;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 覆盖表的服务端应用：收到 GUI/命令提交的改动后
 *
 *  1. 写入 {@link TypeOverride} 并落盘；
 *  2. **立刻**让该类型的群集单位用上新属性（{@link CrowdManager#reapply}）；
 *  3. 把新整表回给所有管理员（客户端据此换模型/重烘焙网格）。
 */
public final class EditService {

    private EditService() {}

    public static void apply(EditPackets.Set msg, ServerPlayer player) {
        if (msg.type == null || msg.type.isEmpty()) {
            // 还原全部
            for (ResourceLocation id : new java.util.ArrayList<>(TypeOverride.all().keySet())) {
                TypeOverride.reset(id);
                reapplyAll(id);
            }
            broadcast(player);
            return;
        }
        ResourceLocation id = ResourceLocation.tryParse(msg.type);
        if (id == null) {
            return;
        }
        if (msg.reset) {
            TypeOverride.reset(id);
        } else {
            TypeOverride o = EditPackets.readOne(msg.json);
            if (o == null) {
                return;
            }
            TypeOverride.put(id, o);
        }
        reapplyAll(id);
        broadcast(player);
    }

    /**
     * 该类型可能在多个维度都有群集，全部重算。
     *
     * 除群集属性外，同时把「虚拟投掷物」那边的两项也刷新：
     *   - 施法者注册的投掷物类型参数（初速/重力/寿命……改了立刻对之后发射的生效）；
     *   - 「是否伤害真实实体」开关。
     * 已经在飞的投掷物不受影响（它们的参数在生成时已经快照进数组，这是有意的：
     * 「改配置不该把天上的箭变成另一种东西」）。
     */
    public static void reapplyAll(ResourceLocation id) {
        for (ServerLevel level : dev.swordkingdom.horde.HordeMod.server.getAllLevels()) {
            CrowdManager m = CrowdManagers.getIfPresent(level.dimension());
            if (m != null) {
                m.reapply(id);
            }
            // 「同时在场上限」改了可能要扩容投掷物管理器
            dev.swordkingdom.horde.crowd.ProjectileManagers.reconfigure(level);
            dev.swordkingdom.horde.crowd.ProjectileManager pm =
                    dev.swordkingdom.horde.crowd.ProjectileManagers.getIfPresent(level.dimension());
            if (pm != null) {
                pm.reapply(id);
            }
        }
    }

    /** 把整表下发给所有有权限的玩家（含改动发起者） */
    public static void broadcast(ServerPlayer initiator) {
        String json = EditPackets.writeTable();
        HordeMod.LOGGER.info("[horde] 覆盖表已更新（{} 条），已同步给管理员", TypeOverride.all().size());
        for (ServerPlayer p : HordeMod.server.getPlayerList().getPlayers()) {
            if (p.hasPermissions(2)) {
                HordeNetwork.sendToPlayer(new EditPackets.Sync(json), p);
            }
        }
    }

    public static void requestFrom(ServerPlayer player) {
        HordeNetwork.sendToPlayer(new EditPackets.Sync(EditPackets.writeTable()), player);
    }
}
