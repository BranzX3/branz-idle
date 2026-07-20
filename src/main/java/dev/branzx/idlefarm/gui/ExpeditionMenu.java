package dev.branzx.idlefarm.gui;

import dev.branzx.idlefarm.node.ChunkKey;
import dev.branzx.idlefarm.node.NodeRecord;
import dev.branzx.idlefarm.service.GlobalExpeditionService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Weekly Global Expedition: rankings + commit button. */
public final class ExpeditionMenu extends Menu {

    private final GuiManager gui;
    private final GlobalExpeditionService expedition;

    public ExpeditionMenu(Player viewer, GuiManager gui, GlobalExpeditionService expedition) {
        super(viewer);
        this.gui = gui;
        this.expedition = expedition;
    }

    @Override
    protected int rows() {
        return 5;
    }

    @Override
    protected Component title() {
        return Component.text("Global Expedition " + expedition.activeWeek(), NamedTextColor.GOLD);
    }

    @Override
    protected void build() {
        fill();

        // Rankings (top 10) across rows 1-2.
        List<GlobalExpeditionService.Score> top = expedition.top(10);
        int slot = 9;
        int rank = 1;
        for (GlobalExpeditionService.Score score : top) {
            var offline = Bukkit.getOfflinePlayer(score.owner());
            Material medal = switch (rank) {
                case 1 -> Material.GOLD_BLOCK;
                case 2 -> Material.IRON_BLOCK;
                case 3 -> Material.COPPER_BLOCK;
                default -> Material.STONE;
            };
            boolean self = score.owner().equals(viewer.getUniqueId());
            set(slot, Icon.of(medal)
                    .name("#" + rank + " " + (offline.getName() == null ? "?" : offline.getName()),
                            self ? NamedTextColor.GREEN : NamedTextColor.WHITE)
                    .loreComponents(List.of(
                            Ui.line("Contribution " + Ui.num(score.contribution()),
                                    NamedTextColor.GOLD)))
                    .build());
            slot++;
            rank++;
        }

        // Your standing + commit.
        long mine = expedition.contributionOf(viewer.getUniqueId());
        List<Component> mineLore = new ArrayList<>();
        mineLore.add(Ui.line("Your contribution: " + Ui.num(mine), NamedTextColor.GOLD));
        GlobalExpeditionService.ParticipationBand next =
                expedition.nextBand(viewer.getUniqueId());
        if (next == null) {
            mineLore.add(Ui.line("All participation bands reached", NamedTextColor.GREEN));
        } else {
            mineLore.add(Ui.line("Next: " + next.name() + " at "
                    + Ui.num(next.threshold()) + " (+" + Ui.num(next.coins()) + " Coins)",
                    NamedTextColor.AQUA));
        }
        for (GlobalExpeditionService.ParticipationBand band : expedition.participationBands()) {
            mineLore.add(Ui.line((mine >= band.threshold() ? "✓ " : "• ")
                    + band.name() + " — " + Ui.num(band.threshold()),
                    mine >= band.threshold() ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY));
        }
        mineLore.add(Ui.divider());
        mineLore.add(Ui.line("Stand in your node and click:", NamedTextColor.GRAY));
        mineLore.add(Ui.line("commits its idle workers for "
                + expedition.commitDurationMinutes() + "m", NamedTextColor.GRAY));
        mineLore.add(Ui.line("(they stop producing while away)", NamedTextColor.DARK_GRAY));
        set(31, Icon.of(Material.CAMPFIRE).name("Send Workers!", NamedTextColor.GREEN)
                .loreComponents(mineLore).build(), e -> commit());

        backToHub(gui);
    }

    private void commit() {
        NodeRecord node = gui.nodeStore().getByChunk(new ChunkKey(viewer.getWorld().getName(),
                viewer.getLocation().getBlockX() >> 4,
                viewer.getLocation().getBlockZ() >> 4));
        if (node == null || !node.getType().isProduction()
                || !node.getOwnerUuid().equals(viewer.getUniqueId())) {
            viewer.sendMessage(Component.text("Stand in one of your production nodes.",
                    NamedTextColor.RED));
            return;
        }
        String error = expedition.commit(viewer.getUniqueId(), node);
        if (error == null) {
            gui.npcManager().refreshNode(node, viewer.getWorld());
            viewer.sendMessage(Component.text("Workers sent to the Global Expedition!",
                    NamedTextColor.GOLD));
        } else {
            viewer.sendMessage(Component.text(error, NamedTextColor.RED));
        }
        redraw();
    }
}
