package dev.swordkingdom.horde.client;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.swordkingdom.horde.action.ActionDriver;
import dev.swordkingdom.horde.edit.TypeOverride;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 游戏内「群集实体实时配置」界面。
 *
 * 布局：左边类型列表（翻页）、中间 **3D 实时预览**、右边分页签（模型 / 动作 / 攻击 / 属性）。
 *
 * 预览直接用**原版实体渲染管线**画真实实体（不是我们烘焙的群集网格），
 * 所以「模型 / 动作」就是该模组自己的模型与动画 —— 选哪个动作就用
 * {@link ActionDriver} 把状态写到实体上，渲染器自己会调 {@code setupAnim} 播出来。
 *
 * 改动提交给服务端（全局共享），服务端立刻重算属性并回下发表格，
 * 世界里已有的群集单位马上生效。
 */
public class CrowdEditScreen extends Screen {

    private static final int ROW_H = 20;
    /** 可选的模型类型（只列有 LivingEntityRenderer 且有群集模型的生物） */
    private static List<ResourceLocation> modelChoices;
    private static int modelChoicePage;

    private final List<ResourceLocation> types = new ArrayList<>();
    private int typePage;
    private ResourceLocation selected;
    private TypeOverride draft;
    private int tab;
    private String status = "§7从左边选一个实体类型；改完点「应用」";

    /** 预览用 */
    private Entity preview;
    private ResourceLocation previewTypeId;
    private ActionDriver previewDriver;
    private int previewAction;
    private int previewTick;
    private int previewActionTick;
    private boolean previewPlaying = true;

    /** 动作候选（-1 = 自动，0 = 不播，k = 第 k 个） */
    private int actionPage;
    /** 「技能」页签的当前子页（0 = 发射参数，1 = 命中效果） */
    private int skillPage;

    /** 「部件」页签：当前选中的部件下标与部件名缓存 */
    private int partIndex;
    private List<String> partNames;
    private ResourceLocation partNamesFor;

