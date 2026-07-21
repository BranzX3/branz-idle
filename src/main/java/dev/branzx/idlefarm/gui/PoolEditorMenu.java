package dev.branzx.idlefarm.gui;

import dev.branzx.idlefarm.service.AuditService;
import dev.branzx.idlefarm.service.DropTableService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Admin drop-pool editor. Real items in a chest grid, weight in lore.
 * Each item opens explicit weight controls that work on mouse, touch and
 * controller. Removal and exact-value changes remain audited.
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
            lore.add(Ui.line("Open explicit weight controls", NamedTextColor.AQUA));
            String materialKey = entry.getKey();
            set(slot, Icon.of(material).name(Ui.pretty(materialKey), NamedTextColor.WHITE)
                    .loreComponents(lore).build(), e -> openWeightActions(materialKey, weight));
            slot++;
        }

        if (slot < 45) {
            set(slot++, Icon.of(Material.LIME_DYE)
                    .name("Add Held Item", NamedTextColor.GREEN)
                    .lore("Adds the main-hand item with weight 10", NamedTextColor.GRAY).build(),
                    e -> addHeld());
        }
        set(49, Icon.of(Material.BARRIER).name("Close", NamedTextColor.RED)
                .lore("Changes are already saved", NamedTextColor.GRAY).build(),
                e -> viewer.closeInventory());
    }

    private void openWeightActions(String material, double current) {
        List<String> options = List.of("-10", "-1", "SET_EXACT", "+1", "+10", "REMOVE");
        new AdminOptionMenu(viewer, "Weight • " + Ui.pretty(material), options,
                option -> adjust(material, current, option), this::open).open();
    }

    private void adjust(String material, double current, String option) {
        if ("REMOVE".equals(option)) {
            mutate("Remove " + Ui.pretty(material),
                    path + " remove " + material, () -> drops.setWeight(path, material, 0));
            return;
        }
        if ("SET_EXACT".equals(option)) {
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
        double delta;
        try {
            delta = Double.parseDouble(option);
        } catch (NumberFormatException ignored) {
            return;
        }
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
