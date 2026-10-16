package dev.swordkingdom.horde.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.swordkingdom.horde.crowd.CrowdManager;
import dev.swordkingdom.horde.crowd.CrowdManagers;
import dev.swordkingdom.horde.crowd.RealEntities;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.stream.StreamSupport;

/**
 * /horde spawn &lt;实体&gt; &lt;数量&gt; [阵营] [半径]
 * /horde real  &lt;实体&gt; &lt;数量&gt; [半径]
 * /horde clear | clearreal | status
 */
public final class HordeCommand {

    private static final SuggestionProvider<CommandSourceStack> ENTITY_TYPES =
            (ctx, builder) -> SharedSuggestionProvider.suggest(
                    StreamSupport.stream(ForgeRegistries.ENTITY_TYPES.getKeys().spliterator(), false)
                            .map(ResourceLocation::toString),
                    builder);

    private static final SuggestionProvider<CommandSourceStack> TEAMS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(new String[]{"0", "1", "2", "3"}, builder);

    private HordeCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("horde")
                .requires(source -> source.hasPermission(2))
                // ---- 群集模式：显式指定实体类型与阵营 ----
                .then(Commands.literal("spawn")
                        .then(Commands.argument("type", ResourceLocationArgument.id())
                                .suggests(ENTITY_TYPES)
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 200000))
                                        .executes(ctx -> spawnCrowd(ctx.getSource(),
                                                ResourceLocationArgument.getId(ctx, "type"),
                                                ctx.getArgument("count", Integer.class), 0, 12.0D))
                                        .then(Commands.argument("team", IntegerArgumentType.integer(0, 3))
                                                .suggests(TEAMS)
                                                .executes(ctx -> spawnCrowd(ctx.getSource(),
                                                        ResourceLocationArgument.getId(ctx, "type"),
                                                        ctx.getArgument("count", Integer.class),
                                                        ctx.getArgument("team", Integer.class), 12.0D))
                                                .then(Commands.argument("spread", IntegerArgumentType.integer(1, 512))
                                                        .executes(ctx -> spawnCrowd(ctx.getSource(),
                                                                ResourceLocationArgument.getId(ctx, "type"),
                                                                ctx.getArgument("count", Integer.class),
                                                                ctx.getArgument("team", Integer.class),
                                                                ctx.getArgument("spread", Integer.class).doubleValue())))))))
                .then(Commands.literal("clear")
                        .executes(ctx -> clearCrowd(ctx.getSource())))
                // ---- 真实体模式：任意已注册实体，原版行为 ----
                .then(Commands.literal("real")
                        .then(Commands.argument("type", ResourceLocationArgument.id())
                                .suggests(ENTITY_TYPES)
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 20000))
                                        .executes(ctx -> spawnReal(ctx.getSource(),
                                                ResourceLocationArgument.getId(ctx, "type"),
                                                ctx.getArgument("count", Integer.class), 10.0D))
                                        .then(Commands.argument("spread", IntegerArgumentType.integer(1, 512))
                                                .executes(ctx -> spawnReal(ctx.getSource(),
                                                        ResourceLocationArgument.getId(ctx, "type"),
                                                        ctx.getArgument("count", Integer.class),
                                                        ctx.getArgument("spread", Integer.class).doubleValue()))))))
                .then(Commands.literal("clearreal")
                        .executes(ctx -> clearReal(ctx.getSource())))
                .then(Commands.literal("status")
                        .executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("edit")
                        .executes(ctx -> openEdit(ctx.getSource()))
                        .then(Commands.literal("list")
                                .executes(ctx -> listOverrides(ctx.getSource())))
                        .then(Commands.literal("reset")
                                .then(Commands.argument("type", ResourceLocationArgument.id())
                                        .executes(ctx -> resetOverride(ctx.getSource(),
                                                ResourceLocationArgument.getId(ctx, "type")))))));
    }

    // ------------------------------------------------------------------
    // 游戏内实时配置
    // ------------------------------------------------------------------

    /** `/horde edit`：让该玩家打开配置界面（模型/动作/攻击/属性都能改，全局共享、立刻生效） */
    private static int openEdit(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        dev.swordkingdom.horde.edit.EditService.requestFrom(player);
        dev.swordkingdom.horde.network.HordeNetwork.sendToPlayer(
                new dev.swordkingdom.horde.network.EditPackets.OpenGui(), player);
        source.sendSuccess(() -> Component.literal("§a[horde] §f已打开配置界面（也可以直接按 §eH§f 键）"), false);
        return 1;
    }

    private static int listOverrides(CommandSourceStack source) {
        var all = dev.swordkingdom.horde.edit.TypeOverride.all();
        if (all.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§7[horde] 目前没有任何覆盖（全部用实体自身属性）"), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal("§a[horde] §f共 §e" + all.size() + " §f条覆盖："), false);
        for (var e : all.entrySet()) {
            var o = e.getValue();
            source.sendSuccess(() -> Component.literal("  §7" + e.getKey() + " §f"
                    + (o.model.isEmpty() ? "" : "模型=" + o.model + " ")
                    + (o.action >= 0 ? "动作=" + o.action + " " : "")
                    + (o.damageScale >= 0 ? "伤害×" + o.damageScale + " " : "")
                    + (o.maxHp >= 0 ? "生命=" + o.maxHp + " " : "")
                    + (o.damage >= 0 ? "攻击=" + o.damage + " " : "")
                    + (o.moveSpeed >= 0 ? "速度=" + o.moveSpeed : "")), false);
        }
        return 1;
    }

    private static int resetOverride(CommandSourceStack source, ResourceLocation typeId) {
        dev.swordkingdom.horde.edit.TypeOverride.reset(typeId);
        dev.swordkingdom.horde.edit.EditService.reapplyAll(typeId);
        if (source.getServer() != null) {
            dev.swordkingdom.horde.edit.EditService.broadcast(null);
        }
        source.sendSuccess(() -> Component.literal("§a[horde] §f已还原 §e" + typeId + " §f的全部覆盖"), true);
        return 1;
    }

    // ------------------------------------------------------------------
    // 群集模式
    // ------------------------------------------------------------------

    private static int spawnCrowd(CommandSourceStack source, ResourceLocation typeId, int count, int team, double spread)
            throws CommandSyntaxException {
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(typeId);
        if (type == null) {
            source.sendFailure(Component.literal("§c[horde] §f未注册的实体类型: " + typeId));
            return 0;
        }

        ServerLevel level = source.getLevel();
        // 注意：EntityType#getBaseClass() 运行时并不返回实体类，用「创建一次」来判断
        Entity probe;
        try {
            probe = type.create(level);
        } catch (Throwable t) {
            probe = null;
        }
        if (!(probe instanceof LivingEntity)) {
            source.sendFailure(Component.literal("§c[horde] §f群集模式只支持生物实体: " + typeId
                    + " §7（可用 /horde real 生成原版实体）"));
            return 0;
        }

        ServerPlayer player = source.getPlayerOrException();
        CrowdManager manager = CrowdManagers.get(level);
        int added = manager.spawn(level, typeId, count, team, player.getX(), player.getY(), player.getZ(), spread);
        if (added < 0) {
            source.sendFailure(Component.literal("§c[horde] §f同时使用的实体类型已达上限 "
                    + CrowdManager.MAX_TYPES + "，请先 /horde clear"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "§a[horde] §f阵营 §e" + team + " §f已生成 §b" + typeId + " §f× §e" + added + " / " + count
                        + " §7(半径 " + (int) spread + ")"), true);
        return added;
    }

    private static int clearCrowd(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        CrowdManager manager = CrowdManagers.getIfPresent(level.dimension());
        int n = manager == null ? 0 : manager.aliveCount();
        if (manager != null) {
            manager.clear();
        }
        source.sendSuccess(() -> Component.literal("§a[horde] §f已清除群集 §e" + n + " §f个单位"), true);
        return n;
    }

    // ------------------------------------------------------------------
    // 真实体模式
    // ------------------------------------------------------------------

    private static int spawnReal(CommandSourceStack source, ResourceLocation typeId, int count, double spread)
            throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = source.getLevel();
        int added = RealEntities.spawn(level, typeId, count,
                player.getX(), player.getY(), player.getZ(), spread);
        if (added < 0) {
            source.sendFailure(Component.literal("§c[horde] §f未注册的实体类型: " + typeId));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "§a[horde] §f真实体已生成 §b" + typeId + " §f× §e" + added + " / " + count
                        + " §7(半径 " + (int) spread + ")"), true);
        return added;
    }

    private static int clearReal(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        int removed = RealEntities.clear(level);
        source.sendSuccess(() -> Component.literal("§a[horde] §f已清除真实体 §e" + removed + " §f个"), true);
        return removed;
    }

    private static int status(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        CrowdManager manager = CrowdManagers.getIfPresent(level.dimension());
        int alive = manager == null ? 0 : manager.aliveCount();
        int spawned = manager == null ? 0 : manager.spawnedTotal();
        int killed = manager == null ? 0 : manager.killedTotal();
        int real = RealEntities.count(level);
        String dim = level.dimension().location().toString();
        String types = manager == null || manager.typeIds().isEmpty()
                ? "-" : manager.typeIds().toString();
        source.sendSuccess(() -> Component.literal(
                "§6[horde] §f维度 §b" + dim
                        + " §7| §a群集存活 §f" + alive
                        + " §7| §7累计生成 §f" + spawned
                        + " §7| §c累计阵亡 §f" + killed
                        + " §7| §d真实体 §f" + real
                        + " §7| §b类型 §f" + types), false);
        return alive;
    }
}
