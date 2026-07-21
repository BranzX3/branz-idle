package dev.branzx.idlefarm.gui;

import dev.branzx.idlefarm.service.WorkerService;
import dev.branzx.idlefarm.worker.WorkerRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The crew roster. This used to be three buttons pointing at other screens,
 * which cost a click before the player saw a single worker; the crew itself is
 * now the content and Hire / Bag / Fuse sit on the bottom bar.
 */
public final class WorkersMenu extends Menu {

    private final GuiManager gui;
    private final int page;

    public WorkersMenu(Player viewer, GuiManager gui) {
        this(viewer, gui, 0);
    }

    public WorkersMenu(Player viewer, GuiManager gui, int page) {
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
        return Component.text(Lang.get("menu.workers.title"), NamedTextColor.DARK_AQUA);
    }

    @Override
    protected void build() {
        List<WorkerRecord> crew = roster();

        int used = gui.workerStore().bagCount(viewer.getUniqueId());
        int cap = gui.workerService().bagCapacity(viewer.getUniqueId());
        set(SUMMARY_SLOT, Icon.of(Material.VILLAGER_SPAWN_EGG)
                .name(Lang.get("menu.workers.summary", "count", crew.size()),
                        NamedTextColor.AQUA)
                .loreComponents(List.of(
                        Ui.bar(Lang.get("menu.workers.list.bar-bag"),
                                cap == 0 ? 0 : used / (double) cap,
                                used >= cap ? NamedTextColor.RED : NamedTextColor.AQUA,
                                used + "/" + cap),
                        Lang.line("menu.workers.hint", NamedTextColor.GRAY)))
                .build());

        int start = page * CONTENT_GRID.length;
        for (int i = 0; i < CONTENT_GRID.length && start + i < crew.size(); i++) {
            WorkerRecord worker = crew.get(start + i);
            List<Component> lore = new ArrayList<>(gui.workerService().workerLore(worker));
            lore.add(Lang.click("menu.workers.list.click-manage"));
            set(CONTENT_GRID[i], Icon.head(gui.skinHeadCache(), worker.getSkin())
                    .name(worker.getName(), worker.getRarity().color())
                    .loreComponents(lore).build(),
                    event -> gui.openWorkerDetail(viewer, worker.getWorkerUuid()));
        }
        if (crew.isEmpty()) {
            set(CONTENT_GRID[10], Icon.of(Material.BARRIER)
                    .name(Lang.get("menu.workers.empty.name"), NamedTextColor.RED)
                    .lore(Lang.get("menu.workers.empty.hint"), NamedTextColor.GRAY).build());
        }

        drawHire();
        set(navRow() + 2, Icon.of(Material.CHEST_MINECART)
                .name(Lang.get("menu.workers.bag.name"), NamedTextColor.AQUA)
                .loreComponents(List.of(
                        Lang.line("menu.workers.bag.stored", NamedTextColor.GRAY,
                                "used", used, "cap", cap),
                        Lang.click("menu.workers.bag.click")))
                .build(), event -> gui.openWorkerBag(viewer));
        set(navRow() + 7, Icon.of(Material.SMITHING_TABLE)
                .name(Lang.get("menu.workers.fuse.name"), NamedTextColor.LIGHT_PURPLE)
                .loreComponents(List.of(
                        Lang.line("menu.workers.fuse.hint", NamedTextColor.GRAY),
                        Lang.click("menu.workers.fuse.click")))
                .build(), event -> gui.openFuse(viewer));

        navBarToHub(gui);
        pager(page, pageCount(crew.size()),
                target -> new WorkersMenu(viewer, gui, target).open());
    }

    /** Everyone the player owns: assigned to Nodes first, then bagged. */
    private List<WorkerRecord> roster() {
        List<WorkerRecord> crew = new ArrayList<>();
        gui.nodeStore().getByOwner(viewer.getUniqueId()).forEach(node ->
                crew.addAll(gui.workerStore().getAssigned(node.getId())));
        crew.addAll(gui.workerStore().getBag(viewer.getUniqueId()));
        return crew;
    }

    private void drawHire() {
        double hireCost = gui.workerService().hireCost();
        List<Component> lore = new ArrayList<>();
        lore.add(Lang.line("menu.workers.hire.cost", NamedTextColor.GOLD,
                "cost", Ui.num(hireCost)));
        lore.add(Lang.line("menu.workers.hire.random", NamedTextColor.GRAY));
        var oddsSection = gui.plugin().getConfig().getConfigurationSection("workers.gacha-odds");
        if (oddsSection != null) {
            for (String key : oddsSection.getKeys(false)) {
                lore.add(Ui.line("  " + key + ": " + oddsSection.getDouble(key) + "%",
                        NamedTextColor.DARK_GRAY));
            }
        }
        lore.add(Lang.click("menu.workers.hire.click"));
        setConfirm(navRow() + 1, Icon.of(Material.EXPERIENCE_BOTTLE)
                .name(Lang.get("menu.workers.hire.name"), NamedTextColor.GREEN)
                .loreComponents(lore).build(), this::hire);
    }

    private void hire() {
        WorkerService.Result result = gui.workerService().hire(viewer.getUniqueId());
        if (result.success() && result.item() != null) {
            giveOrDrop(result.item());
        }
        viewer.sendMessage(Component.text(result.message(),
                result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
        redraw();
    }

    private void giveOrDrop(ItemStack item) {
        var leftover = viewer.getInventory().addItem(item);
        for (ItemStack overflow : leftover.values()) {
            viewer.getWorld().dropItemNaturally(viewer.getLocation(), overflow);
        }
    }

    @Override
    protected Material frameMaterial() {
        return Material.MAGENTA_STAINED_GLASS_PANE;
    }
}
