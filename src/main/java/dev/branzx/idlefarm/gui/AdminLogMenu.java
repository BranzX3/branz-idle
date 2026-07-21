package dev.branzx.idlefarm.gui;

import dev.branzx.idlefarm.service.AuditService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/** Async audit query rendered as a paginated inventory. */
public final class AdminLogMenu extends Menu {

    private final GuiManager gui;
    private final List<AuditService.Entry> entries;
    private final int page;
    private final Runnable onBack;

    private AdminLogMenu(Player viewer, GuiManager gui, List<AuditService.Entry> entries,
                         int page, Runnable onBack) {
        super(viewer);
        this.gui = gui;
        this.entries = entries;
        this.page = page;
        this.onBack = onBack;
    }

    public static void open(Player viewer, GuiManager gui, UUID actorFilter, Runnable onBack) {
        viewer.closeInventory();
        viewer.sendMessage(Component.text("กำลังโหลด Audit…", NamedTextColor.GRAY));
        gui.plugin().getServer().getScheduler().runTaskAsynchronously(gui.plugin(), () -> {
            List<AuditService.Entry> loaded = gui.auditService().recentSync(actorFilter, 90);
            gui.plugin().getServer().getScheduler().runTask(gui.plugin(),
                    () -> new AdminLogMenu(viewer, gui, loaded, 0, onBack).open());
        });
    }

    @Override protected int rows() { return 6; }

    @Override protected Component title() {
        return Component.text("Admin • Audit Log", NamedTextColor.DARK_AQUA);
    }

    @Override
    protected void build() {
        int start = page * 45;
        for (int index = 0; index < 45 && start + index < entries.size(); index++) {
            AuditService.Entry entry = entries.get(start + index);
            set(index, Icon.of(Material.PAPER)
                    .name(entry.action(), NamedTextColor.YELLOW)
                    .loreComponents(List.of(
                            Ui.line(entry.createdAt(), NamedTextColor.GRAY),
                            Ui.line("Actor: " + shortActor(entry.actor()), NamedTextColor.AQUA),
                            Ui.line(entry.detail(), NamedTextColor.WHITE))).build());
        }
        set(49, Icon.of(Material.ARROW).name("กลับ", NamedTextColor.GREEN).build(),
                event -> onBack.run());
        if (page > 0) {
            set(45, Icon.of(Material.ARROW).name("ก่อนหน้า", NamedTextColor.YELLOW).build(),
                    event -> new AdminLogMenu(viewer, gui, entries, page - 1, onBack).open());
        }
        if (start + 45 < entries.size()) {
            set(53, Icon.of(Material.ARROW).name("ถัดไป", NamedTextColor.YELLOW).build(),
                    event -> new AdminLogMenu(viewer, gui, entries, page + 1, onBack).open());
        }
    }

    private String shortActor(String actor) {
        try {
            String name = gui.plugin().getServer().getOfflinePlayer(UUID.fromString(actor)).getName();
            return name == null ? actor.substring(0, 8) : name;
        } catch (IllegalArgumentException ignored) {
            return actor;
        }
    }
}
