package dev.branzx.idlefarm.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

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
        fill();
        set(4, Icon.of(Material.BOOKSHELF).name("Content Workflow", NamedTextColor.LIGHT_PURPLE)
                .loreComponents(List.of(
                        Ui.line("Edit → Validate → Publish/Reload", NamedTextColor.GRAY),
                        Ui.line("Rollback พร้อม confirmation", NamedTextColor.YELLOW))).build());
        set(10, Icon.of(Material.CHEST).name("Drop Pool Editor", NamedTextColor.AQUA)
                .lore("เลือก type และ bracket ผ่าน GUI", NamedTextColor.GRAY).build(),
                event -> gui.runAdmin(viewer, "pool"));
        set(12, Icon.of(Material.COMPARATOR).name("Validate Content", NamedTextColor.YELLOW)
                .lore("แสดงผล validation ใน GUI", NamedTextColor.GRAY).build(),
                event -> validate());
        set(14, Icon.of(Material.RECOVERY_COMPASS).name("Rollback Pools", NamedTextColor.RED)
                .lore("ย้อนกลับ revision ล่าสุดที่ valid", NamedTextColor.GRAY).build(),
                event -> new ConfirmMenu(viewer, "Rollback Drop Pools?",
                        List.of("Restore latest valid revision"),
                        () -> {
                            gui.runAdmin(viewer, "pool", "rollback");
                            open();
                        }, this::open).open());
        set(16, Icon.of(Material.STRUCTURE_BLOCK).name("Schematic Authoring",
                        NamedTextColor.LIGHT_PURPLE)
                .lore("Capture, anchors, animation และ rebuild", NamedTextColor.GRAY).build(),
                event -> new AdminSchematicMenu(viewer, gui).open());
        set(22, Icon.of(Material.LIME_DYE).name("Reload Content", NamedTextColor.GREEN)
                .lore("Reload config, pools และ schematics", NamedTextColor.GRAY).build(),
                event -> new ConfirmMenu(viewer, "Reload IdleFarm Content?",
                        List.of("Unsaved external edits may be replaced"),
                        () -> {
                            gui.runAdmin(viewer, "reload");
                            open();
                        }, this::open).open());
        set(31, Icon.of(Material.ARROW).name("กลับ Admin Hub", NamedTextColor.GREEN).build(),
                event -> gui.openAdminHub(viewer));
    }

    private void validate() {
        List<String> errors = gui.dropTableService().validate();
        List<String> report = errors.isEmpty()
                ? List.of("Validation passed: all pools are publishable.")
                : errors;
        new AdminReportMenu(viewer,
                errors.isEmpty() ? "Validation • Passed" : "Validation • " + errors.size() + " errors",
                report, 0, this::open).open();
    }
}
