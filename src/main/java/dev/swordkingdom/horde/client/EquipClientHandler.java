package dev.swordkingdom.horde.client;

import dev.swordkingdom.horde.HordeMod;
import dev.swordkingdom.horde.edit.EquipPalette;
import dev.swordkingdom.horde.network.EquipPackets;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;

/**
 * 客户端侧收到服务端的拾取回执之后该干什么。
 *
 * <pre>
 *   命中 + 手里有物品  -> 服务端已经装好了（它才是权威），这里只在动作栏提示「装到了第几号」
 *   命中 + 空手        -> 打开装备面板（{@link EquipScreen}），并预选这个单位
 *   没命中             -> 什么都不做（避免右键空地时刷屏）
 * </pre>
 *
 * <h2>「对应槽」怎么定的</h2>
 * 不靠猜：问物品自己。{@link ArmorItem#getEquipmentSlot()}（护甲）、
 * {@code Equipable#getEquipmentSlot()}（盾/其它可穿戴物），
 * 都不是就按主手。这套判定在客户端/服务端两侧是**同一份代码**（
 * {@link #slotForStack}），所以两边算出来的槽一定一致。
 */
public final class EquipClientHandler {

    private EquipClientHandler() {}

    /**
     * 决定「这件物品应该装到哪个槽」。
     *
     * <p>优先 {@code Equipable}（它把护甲、盾、以及模组自己实现的可穿戴物统一起来），
     * 再退回 {@link ArmorItem}，都没有就当主手武器/工具。
     */
    public static int slotForStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return EquipPalette.SLOT_MAINHAND;
        }
        try {
            if (stack.getItem() instanceof net.minecraft.world.item.Equipable equipable) {
                return fromVanillaSlot(equipable.getEquipmentSlot());
            }
        } catch (Throwable ignored) {
            // 试下一条
        }
        try {
            if (stack.getItem() instanceof ArmorItem armor) {
                return fromVanillaSlot(armor.getEquipmentSlot());
            }
        } catch (Throwable ignored) {
            // 落到主手
        }
        return EquipPalette.SLOT_MAINHAND;
    }

    /** 原版槽位 -> 我们的槽位下标 */
    public static int fromVanillaSlot(EquipmentSlot slot) {
        if (slot == null) {
            return EquipPalette.SLOT_MAINHAND;
        }
        return switch (slot) {
            case HEAD -> EquipPalette.SLOT_HEAD;
            case CHEST -> EquipPalette.SLOT_CHEST;
            case LEGS -> EquipPalette.SLOT_LEGS;
            case FEET -> EquipPalette.SLOT_FEET;
            case OFFHAND -> EquipPalette.SLOT_OFFHAND;
            default -> EquipPalette.SLOT_MAINHAND;
        };
    }

    /** 收服务端回执 */
    public static void accept(EquipPackets.PickResult msg) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        if (!msg.hit()) {
            return;
        }
        if (player.getMainHandItem().isEmpty()) {
            // 空手右键命中：打开面板并预选这个单位
            mc.setScreen(new EquipScreen(msg.dimension, msg.unitId, msg.unitType, msg.team));
        } else {
            player.displayClientMessage(Component.literal("[horde] 已给单位 #" + msg.unitId
                            + " 装上手部物品（距离 " + fmt(msg.distance) + " 格）")
                    .withStyle(ChatFormatting.GREEN), true);
        }
    }

    /** 收到的批量/单次操作回执 */
    public static void acceptResult(EquipPackets.Result msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || msg.message.isEmpty()) {
            return;
        }
        mc.player.displayClientMessage(Component.literal("[horde] " + msg.message)
                .withStyle(msg.changed > 0 ? ChatFormatting.GREEN : ChatFormatting.YELLOW), true);
    }

    private static String fmt(float v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }
}
