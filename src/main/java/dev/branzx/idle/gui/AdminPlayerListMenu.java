package dev.branzx.idle.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Online player browser with offline-name search. */
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
        return Component.text("Admin | Players", NamedTextColor.DARK_GREEN);
    }

    @Override
    protected void build() {
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        players.sort(Comparator.comparing(Player::getName,
                String.CASE_INSENSITIVE_ORDER));
        int start = page * PAGE_SIZE;
        for (int index = 0;
             index < PAGE_SIZE && start + index < players.size(); index++) {
            Player target = players.get(start + index);
            var data = gui.dataStore().getOnline(target.getUniqueId());
            set(index, Icon.head(gui.skinHeadCache(), target.getName())
                    .name(target.getName(), NamedTextColor.GREEN)
                    .loreComponents(List.of(
                            Ui.status("ONLINE", NamedTextColor.GREEN),
                            Ui.line("Coins: "
                                    + Ui.num(data == null ? 0 : data.getBalance()),
                                    NamedTextColor.GOLD),
                            Ui.line("Nodes: " + gui.nodeStore()
                                    .getByOwner(target.getUniqueId()).size(),
                                    NamedTextColor.GRAY),
                            Ui.click("open Player Control")))
                    .build(), event ->
                            new AdminPlayerMenu(viewer, gui, target).open());
        }

        set(48, Icon.of(Material.NAME_TAG)
                .name("Search Player", NamedTextColor.AQUA)
                .loreComponents(List.of(
                        Ui.line("Supports known offline players", NamedTextColor.GRAY),
                        Ui.click("enter a player name")))
                .build(), event -> search());
        backButton(49, "Admin Hub", () -> gui.openAdminHub(viewer));
        if (page > 0) {
            set(45, Icon.of(Material.ARROW)
                    .name("Previous Page", NamedTextColor.YELLOW).build(),
                    event -> new AdminPlayerListMenu(viewer, gui, page - 1).open());
        }
        if (start + PAGE_SIZE < players.size()) {
            set(53, Icon.of(Material.ARROW)
                    .name("Next Page", NamedTextColor.YELLOW).build(),
                    event -> new AdminPlayerListMenu(viewer, gui, page + 1).open());
        }
    }

    private void search() {
        gui.chatPrompt().request(viewer, "Enter a player name", raw -> {
            OfflinePlayer target = Bukkit.getOfflinePlayer(raw.trim());
            if (!target.isOnline() && !target.hasPlayedBefore()) {
                viewer.sendMessage(Component.text(
                        "Player '" + raw.trim() + "' was not found.",
                        NamedTextColor.RED));
                open();
                return;
            }
            new AdminPlayerMenu(viewer, gui, target).open();
        }, this::open);
    }
}
