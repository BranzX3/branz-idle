package dev.branzx.idlefarm.gui;

import dev.branzx.idlefarm.node.TrustLevel;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manage territory trust without commands: left side lists people you trust
 * (click to cycle Visitor→Helper→Manager→remove), right side lists online
 * players you can grant Visitor to.
 */
public final class TrustMenu extends Menu {

    private final GuiManager gui;

    public TrustMenu(Player viewer, GuiManager gui) {
        super(viewer);
        this.gui = gui;
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected Component title() {
        return Component.text("Territory Trust", NamedTextColor.DARK_GREEN);
    }

    @Override
    protected void build() {
        for (int i = 0; i < rows() * 9; i++) {
            set(i, Icon.filler());
        }
        set(4, Icon.of(Material.OAK_HANGING_SIGN).name("Trusted Players", NamedTextColor.AQUA)
                .lore(List.of("Left: people you trust (click to cycle level)",
                        "Right: online players to add"), NamedTextColor.GRAY).build());

        // Left column: current trusts.
        Map<UUID, TrustLevel> trusted = gui.nodeStore().getTrustedOf(viewer.getUniqueId());
        int slot = 9;
        for (Map.Entry<UUID, TrustLevel> entry : trusted.entrySet()) {
            if (slot % 9 >= 4) {
                slot = (slot / 9 + 1) * 9; // wrap to next row's left block
            }
            if (slot >= 45) {
                break;
            }
            var offline = Bukkit.getOfflinePlayer(entry.getKey());
            String name = offline.getName() == null ? "?" : offline.getName();
            TrustLevel level = entry.getValue();
            set(slot, Icon.of(Material.PLAYER_HEAD).name(name, NamedTextColor.GREEN)
                    .lore(List.of("Level: " + level, "Click: cycle level",
                            "Shift-click: remove"), NamedTextColor.GRAY).build(),
                    e -> {
                        if (e.isShiftClick()) {
                            gui.trustService().removeTrust(viewer.getUniqueId(), entry.getKey());
                        } else {
                            gui.trustService().setTrust(viewer.getUniqueId(), entry.getKey(), cycle(level));
                        }
                        redraw();
                    });
            slot++;
        }

        // Right column: online players not yet trusted.
        int addSlot = 14;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getUniqueId().equals(viewer.getUniqueId())
                    || trusted.containsKey(online.getUniqueId())) {
                continue;
            }
            if (addSlot % 9 < 4) {
                addSlot = (addSlot / 9 + 1) * 9 + 5;
            }
            if (addSlot >= 45) {
                break;
            }
            set(addSlot, Icon.of(Material.PLAYER_HEAD).name("+ " + online.getName(), NamedTextColor.YELLOW)
                    .lore("Click: grant Visitor", NamedTextColor.GRAY).build(),
                    e -> {
                        gui.trustService().setTrust(viewer.getUniqueId(), online.getUniqueId(),
                                TrustLevel.VISITOR);
                        redraw();
                    });
            addSlot++;
        }

        set(49, Icon.of(Material.NETHER_STAR).name("Main Menu", NamedTextColor.GREEN).build(),
                e -> gui.openMainHub(viewer));
    }

    private TrustLevel cycle(TrustLevel level) {
        return switch (level) {
            case VISITOR -> TrustLevel.HELPER;
            case HELPER -> TrustLevel.MANAGER;
            case MANAGER -> TrustLevel.VISITOR;
        };
    }
}
