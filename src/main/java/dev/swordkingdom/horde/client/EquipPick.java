package dev.swordkingdom.horde.client;

import dev.swordkingdom.horde.HordeMod;
import dev.swordkingdom.horde.network.EquipPackets;
import dev.swordkingdom.horde.network.HordeNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 「玩家手持物品右键虚拟单位」——客户端侧。
 *
 * <h2>为什么命中判定放在服务端</h2>
 * 客户端只把**自己的视线**（眼位 + 视线方向 + 最大距离）发过去；
 * 谁是最近被命中的单位、装到哪个槽、要不要把单位身上那件换回来，全部由服务端判定
 * —— 玩家改不了「我打中了第几号」。
 *
 * <h2>右键的语义映射（也在报告里写一遍）</h2>
 * <pre>
 *   手持物品 + 右键        -> 把手上的物品装到**该单位**的「对应槽」（单个）
 *   潜行 + 手持物品 + 右键 -> 批量装到**该单位所属的实体类型**的全部单位
 *   空手右键（命中）       -> 打开装备面板并预选这个单位
 *   没命中                 -> 什么都不做（不打扰正常右键）
 * </pre>
 * 「对应槽」不靠猜：问物品自己（护甲 → 头/胸/腿/脚，盾 → 副手，其它 → 主手），
 * 见 {@link EquipClientHandler#slotForStack}。
 *
 * <h2>为什么不取消右键事件</h2>
 * 群集单位**不是真实实体**（不在 {@code level.getEntities()} 里），原版射线永远打不到它们，
 * 所以「给虚拟单位装装备」和「原版右键行为」不会互相干扰 —— 不取消更安全。
 */
@Mod.EventBusSubscriber(modid = HordeMod.MODID, value = Dist.CLIENT)
public final class EquipPick {

    /** 拾取射线的最大距离（格）——与原版「伸手够得着」的量级一致 */
    private static final float PICK_RANGE = 6.0F;
    /** 预筛半径：附近没有群集数据时连包都不发 */
    private static final float EMPTY_HAND_RANGE = 12.0F;

    private EquipPick() {}

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (!(event.getEntity() instanceof LocalPlayer player)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) {
            return;
        }
        ClientEquipData data = ClientEquipData.forCurrentLevel();
        if (data == null || data.dimension == null) {
            return;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 dir = player.getLookAngle();
        boolean emptyHand = player.getMainHandItem().isEmpty();
        float range = emptyHand ? EMPTY_HAND_RANGE : PICK_RANGE;
        HordeNetwork.sendToServer(new EquipPackets.Pick(
                data.dimension, eye.x, eye.y, eye.z,
                (float) dir.x, (float) dir.y, (float) dir.z, range,
                !emptyHand && player.isShiftKeyDown()));
    }
}
