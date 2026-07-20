package dev.branzx.idlefarm.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** GUI wrapper for position-sensitive schematic authoring actions. */
public final class AdminSchematicMenu extends Menu {

    private final GuiManager gui;

    public AdminSchematicMenu(Player viewer, GuiManager gui) {
        super(viewer);
        this.gui = gui;
    }

    @Override protected int rows() { return 5; }
    @Override protected Component title() {
        return Component.text("Admin • Schematic Authoring", NamedTextColor.DARK_PURPLE);
    }

    @Override
    protected void build() {
        fill();
        set(4, Icon.of(Material.STRUCTURE_BLOCK).name("Position-sensitive tools",
                        NamedTextColor.LIGHT_PURPLE)
                .loreComponents(List.of(
                        Ui.line("ยืนใน chunk/building ก่อนกด action", NamedTextColor.GRAY),
                        Ui.line("Anchors ใช้ตำแหน่งเท้าปัจจุบัน", NamedTextColor.YELLOW))).build());
        set(10, Icon.of(Material.SPYGLASS).name("Capture Chunk", NamedTextColor.AQUA)
                .lore("กำหนด ID, baseY และความสูง", NamedTextColor.GRAY).build(),
                event -> promptId("Schematic ID สำหรับ capture",
                        id -> gui.chatPrompt().requestSignedNumber(viewer, "Base Y", baseY ->
                                gui.chatPrompt().requestNumber(viewer, "ความสูงของ capture", height ->
                                        confirmRun("Capture '" + id + "'?",
                                                List.of("Whole chunk", "BaseY: " + baseY.intValue(),
                                                        "Height: " + safeHeight(height.intValue())),
                                                "capture", id, Integer.toString(baseY.intValue()),
                                                Integer.toString(safeHeight(height.intValue()))),
                                        this::open), this::open)));
        set(11, Icon.of(Material.WRITABLE_BOOK).name("Start Edit Session", NamedTextColor.YELLOW)
                .lore("เลือก schematic ID และ anchor ที่ Node ปัจจุบัน", NamedTextColor.GRAY).build(),
                event -> promptId("Schematic ID ที่ต้องการ edit",
                        id -> AdminUiFlow.requireReason(viewer, gui, "Start edit session?",
                                List.of("Schematic: " + id), reason -> {
                                    gui.runAdmin(viewer, "schem", "edit", id, reason);
                                    open();
                                }, this::open)));
        set(13, Icon.of(Material.RED_BED).name("Set Spawn Anchor", NamedTextColor.GREEN)
                .lore("บันทึกตำแหน่งเท้าเป็น spawn slot", NamedTextColor.GRAY).build(),
                event -> gui.chatPrompt().requestNumber(viewer, "Spawn slot (เริ่มที่ 1)", slot -> {
                    AdminUiFlow.requireReason(viewer, gui, "Set spawn anchor?",
                            List.of("Slot: " + slot.intValue()), reason -> {
                                gui.runAdmin(viewer, "schem", "setspawn",
                                        Integer.toString(slot.intValue()), reason);
                                open();
                            }, this::open);
                }, this::open));
        set(14, Icon.of(Material.IRON_PICKAXE).name("Set Work Anchor", NamedTextColor.GREEN)
                .lore("บันทึกตำแหน่งเท้าเป็น work slot", NamedTextColor.GRAY).build(),
                event -> gui.chatPrompt().requestNumber(viewer, "Work slot (เริ่มที่ 1)", slot -> {
                    AdminUiFlow.requireReason(viewer, gui, "Set work anchor?",
                            List.of("Slot: " + slot.intValue()), reason -> {
                                gui.runAdmin(viewer, "schem", "setwork",
                                        Integer.toString(slot.intValue()), reason);
                                open();
                            }, this::open);
                }, this::open));
        set(15, Icon.of(Material.COMPASS).name("Set Wander Radius", NamedTextColor.AQUA)
                .lore("กำหนดรัศมีเดินของ NPC", NamedTextColor.GRAY).build(),
                event -> gui.chatPrompt().requestNumber(viewer, "Wander radius", radius -> {
                    AdminUiFlow.requireReason(viewer, gui, "Set wander radius?",
                            List.of("Radius: " + radius.intValue()), reason -> {
                                gui.runAdmin(viewer, "schem", "setwander",
                                        Integer.toString(radius.intValue()), reason);
                                open();
                            }, this::open);
                }, this::open));
        set(16, Icon.of(Material.ARMOR_STAND).name("Set Animation Profile",
                        NamedTextColor.LIGHT_PURPLE)
                .lore("เลือก state แล้วระบุ profile", NamedTextColor.GRAY).build(),
                event -> new AdminOptionMenu(viewer, "Animation State",
                        List.of("working", "idle", "stop"), state ->
                        gui.chatPrompt().request(viewer, "Animation profile สำหรับ " + state, profile -> {
                            AdminUiFlow.requireReason(viewer, gui, "Set animation profile?",
                                    List.of(state + " → " + profile.trim()), reason -> {
                                        gui.runAdmin(viewer, "schem", "setanim", state,
                                                profile.trim(), reason);
                                        open();
                                    }, this::open);
                        }, this::open), this::open).open());
        set(29, Icon.of(Material.LIME_DYE).name("Save Session", NamedTextColor.GREEN)
                .lore("บันทึก schematic และปิด edit session", NamedTextColor.GRAY).build(),
                event -> AdminUiFlow.requireReason(viewer, gui, "Save schematic?",
                        List.of("Persist the current edit session"), reason -> {
                            gui.runAdmin(viewer, "schem", "save", reason);
                            open();
                        }, this::open));
        set(31, Icon.of(Material.REPEATER).name("Rebuild Current Node", NamedTextColor.YELLOW)
                .lore("วาง schematic ใหม่ใน Node ที่ยืนอยู่", NamedTextColor.GRAY).build(),
                event -> confirmRun("Rebuild current Node?",
                        List.of("Terrain snapshot remains untouched"), "rebuild"));
        set(40, Icon.of(Material.ARROW).name("กลับ Content Control", NamedTextColor.GREEN).build(),
                event -> new AdminContentMenu(viewer, gui).open());
    }

    private void promptId(String prompt, java.util.function.Consumer<String> action) {
        gui.chatPrompt().request(viewer, prompt, raw -> action.accept(raw.trim()), this::open);
    }

    private void confirmRun(String question, List<String> details, String... args) {
        AdminUiFlow.requireReason(viewer, gui, question, details, reason -> {
            String[] command = new String[args.length + 2];
            command[0] = "schem";
            System.arraycopy(args, 0, command, 1, args.length);
            command[command.length - 1] = reason;
            gui.runAdmin(viewer, command);
            open();
        }, this::open);
    }

    private int safeHeight(int height) {
        return Math.max(1, Math.min(128, height));
    }
}