    public CrowdEditScreen() {
        super(Component.literal("群集实体配置"));
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    protected void init() {
        refreshTypes();
        ClientOverrides.request();
        rebuild();
    }

    private void refreshTypes() {
        types.clear();
        // 活的群集类型 + 覆盖表里已有的类型
        ClientCrowdData data = ClientCrowdData.forCurrentLevel();
        if (data != null && data.types != null) {
            for (ResourceLocation t : data.types) {
                if (t != null && !types.contains(t)) {
                    types.add(t);
                }
            }
        }
        for (ResourceLocation t : TypeOverride.all().keySet()) {
            if (!types.contains(t)) {
                types.add(t);
            }
        }
        if (selected == null && !types.isEmpty()) {
            select(types.get(0));
        }
    }

    /** 覆盖表变了（服务端回包）：刷新草稿与预览 */
    public void onOverridesChanged() {
        if (selected != null) {
            TypeOverride o = TypeOverride.get(selected);
            draft = o == null ? new TypeOverride() : o.copy();
            refreshPreview();
        }
        rebuild();
    }

    private void select(ResourceLocation id) {
        selected = id;
        TypeOverride o = TypeOverride.get(id);
        draft = o == null ? new TypeOverride() : o.copy();
        refreshPreview();
        status = "§7正在编辑 §f" + id;
    }

    /** 预览实体按「实际会被渲染的模型」创建 */
    private void refreshPreview() {
        preview = null;
        previewDriver = null;
        previewTypeId = null;
        if (selected == null || minecraft == null || minecraft.level == null) {
            return;
        }
        ResourceLocation modelId = TypeOverride.modelFor(selected);
        EntityType<?> t = BuiltInRegistries.ENTITY_TYPE.get(modelId);
        if (t == null) {
            return;
        }
        Entity e = t.create(minecraft.level);
        if (!(e instanceof LivingEntity)) {
            return;
        }
        preview = e;
        previewTypeId = modelId;
        previewDriver = ActionDriver.of(e.getClass());
        previewAction = draft != null && draft.action >= 0 ? draft.action : 1;
        previewTick = 0;
        previewActionTick = 0;
    }

    @Override
    public void tick() {
        super.tick();
        if (preview == null) {
            refreshPreview();
        }
        if (preview != null) {
            preview.tickCount++;
            if (previewPlaying && previewDriver != null && previewDriver.usable() && previewAction > 0) {
                previewDriver.apply(preview, previewAction, previewActionTick);
                previewActionTick++;
                if (previewActionTick > 200) {
                    previewActionTick = 0;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 控件
    // ------------------------------------------------------------------

    private void rebuild() {
        clearWidgets();
        int left = 8;
        int top = 34;

        // ---- 左：类型列表（翻页） ----
        int perPage = 12;
        int pages = Math.max(1, (types.size() + perPage - 1) / perPage);
        typePage = Math.max(0, Math.min(typePage, pages - 1));
        for (int i = 0; i < perPage; i++) {
            int idx = typePage * perPage + i;
            if (idx >= types.size()) {
                break;
            }
            ResourceLocation id = types.get(idx);
            boolean sel = id.equals(selected);
            addRenderableWidget(Button.builder(Component.literal((sel ? "§a▶ " : "") + shortName(id)),
                            b -> {
                                select(id);
                                rebuild();
                            })
                    .bounds(left, top + i * ROW_H, 150, 18).build());
        }
        addRenderableWidget(Button.builder(Component.literal("◀"), b -> {
            typePage--;
            rebuild();
        }).bounds(left, top + perPage * ROW_H + 2, 40, 18).build());
        addRenderableWidget(Button.builder(Component.literal((typePage + 1) + "/" + pages), b -> { })
                .bounds(left + 44, top + perPage * ROW_H + 2, 60, 18).build());
        addRenderableWidget(Button.builder(Component.literal("▶"), b -> {
            typePage++;
            rebuild();
        }).bounds(left + 108, top + perPage * ROW_H + 2, 40, 18).build());

        // ---- 右：分页签 ----
        int panelX = width - 246;
        String[] tabs = {"模型", "动作", "技能", "属性", "部件"};
        for (int i = 0; i < tabs.length; i++) {
            boolean on = tab == i;
            final int t = i;
            addRenderableWidget(Button.builder(Component.literal((on ? "§e" : "§7") + tabs[i]), b -> {
                tab = t;
                rebuild();
            }).bounds(panelX + i * 48, top - 22, 46, 18).build());
        }

        if (selected == null || draft == null) {
            status = "§c世界里还没有群集单位，先用 /horde spawn 生成";
            return;
        }

        int y = top + 2;
        switch (tab) {
            case 0 -> {
                addRenderableWidget(Button.builder(modelLabel(), b -> openModelPicker())
                        .bounds(panelX, y, 236, 18).build());
                y += ROW_H + 4;
                addRenderableWidget(Button.builder(Component.literal("重置模型"), b -> {
                    draft.model = "";
                    refreshPreview();
                    rebuild();
                }).bounds(panelX, y, 236, 18).build());
            }
            case 1 -> {
                y = actionRow(panelX, y, "动作选择", draft.action, v -> draft.action = v);
                addRenderableWidget(Button.builder(Component.literal("预览：播放/暂停"), b -> {
                    previewPlaying = !previewPlaying;
                }).bounds(panelX, y, 236, 18).build());
            }
            case 2 -> {
                y = skillTab(panelX, y);
            }
            default -> {
                y = slider(panelX, y, "生命值", draft.maxHp, 0.0F, 2000.0F, 5.0F, v -> draft.maxHp = v);
                y = slider(panelX, y, "攻击力", draft.damage, 0.0F, 200.0F, 1.0F, v -> draft.damage = v);
                y = slider(panelX, y, "移动速度", draft.moveSpeed, 0.0F, 1.0F, 0.01F, v -> draft.moveSpeed = v);
                y = slider(panelX, y, "索敌范围", draft.followRange, 0.0F, 128.0F, 1.0F, v -> draft.followRange = v);
                y = slider(panelX, y, "护甲", draft.armor, 0.0F, 30.0F, 0.5F, v -> draft.armor = v);
                y = slider(panelX, y, "护甲韧性", draft.armorToughness, 0.0F, 20.0F, 0.5F, v -> draft.armorToughness = v);
                y = slider(panelX, y, "击退抗性", draft.knockbackResistance, 0.0F, 1.0F, 0.05F, v -> draft.knockbackResistance = v);
                y = slider(panelX, y, "宽度", draft.width, 0.1F, 8.0F, 0.1F, v -> draft.width = v);
                y = slider(panelX, y, "高度", draft.height, 0.1F, 12.0F, 0.1F, v -> draft.height = v);
                y = slider(panelX, y, "眼睛高度", draft.eyeHeight, 0.1F, 12.0F, 0.1F, v -> draft.eyeHeight = v);
                y = intSlider(panelX, y, "攻击冷却(tick)", draft.attackCooldownTicks, 2, 200, 1, v -> draft.attackCooldownTicks = v);
            }
            case 4 -> partTab(panelX, y);
        }

        // ---- 底部操作 ----
        int bottom = height - 26;
        addRenderableWidget(Button.builder(Component.literal("§a应用（全局）"), b -> {
            ClientOverrides.submit(selected.toString(), draft, false);
            status = "§a已提交，服务端会立刻生效";
        }).bounds(panelX, bottom, 76, 20).build());
        addRenderableWidget(Button.builder(Component.literal("§c还原"), b -> {
            draft = new TypeOverride();
            refreshPreview();
            ClientOverrides.submit(selected.toString(), draft, true);
            status = "§c已还原成实体自身属性";
            rebuild();
        }).bounds(panelX + 80, bottom, 76, 20).build());
        addRenderableWidget(Button.builder(Component.literal("关闭"), b -> onClose())
                .bounds(panelX + 160, bottom, 76, 20).build());
    }

    /**
     * 「技能」页签：**虚拟投掷物**的完整配置。
     *
     * 一条配置 = 一个 (实体类型, 动作编号)。动作编号就是「动作」页签里那个编号 ——
     * 单位把这个动作播到命中帧时，就按这里的配置发射一枚虚拟投掷物。
     *
     * 分两页是为了塞得下（每行 22 像素，一页 12 行左右）：
     *   第 1 页：发射什么 + 怎么发（投掷物、初速、仰角、重力、寿命、散射）
     *   第 2 页：打出去会怎样 + 全局项（伤害、命中半径、穿透、AoE、同时在场上限、是否伤真实实体）
     *
     * 所有数值行都沿用现成的 {@code numberRow}（可直接输入 + −/+/×），
     * 没有另起一套输入控件。
     */
    private int skillTab(int x, int y) {
        // ---- 动作选择：和配置一一对应 ----
        int maxAction = previewDriver == null ? 0 : previewDriver.actionCount();
        final int action = draft.action >= 0 ? draft.action : 1;
        dev.swordkingdom.horde.edit.ProjectileSkill skill = draft.mutableAction(action);

        addRenderableWidget(Button.builder(Component.literal("§7配置动作：§f第 " + action + " 个"
                        + (maxAction > 0 ? " / 共 " + maxAction : "§7（该实体没有动作清单）")), b -> { })
                .bounds(x, y, 160, 18).build());
        addRenderableWidget(Button.builder(Component.literal("−"), b -> {
            draft.action = Math.max(0, draft.action < 0 ? 1 : draft.action - 1);
            rebuild();
        }).bounds(x + 164, y, 32, 18).build());
        addRenderableWidget(Button.builder(Component.literal("+"), b -> {
            int v = (draft.action < 0 ? 1 : draft.action) + 1;
            if (maxAction > 0 && v > maxAction) {
                v = maxAction;
            }
            draft.action = v;
            rebuild();
        }).bounds(x + 200, y, 36, 18).build());
        y += ROW_H + 2;

        // 页切换
        addRenderableWidget(Button.builder(Component.literal("§7◀ 发射参数"), b -> {
            skillPage = 0;
            rebuild();
        }).bounds(x, y, 114, 18).build());
        addRenderableWidget(Button.builder(Component.literal("§7命中效果 ▶"), b -> {
            skillPage = 1;
            rebuild();
        }).bounds(x + 118, y, 118, 18).build());
        y += ROW_H + 2;

        if (!draft.hasProjectileSkills() && skill.isEmpty()) {
            addRenderableWidget(Button.builder(
                            Component.literal("§8（当前动作没有投掷物配置）"), b -> { })
                    .bounds(x, y, 236, 18).build());
            y += ROW_H + 2;
        }

        if (skillPage == 0) {
            // ---- 发射什么 ----
            String shown = skill.disabled ? "§c不发射"
                    : (skill.projectile == null || skill.projectile.isEmpty()
                    ? "§a自动（同命名空间+名字词干）" : "§f" + skill.projectile);
            addRenderableWidget(Button.builder(Component.literal("§7投掷物："), b -> { })
                    .bounds(x, y, 52, 18).build());
            addRenderableWidget(Button.builder(Component.literal(trimLabel(shown, 22)), b -> openProjectilePicker(action))
                    .bounds(x + 54, y, 140, 18).build());
            addRenderableWidget(Button.builder(Component.literal(skill.disabled ? "§c×" : "§a●"), b -> {
                skill.disabled = !skill.disabled;
                rebuild();
            }).bounds(x + 196, y, 40, 18).build());
            y += ROW_H + 2;

            // 「发射用物品」：只有物品投掷物（雪球/蛋/珍珠）的渲染器需要它
            String itemShown = skill.renderItem == null || skill.renderItem.isEmpty()
                    ? "§7（不用）" : "§f" + skill.renderItem;
            addRenderableWidget(Button.builder(Component.literal("§7发射用物品：§r" + trimLabel(itemShown, 16)),
                            b -> openItemPicker(action))
                    .bounds(x, y, 196, 18).build());
            addRenderableWidget(Button.builder(Component.literal("×"), b -> {
                skill.renderItem = "";
                ProjectileRenderer.invalidateAll();
                rebuild();
            }).bounds(x + 198, y, 38, 18).build());
            y += ROW_H + 2;

            // ---- 怎么发 ----
            y = slider(x, y, "初速(格/tick)", skill.speed, 0.05F, 12.0F, 0.05F, v -> skill.speed = v);
            y = offsetRow(x, y, "仰角(度)", skill.pitch, v -> skill.pitch = v);
            y = slider(x, y, "重力", skill.gravity, 0.0F, 0.2F, 0.005F, v -> skill.gravity = v);
            y = intSlider(x, y, "寿命(tick)", skill.life, 1, 1200, 5, v -> skill.life = v);
            y = intSlider(x, y, "一次发射(枚)", skill.count, 1, 32, 1, v -> skill.count = v);
            y = slider(x, y, "散射角(度)", skill.spread, 0.0F, 90.0F, 1.0F, v -> skill.spread = v);
            return y;
        }

        // ---- 命中效果 ----
        y = slider(x, y, "伤害倍率", skill.damageScale, 0.0F, 20.0F, 0.1F, v -> skill.damageScale = v);
        y = slider(x, y, "命中半径(格)", skill.hitRadius, 0.05F, 4.0F, 0.05F, v -> skill.hitRadius = v);
        y = intSlider(x, y, "穿透次数", skill.pierce, 0, 32, 1, v -> skill.pierce = v);
        y = slider(x, y, "AoE半径(格)", skill.blastRadius, 0.0F, 16.0F, 0.5F, v -> skill.blastRadius = v);
        y = slider(x, y, "AoE伤害倍率", skill.blastScale, 0.0F, 5.0F, 0.1F, v -> skill.blastScale = v);
        y = slider(x, y, "击退", skill.knockback, 0.0F, 4.0F, 0.05F, v -> skill.knockback = v);
        y = intSlider(x, y, "撞方块卡住", skill.stickOnBlock, 0, 1, 1, v -> skill.stickOnBlock = v);

        // ---- 全局（该实体类型） ----
        y = intSlider(x, y, "同时在场上限", draft.maxProjectiles, 0, 2048, 16, v -> draft.maxProjectiles = v);
        y = intSlider(x, y, "伤害真实实体", draft.hurtRealEntities, 0, 1, 1, v -> draft.hurtRealEntities = v);

        // 旧的近战/范围技能参数仍在（投掷物之外的兜底结算用）
        y = slider(x, y, "范围倍率", draft.radiusScale, 0.1F, 8.0F, 0.1F, v -> draft.radiusScale = v);
        y = slider(x, y, "命中帧比例", draft.impactFraction, 0.05F, 0.95F, 0.05F, v -> draft.impactFraction = v);
        y = intSlider(x, y, "施放间隔(tick)", draft.intervalTicks, 0, 1200, 20, v -> draft.intervalTicks = v);
        return y;
    }

    private static String trimLabel(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    /** 投掷物选择：候选池来自**全注册表按原版基类筛出来的投掷物**（不写死任何模组） */
    private void openProjectilePicker(int action) {
        minecraft.setScreen(new ProjectilePickerScreen(this, action));
    }

    /** 发射用物品选择：只有物品投掷物的渲染器需要（列表就是物品注册表） */
    private void openItemPicker(int action) {
        minecraft.setScreen(new ItemPickerScreen(this, action));
    }

    /** 投掷物候选池（懒扫描一次，按「同命名空间优先」排序，方便找施法者自己的弹） */
    private static List<dev.swordkingdom.horde.crowd.ProjectileCatalog.Candidate> projectileChoices;

    private static List<dev.swordkingdom.horde.crowd.ProjectileCatalog.Candidate> projectileChoices() {
        if (projectileChoices == null) {
            projectileChoices = dev.swordkingdom.horde.crowd.ProjectileCatalog.scanAll();
            projectileChoices.sort((a, b) -> a.id.toString().compareTo(b.id.toString()));
        }
        return projectileChoices;
    }

    /** 投掷物选择界面：可滚动列表 + 「自动」项 + 「不发射」项 */
    class ProjectilePickerScreen extends Screen {
        private final CrowdEditScreen parent;
        private final int action;
        private String filter = "";
        private int page;
        private EditBox filterBox;

        ProjectilePickerScreen(CrowdEditScreen parent, int action) {
            super(Component.literal("选择投掷物"));
            this.parent = parent;
            this.action = action;
        }

        @Override
        protected void init() {
            clearWidgets();
            int perPage = 14;
            List<dev.swordkingdom.horde.crowd.ProjectileCatalog.Candidate> list = new ArrayList<>();
            for (var c : projectileChoices()) {
                if (filter.isEmpty() || c.id.toString().contains(filter)) {
                    list.add(c);
                }
            }
            int pages = Math.max(1, (list.size() + perPage - 1) / perPage);
            page = Math.max(0, Math.min(page, pages - 1));

            filterBox = new EditBox(font, 8, 26, 200, 18, Component.literal("筛选"));
            filterBox.setValue(filter);
            filterBox.setResponder(t -> {
                filter = t;
                page = 0;
                rebuild();
            });
            addRenderableWidget(filterBox);

            addRenderableWidget(Button.builder(Component.literal("§a自动（按命名空间/名字词干挑）"), b -> {
                parent.draft.mutableAction(action).projectile = "";
                parent.back();
            }).bounds(8, 46, 240, 18).build());
            addRenderableWidget(Button.builder(Component.literal("§c不发射"), b -> {
                var s = parent.draft.mutableAction(action);
                s.disabled = true;
                parent.back();
            }).bounds(8, 66, 240, 18).build());

            for (int i = 0; i < perPage; i++) {
                int idx = page * perPage + i;
                if (idx >= list.size()) {
                    break;
                }
                var c = list.get(idx);
                ResourceLocation id = c.id;
                addRenderableWidget(Button.builder(
                                Component.literal("§7[" + c.kind + "] §f" + id),
                                b -> {
                                    parent.draft.mutableAction(action).projectile = id.toString();
                                    parent.draft.mutableAction(action).disabled = false;
                                    parent.back();
                                })
                        .bounds(8, 90 + i * 18, 240, 17).build());
            }
            addRenderableWidget(Button.builder(Component.literal("◀"), b -> {
                page--;
                rebuild();
            }).bounds(8, height - 26, 40, 20).build());
            addRenderableWidget(Button.builder(Component.literal((page + 1) + "/" + pages), b -> { })
                    .bounds(52, height - 26, 60, 20).build());
            addRenderableWidget(Button.builder(Component.literal("▶"), b -> {
                page++;
                rebuild();
            }).bounds(116, height - 26, 40, 20).build());
            addRenderableWidget(Button.builder(Component.literal("返回"), b -> parent.back())
                    .bounds(164, height - 26, 60, 20).build());
        }

        private void rebuild() {
            String keep = filter;
            int keepPage = page;
            init();
            filter = keep;
            page = keepPage;
            if (filterBox != null) {
                filterBox.setValue(keep);
            }
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
            renderBackground(g);
            g.drawCenteredString(font, "§f选择投掷物 §7（候选来自已注册实体，按原版投掷物基类筛选）", width / 2, 8, 0xFFFFFF);
            super.render(g, mouseX, mouseY, partial);
        }
    }

    /** 物品选择：给物品投掷物的渲染器提供 ItemStack（不选就退化成方块网格） */
    class ItemPickerScreen extends Screen {
        private final CrowdEditScreen parent;
        private final int action;
        private String filter = "";
        private int page;

        ItemPickerScreen(CrowdEditScreen parent, int action) {
            super(Component.literal("选择发射用物品"));
            this.parent = parent;
            this.action = action;
        }

        @Override
        protected void init() {
            clearWidgets();
            int perPage = 14;
            List<ResourceLocation> list = new ArrayList<>();
            for (var item : net.minecraftforge.registries.ForgeRegistries.ITEMS.getValues()) {
                ResourceLocation id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(item);
                if (id != null && (filter.isEmpty() || id.toString().contains(filter))) {
                    list.add(id);
                }
            }
            list.sort(java.util.Comparator.comparing(ResourceLocation::toString));
            int pages = Math.max(1, (list.size() + perPage - 1) / perPage);
            page = Math.max(0, Math.min(page, pages - 1));

            EditBox box = new EditBox(font, 8, 26, 200, 18, Component.literal("筛选"));
            box.setValue(filter);
            box.setResponder(t -> {
                filter = t;
                rebuild();
            });
            addRenderableWidget(box);

            for (int i = 0; i < perPage; i++) {
                int idx = page * perPage + i;
                if (idx >= list.size()) {
                    break;
                }
                ResourceLocation id = list.get(idx);
                addRenderableWidget(Button.builder(Component.literal("§f" + id), b -> {
                    parent.draft.mutableAction(action).renderItem = id.toString();
                    ProjectileRenderer.invalidateAll();
                    parent.back();
                }).bounds(8 + (i % 2) * 124, 48 + (i / 2) * 18, 122, 17).build());
            }
            addRenderableWidget(Button.builder(Component.literal("◀"), b -> {
                page--;
                rebuild();
            }).bounds(8, height - 26, 40, 20).build());
            addRenderableWidget(Button.builder(Component.literal("▶"), b -> {
                page++;
                rebuild();
            }).bounds(52, height - 26, 40, 20).build());
            addRenderableWidget(Button.builder(Component.literal("返回"), b -> parent.back())
                    .bounds(100, height - 26, 60, 20).build());
        }

        private void rebuild() {
            init();
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
            renderBackground(g);
            g.drawCenteredString(font, "§f发射用物品 §7（物品投掷物的渲染器要从这个物品取模型）", width / 2, 8, 0xFFFFFF);
            super.render(g, mouseX, mouseY, partial);
        }
    }

    /** 从选择子界面回到主界面 */
    void back() {
        minecraft.setScreen(this);
        rebuild();
    }

    private void applyCase2Legacy(int panelX, int y) {
        // 保留旧「攻击」页签的四个参数（近战/范围结算），已在 skillTab 第二页给出
    }
    private String shortName(ResourceLocation id) {
        return id.getPath().length() > 18 ? id.getPath().substring(0, 18) : id.getPath();
    }

    private Component modelLabel() {
        String m = draft.model == null || draft.model.isEmpty() ? "(用它自己的模型)" : draft.model;
        return Component.literal("模型：" + (m.length() > 24 ? m.substring(0, 24) : m));
    }

    /** 一行「动作」：− / 值 / +，自动 = -1 */
    private int actionRow(int x, int y, String label, int value, java.util.function.IntConsumer setter) {
        int max = previewDriver == null ? 0 : previewDriver.actionCount();
        String text = value < 0 ? "自动轮换" : (value == 0 ? "不播动作" : ("第 " + value + " 个 / 共 " + max));
        addRenderableWidget(Button.builder(Component.literal("§7" + label + "：§f" + text), b -> { })
                .bounds(x, y, 160, 18).build());
        addRenderableWidget(Button.builder(Component.literal("−"), b -> {
            int v = value - 1;
            setter.accept(Math.max(-1, v));
            previewAction = value <= 0 ? 0 : value;
            rebuild();
        }).bounds(x + 164, y, 32, 18).build());
        addRenderableWidget(Button.builder(Component.literal("+"), b -> {
            int v = value < 0 ? 0 : value + 1;
            if (max > 0 && v > max) {
                v = max;
            }
            setter.accept(v);
            previewAction = v;
            rebuild();
        }).bounds(x + 200, y, 36, 18).build());
        return y + ROW_H + 2;
    }

    /**
     * 数值行：**可直接输入数字** + `−/+` 微调 + `×` 恢复成「实体自身」。
     *
     * 输入框的响应器只写值、**不重建控件**（重建会打断输入、丢焦点）；只有点 `−/+`/`×` 才重建刷新显示。
     * 超出该字段允许范围时自动夹取，输入非法（空/非数字/「自身」）则视为「不覆盖」。
     */
    /** 「部件」页签：整体变换 + 逐部件（组件）的显隐/偏移/缩放 */
    private void partTab(int x, int y) {
        y = slider(x, y, "整体缩放X", draft.modelScaleX, 0.05F, 8.0F, 0.05F, v -> draft.modelScaleX = v);
        y = slider(x, y, "整体缩放Y", draft.modelScaleY, 0.05F, 8.0F, 0.05F, v -> draft.modelScaleY = v);
        y = slider(x, y, "整体缩放Z", draft.modelScaleZ, 0.05F, 8.0F, 0.05F, v -> draft.modelScaleZ = v);
        y = offsetRow(x, y, "整体偏移X", draft.modelOffsetX, v -> draft.modelOffsetX = v);
        y = offsetRow(x, y, "整体偏移Y", draft.modelOffsetY, v -> draft.modelOffsetY = v);
        y = offsetRow(x, y, "整体偏移Z", draft.modelOffsetZ, v -> draft.modelOffsetZ = v);

        List<String> names = partNames();
        if (names.isEmpty()) {
            addRenderableWidget(Button.builder(Component.literal("§7（该模型没有可编辑的部件）"), b -> { })
                    .bounds(x, y, 236, 18).build());
            return;
        }
        int idx = Math.max(0, Math.min(partIndex, names.size() - 1));
        partIndex = idx;
        String name = names.get(idx);
        addRenderableWidget(Button.builder(Component.literal("◀"), b -> {
            partIndex = idx - 1;
            rebuild();
        }).bounds(x, y, 22, 18).build());
        addRenderableWidget(Button.builder(Component.literal("§f" + trimName(name) + " §7(" + (idx + 1) + "/" + names.size() + ")"),
                        b -> { })
                .bounds(x + 24, y, 188, 18).build());
        addRenderableWidget(Button.builder(Component.literal("▶"), b -> {
            partIndex = idx + 1;
            rebuild();
        }).bounds(x + 214, y, 22, 18).build());
        y += ROW_H + 2;

        var edit = draft.mutableBone(name);
        String vis = edit.visible < 0 ? "不改" : (edit.visible == 0 ? "§c隐藏" : "§a显示");
        addRenderableWidget(Button.builder(Component.literal("§7显隐：§f" + vis), b -> {
            edit.visible = edit.visible < 0 ? 0 : (edit.visible == 0 ? 1 : -1);
            rebuild();
        }).bounds(x, y, 236, 18).build());
        y += ROW_H + 2;
        y = offsetRow(x, y, "偏移X", edit.offX, v -> edit.offX = v);
        y = offsetRow(x, y, "偏移Y", edit.offY, v -> edit.offY = v);
        y = offsetRow(x, y, "偏移Z", edit.offZ, v -> edit.offZ = v);
        y = slider(x, y, "缩放X", edit.scaleX, 0.05F, 8.0F, 0.05F, v -> edit.scaleX = v);
        y = slider(x, y, "缩放Y", edit.scaleY, 0.05F, 8.0F, 0.05F, v -> edit.scaleY = v);
        slider(x, y, "缩放Z", edit.scaleZ, 0.05F, 8.0F, 0.05F, v -> edit.scaleZ = v);
    }

    /**
     * 位置偏移行：允许真实负值，所以「未设置」用的是 OFFSET_UNSET 而不是 -1，
     * 清空（×）会恢复成「不偏移」。
     */
    private int offsetRow(int x, int y, String label, float value,
                          java.util.function.Consumer<Float> setter) {
        boolean unset = value == TypeOverride.OFFSET_UNSET;
        return dynamicRow(x, y, label, unset ? "不偏移" : trim(value), 94, text -> {
            String t = text.trim();
            if (t.isEmpty() || t.equals("不偏移")) {
                setter.accept(TypeOverride.OFFSET_UNSET);
                return;
            }
            try {
                setter.accept(Math.max(-32.0F, Math.min(32.0F, Float.parseFloat(t))));
            } catch (Throwable ignored) {
                // 输入中
            }
        }, () -> {
            setter.accept(unset ? -0.1F : value - 0.1F);
            rebuild();
        }, () -> {
            setter.accept(unset ? 0.1F : value + 0.1F);
            rebuild();
        }, () -> {
            setter.accept(TypeOverride.OFFSET_UNSET);
            rebuild();
        });
    }

    /** 通用一行：标签 + 输入框 + − + × */
    private int dynamicRow(int x, int y, String label, String shown, int labelW,
                           java.util.function.Consumer<String> onText,
                           Runnable minus, Runnable plus, Runnable clear) {
        addRenderableWidget(Button.builder(Component.literal("§7" + label), b -> { })
                .bounds(x, y, labelW, 18).build());
        EditBox box = new EditBox(font, x + labelW + 2, y, 62, 18, Component.literal(label));
        box.setMaxLength(12);
        box.setValue(shown);
        box.setResponder(onText);
        addRenderableWidget(box);
        addRenderableWidget(Button.builder(Component.literal("−"), b -> minus.run())
                .bounds(x + 160, y, 24, 18).build());
        addRenderableWidget(Button.builder(Component.literal("+"), b -> plus.run())
                .bounds(x + 186, y, 24, 18).build());
        addRenderableWidget(Button.builder(Component.literal("×"), b -> clear.run())
                .bounds(x + 212, y, 24, 18).build());
        return y + ROW_H + 2;
    }

    private String trimName(String name) {
        return name.length() > 16 ? name.substring(0, 16) : name;
    }

    /** 该类型实际使用的模型里的部件名（客户端按需烘焙一次，缓存起来） */
    private List<String> partNames() {
        if (partNames != null && selected != null && selected.equals(partNamesFor)) {
            return partNames;
        }
        partNames = new ArrayList<>();
        partNamesFor = selected;
        try {
            ResourceLocation modelId = TypeOverride.modelFor(selected);
            EntityType<?> t = BuiltInRegistries.ENTITY_TYPE.get(modelId);
            if (t != null) {
                dev.swordkingdom.horde.client.EntityMeshBaker.Mesh mesh =
                        dev.swordkingdom.horde.client.EntityMeshBaker.bake(t, selected);
                if (mesh != null && mesh.supported && mesh.pose != null) {
                    for (var p : mesh.pose.parts()) {
                        String n = p.name();
                        if (n != null && !n.isEmpty() && !partNames.contains(n)) {
                            partNames.add(n);
                        }
                    }
                    partNames.sort(String::compareTo);
                }
            }
        } catch (Throwable ignored) {
            // 烘焙失败就显示空列表
        }
        return partNames;
    }

    /** 预取用的行（浮点，允许负值）—— 见 offsetRow */
    private int slider(int x, int y, String label, float value, float min, float max, float step,
                       java.util.function.Consumer<Float> setter) {
        return numberRow(x, y, label, value, min, max, step, setter, false);
    }

    private int intSlider(int x, int y, String label, int value, int min, int max, int step,
                          java.util.function.IntConsumer setter) {
        return numberRow(x, y, label, value, min, max, step, v -> setter.accept(Math.round(v)), true);
    }

    private int numberRow(int x, int y, String label, float value, float min, float max, float step,
                          java.util.function.Consumer<Float> setter, boolean integer) {
        boolean unset = value < 0.0F;
        addRenderableWidget(Button.builder(Component.literal("§7" + label), b -> { })
                .bounds(x, y, 94, 18).build());

        EditBox box = new EditBox(font, x + 96, y, 62, 18, Component.literal(label));
        box.setMaxLength(12);
        box.setValue(unset ? "自身" : (integer ? String.valueOf((int) value) : trim(value)));
        box.setResponder(text -> {
            String t = text.trim();
            if (t.isEmpty() || t.equals("自身")) {
                setter.accept(-1.0F);
                return;
            }
            try {
                float v = Float.parseFloat(t);
                if (Float.isNaN(v) || Float.isInfinite(v)) {
                    return;
                }
                setter.accept(Math.max(min, Math.min(max, v)));
            } catch (Throwable ignored) {
                // 输入到一半（"-" 之类）先不动
            }
        });
        addRenderableWidget(box);

        addRenderableWidget(Button.builder(Component.literal("−"), b -> {
            setter.accept(unset ? -1.0F : Math.max(min, value - step));
            rebuild();
        }).bounds(x + 160, y, 24, 18).build());
        addRenderableWidget(Button.builder(Component.literal("+"), b -> {
            setter.accept(unset ? min : Math.min(max, value + step));
            rebuild();
        }).bounds(x + 186, y, 24, 18).build());
        addRenderableWidget(Button.builder(Component.literal("×"), b -> {
            setter.accept(-1.0F);
            rebuild();
        }).bounds(x + 212, y, 24, 18).build());
        return y + ROW_H + 2;
    }

    private static String trim(float v) {
        if (v == Math.round(v)) {
            return String.valueOf(Math.round(v));
        }
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private void openModelPicker() {
        if (modelChoices == null) {
            modelChoices = new ArrayList<>();
            for (EntityType<?> t : BuiltInRegistries.ENTITY_TYPE) {
                ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(t);
                if (id == null) {
                    continue;
                }
                // 只列「能走群集渲染」的：有生物模型 + LivingEntityRenderer
                if (dev.swordkingdom.horde.client.EntityMeshBaker.canRender(t)) {
                    modelChoices.add(id);
                }
            }
            modelChoices.sort(java.util.Comparator.comparing(ResourceLocation::toString));
        }
        minecraft.setScreen(new ModelPickerScreen(this));
    }

    /** 模型选择：列出所有「可直接调用其模型」的已注册实体类型（来自各模组自己的注册表） */
    class ModelPickerScreen extends Screen {
        private final CrowdEditScreen parent;
        private String filter = "";
        private int page;

        ModelPickerScreen(CrowdEditScreen parent) {
            super(Component.literal("选择模型"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            clearWidgets();
            int perPage = 14;
            List<ResourceLocation> list = new ArrayList<>();
            for (ResourceLocation id : modelChoices) {
                if (filter.isEmpty() || id.toString().contains(filter)) {
                    list.add(id);
                }
            }
            int pages = Math.max(1, (list.size() + perPage - 1) / perPage);
            page = Math.max(0, Math.min(page, pages - 1));
            addRenderableWidget(new EditBox(font, 8, 26, 200, 18, Component.literal("筛选")))
                    .setValue(filter);
            // EditBox 的值在 rebuild 时会丢，这里用简单做法：回车/失焦时读取
            for (int i = 0; i < perPage; i++) {
                int idx = page * perPage + i;
                if (idx >= list.size()) {
                    break;
                }
                ResourceLocation id = list.get(idx);
                addRenderableWidget(Button.builder(Component.literal(id.toString()), b -> {
                    parent.draft.model = id.toString();
                    parent.refreshPreview();
                    minecraft.setScreen(parent);
                    parent.rebuild();
                }).bounds(8 + (i % 2) * 130, 48 + (i / 2) * ROW_H, 126, 18).build());
            }
            addRenderableWidget(Button.builder(Component.literal("◀"), b -> {
                page--;
                rebuild();
            }).bounds(8, height - 26, 40, 20).build());
            addRenderableWidget(Button.builder(Component.literal("▶"), b -> {
                page++;
                rebuild();
            }).bounds(52, height - 26, 40, 20).build());
            addRenderableWidget(Button.builder(Component.literal("返回"), b -> minecraft.setScreen(parent))
                    .bounds(100, height - 26, 60, 20).build());
        }

        private void rebuild() {
            String keep = filter;
            init();
            filter = keep;
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
            renderBackground(g);
            g.drawCenteredString(font, "§f选择模型（内容来自已注册实体，含各模组自己的模型）",
                    width / 2, 8, 0xFFFFFF);
            super.render(g, mouseX, mouseY, partial);
        }
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        g.drawCenteredString(font, "§f群集实体实时配置 §7（全局共享 · 改完立刻生效）", width / 2, 8, 0xFFFFFF);
        super.render(g, mouseX, mouseY, partialTick);
        g.drawString(font, status, 8, height - 18, 0xA0A0A0);

        // 中间 3D 预览
        int cx = (8 + 150 + (width - 246)) / 2;
        int cy = height / 2 - 20;
        renderPreview(g, cx, cy, 60);
        g.drawCenteredString(font,
                preview == null ? "§7（没有可预览的模型）" : "§7预览：§f" + previewTypeId,
                cx, cy + 78, 0xFFFFFF);
        if (previewDriver != null && previewDriver.usable()) {
            g.drawCenteredString(font, "§7该实体提供 §f" + previewDriver.actionCount() + " §7个动作动画",
                    cx, cy + 92, 0xFFFFFF);
        }
    }

    /** 用原版实体渲染管线画真实实体：模型与动作都来自该模组自己 */
    private void renderPreview(GuiGraphics g, int x, int y, int scale) {
        if (preview == null) {
            return;
        }
        float rot = (preview.tickCount % 360) * 1.2F;
        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(x, y + scale * 1.2F, 120.0F);
        pose.scale(scale, scale, -scale);
        pose.mulPose(new Quaternionf().rotateZ((float) Math.PI));
        pose.mulPose(new Quaternionf().rotateX(-0.15F));
        Lighting.setupForEntityInInventory();
        EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        dispatcher.setRenderShadow(false);
        try {
            dispatcher.render(preview, 0.0D, 0.0D, 0.0D, rot, 1.0F, pose,
                    g.bufferSource(), 15728880);
        } catch (Throwable ignored) {
            // 某些模型在离屏/无世界状态下可能画不出来，界面照常用
        }
        dispatcher.setRenderShadow(true);
        g.flush();
        Lighting.setupFor3DItems();
        pose.popPose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
