package dev.branzx.idlefarm.gui;

import dev.branzx.idlefarm.node.ChunkKey;
import dev.branzx.idlefarm.node.NodeRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** Task-oriented admin entry point; dangerous mutations remain typed commands. */
public final class AdminHubMenu extends Menu {

    private final GuiManager gui;

    public AdminHubMenu(Player viewer, GuiManager gui) {
        super(viewer);
        this.gui = gui;
    }

    @Override
    protected int rows() {
        return 5;
    }

    @Override
    protected Component title() {
        return Component.text("IdleFarm • Admin Hub", NamedTextColor.DARK_RED);
    }

    @Override
    protected void build() {
        fill();
        set(4, Icon.head(viewer.getName())
                .name("Admin: " + viewer.getName(), NamedTextColor.RED)
                .loreComponents(List.of(
                        Ui.line("เลือกงานตาม flow ด้านล่าง", NamedTextColor.GRAY),
                        Ui.line("คำสั่งที่แก้ข้อมูลต้องมี reason", NamedTextColor.YELLOW)))
                .build());

        if (can("idlefarm.admin.operations")) {
            set(10, Icon.of(Material.SPYGLASS)
                    .name("ตรวจสอบ Node ปัจจุบัน", NamedTextColor.AQUA)
                    .loreComponents(nodeLore()).build(), event -> run("idle admin node"));
        }
        if (can("idlefarm.admin.content")) {
            set(12, Icon.of(Material.CHEST)
                    .name("Content & Drop Pools", NamedTextColor.LIGHT_PURPLE)
                    .loreComponents(List.of(
                            Ui.line("ดูและแก้ pool ตาม type/bracket", NamedTextColor.GRAY),
                            Ui.click("เปิด Content Browser"))).build(),
                    event -> run("idle admin pool"));
            set(14, Icon.of(Material.COMPARATOR)
                    .name("Validate Content", NamedTextColor.YELLOW)
                    .loreComponents(List.of(
                            Ui.line("ตรวจ material, weight และ fallback", NamedTextColor.GRAY),
                            Ui.click("รัน validation"))).build(),
                    event -> run("idle admin validate"));
        }
        if (can("idlefarm.admin.audit")) {
            set(16, Icon.of(Material.REDSTONE_TORCH)
                    .name("System Health", NamedTextColor.RED)
                    .loreComponents(List.of(
                            Ui.line("Economy และ telemetry summary", NamedTextColor.GRAY),
                            Ui.click("โหลด metrics"))).build(),
                    event -> run("idle admin metrics"));
        }

        if (can("idlefarm.admin.economy")) {
            set(28, Icon.of(Material.PLAYER_HEAD)
                    .name("Player & Economy", NamedTextColor.GREEN)
                    .loreComponents(List.of(
                            Ui.line("Node cap, Coins และ Credits", NamedTextColor.GRAY),
                            Ui.click("ดูคำสั่งพร้อมรูปแบบ"))).build(),
                    event -> showHelp("economy"));
        }
        if (can("idlefarm.admin.operations")) {
            set(30, Icon.of(Material.CLOCK)
                    .name("Events & Operations", NamedTextColor.GOLD)
                    .loreComponents(List.of(
                            Ui.line("Event และ Node operations", NamedTextColor.GRAY),
                            Ui.click("ดูคำสั่ง operations"))).build(),
                    event -> showHelp("operations"));
            set(34, Icon.of(Material.TNT)
                    .name("Danger Zone", NamedTextColor.DARK_RED)
                    .loreComponents(List.of(
                            Ui.line("งานย้อนกลับยากต้องพิมพ์ reason", NamedTextColor.RED),
                            Ui.click("ดูคำสั่ง danger"))).build(),
                    event -> showHelp("danger"));
        }
        if (can("idlefarm.admin.audit")) {
            set(32, Icon.of(Material.WRITABLE_BOOK)
                    .name("Audit", NamedTextColor.AQUA)
                    .loreComponents(List.of(
                            Ui.line("15 รายการล่าสุด", NamedTextColor.GRAY),
                            Ui.click("เปิด audit log"))).build(),
                    event -> run("idle admin audit"));
        }
        backToHub(gui);
    }

    private List<Component> nodeLore() {
        NodeRecord node = gui.nodeStore().getByChunk(new ChunkKey(
                viewer.getWorld().getName(),
                viewer.getLocation().getBlockX() >> 4,
                viewer.getLocation().getBlockZ() >> 4));
        if (node == null) {
            return List.of(
                    Ui.status("Chunk นี้ยังไม่ถูก claim", NamedTextColor.YELLOW),
                    Ui.line("ยืนใน Node แล้วเปิดเมนูใหม่", NamedTextColor.GRAY));
        }
        return List.of(
                Ui.status(node.getType() + " • " + node.getState(), NamedTextColor.GREEN),
                Ui.line("Node #" + node.getId() + " • Tier " + node.getTier(), NamedTextColor.GRAY),
                Ui.click("ดูรายละเอียดใน chat"));
    }

    private void run(String command) {
        viewer.closeInventory();
        viewer.performCommand(command);
    }

    private void showHelp(String category) {
        run("idle admin help " + category);
    }

    private boolean can(String permission) {
        return viewer.hasPermission("idlefarm.admin") || viewer.hasPermission(permission);
    }
}
