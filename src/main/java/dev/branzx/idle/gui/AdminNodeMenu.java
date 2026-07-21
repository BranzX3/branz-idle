package dev.branzx.idle.gui;

import dev.branzx.idle.node.ChunkKey;
import dev.branzx.idle.node.NodeRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** Context-sensitive control panel for the chunk the admin is standing in. */
public final class AdminNodeMenu extends Menu {

    private final GuiManager gui;

    public AdminNodeMenu(Player viewer, GuiManager gui) {
        super(viewer);
        this.gui = gui;
    }

    @Override protected int rows() { return 5; }

    @Override protected Component title() {
        return Component.text("Admin • Node Control", NamedTextColor.DARK_RED);
    }

    @Override
    protected void build() {
        if (!viewer.hasPermission("idle.admin")
                && !viewer.hasPermission("idle.admin.operations")) {
            set(22, Icon.of(Material.BARRIER).name("ไม่มีสิทธิ์ Node Operations",
                    NamedTextColor.RED).build());
            set(40, Icon.of(Material.ARROW).name("กลับ Admin Hub", NamedTextColor.GREEN).build(),
                    event -> gui.openAdminHub(viewer));
            return;
        }
        NodeRecord node = currentNode();
        if (node == null) {
            set(22, Icon.of(Material.BARRIER).name("Chunk นี้ยังไม่ถูก claim", NamedTextColor.RED)
                    .lore("ยืนใน Node แล้วเปิดหน้านี้อีกครั้ง", NamedTextColor.GRAY).build());
            set(40, Icon.of(Material.ARROW).name("กลับ Admin Hub", NamedTextColor.GREEN).build(),
                    event -> gui.openAdminHub(viewer));
            return;
        }

        set(4, Icon.of(node.getType().isProduction() ? Material.GRASS_BLOCK : Material.OAK_DOOR)
                .name("#" + node.getId() + " " + node.getType(), NamedTextColor.GREEN)
                .loreComponents(List.of(
                        Ui.status(node.getState(), NamedTextColor.YELLOW),
                        Ui.line("Owner: " + node.getOwnerUuid(), NamedTextColor.GRAY),
                        Ui.line("Tier " + node.getTier() + " • Exploration Lv."
                                + node.getExplorationLevel(), NamedTextColor.AQUA),
                        Ui.line(node.getChunk().world() + " " + node.getChunk().x()
                                + "," + node.getChunk().z(), NamedTextColor.DARK_GRAY))).build());

        if (node.getType().isProduction()) {
            var event = gui.explorationService().getEvent(node.getId());
            set(10, Icon.of(Material.FIREWORK_STAR).name("Spawn Event", NamedTextColor.GOLD)
                    .lore(event == null ? "สร้าง Exploration event" : "มี event อยู่แล้ว",
                            event == null ? NamedTextColor.GRAY : NamedTextColor.RED).build(),
                    click -> openEventTypes(node));
            set(11, Icon.of(Material.BARRIER).name("Cancel Event", NamedTextColor.RED)
                    .lore(event == null ? "ไม่มี event" : event.getState(), NamedTextColor.GRAY).build(),
                    click -> cancelEvent(node));
            set(12, Icon.of(Material.EXPERIENCE_BOTTLE).name("Set Exploration Level",
                            NamedTextColor.LIGHT_PURPLE)
                    .lore("กำหนด Lv. 1-100+", NamedTextColor.GRAY).build(),
                    click -> setLevel(node));
            set(13, Icon.of(Material.PAPER).name("NPC Status", NamedTextColor.AQUA)
                    .lore("ดู NPC, state และตำแหน่งปัจจุบัน", NamedTextColor.GRAY).build(),
                    click -> {
                        List<String> lines = gui.npcManager().describeNode(node.getId());
                        if (lines.isEmpty()) lines = List.of("No active NPCs at this node.");
                        new AdminReportMenu(viewer, "NPC Status • #" + node.getId(),
                                lines, 0, this::open).open();
                    });
            set(14, Icon.of(Material.VILLAGER_SPAWN_EGG).name("Refresh NPC", NamedTextColor.GREEN)
                    .lore("Respawn worker NPC ของ Node", NamedTextColor.GRAY).build(),
                    click -> AdminUiFlow.requireReason(viewer, gui, "Refresh NPCs?",
                            List.of("Node #" + node.getId()), reason -> {
                                gui.runAdmin(viewer, "npc", "refresh", reason);
                                open();
                            }, this::open));
            set(15, Icon.of(Material.LEVER).name("NPC State Override", NamedTextColor.YELLOW)
                    .lore("Working / Idle / Stop / Clear", NamedTextColor.GRAY).build(),
                    click -> openNpcStates());
            set(16, Icon.of(Material.STRUCTURE_BLOCK).name("Rebuild Schematic", NamedTextColor.AQUA)
                    .lore("วาง building ใหม่จาก schematic", NamedTextColor.GRAY).build(),
                    click -> AdminUiFlow.requireReason(viewer, gui, "Rebuild Node?",
                            List.of("Node #" + node.getId(), "Terrain snapshot is untouched"),
                            reason -> {
                                gui.runAdmin(viewer, "schem", "rebuild", reason);
                                open();
                            }, this::open));
        }

        set(31, Icon.of(Material.TNT).name("Force Unclaim", NamedTextColor.DARK_RED)
                .lore("ต้องระบุ reason และยืนยัน", NamedTextColor.RED).build(),
                click -> forceUnclaim(node));
        set(40, Icon.of(Material.ARROW).name("กลับ Admin Hub", NamedTextColor.GREEN).build(),
                event -> gui.openAdminHub(viewer));
    }

