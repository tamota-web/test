package dev.swordkingdom.horde.client;

import dev.swordkingdom.horde.crowd.EquipData;
import dev.swordkingdom.horde.edit.EquipPalette;
import dev.swordkingdom.horde.network.EquipPackets;
import dev.swordkingdom.horde.network.HordeNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 装备面板：**单个**或**批量**给虚拟实体设置 6 个槽。
 *
 * <h2>布局（可复用面板：既能独立开屏，也能被 {@code CrowdEditScreen} 当一页签嵌进去）</h2>
 * <pre>
 *   ┌ 左：作用对象 ─────────────┬ 右：6 个槽 ────────────────────────────┐
 *   │ [单位 #id 输入框]         │ 头    [物品名]  [装] [清] [换成手上的]  │
 *   │ [类型] [阵营] [全部] [半径]│ 胸    …                                │
 *   │ [按类型/阵营/全部/半径]   │ 腿 / 脚 / 主手 / 副手                   │
 *   └───────────────────────────┴────────────────────────────────────────┘
 *   [取手上的物品到该槽] [用面板装到选中的对象]
 * </pre>
 *
 * <h2>批量为什么不是「循环发 N 个包」</h2>
 * 面板把**过滤条件**（类型/阵营/全部/半径）随一次 {@link EquipPackets.ApplyBatch} 发过去，
 * 服务端一次扫完并回一个「改了多少个」。1000 只换装也只占一个包。
 *
 * <h2>索引从哪来</h2>
 * 「装」按钮装的是**玩家手上那件物品**（服务端按它自己的调色板登记索引）
 * —— 这样客户端不需要「凭空造一个索引」，也不会出现「客户端说 7 号是铁剑、服务端 7 号是钻石胸甲」
 * 这种错位。面板上显示的槽内容来自服务端下发的调色板 + 逐单位索引。
 */
public class EquipScreen extends Screen {

    private final ResourceLocation dimension;
    private final ResourceLocation unitType;
    private final int team;

    /** 作用对象：单个单位 */
    private EditBox unitField;
    /** 批量目标文本（按类型/阵营时显示） */
    private EditBox typeField;
    private int unitId;
    private int batchMode = EquipPackets.ApplyBatch.MODE_ALL;
    /** 每个槽的「待装物品」= 该槽当前索引（显示用） */
    private final short[] pending = new short[EquipData.SLOT_COUNT];
    private final String[] status = new String[EquipData.SLOT_COUNT];

    private final List<Button> modeButtons = new ArrayList<>();
    private String message = "";

    public EquipScreen(ResourceLocation dimension, int unitId, ResourceLocation unitType, int team) {
        super(Component.literal("虚拟实体装备"));
        this.dimension = dimension;
        this.unitId = unitId;
        this.unitType = unitType;
        this.team = team;
    }

    public EquipScreen() {
        this(null, -1, null, -1);
    }

    @Override
    protected void init() {
        int left = 20;
        int top = 44;
        // 调色板没到就回请一次（服务端会先发表，再补一个整帧）
        ClientEquipData data = ClientEquipData.forCurrentLevel();
        if (data != null && (!data.hasPalette() || data.stale)) {
            ClientEquipData.requestPalette();
        }

        unitField = new EditBox(this.font, left, top, 90, 18, Component.literal("单位 id"));
        unitField.setValue(unitId < 0 ? "" : Integer.toString(unitId));
        addRenderableWidget(unitField);

        typeField = new EditBox(this.font, left, top + 24, 150, 18, Component.literal("实体类型 id"));
        typeField.setValue(unitType == null ? "" : unitType.toString());
        addRenderableWidget(typeField);

        int by = top + 52;
        modeButtons.clear();
        modeButtons.add(addRenderableWidget(Button.builder(Component.literal("按类型"),
                b -> setMode(EquipPackets.ApplyBatch.MODE_TYPE)).bounds(left, by, 70, 18).build()));
        modeButtons.add(addRenderableWidget(Button.builder(Component.literal("按阵营"),
                b -> setMode(EquipPackets.ApplyBatch.MODE_TEAM)).bounds(left + 74, by, 70, 18).build()));
        modeButtons.add(addRenderableWidget(Button.builder(Component.literal("全部"),
                b -> setMode(EquipPackets.ApplyBatch.MODE_ALL)).bounds(left, by + 22, 70, 18).build()));
        modeButtons.add(addRenderableWidget(Button.builder(Component.literal("半径 32"),
                b -> setMode(EquipPackets.ApplyBatch.MODE_RADIUS)).bounds(left + 74, by + 22, 70, 18).build()));

        // 右侧 6 个槽：每行 [装到该槽] [清空] [从单位换到手]
        int rx = 200;
        for (int s = 0; s < EquipData.SLOT_COUNT; s++) {
            final int slot = s;
            int y = top + s * 26;
            addRenderableWidget(Button.builder(Component.literal("装"), b -> equipHeld(slot, false))
                    .bounds(rx + 110, y, 34, 18).build());
            addRenderableWidget(Button.builder(Component.literal("清"), b -> equipHeld(slot, false, true))
                    .bounds(rx + 146, y, 34, 18).build());
            addRenderableWidget(Button.builder(Component.literal("换"), b -> equipHeld(slot, true))
                    .bounds(rx + 182, y, 34, 18).build());
        }

        // 底部：单装 / 批装 / 取回 / 关闭
        int footY = this.height - 32;
        addRenderableWidget(Button.builder(Component.literal("单装到 #" + (unitId < 0 ? "?" : unitId)),
                b -> applySingle()).bounds(20, footY, 140, 20).build());
        addRenderableWidget(Button.builder(Component.literal("批量装（左选条件）"),
                b -> applyBatch()).bounds(166, footY, 150, 20).build());
        addRenderableWidget(Button.builder(Component.literal("刷新"), b -> refresh())
                .bounds(322, footY, 60, 20).build());
        addRenderableWidget(Button.builder(Component.literal("关闭"), b -> this.onClose())
                .bounds(388, footY, 60, 20).build());
        refresh();
    }

    private void setMode(int mode) {
        this.batchMode = mode;
        this.message = switch (mode) {
            case EquipPackets.ApplyBatch.MODE_TYPE -> "批量目标：与输入框里同类型的全部单位";
            case EquipPackets.ApplyBatch.MODE_TEAM -> "批量目标：同阵营的全部单位";
            case EquipPackets.ApplyBatch.MODE_RADIUS -> "批量目标：以我为中心 32 格内";
            default -> "批量目标：场上全部单位";
        };
    }

    private void refresh() {
        ClientEquipData data = ClientEquipData.forCurrentLevel();
        if (unitId >= 0 && data != null) {
            for (int s = 0; s < EquipData.SLOT_COUNT; s++) {
                pending[s] = data.slot(unitId, s);
                status[s] = describe(data, s, pending[s]);
            }
        }
    }

    private String describe(ClientEquipData data, int slot, short index) {
        if (index == 0) {
            return "-";
        }
        ClientEquipData.Entry e = data == null || unitType == null ? null : data.entry(unitType, index);
        if (e == null) {
            return "#" + (index & 0xFFFF) + "（等调色板）";
        }
        return "#" + (index & 0xFFFF) + " " + shortName(e.item.toString()) + (e.glint ? " ✦" : "");
    }

    private static String shortName(String id) {
        int c = id.indexOf(':');
        return c < 0 ? id : id.substring(c + 1);
    }

    // ------------------------------------------------------------------
    // 操作
    // ------------------------------------------------------------------

    /** 「装」/「清」/「换」：装的是**手上的物品**，服务端自己登记索引 */
    private void equipHeld(int slot, boolean swap) {
        equipHeld(slot, swap, false);
    }

    private void equipHeld(int slot, boolean swap, boolean clear) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        if (clear) {
            // 清空：发一个「空手 + 该槽归零」的批量/单个请求
            if (unitId >= 0) {
                short[] six = currentSlots();
                six[slot] = 0;
                HordeNetwork.sendToServer(new EquipPackets.ApplyUnit(dimension, unitId, six,
                        (byte) 0));
                message = "已请求清空第 " + slot + " 槽";
            } else {
                short[] six = new short[EquipData.SLOT_COUNT];
                HordeNetwork.sendToServer(new EquipPackets.ApplyBatch(dimension, batchMode,
                        parseType(), team, mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                        32.0F, six, (byte) 0));
                message = "已请求批量清空（模式 " + batchMode + "）";
            }
            return;
        }
        // 「换」或「装」：按玩家的手部物品
        if (mc.player.getMainHandItem().isEmpty()) {
            message = "手上没有物品";
            return;
        }
        if (unitId >= 0) {
            HordeNetwork.sendToServer(new EquipPackets.ApplyHeld(dimension, unitId, batchMode, slot,
                    swap, parseType(), team));
            message = "已请求把手上物品装到 #" + unitId + " 的第 " + slot + " 槽"
                    + (swap ? "（并换回原来那件）" : "");
        } else {
            HordeNetwork.sendToServer(new EquipPackets.ApplyHeld(dimension, -1, batchMode, slot,
                    false, parseType(), team));
            message = "已请求批量装到第 " + slot + " 槽（模式 " + batchMode + "）";
        }
    }

    private short[] currentSlots() {
        short[] six = new short[EquipData.SLOT_COUNT];
        System.arraycopy(pending, 0, six, 0, EquipData.SLOT_COUNT);
        return six;
    }

    private void applySingle() {
        try {
            unitId = Integer.parseInt(unitField.getValue().trim());
        } catch (Throwable t) {
            message = "单位 id 必须是数字";
            return;
        }
        // 「单装」= 把面板上这 6 个槽（可能被「清」改过）整体写回去
        HordeNetwork.sendToServer(new EquipPackets.ApplyUnit(dimension, unitId, currentSlots(),
                (byte) 0));
        message = "已请求把面板内容写到 #" + unitId;
    }

    private void applyBatch() {
        HordeNetwork.sendToServer(new EquipPackets.ApplyBatch(dimension, batchMode, parseType(),
                team, Minecraft.getInstance().player == null ? 0.0D : Minecraft.getInstance().player.getX(),
                Minecraft.getInstance().player == null ? 0.0D : Minecraft.getInstance().player.getY(),
                Minecraft.getInstance().player == null ? 0.0D : Minecraft.getInstance().player.getZ(),
                32.0F, currentSlots(), (byte) 0));
        message = "已请求批量写入（模式 " + batchMode + "）";
    }

    private ResourceLocation parseType() {
        String v = typeField == null ? "" : typeField.getValue().trim();
        if (v.isEmpty()) {
            return unitType;
        }
        return ResourceLocation.tryParse(v);
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        g.drawCenteredString(this.font, this.title, this.width / 2, 16, 0xFFFFFF);

        int left = 20;
        int top = 44;
        g.drawString(this.font, "作用对象（单个）", left, top - 12, 0xA0A0A0);
        g.drawString(this.font, "类型 id", left, top + 14, 0xA0A0A0);
        g.drawString(this.font, "批量条件（当前模式 " + batchMode + "）", left, top + 44, 0xA0A0A0);

        int rx = 200;
        for (int s = 0; s < EquipData.SLOT_COUNT; s++) {
            int y = top + s * 26;
            g.drawString(this.font, EquipPalette.SLOT_NAMES[s], rx, y + 5, 0xFFFFFF);
            g.drawString(this.font, status[s] == null ? "-" : status[s], rx + 40, y + 5, 0x8080FF);
        }

        if (!message.isEmpty()) {
            g.drawString(this.font, message, left, this.height - 52, 0xFFFF80);
        }
        if (ClientEquipData.forCurrentLevel() != null && ClientEquipData.forCurrentLevel().stale) {
            g.drawString(this.font, "调色板已过期，正在重新同步…", left, this.height - 64, 0xFF8080);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
