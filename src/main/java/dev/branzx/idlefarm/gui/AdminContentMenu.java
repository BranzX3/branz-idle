package dev.branzx.idlefarm.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** Complete draft/validate/publish/rollback control surface for content. */
public final class AdminContentMenu extends Menu {

    private final GuiManager gui;

    public AdminContentMenu(Player viewer, GuiManager gui) {
        super(viewer);
        this.gui = gui;
    }

    @Override protected int rows() { return 4; }

    @Override protected Component title() {
        return Component.text("Admin • Content Control", NamedTextColor.DARK_PURPLE);
    }

    @Override
    protected void build() {
        var status = gui.dropTableService().status();
        set(4, Icon.of(Material.BOOKSHELF).name("Content Workflow", NamedTextColor.LIGHT_PURPLE)
                .loreComponents(List.of(
                        Ui.line("Draft → Validate → Publish → Rollback", NamedTextColor.GRAY),
                        Ui.status(status.draftDirty() ? "Draft has changes" : "Draft matches published",
                                status.draftDirty() ? NamedTextColor.YELLOW : NamedTextColor.GREEN),
                        Ui.line("Validation errors: " + status.validationErrors(), status.publishable()
                                ? NamedTextColor.GREEN : NamedTextColor.RED),
                        Ui.line("Rollback revisions: " + status.rollbackRevisions(), NamedTextColor.AQUA)))
                .build());
        set(10, Icon.of(Material.CHEST).name("Drop Pool Draft Editor", NamedTextColor.AQUA)
                .lore("Select a node type and bracket", NamedTextColor.GRAY).build(),
                event -> gui.runAdmin(viewer, "pool"));
        set(12, Icon.of(Material.COMPARATOR).name("Validate Draft", NamedTextColor.YELLOW)
                .lore("Pools plus source/sink/unlock/cap coverage", NamedTextColor.GRAY).build(),
                event -> validate());
        set(14, Icon.of(Material.LIME_CONCRETE).name("Publish Draft", NamedTextColor.GREEN)
                .lore(status.publishable() ? "Atomically replace runtime pools"
                                : "Fix validation errors first",
                        status.publishable() ? NamedTextColor.GRAY : NamedTextColor.RED).build(),
                event -> audited("Publish Drop Pools?", List.of("Runtime changes immediately"),
                        "publish"));
        set(16, Icon.of(Material.RECOVERY_COMPASS).name("Rollback Published", NamedTextColor.RED)
                .lore("Restore the latest valid published revision", NamedTextColor.GRAY).build(),
                event -> audited("Rollback Published Pools?",
                        List.of("Published and draft content will be restored"), "rollback"));
        set(20, Icon.of(Material.ORANGE_DYE).name("Discard Draft", NamedTextColor.GOLD)
                .lore("Reset draft to current published content", NamedTextColor.GRAY).build(),
                event -> audited("Discard Draft?", List.of("All unpublished edits are lost"),
                        "discard"));
        set(24, Icon.of(Material.STRUCTURE_BLOCK).name("Schematic Authoring",
                        NamedTextColor.LIGHT_PURPLE)
                .lore("Capture, anchors, animation and rebuild", NamedTextColor.GRAY).build(),
                event -> new AdminSchematicMenu(viewer, gui).open());
        set(31, Icon.of(Material.ARROW).name("Back to Admin Hub", NamedTextColor.GREEN).build(),
                event -> gui.openAdminHub(viewer));
    }

    private void validate() {
        List<String> errors = gui.dropTableService().validateDraft();
        int resources = gui.dropTableService().resourcePolicies(true).size();
        List<String> report = errors.isEmpty()
                ? List.of("Validation passed: all pools are publishable.",
                        resources + " resources have source/sink/unlock/cap metadata.")
                : errors;
        new AdminReportMenu(viewer,
                errors.isEmpty() ? "Validation • Passed" : "Validation • " + errors.size() + " errors",
                report, 0, this::open).open();
    }

    private void audited(String question, List<String> details, String action) {
        AdminUiFlow.requireReason(viewer, gui, question, details, reason -> {
            gui.runAdmin(viewer, "pool", action, reason);
            open();
        }, this::open);
    }
}
