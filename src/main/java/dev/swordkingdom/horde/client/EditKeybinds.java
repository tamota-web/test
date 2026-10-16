package dev.swordkingdom.horde.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.swordkingdom.horde.HordeMod;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * 游戏内配置界面的入口：默认按 <b>H</b> 打开（可在「按键设置」里改），
 * 等价于命令 {@code /horde edit}。
 */
@Mod.EventBusSubscriber(modid = HordeMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class EditKeybinds {

    public static final KeyMapping OPEN_EDIT = new KeyMapping(
            "key.horde.edit", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, "key.categories.horde");

    private EditKeybinds() {}

    @SubscribeEvent
    public static void register(RegisterKeyMappingsEvent event) {
        event.register(OPEN_EDIT);
    }

    /** 每帧检查按键（用 FORGE 总线单独一个类，避免和 MOD 总线的注册混淆） */
    @Mod.EventBusSubscriber(modid = HordeMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static final class Handler {
        private Handler() {}

        @SubscribeEvent
        public static void onKey(InputEvent.Key event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen != null || mc.player == null) {
                return;
            }
            while (OPEN_EDIT.consumeClick()) {
                mc.setScreen(new CrowdEditScreen());
            }
        }
    }
}