    private void openEventTypes(NodeRecord node) {
        List<String> types = gui.explorationService().eventTypes();
        new AdminOptionMenu(viewer, "เลือก Event Type", types, type ->
                AdminUiFlow.requireReason(viewer, gui, "Spawn " + type + "?",
                        List.of("Node #" + node.getId()), reason -> {
                            gui.runAdmin(viewer, "event", "spawn", type, reason);
                            open();
                        }, this::open), this::open).open();
    }

    private void cancelEvent(NodeRecord node) {
        if (gui.explorationService().getEvent(node.getId()) == null) {
            viewer.sendMessage(Component.text("Node นี้ไม่มี event", NamedTextColor.RED));
            redraw();
            return;
        }
        AdminUiFlow.requireReason(viewer, gui, "Cancel Event?",
                List.of("Node #" + node.getId()), reason -> {
                    gui.runAdmin(viewer, "event", "cancel", reason);
                    open();
                }, this::open);
    }

    private void setLevel(NodeRecord node) {
        gui.chatPrompt().requestNumber(viewer, "Exploration level ใหม่", value ->
                AdminUiFlow.requireReason(viewer, gui, "Set Exploration Level?",
                        List.of("Node #" + node.getId(), "Level: " + value.intValue()),
                        reason -> {
                            gui.runAdmin(viewer, "explevel", Integer.toString(value.intValue()), reason);
                            open();
                        }, this::open), this::open);
    }

    private void openNpcStates() {
        new AdminOptionMenu(viewer, "NPC State",
                List.of("working", "idle", "stop", "clear"), state -> {
                    AdminUiFlow.requireReason(viewer, gui, "Override NPC state?",
                            List.of("State: " + state), reason -> {
                                gui.runAdmin(viewer, "npc", "state", state, reason);
                                open();
                            }, this::open);
                }, this::open).open();
    }

    private void forceUnclaim(NodeRecord node) {
        AdminUiFlow.requireReason(viewer, gui, "Force Unclaim?",
                List.of("Node #" + node.getId(), node.getChunk().world() + " "
                        + node.getChunk().x() + "," + node.getChunk().z(),
                        "This cannot be undone"), reason -> {
                    gui.runAdmin(viewer, "forceunclaim", node.getChunk().world(),
                            Integer.toString(node.getChunk().x()),
                            Integer.toString(node.getChunk().z()), reason);
                    gui.openAdminHub(viewer);
                }, this::open);
    }

    private NodeRecord currentNode() {
        return gui.nodeStore().getByChunk(new ChunkKey(viewer.getWorld().getName(),
                viewer.getLocation().getBlockX() >> 4,
                viewer.getLocation().getBlockZ() >> 4));
    }
}
