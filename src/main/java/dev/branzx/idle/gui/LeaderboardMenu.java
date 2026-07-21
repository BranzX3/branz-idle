package dev.branzx.idle.gui;

import dev.branzx.idle.storage.PlayerData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;

/** Richest players. Data is read off-thread, then the menu fills in. */
public final class LeaderboardMenu extends Menu {

    private final GuiManager gui;
    private List<PlayerData> top;

    public LeaderboardMenu(Player viewer, GuiManager gui) {
        super(viewer);
        this.gui = gui;
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected Component title() {
        return Component.text(Lang.get("menu.leaderboard.title"), NamedTextColor.GOLD);
    }

    @Override
    protected void build() {
        navBar(Lang.get("menu.leaderboard.back"), () -> gui.openSocial(viewer));

        if (top == null) {
            set(SUMMARY_SLOT, Icon.of(Material.CLOCK)
                    .name(Lang.get("menu.leaderboard.loading"), NamedTextColor.GRAY).build());
            loadThenRefresh();
            return;
        }

        String currency = gui.plugin().getConfig().getString("currency-name", "Coins");
        set(SUMMARY_SLOT, Icon.of(Material.GOLD_INGOT)
                .name(Lang.get("menu.leaderboard.title"), NamedTextColor.GOLD)
                .loreComponents(List.of(
                        Lang.line("menu.leaderboard.summary", NamedTextColor.GRAY,
                                "count", top.size())))
                .build());
        for (int i = 0; i < top.size() && i < CONTENT_GRID.length; i++) {
            PlayerData data = top.get(i);
            Icon icon = switch (i) {
                case 0 -> Icon.of(Material.GOLD_BLOCK);
                case 1 -> Icon.of(Material.IRON_BLOCK);
                case 2 -> Icon.of(Material.COPPER_BLOCK);
                default -> Icon.head(gui.skinHeadCache(), data.getName());
            };
            boolean self = data.getUuid().equals(viewer.getUniqueId());
            set(CONTENT_GRID[i], icon
                    .name(Lang.get("menu.leaderboard.rank",
                                    "rank", i + 1, "player", data.getName()),
                            self ? NamedTextColor.GREEN : NamedTextColor.WHITE)
                    .loreComponents(List.of(Ui.line(Ui.num(data.getBalance()) + " " + currency,
                            NamedTextColor.GOLD))).build());
        }
    }

    private void loadThenRefresh() {
        int minMinutes = gui.plugin().getConfig().getInt("top-min-minutes", 1);
        new BukkitRunnable() {
            @Override
            public void run() {
                List<PlayerData> loaded = gui.dataStore().loadTopSync(10, minMinutes);
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        top = loaded;
                        if (viewer.getOpenInventory().getTopInventory().getHolder() == LeaderboardMenu.this) {
                            redraw();
                        }
                    }
                }.runTask(gui.plugin());
            }
        }.runTaskAsynchronously(gui.plugin());
    }

    @Override
    protected Material frameMaterial() {
        return Material.ORANGE_STAINED_GLASS_PANE;
    }
}
