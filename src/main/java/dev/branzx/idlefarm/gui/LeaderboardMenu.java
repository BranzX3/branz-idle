package dev.branzx.idlefarm.gui;

import dev.branzx.idlefarm.storage.PlayerData;
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
        return 4;
    }

    @Override
    protected Component title() {
        return Component.text("Leaderboard", NamedTextColor.GOLD);
    }

    @Override
    protected void build() {
        fill();
        backToHub(gui);

        if (top == null) {
            set(13, Icon.of(Material.CLOCK).name("Loading…", NamedTextColor.GRAY).build());
            loadThenRefresh();
            return;
        }

        String currency = gui.plugin().getConfig().getString("currency-name", "Coins");
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21};
        for (int i = 0; i < top.size() && i < slots.length; i++) {
            PlayerData data = top.get(i);
            Icon icon = switch (i) {
                case 0 -> Icon.of(Material.GOLD_BLOCK);
                case 1 -> Icon.of(Material.IRON_BLOCK);
                case 2 -> Icon.of(Material.COPPER_BLOCK);
                default -> Icon.head(data.getName());
            };
            boolean self = data.getUuid().equals(viewer.getUniqueId());
            set(slots[i], icon
                    .name("#" + (i + 1) + " " + data.getName(),
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
}
