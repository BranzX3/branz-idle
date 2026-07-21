package dev.branzx.idlefarm.gui;

import dev.branzx.idlefarm.worker.WorkerRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;
import java.util.List;

/** A single worker's card with cosmetic actions (rename / reskin / withdraw). */
public final class WorkerDetailMenu extends Menu {

    private final GuiManager gui;
    private final UUID workerUuid;
    private final String backLabel;
    private final Runnable onBack;

    public WorkerDetailMenu(Player viewer, GuiManager gui, UUID workerUuid) {
        this(viewer, gui, workerUuid, Lang.get("menu.workers.bag.title"),
                () -> gui.openWorkerBag(viewer));
    }

    public WorkerDetailMenu(Player viewer, GuiManager gui, UUID workerUuid,
                            String backLabel, Runnable onBack) {
        super(viewer);
        this.gui = gui;
        this.workerUuid = workerUuid;
        this.backLabel = backLabel;
        this.onBack = onBack;
    }

    /** Identity row, stats row, actions row, nav row. */
    private static final int LOCK_SLOT = 19;
    private static final int CHARM_SLOT = 21;
    private static final int NOTES_SLOT = 23;
    private static final int RENAME_SLOT = 28;
    private static final int SKIN_SLOT = 30;
    private static final int MOVE_SLOT = 32;

    @Override
    protected int rows() {
        // Five rows so the card, its stats and its actions each get a row
        // instead of being scattered across three.
        return 5;
    }

    @Override
    protected Component title() {
        return Component.text(Lang.get("menu.worker.title"), NamedTextColor.DARK_AQUA);
    }

    @Override
    protected void build() {
        WorkerRecord worker = gui.workerStore().get(workerUuid);
        if (worker == null) {
            viewer.closeInventory();
            return;
        }
        set(SUMMARY_SLOT, Icon.head(gui.skinHeadCache(), worker.getSkin())
                .name(worker.getName(), worker.getRarity().color())
                .loreComponents(gui.workerService().workerLore(worker)).build());

        set(RENAME_SLOT, Icon.of(Material.NAME_TAG)
                .name(Lang.get("menu.worker.rename.name"), NamedTextColor.GREEN)
                .lore(Lang.get("menu.worker.rename.hint"), NamedTextColor.GRAY).build(),
                e -> gui.chatPrompt().request(viewer,
                        "Enter a new name for " + worker.getName(),
                        input -> {
                            var result = gui.workerService().rename(worker, input);
                            viewer.sendMessage(Component.text(result.message(),
                                    result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
                            open();
                        },
                        this::open));

        boolean canSkin = viewer.hasPermission("idlefarm.skin");
        set(SKIN_SLOT, Icon.of(canSkin ? Material.PLAYER_HEAD : Material.BARRIER)
                .name("Change Skin", canSkin ? NamedTextColor.AQUA : NamedTextColor.DARK_GRAY)
                .lore(canSkin ? "Type a player username in chat"
                        : "Requires a rank", NamedTextColor.GRAY).build(),
                canSkin ? e -> gui.chatPrompt().request(viewer,
                        "Enter a player username for the skin",
                        input -> {
                            var result = gui.workerService().setSkin(worker, input.trim());
                            viewer.sendMessage(Component.text(result.message(),
                                    result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
                            open();
                        },
                        this::open) : null);

        boolean locked = gui.gameDesignService() != null
                && gui.gameDesignService().isWorkerLocked(worker.getWorkerUuid());
        boolean starter = gui.gameDesignService() != null
                && gui.gameDesignService().isStarterWorker(worker.getWorkerUuid());
        set(LOCK_SLOT, Icon.of(locked ? Material.REDSTONE_TORCH : Material.LEVER)
                .name(locked ? "Favorite / Locked" : "Lock Worker",
                        locked ? NamedTextColor.RED : NamedTextColor.YELLOW)
                .lore("Locked workers cannot fuse, trade, or withdraw", NamedTextColor.GRAY).build(),
                starter ? null : e -> {
                    boolean next = gui.gameDesignService()
                            .toggleWorkerLock(viewer.getUniqueId(), worker.getWorkerUuid());
                    viewer.sendMessage(Component.text(next ? "Worker locked." : "Worker unlocked.",
                            next ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
                    redraw();
                });

        if (gui.gameDesignService() != null) {
            String currentCharm = gui.gameDesignService()
                    .workerCharm(viewer.getUniqueId(), worker.getWorkerUuid());
            List<String> charms = List.of("SURVEY_COMPASS", "HEAVY_GLOVES",
                    "LUCKY_TOKEN", "TRAIL_BOOTS", "NONE");
            set(CHARM_SLOT, Icon.of(Material.HEART_OF_THE_SEA).name("Charm: " + Ui.pretty(currentCharm),
                            NamedTextColor.LIGHT_PURPLE)
                    .lore("Choose a situational Charm", NamedTextColor.GRAY).build(),
                    e -> new AdminOptionMenu(viewer, "Choose Charm", charms,
                            selected -> {
                                var result = gui.gameDesignService().equipCharm(
                                        viewer.getUniqueId(), worker, selected);
                                viewer.sendMessage(Component.text(result.message(),
                                        result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
                                open();
                            }, this::open).open());
            long notes = gui.gameDesignService().trainingNotes(viewer.getUniqueId());
            set(NOTES_SLOT, Icon.of(Material.EXPERIENCE_BOTTLE)
                    .name("Training Notes: " + notes, NamedTextColor.GREEN)
                    .lore("Click: apply all returned Fuse EXP", NamedTextColor.GRAY).build(),
                    e -> {
                        var result = gui.workerService()
                                .applyTrainingNotes(viewer.getUniqueId(), worker, notes);
                        viewer.sendMessage(Component.text(result.message(),
                                result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
                        redraw();
                    });
        }

        // Withdraw is only meaningful for a tradeable, unlocked bag worker. It
        // hands a real item back, so it asks twice.
        if (worker.isInBag() && !locked && !starter) {
            setDangerConfirm(MOVE_SLOT, Icon.of(Material.HOPPER)
                    .name(Lang.get("menu.worker.withdraw.name"), NamedTextColor.GOLD)
                    .loreComponents(List.of(
                            Lang.line("menu.worker.withdraw.hint", NamedTextColor.GRAY),
                            Lang.click("menu.worker.withdraw.click"))).build(),
                    () -> {
                        ItemStack item = gui.workerService().withdraw(viewer.getUniqueId(), worker);
                        if (item != null) {
                            giveOrDrop(item);
                        }
                        gui.openWorkerBag(viewer);
                    });
        } else if (worker.getAssignedNodeId() != null) {
            set(MOVE_SLOT, Icon.of(Material.CHEST_MINECART)
                    .name(Lang.get("menu.worker.store.name"), NamedTextColor.GREEN)
                    .lore(Lang.get("menu.worker.store.hint"), NamedTextColor.GRAY).build(),
                    e -> storeAssignedWorker(worker));
        }

        navBar(backLabel, onBack);
    }

    private void storeAssignedWorker(WorkerRecord worker) {
        Long nodeId = worker.getAssignedNodeId();
        var result = gui.workerService().eject(viewer.getUniqueId(), worker);
        if (result.success()) {
            if (result.item() != null) {
                giveOrDrop(result.item());
            }
            if (nodeId != null) {
                gui.nodeStore().getAll().stream()
                        .filter(node -> node.getId() == nodeId)
                        .findFirst()
                        .ifPresent(node -> gui.npcManager().refreshNode(node, viewer.getWorld()));
            }
        }
        viewer.sendMessage(Component.text(result.message(),
                result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
        onBack.run();
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
