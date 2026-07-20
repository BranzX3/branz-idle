package dev.branzx.idlefarm.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.List;

/** Full player-scoped administration without requiring typed commands. */
public final class AdminPlayerMenu extends Menu {

    private final GuiManager gui;
    private final OfflinePlayer target;

    public AdminPlayerMenu(Player viewer, GuiManager gui, OfflinePlayer target) {
        super(viewer);
        this.gui = gui;
        this.target = target;
    }

    @Override
    protected int rows() {
        return 5;
    }

    @Override
    protected Component title() {
        return Component.text("Admin • " + name(), NamedTextColor.DARK_RED);
    }

    @Override
    protected void build() {
        fill();
        var data = gui.dataStore().getOnline(target.getUniqueId());
        set(4, Icon.head(name()).name(name(), NamedTextColor.YELLOW)
                .loreComponents(List.of(
                        Ui.status(target.isOnline() ? "Online" : "Offline",
                                target.isOnline() ? NamedTextColor.GREEN : NamedTextColor.GRAY),
                        Ui.line("UUID: " + target.getUniqueId(), NamedTextColor.DARK_GRAY),
                        Ui.line("Coins: " + (data == null ? "offline" : Ui.num(data.getBalance())),
                                NamedTextColor.GOLD),
                        Ui.line("Credits: " + gui.creditService().balance(target.getUniqueId()),
                                NamedTextColor.AQUA),
                        Ui.line("Nodes: " + gui.nodeStore().getByOwner(target.getUniqueId()).size(),
                                NamedTextColor.GREEN),
                        Ui.line("Node cap: " + gui.claimService().nodeCap(target.getUniqueId()),
                                NamedTextColor.GRAY))).build());

        if (can("idlefarm.admin.operations")) {
            set(10, Icon.of(Material.GRASS_BLOCK).name("Claims & Nodes", NamedTextColor.GREEN)
                    .lore("ดู Node ทั้งหมดของผู้เล่น", NamedTextColor.GRAY).build(),
                    event -> new AdminClaimsMenu(viewer, gui, target, 0).open());
        }

        if (can("idlefarm.admin.economy")) {
            set(12, Icon.of(Material.GOLD_INGOT).name("ปรับ Coins", NamedTextColor.GOLD)
                    .lore(target.isOnline() ? "เพิ่มหรือลด Coins" : "ผู้เล่นต้อง online",
                            target.isOnline() ? NamedTextColor.GRAY : NamedTextColor.RED).build(),
                    event -> adjustCoins());
            set(13, Icon.of(Material.AMETHYST_SHARD).name("ปรับ Credits", NamedTextColor.AQUA)
                    .lore("รองรับทั้งผู้เล่น online และ offline", NamedTextColor.GRAY).build(),
                    event -> adjustCredits());
            set(14, Icon.of(Material.REPEATER).name("ตั้ง Node Cap", NamedTextColor.YELLOW)
                    .lore("กำหนด base และ bonus cap", NamedTextColor.GRAY).build(),
                    event -> setCap());
            set(16, Icon.of(Material.CHEST_MINECART).name("มอบ Item", NamedTextColor.LIGHT_PURPLE)
                    .lore(target.isOnline() ? "เลือก material และจำนวน" : "ผู้เล่นต้อง online",
                            target.isOnline() ? NamedTextColor.GRAY : NamedTextColor.RED).build(),
                    event -> giveItem());
        }

        if (can("idlefarm.admin.audit")) {
            set(30, Icon.of(Material.WRITABLE_BOOK).name("Audit ของผู้เล่น", NamedTextColor.AQUA)
                    .lore("ดูรายการล่าสุด", NamedTextColor.GRAY).build(),
                    event -> AdminLogMenu.open(viewer, gui, target.getUniqueId(),
                            () -> new AdminPlayerMenu(viewer, gui, target).open()));
        }
        set(40, Icon.of(Material.ARROW).name("กลับรายชื่อผู้เล่น", NamedTextColor.GREEN).build(),
                event -> new AdminPlayerListMenu(viewer, gui, 0).open());
    }

    private void adjustCoins() {
        if (!target.isOnline()) {
            viewer.sendMessage(Component.text("การปรับ Coins ต้องให้ผู้เล่น online",
                    NamedTextColor.RED));
            redraw();
            return;
        }
        gui.chatPrompt().requestSignedNumber(viewer, "จำนวน Coins (+ เพิ่ม / - ลด)", amount ->
                AdminUiFlow.requireReason(viewer, gui, "ยืนยันปรับ Coins",
                        List.of(name() + ": " + amount),
                        reason -> {
                            gui.runAdmin(viewer, "give", "money", name(),
                                    Double.toString(amount), reason);
                            open();
                        }, this::open), this::open);
    }

    private void adjustCredits() {
        gui.chatPrompt().requestSignedNumber(viewer, "จำนวน Credits (+ เพิ่ม / - ลด)", amount -> {
            long credits = Math.round(amount);
            AdminUiFlow.requireReason(viewer, gui, "ยืนยันปรับ Credits",
                    List.of(name() + ": " + credits),
                    reason -> {
                        gui.runAdmin(viewer, "credits", name(), Long.toString(credits), reason);
                        open();
                    }, this::open);
        }, this::open);
    }

    private void setCap() {
        gui.chatPrompt().requestNumber(viewer, "Base node cap", base ->
                gui.chatPrompt().requestNumber(viewer, "Bonus node cap", bonus ->
                        AdminUiFlow.requireReason(viewer, gui, "ยืนยัน Node Cap",
                                List.of(name(), "Base: " + base.intValue(),
                                        "Bonus: " + bonus.intValue()),
                                reason -> {
                                    gui.runAdmin(viewer, "setcap", name(),
                                            Integer.toString(base.intValue()),
                                            Integer.toString(bonus.intValue()), reason);
                                    open();
                                }, this::open), this::open), this::open);
    }

    private void giveItem() {
        if (!target.isOnline()) {
            viewer.sendMessage(Component.text("การมอบ Item ต้องให้ผู้เล่น online",
                    NamedTextColor.RED));
            redraw();
            return;
        }
        gui.chatPrompt().request(viewer, "Material เช่น DIAMOND หรือ minecraft:diamond", material ->
                gui.chatPrompt().requestNumber(viewer, "จำนวน Item", count ->
                        AdminUiFlow.requireReason(viewer, gui, "ยืนยันมอบ Item",
                                List.of(name(), material, "Count: " + count.intValue()),
                                reason -> {
                                    int safeCount = Math.max(1, Math.min(64, count.intValue()));
                                    gui.runAdmin(viewer, "give", "item", name(), material,
                                            Integer.toString(safeCount), reason);
                                    open();
                                }, this::open), this::open), this::open);
    }

    private String name() {
        return target.getName() == null ? target.getUniqueId().toString() : target.getName();
    }

    private boolean can(String permission) {
        return viewer.hasPermission("idlefarm.admin") || viewer.hasPermission(permission);
    }
}
