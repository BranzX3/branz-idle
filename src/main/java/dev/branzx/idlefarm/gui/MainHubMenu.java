package dev.branzx.idlefarm.gui;

import dev.branzx.idlefarm.node.NodeRecord;
import dev.branzx.idlefarm.service.PerkService;
import dev.branzx.idlefarm.storage.PlayerData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public final class MainHubMenu extends Menu {

    private final GuiManager gui;

    public MainHubMenu(Player viewer, GuiManager gui) {
        super(viewer);
        this.gui = gui;
    }

    @Override
    protected int rows() {
        return 4;
    }

    @Override
    protected Component title() {
        return Component.text("IdleFarm", NamedTextColor.DARK_GREEN);
    }

    @Override
    protected void build() {
        for (int i = 0; i < rows() * 9; i++) {
            set(i, Icon.filler());
        }

        PlayerData data = gui.dataStore().getOnline(viewer.getUniqueId());
        String currency = gui.plugin().getConfig().getString("currency-name", "Coins");
        List<NodeRecord> owned = gui.nodeStore().getByOwner(viewer.getUniqueId());
        long production = owned.stream().filter(n -> n.getType().isProduction()).count();
        long fullBuffers = owned.stream()
                .filter(n -> n.getType().isProduction() && "STORAGE_FULL".equals(n.getState())).count();
        long eventsReady = owned.stream().filter(n -> {
            var event = gui.explorationService().getEvent(n.getId());
            return event != null && ("AVAILABLE".equals(event.getState())
                    || "COMPLETED".equals(event.getState()));
        }).count();

        // Row 1: core sections.
        List<net.kyori.adventure.text.Component> nodeLore = new ArrayList<>();
        nodeLore.add(Ui.line(production + "/" + gui.claimService().nodeCap(viewer.getUniqueId())
                + " production nodes", NamedTextColor.GRAY));
        if (fullBuffers > 0) {
            nodeLore.add(Ui.line("⚠ " + fullBuffers + " buffer(s) FULL", NamedTextColor.RED));
        }
        if (eventsReady > 0) {
            nodeLore.add(Ui.line("★ " + eventsReady + " event(s) waiting", NamedTextColor.GOLD));
        }
        if (fullBuffers == 0 && eventsReady == 0) {
            nodeLore.add(Ui.line("● All running smoothly", NamedTextColor.GREEN));
        }
        set(10, Icon.of(Material.GRASS_BLOCK).name("Nodes", NamedTextColor.GREEN)
                .loreComponents(nodeLore)
                .build(), e -> gui.openNodes(viewer));

        set(11, Icon.of(Material.FILLED_MAP).name("Territory Map", NamedTextColor.GREEN)
                .lore("Claim & manage from a chunk map", NamedTextColor.GRAY).build(),
                e -> gui.openTerritoryMap(viewer));

        int stored = gui.warehouseService().total(viewer.getUniqueId());
        int capacity = gui.warehouseService().getCapacity(viewer.getUniqueId());
        set(13, Icon.of(Material.CHEST).name("Warehouse", NamedTextColor.GOLD)
                .loreComponents(List.of(Ui.bar("", capacity == 0 ? 0 : stored / (double) capacity,
                        stored >= capacity ? NamedTextColor.RED : NamedTextColor.GOLD,
                        Ui.num(stored) + "/" + Ui.num(capacity)))).build(),
                e -> gui.openWarehouse(viewer, viewer.getUniqueId()));

        set(15, Icon.of(Material.VILLAGER_SPAWN_EGG).name("Workers", NamedTextColor.AQUA)
                .lore("Hire & fuse workers", NamedTextColor.GRAY).build(),
                e -> gui.openWorkers(viewer));

        set(16, Icon.of(Material.BEACON).name("Boosters & Perks", NamedTextColor.LIGHT_PURPLE)
                .lore("Multipliers & QoL unlocks", NamedTextColor.GRAY).build(),
                e -> gui.openShop(viewer));

        if (gui.expeditionService() != null) {
            long mine = gui.expeditionService().contributionOf(viewer.getUniqueId());
            set(19, Icon.of(Material.CAMPFIRE).name("Global Expedition", NamedTextColor.GOLD)
                    .loreComponents(List.of(
                            Ui.line("Week " + gui.expeditionService().activeWeek(), NamedTextColor.GRAY),
                            Ui.line("Your contribution: " + Ui.num(mine), NamedTextColor.GOLD),
                            Ui.line("Weekly rewards for top ranks!", NamedTextColor.YELLOW)))
                    .build(), e -> gui.openExpedition(viewer));
        }

        // Row 2: quick actions + profile.
        if (gui.perkService() != null
                && gui.perkService().has(viewer.getUniqueId(), PerkService.REMOTE_COLLECT)) {
            set(21, Icon.of(Material.HOPPER_MINECART).name("Collect All", NamedTextColor.GOLD)
                    .lore("All node buffers → Warehouse", NamedTextColor.GRAY).build(),
                    e -> collectAll(owned));
        }

        double balance = data == null ? 0 : data.getBalance();
        int streak = gui.streakService() == null ? 0
                : gui.streakService().currentStreak(viewer.getUniqueId());
        List<net.kyori.adventure.text.Component> profileLore = new ArrayList<>();
        profileLore.add(Ui.line("⛁ " + Ui.num(balance) + " " + currency, NamedTextColor.GOLD));
        profileLore.add(Ui.line("⌚ " + Ui.time((data == null ? 0 : data.getTotalOnlineMinutes()) * 60_000L)
                + " online", NamedTextColor.AQUA));
        profileLore.add(Ui.line("🔥 Streak " + streak + " day(s)", NamedTextColor.RED));
        if (gui.boosterService() != null) {
            long moneyMs = gui.boosterService().remainingMillis(viewer.getUniqueId(), "money");
            long prodMs = gui.boosterService().remainingMillis(viewer.getUniqueId(), "production");
            if (moneyMs > 0) {
                profileLore.add(Ui.line("▲ Money boost " + Ui.time(moneyMs), NamedTextColor.LIGHT_PURPLE));
            }
            if (prodMs > 0) {
                profileLore.add(Ui.line("▲ Production boost " + Ui.time(prodMs), NamedTextColor.LIGHT_PURPLE));
            }
        }
        set(23, Icon.of(Material.SUNFLOWER).name("Profile", NamedTextColor.YELLOW)
                .loreComponents(profileLore).build());

        set(31, Icon.of(Material.BARRIER).name("Close", NamedTextColor.RED).build(),
                e -> viewer.closeInventory());
    }

    private void collectAll(List<NodeRecord> owned) {
        int moved = 0;
        for (NodeRecord node : owned) {
            if (!node.getType().isProduction() || node.getStorage().isEmpty()) {
                continue;
            }
            for (var entry : List.copyOf(node.getStorage().entrySet())) {
                int stored = gui.warehouseService().deposit(viewer.getUniqueId(),
                        entry.getKey(), entry.getValue());
                moved += stored;
                if (stored >= entry.getValue()) {
                    node.getStorage().remove(entry.getKey());
                } else {
                    if (stored > 0) {
                        node.getStorage().put(entry.getKey(), entry.getValue() - stored);
                    }
                    break;
                }
            }
            node.setState("ACTIVE");
            gui.nodeStore().updateProduction(node);
        }
        viewer.sendMessage(Component.text("Collected " + moved + " items to Warehouse.",
                NamedTextColor.GREEN));
        redraw();
    }

    private String formatAmount(double amount) {
        return amount == Math.floor(amount) ? String.valueOf((long) amount) : String.format("%.2f", amount);
    }
}
