package dev.swordkingdom.horde.harness;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/** 一次性诊断：把每个原版远程怪自己的 goal 实例的字段值打出来（用来定 RangedProfile 的镜像规则）。 */
public final class GoalProbe {
    public static void run() {
        main(new String[0]);
    }

    public static void main(String[] args) {
        try {
            net.minecraft.server.Bootstrap.bootStrap();
            System.out.println("   [probe] Bootstrap 成功");
        } catch (Throwable t) {
            System.out.println("   [probe] Bootstrap 失败：" + t);
        }
        String[] ids = {
                "minecraft:skeleton", "minecraft:stray", "minecraft:wither_skeleton",
                "minecraft:snow_golem", "minecraft:witch", "minecraft:llama", "minecraft:pillager",
                "minecraft:drowned", "minecraft:blaze", "minecraft:ghast", "minecraft:shulker",
                "minecraft:wither", "minecraft:zombie"
        };
        for (String s : ids) {
            ResourceLocation id = ResourceLocation.tryParse(s);
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(id);
            System.out.println("===== " + s + " type=" + (type == null ? "null" : "ok"));
            if (type == null) {
                continue;
            }
            Entity e = null;
            Throwable err = null;
            try {
                e = type.create(null);
            } catch (Throwable t) {
                err = t;
            }
            System.out.println("   entity=" + (e == null ? "null" : e.getClass().getName())
                    + (err == null ? "" : " err=" + err));
            if (!(e instanceof Mob mob)) {
                continue;
            }
            for (WrappedGoal wg : mob.goalSelector.getAvailableGoals()) {
                Goal g = wg.getGoal();
                String cn = g.getClass().getName();
                if (!(cn.contains("Ranged") || cn.contains("Attack") || cn.contains("Shoot")
                        || cn.contains("Fireball") || cn.contains("Bow") || cn.contains("Crossbow")
                        || cn.contains("Trident") || cn.contains("Spell"))) {
                    continue;
                }
                System.out.println("   GOAL " + cn + " (priority " + wg.getPriority() + ")");
                for (Class<?> k = g.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                    for (Field f : k.getDeclaredFields()) {
                        if (Modifier.isStatic(f.getModifiers())) {
                            continue;
                        }
                        if (f.getType().isPrimitive() || f.getType() == String.class) {
                            try {
                                f.setAccessible(true);
                                Object v = f.get(g);
                                if (v instanceof Number || v instanceof Boolean || v instanceof String) {
                                    System.out.println("      " + k.getSimpleName() + "." + f.getName() + " = " + v);
                                }
                            } catch (Throwable ignored) {
                                // 读不到就算了
                            }
                        }
                    }
                }
            }
            mob.discard();
        }
    }
}
