package dev.branzx.idlefarm.gui;

import dev.branzx.idlefarm.service.AuditService;
import dev.branzx.idlefarm.service.DropTableService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Admin drop-pool editor. Real items in a chest grid, weight in lore.
 *  - L/R click: weight -1/+1 (shift: ±10)
 *  - Drop key (Q): remove from pool
 *  - Click an empty slot while holding an item: add it (weight 10)
 * Every change writes only drops-draft.yml. Publish is a separate validated
 * and audited operation in Content Control.
 */
public final class PoolEditorMenu extends Menu {

    private final GuiManager gui;
    private final DropTableService drops;
    private final AuditService audit;
    private final String path; // e.g. "mining" or "mining.bracket-2"

    public PoolEditorMenu(Player viewer, GuiManager gui, DropTableService drops,
                          AuditService audit, String path) {
        super(viewer);
        this.gui = gui;
        this.drops = drops;
        this.audit = audit;
        this.path = path;
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected Component title() {
        return Component.text("Pool: " + path, NamedTextColor.DARK_RED);
    }

    @Override
    protected void build() {
        Map<String, Double> table = drops.editable(path);
        double total = table.values().stream().mapToDouble(Double::doubleValue).sum();

        int slot = 0;
        for (Map.Entry<String, Double> entry : table.entrySet()) {
            if (slot >= 45) {
                break;
            }
            Material material = Material.matchMaterial(entry.getKey());
            if (material == null) {
                continue;
            }
            double weight = entry.getValue();
            double percent = total <= 0 ? 0 : weight / total * 100.0;
            List<Component> lore = new ArrayList<>();
            lore.add(Ui.line("Weight " + Ui.num(weight) + "  (" + String.format("%.1f", percent) + "%)",
                    NamedTextColor.GOLD));
            lore.add(Ui.divider());
            lore.add(Ui.line("L/R click: -1/+1  (shift ±10)", NamedTextColor.GRAY));
            lore.add(Ui.line("Middle click: type exact weight", NamedTextColor.AQUA));
            lore.add(Ui.line("Drop key (Q): remove", NamedTextColor.RED));
            String materialKey = entry.getKey();
            set(slot, Icon.of(material).name(Ui.pretty(materialKey), NamedTextColor.WHITE)
                    .loreComponents(lore).build(), e -> adjust(materialKey, weight, e.getClick()));
            slot++;
        }

        // Remaining grid slots accept a held item to add it to the pool.
        for (int i = slot; i < 45; i++) {
            set(i, Icon.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE)
                    .name("Add item", NamedTextColor.GRAY)
                    .lore("Click while holding an item in", NamedTextColor.DARK_GRAY).build(),
                    e -> addHeld());
        }

        for (int i = 45; i < 54; i++) {
            set(i, Icon.filler());
        }
        set(49, Icon.of(Material.BARRIER).name("Close", NamedTextColor.RED)
                .lore("Changes are already saved", NamedTextColor.GRAY).build(),
                e -> viewer.closeInventory());
    }

    private void adjust(String material, double current, ClickType click) {
        if (click == ClickType.DROP || click == ClickType.CONTROL_DROP) {
            mutate("Remove " + Ui.pretty(material),
                    path + " remove " + material, () -> drops.setWeight(path, material, 0));
            return;
        }
        // Middle click: type an exact weight in chat.
        if (click == ClickType.MIDDLE) {
            gui.chatPrompt().requestNumber(viewer,
                    "Enter exact weight for " + Ui.pretty(material) + " (current " + Ui.num(current) + ")",
                    value -> {
                        mutate("Set exact weight",
                                path + " " + material + "=" + value,
                                () -> drops.setWeight(path, material, value));
                    },
                    this::open);
            return;
        }
        double delta = switch (click) {
            case LEFT -> -1;
            case RIGHT -> 1;
            case SHIFT_LEFT -> -10;
            case SHIFT_RIGHT -> 10;
            default -> 0;
        };
        if (delta == 0) {
            return;
        }
        double updated = Math.max(0, current + delta);
        mutate("Adjust " + Ui.pretty(material),
                path + " " + material + " " + current + "->" + updated,
                () -> drops.setWeight(path, material, updated));
    }

    private void addHeld() {
        var held = viewer.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            viewer.sendMessage(Component.text("Hold the item to add in your main hand.",
                    NamedTextColor.RED));
            return;
        }
        String material = held.getType().name().toLowerCase(Locale.ROOT);
        mutate("Add " + Ui.pretty(material),
                path + " add " + material + " w=10",
                () -> drops.setWeight(path, material, 10));
    }

    private void mutate(String question, String detail, BooleanSupplier mutation) {
        AdminUiFlow.requireReason(viewer, gui, question + "?",
                List.of("Draft only", detail), reason -> {
                    if (!mutation.getAsBoolean()) {
                        rejected();
                        open();
                        return;
                    }
                    String auditId = UUID.randomUUID().toString();
                    audit.logAdmin(viewer.getUniqueId(), auditId, reason,
                            "CONTENT_DRAFT_EDIT", detail);
                    viewer.sendMessage(Component.text("Draft updated | audit " + auditId,
                            NamedTextColor.GREEN));
                    open();
                }, this::open);
    }

    private void rejected() {
        viewer.sendMessage(Component.text(
                "Edit rejected: material or weight is invalid.",
                NamedTextColor.RED));
    }
}
