package dev.swordkingdom.horde.client;

import dev.swordkingdom.horde.network.EditPackets;
import net.minecraft.client.Minecraft;

/**
 * 客户端侧的覆盖表镜像：服务端下发后存在这里。
 *
 * 两个用途：
 *  1. GUI 显示当前值；
 *  2. 渲染时决定「这个类型该用哪个模型」—— 换模型后要重烘焙网格，所以收到新表就作废网格缓存。
 */
public final class ClientOverrides {

    private ClientOverrides() {}

    public static void accept(EditPackets.Sync msg) {
        EditPackets.readTable(msg.json);
        // 覆盖可能改了模型 —— 已有的网格要重烘焙
        CrowdRenderer.invalidateAll();
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof CrowdEditScreen screen) {
            screen.onOverridesChanged();
        }
    }

    public static void openScreen() {
        Minecraft.getInstance().setScreen(new CrowdEditScreen());
    }

    public static void request() {
        dev.swordkingdom.horde.network.HordeNetwork.sendToServer(new EditPackets.Request());
    }

    public static void submit(String type, dev.swordkingdom.horde.edit.TypeOverride override, boolean reset) {
        dev.swordkingdom.horde.network.HordeNetwork.sendToServer(
                new EditPackets.Set(type, EditPackets.writeOne(override), reset));
    }
}
