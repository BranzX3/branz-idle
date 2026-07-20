package dev.branzx.idlefarm.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Online player picker with an offline-name search fallback. */
public final class AdminPlayerListMenu extends Menu {

    private static final int PAGE_SIZE = 36;
    private final GuiManager gui;
    private final int page;

    public AdminPlayerListMenu(Player viewer, GuiManager gui, int page) {
        super(viewer);
        this.gui = gui;
        this.page = Math.max(0, page);
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected Component title() {
        return Component.text("Admin • Players", NamedTextColor.DARK_GREEN);
    }

    @Override
    protected void build() {
        fill();
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        players.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        int start = page * PAGE_SIZE;
        for (int index = 0; index < PAGE_SIZE && start + index < players.size(); index++) {
            Player target = players.get(start + index);
            var data = gui.dataStore().getOnline(target.getUniqueId());
            set(index, Icon.head(gui.skinHeadCache(), target.getName())
                    .name(target.getName(), NamedTextColor.GREEN)
                    .loreComponents(List.of(
                            Ui.status("Online", NamedTextColor.GREEN),
                            Ui.line("Coins: " + Ui.num(data == null ? 0 : data.getBalance()),
                                    NamedTextColor.GOLD),
                            Ui.line("Nodes: " + gui.nodeStore().getByOwner(target.getUniqueId()).size(),
                                    NamedTextColor.GRAY),
                            Ui.click("เปิด Player Control"))).build(),
                    event -> new AdminPlayerMenu(viewer, gui, target).open());
        }

        set(48, Icon.of(Material.NAME_TAG)
                .name("ค้นหาผู้เล่น", NamedTextColor.AQUA)
                .lore("รองรับผู้เล่น offline ที่เคยเข้าเซิร์ฟเวอร์", NamedTextColor.GRAY).build(),
                event -> search());
        set(49, Icon.of(Material.ARROW).name("กลับ Admin Hub", NamedTextColor.GREEN).build(),
                event -> gui.openAdminHub(viewer));
        if (page > 0) {
            set(45, Icon.of(Material.ARROW).name("ก่อนหน้า", NamedTextColor.YELLOW).build(),
                    event -> new AdminPlayerListMenu(viewer, gui, page - 1).open());
        }
        if (start + PAGE_SIZE < players.size()) {
            set(53, Icon.of(Material.ARROW).name("ถัดไป", NamedTextColor.YELLOW).build(),
                    event -> new AdminPlayerListMenu(viewer, gui, page + 1).open());
        }
    }

    private void search() {
        gui.chatPrompt().request(viewer, "พิมพ์ชื่อผู้เล่น", raw -> {
            OfflinePlayer target = Bukkit.getOfflinePlayer(raw.trim());
            if (!target.isOnline() && !target.hasPlayedBefore()) {
                viewer.sendMessage(Component.text("ไม่พบผู้เล่น '" + raw.trim() + "'",
                        NamedTextColor.RED));
                open();
                return;
            }
            new AdminPlayerMenu(viewer, gui, target).open();
        }, this::open);
    }
}
