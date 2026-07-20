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
        this(viewer, gui, workerUuid, "Back to Bag", () -> gui.openWorkerBag(viewer));
    }

    public WorkerDetailMenu(Player viewer, GuiManager gui, UUID workerUuid,
                            String backLabel, Runnable onBack) {
        super(viewer);
        this.gui = gui;
        this.workerUuid = workerUuid;
        this.backLabel = backLabel;
        this.onBack = onBack;
    }

    @Override
    protected int rows() {
        return 3;
    }

    @Override
    protected Component title() {
        WorkerRecord w = gui.workerStore().get(workerUuid);
        return Component.text(w == null ? "Worker" : w.getName(), NamedTextColor.DARK_AQUA);
    }

    @Override
    protected void build() {
        WorkerRecord worker = gui.workerStore().get(workerUuid);
        if (worker == null) {
            viewer.closeInventory();
            return;
        }
        for (int i = 0; i < rows() * 9; i++) {
            set(i, Icon.filler());
        }

        set(4, Icon.head(worker.getSkin())
                .name("✦ " + worker.getName(), worker.getRarity().color())
                .loreComponents(gui.workerService().workerLore(worker)).build());

        set(11, Icon.of(Material.NAME_TAG).name("Rename", NamedTextColor.GREEN)
                .lore("Free — type a new name in chat", NamedTextColor.GRAY).build(),
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
        set(13, Icon.of(canSkin ? Material.PLAYER_HEAD : Material.BARRIER)
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
        set(17, Icon.of(locked ? Material.REDSTONE_TORCH : Material.LEVER)
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
            set(20, Icon.of(Material.HEART_OF_THE_SEA).name("Charm: " + Ui.pretty(currentCharm),
                            NamedTextColor.LIGHT_PURPLE)
                    .lore("Left/right/shift click selects situational Charm", NamedTextColor.GRAY).build(),
                    e -> {
                        int index = (charms.indexOf(currentCharm) + 1) % charms.size();
                        var result = gui.gameDesignService().equipCharm(
                                viewer.getUniqueId(), worker, charms.get(index));
                        viewer.sendMessage(Component.text(result.message(),
                                result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
                        redraw();
                    });
            long notes = gui.gameDesignService().trainingNotes(viewer.getUniqueId());
            set(21, Icon.of(Material.EXPERIENCE_BOTTLE)
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

        // Withdraw is only meaningful for a tradeable, unlocked bag worker.
        if (worker.isInBag() && !locked && !starter) {
            set(15, Icon.of(Material.HOPPER).name("Withdraw as Item", NamedTextColor.GOLD)
                    .lore("Take out to trade or carry", NamedTextColor.GRAY).build(),
                    e -> {
                        ItemStack item = gui.workerService().withdraw(viewer.getUniqueId(), worker);
                        if (item != null) {
                            var leftover = viewer.getInventory().addItem(item);
                            for (ItemStack overflow : leftover.values()) {
                                viewer.getWorld().dropItemNaturally(viewer.getLocation(), overflow);
                            }
                        }
                        gui.openWorkerBag(viewer);
                    });
        } else if (worker.getAssignedNodeId() != null) {
            set(15, Icon.of(Material.CHEST_MINECART)
                    .name("Store in Worker Bag", NamedTextColor.GREEN)
                    .lore("Return this worker from its Node", NamedTextColor.GRAY).build(),
                    e -> storeAssignedWorker(worker));
        }

        set(22, Icon.of(Material.NETHER_STAR).name(backLabel, NamedTextColor.GREEN).build(),
                e -> onBack.run());
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
}
