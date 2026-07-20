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
        return 6;
    }

    @Override
    protected Component title() {
        return Component.text("IdleFarm", NamedTextColor.DARK_GREEN);
    }

    @Override
    protected void build() {
        fill();

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

        setPriority(owned, production, fullBuffers, eventsReady);

        // Primary player loop: territory -> production -> storage -> workers -> goals.
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
        set(20, Icon.of(Material.GRASS_BLOCK).name("Nodes", NamedTextColor.GREEN)
                .loreComponents(nodeLore)
                .build(), e -> gui.openNodes(viewer));

        set(19, Icon.of(Material.FILLED_MAP).name("Territory Map", NamedTextColor.GREEN)
                .lore("Claim & manage from a chunk map", NamedTextColor.GRAY).build(),
                e -> gui.openTerritoryMap(viewer));

        int stored = gui.warehouseService().total(viewer.getUniqueId());
        int capacity = gui.warehouseService().getCapacity(viewer.getUniqueId());
        set(22, Icon.of(Material.CHEST).name("Warehouse", NamedTextColor.GOLD)
                .loreComponents(List.of(Ui.bar("", capacity == 0 ? 0 : stored / (double) capacity,
                        stored >= capacity ? NamedTextColor.RED : NamedTextColor.GOLD,
                        Ui.num(stored) + "/" + Ui.num(capacity)))).build(),
                e -> gui.openWarehouse(viewer, viewer.getUniqueId()));

        set(23, Icon.of(Material.VILLAGER_SPAWN_EGG).name("Workers", NamedTextColor.AQUA)
                .lore("Hire & fuse workers", NamedTextColor.GRAY).build(),
                e -> gui.openWorkers(viewer));

        set(32, Icon.of(Material.BEACON).name("Boosters & Perks", NamedTextColor.LIGHT_PURPLE)
                .lore("Multipliers & QoL unlocks", NamedTextColor.GRAY).build(),
                e -> gui.openShop(viewer));

        if (gui.expeditionService() != null) {
            long mine = gui.expeditionService().contributionOf(viewer.getUniqueId());
            set(28, Icon.of(Material.CAMPFIRE).name("Global Expedition", NamedTextColor.GOLD)
                    .loreComponents(List.of(
                            Ui.line("Week " + gui.expeditionService().activeWeek(), NamedTextColor.GRAY),
                            Ui.line("Your contribution: " + Ui.num(mine), NamedTextColor.GOLD),
                            Ui.line("Weekly rewards for top ranks!", NamedTextColor.YELLOW)))
                    .build(), e -> gui.openExpedition(viewer));
        }

        if (gui.gameDesignService() != null) {
            Long focusedId = gui.gameDesignService().focusedNode(viewer.getUniqueId());
            NodeRecord focused = focusedId == null ? null : owned.stream()
                    .filter(node -> node.getId() == focusedId).findFirst().orElse(null);
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            if (focused == null) {
                lore.add(Ui.line("Recommended: select a Focused Node", NamedTextColor.YELLOW));
            } else {
                lore.add(Ui.line("Focused " + focused.getType() + " Lv."
                        + focused.getExplorationLevel(), NamedTextColor.AQUA));
                lore.add(Ui.line("Daily commissions, Journal & Projects", NamedTextColor.GRAY));
            }
            set(24, Icon.of(Material.WRITABLE_BOOK).name("Pioneer Chronicle", NamedTextColor.AQUA)
                    .loreComponents(lore).build(), e -> gui.openProgress(viewer));
        }

        set(29, Icon.of(Material.OAK_HANGING_SIGN).name("Trust", NamedTextColor.GREEN)
                .lore("Let friends into your territory", NamedTextColor.GRAY).build(),
                e -> gui.openTrust(viewer));

        set(30, Icon.of(Material.GOLD_INGOT).name("Leaderboard", NamedTextColor.GOLD)
                .lore("Richest players on the server", NamedTextColor.GRAY).build(),
                e -> gui.openLeaderboard(viewer));

        // Collect-all convenience (perk-gated).
        if (gui.perkService() != null
                && gui.perkService().has(viewer.getUniqueId(), PerkService.REMOTE_COLLECT)) {
            set(31, Icon.of(Material.HOPPER_MINECART).name("Collect All", NamedTextColor.GOLD)
                    .lore("All node buffers → Warehouse", NamedTextColor.GRAY).build(),
                    e -> collectAll(owned));
        }

        double balance = data == null ? 0 : data.getBalance();
        int streak = gui.streakService() == null ? 0
                : gui.streakService().currentStreak(viewer.getUniqueId());
        List<net.kyori.adventure.text.Component> profileLore = new ArrayList<>();
        profileLore.add(Ui.line("◆ " + Ui.num(balance) + " " + currency, NamedTextColor.GOLD));
        profileLore.add(Ui.line("☀ " + Ui.time((data == null ? 0 : data.getTotalOnlineMinutes()) * 60_000L)
                + " online", NamedTextColor.AQUA));
        profileLore.add(Ui.line("⚡ Streak " + streak + " day(s)", NamedTextColor.RED));
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
        profileLore.add(Ui.click("แสดงข้อมูลสรุปใน chat"));
        set(4, Icon.of(Material.SUNFLOWER).name(viewer.getName(), NamedTextColor.YELLOW)
                .loreComponents(profileLore).build(),
                event -> viewer.performCommand("idle balance"));

        if (gui.hasAdminAccess(viewer)) {
            set(34, Icon.of(Material.COMMAND_BLOCK).name("Admin Hub", NamedTextColor.RED)
                    .lore("เครื่องมือดูแลระบบแยกตามงาน", NamedTextColor.GRAY).build(),
                    event -> gui.openAdminHub(viewer));
        }
        closeButton();
    }

    private void setPriority(List<NodeRecord> owned, long production, long fullBuffers, long eventsReady) {
        if (production == 0) {
            set(13, Icon.of(Material.COMPASS)
                    .name("แนะนำ: สร้าง Production Node", NamedTextColor.YELLOW)
                    .loreComponents(List.of(
                            Ui.line("เริ่มจาก Mining, Farming หรือ Woodcutting", NamedTextColor.GRAY),
                            Ui.click("เปิด Territory Map"))).build(),
                    event -> gui.openTerritoryMap(viewer));
            return;
        }
        if (fullBuffers > 0) {
            set(13, Icon.of(Material.REDSTONE)
                    .name("ต้องจัดการ: Buffer เต็ม " + fullBuffers + " จุด", NamedTextColor.RED)
                    .loreComponents(List.of(
                            Ui.line("การผลิตหยุดจนกว่าจะเก็บของ", NamedTextColor.GRAY),
                            Ui.click("เปิด Nodes"))).build(),
                    event -> gui.openNodes(viewer));
            return;
        }
        if (eventsReady > 0) {
            set(13, Icon.of(Material.FIREWORK_STAR)
                    .name("พร้อมเล่น: Exploration " + eventsReady + " จุด", NamedTextColor.GOLD)
                    .loreComponents(List.of(
                            Ui.line("มี event รอเริ่มหรือ loot รอรับ", NamedTextColor.GRAY),
                            Ui.click("เปิด Nodes"))).build(),
                    event -> gui.openNodes(viewer));
            return;
        }
        Long focusedId = gui.gameDesignService() == null ? null
                : gui.gameDesignService().focusedNode(viewer.getUniqueId());
        NodeRecord focused = focusedId == null ? null : owned.stream()
                .filter(node -> node.getId() == focusedId).findFirst().orElse(null);
        if (focused == null) {
            set(13, Icon.of(Material.WRITABLE_BOOK)
                    .name("แนะนำ: เลือก Focused Node", NamedTextColor.YELLOW)
                    .loreComponents(List.of(
                            Ui.line("เพื่อเปิด Daily Commission และเป้าหมาย", NamedTextColor.GRAY),
                            Ui.click("เปิด Chronicle"))).build(),
                    event -> gui.openProgress(viewer));
            return;
        }
        set(13, Icon.of(Material.LIME_DYE)
                .name("กำลังผลิตปกติ", NamedTextColor.GREEN)
                .loreComponents(List.of(
                        Ui.line(focused.getType() + " • Lv." + focused.getExplorationLevel(),
                                NamedTextColor.AQUA),
                        Ui.click("เปิด Focused Node"))).build(),
                event -> gui.openNodeDetail(viewer, focused));
    }

    private void collectAll(List<NodeRecord> owned) {
        int moved = 0;
        for (NodeRecord node : owned) {
            if (!node.getType().isProduction() || node.getStorage().isEmpty()) {
                continue;
            }
            int nodeMoved = gui.warehouseService().collectNode(node);
            moved += nodeMoved;
            node.setState("ACTIVE");
            gui.nodeStore().updateProduction(node);
            if (gui.gameDesignService() != null) {
                gui.gameDesignService().onBufferCollected(node, nodeMoved);
            }
        }
        viewer.sendMessage(Component.text("Collected " + moved + " items to Warehouse.",
                NamedTextColor.GREEN));
        redraw();
    }

    private String formatAmount(double amount) {
        return amount == Math.floor(amount) ? String.valueOf((long) amount) : String.format("%.2f", amount);
    }
}
