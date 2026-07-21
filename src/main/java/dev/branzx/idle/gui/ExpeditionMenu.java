package dev.branzx.idle.gui;

import dev.branzx.idle.node.ChunkKey;
import dev.branzx.idle.node.NodeRecord;
import dev.branzx.idle.service.GlobalExpeditionService;
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
        // Six rows: the shared CONTENT_GRID reaches slot 43, which would sit on
        // the nav row of a five-row menu.
        return 6;
    }

    @Override
    protected Component title() {
        return Component.text(Lang.get("menu.expedition.title"), NamedTextColor.GOLD);
    }

    @Override
    protected void build() {

        // Rankings across rows 1-2 of the standard grid, so the board keeps the
        // same margins as every other list on the plugin.
        List<GlobalExpeditionService.Score> top = expedition.top(10);
        int slot = 0;
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
            set(CONTENT_GRID[slot], Icon.of(medal)
                    .name(Lang.get("menu.expedition.rank", "rank", rank,
                                    "player", offline.getName() == null ? "?" : offline.getName()),
                            self ? NamedTextColor.GREEN : NamedTextColor.WHITE)
                    .loreComponents(List.of(
                            Lang.line("menu.expedition.contribution", NamedTextColor.GOLD,
                                    "amount", Ui.num(score.contribution()))))
                    .build());
            slot++;
            rank++;
        }

        // Your standing lives in the summary card; the commit button below
        // carries only what the click actually does.
        long mine = expedition.contributionOf(viewer.getUniqueId());
        List<Component> mineLore = new ArrayList<>();
        mineLore.add(Lang.line("menu.expedition.yours", NamedTextColor.GOLD,
                "amount", Ui.num(mine)));
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
        set(SUMMARY_SLOT, Icon.of(Material.CAMPFIRE)
                .name(Lang.get("menu.expedition.summary", "week", expedition.activeWeek()),
                        NamedTextColor.GOLD)
                .loreComponents(mineLore).build());

        setConfirm(31, Icon.of(Material.CAMPFIRE)
                .name(Lang.get("menu.expedition.send.name"), NamedTextColor.GREEN)
                .loreComponents(List.of(
                        Lang.line("menu.expedition.send.stand", NamedTextColor.GRAY),
                        Lang.line("menu.expedition.send.duration", NamedTextColor.GRAY,
                                "minutes", expedition.commitDurationMinutes()),
                        Lang.line("menu.expedition.send.stops", NamedTextColor.DARK_GRAY),
                        Lang.click("menu.expedition.send.click")))
                .build(), this::commit);

        navBarToHub(gui);
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

    @Override
    protected Material frameMaterial() {
        return Material.ORANGE_STAINED_GLASS_PANE;
    }
}
