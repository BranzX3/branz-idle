package dev.branzx.idle.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** Generic read-only paginated report used by validation and diagnostics. */
public final class AdminReportMenu extends Menu {

    private final String heading;
    private final List<String> lines;
    private final int page;
    private final Runnable onBack;

    public AdminReportMenu(Player viewer, String heading, List<String> lines,
                           int page, Runnable onBack) {
        super(viewer);
        this.heading = heading;
        this.lines = lines;
        this.page = page;
        this.onBack = onBack;
    }

    @Override protected int rows() { return 6; }
    @Override protected Component title() {
        return Component.text(heading, NamedTextColor.DARK_AQUA);
    }

    @Override
    protected void build() {
        int start = page * 45;
        for (int index = 0; index < 45 && start + index < lines.size(); index++) {
            String line = lines.get(start + index);
            set(index, Icon.of(Material.PAPER)
                    .name("#" + (start + index + 1), NamedTextColor.YELLOW)
                    .lore(line, NamedTextColor.GRAY).build());
        }
        set(49, Icon.of(Material.ARROW).name("กลับ", NamedTextColor.GREEN).build(),
                event -> onBack.run());
        if (page > 0) {
            set(45, Icon.of(Material.ARROW).name("ก่อนหน้า", NamedTextColor.YELLOW).build(),
                    event -> new AdminReportMenu(viewer, heading, lines, page - 1, onBack).open());
        }
        if (start + 45 < lines.size()) {
            set(53, Icon.of(Material.ARROW).name("ถัดไป", NamedTextColor.YELLOW).build(),
                    event -> new AdminReportMenu(viewer, heading, lines, page + 1, onBack).open());
        }
    }
}
