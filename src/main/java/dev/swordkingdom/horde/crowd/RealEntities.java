package dev.swordkingdom.horde.crowd;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * 真实体模式。
 *
 * 直接生成**原版实体**（任意已注册的 EntityType），它们由原版自己驱动：
 * AI 目标选择、寻路、传感器、攻击、掉落、与其他 mod 的交互、客户端动画与插值……
 * 全部逐位一致，因为「就是原版实体本身」。
 *
 * 代价：开销也是原版的（AI + 寻路 + 每实体的网络同步 + 每个模型的渲染），
 * 实际上限取决于 CPU，通常在数百到数千之间，而不是群集模式的数万。
 */
public final class RealEntities {

    /** 用于标记 / 批量清除的 NBT 标记 */
    public static final String TAG = "horde_managed";

    private RealEntities() {}

    /**
     * 在指定位置批量生成任意已注册实体。
     *
     * @return 实际生成数量；类型未注册时返回 -1。
     */
    public static int spawn(ServerLevel level, ResourceLocation typeId, int count,
                            double cx, double cy, double cz, double spread) {
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(typeId);
        if (type == null) {
            return -1;
        }
        RandomSource rnd = RandomSource.create();
        int added = 0;
        for (int i = 0; i < count; i++) {
            Entity entity = type.create(level);
            if (entity == null) {
                break;
            }
            double angle = rnd.nextDouble() * Math.PI * 2.0D;
            double radius = Math.sqrt(rnd.nextDouble()) * spread;
            entity.moveTo(cx + Math.cos(angle) * radius, cy, cz + Math.sin(angle) * radius,
                    (float) (rnd.nextDouble() * 360.0D), 0.0F);
            CompoundTag tag = entity.getPersistentData();
            tag.putBoolean(TAG, true);
            if (entity instanceof Mob mob) {
                mob.setPersistenceRequired();
            }
            if (level.addFreshEntity(entity)) {
                added++;
            }
        }
        return added;
    }

    /**
     * 清除本模组生成的全部真实体。
     *
     * 注意：level.getAllEntities() 返回的是实体哈希表的**实时视图**，
     * 迭代过程中 discard() 会立刻把该实体从表里摘掉，从而抛
     * ArrayIndexOutOfBoundsException（Int2ObjectLinkedOpenHashMap 的迭代器）。
     * 因此先收集到列表，再统一 discard。
     */
    public static int clear(ServerLevel level) {
        List<Entity> targets = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (entity.getPersistentData().getBoolean(TAG)) {
                targets.add(entity);
            }
        }
        for (Entity entity : targets) {
            entity.discard();
        }
        return targets.size();
    }

    public static int count(ServerLevel level) {
        int n = 0;
        for (Entity entity : level.getAllEntities()) {
            if (entity.getPersistentData().getBoolean(TAG)) {
                n++;
            }
        }
        return n;
    }
}
