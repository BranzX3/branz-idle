package dev.branzx.idlefarm.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

public final class AdminMetricsMenu extends Menu {

    private final GuiManager gui;
    private final Map<String, Long> metrics;

    private AdminMetricsMenu(Player viewer, GuiManager gui, Map<String, Long> metrics) {
        super(viewer);
        this.gui = gui;
        this.metrics = metrics;
    }

    public static void open(Player viewer, GuiManager gui) {
        viewer.closeInventory();
        viewer.sendMessage(Component.text("กำลังโหลด Metrics…", NamedTextColor.GRAY));
        gui.plugin().getServer().getScheduler().runTaskAsynchronously(gui.plugin(), () -> {
            Map<String, Long> loaded = gui.gameDesignService().telemetrySummarySync();
            gui.plugin().getServer().getScheduler().runTask(gui.plugin(),
                    () -> new AdminMetricsMenu(viewer, gui, loaded).open());
        });
    }

    @Override protected int rows() { return 6; }

    @Override protected Component title() {
        return Component.text("Admin • Metrics (7 days)", NamedTextColor.DARK_AQUA);
    }

    @Override
    protected void build() {
        fill();
        int slot = 0;
        for (var entry : metrics.entrySet()) {
            if (slot >= 45) break;
            set(slot++, Icon.of(Material.COMPARATOR)
                    .name(Ui.pretty(entry.getKey()), NamedTextColor.AQUA)
                    .lore("Count: " + Ui.num(entry.getValue()), NamedTextColor.GRAY).build());
        }
        if (metrics.isEmpty()) {
            set(22, Icon.of(Material.CLOCK).name("ยังไม่มีข้อมูล", NamedTextColor.GRAY).build());
        }
        set(49, Icon.of(Material.ARROW).name("กลับ Admin Hub", NamedTextColor.GREEN).build(),
                event -> gui.openAdminHub(viewer));
    }
}
