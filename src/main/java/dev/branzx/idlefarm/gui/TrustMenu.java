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
        fill();
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
            List<Component> accessLore = new java.util.ArrayList<>();
            accessLore.add(Ui.line("Role: " + Ui.pretty(level.name()), NamedTextColor.AQUA));
            roleAccess(level).forEach(line ->
                    accessLore.add(Ui.line(line, NamedTextColor.GRAY)));
            accessLore.add(Ui.click("manage role and access"));
            set(slot, Icon.head(gui.skinHeadCache(), name).name(name, NamedTextColor.GREEN)
                    .loreComponents(accessLore).build(),
                    e -> new TrustedPlayerMenu(entry.getKey(), name, level).open());
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
            set(addSlot, Icon.head(gui.skinHeadCache(), online.getName()).name("+ " + online.getName(), NamedTextColor.YELLOW)
                    .lore("Click: grant Visitor", NamedTextColor.GRAY).build(),
                    e -> {
                        gui.trustService().setTrust(viewer.getUniqueId(), online.getUniqueId(),
                                TrustLevel.VISITOR);
                        redraw();
                    });
            addSlot++;
        }

        backButton(49, "Social", () -> gui.openSocial(viewer));
    }

    private TrustLevel cycle(TrustLevel level) {
        return switch (level) {
            case VISITOR -> TrustLevel.HELPER;
            case HELPER -> TrustLevel.MANAGER;
            case MANAGER -> TrustLevel.VISITOR;
        };
    }

    private List<String> roleAccess(TrustLevel level) {
        return switch (level) {
            case VISITOR -> List.of("Can visit and use allowed doors",
                    "Cannot collect or manage Nodes");
            case HELPER -> List.of("Visitor access + collect buffers",
                    "Can contribute to projects");
            case MANAGER -> List.of("Helper access + Workers and events",
                    "Cannot upgrade, convert or unclaim");
        };
    }

    private void confirmRole(UUID target, String name,
                             TrustLevel before, TrustLevel after) {
        List<String> details = new java.util.ArrayList<>();
        details.add(Ui.pretty(before.name()) + " -> " + Ui.pretty(after.name()));
        details.addAll(roleAccess(after));
        new ConfirmMenu(viewer, "Change access for " + name + "?",
                details,
                () -> {
                    gui.trustService().setTrust(viewer.getUniqueId(), target, after);
                    new TrustMenu(viewer, gui).open();
                },
                () -> new TrustMenu(viewer, gui).open()).open();
    }

    private void confirmRemove(UUID target, String name) {
        new ConfirmMenu(viewer, "Remove " + name + " from Trust?",
                List.of("All territory access is removed immediately"),
                () -> {
                    gui.trustService().removeTrust(viewer.getUniqueId(), target);
                    new TrustMenu(viewer, gui).open();
                },
                () -> new TrustMenu(viewer, gui).open()).open();
    }

    private final class TrustedPlayerMenu extends Menu {
        private final UUID target;
        private final String name;
        private final TrustLevel current;

        private TrustedPlayerMenu(UUID target, String name, TrustLevel current) {
            super(TrustMenu.this.viewer);
            this.target = target;
            this.name = name;
            this.current = current;
        }

        @Override protected int rows() { return 3; }
        @Override protected Component title() {
            return Component.text("Access • " + name, NamedTextColor.DARK_GREEN);
        }

        @Override
        protected void build() {
            fill();
            set(4, Icon.head(gui.skinHeadCache(), name).name(name, NamedTextColor.GREEN)
                    .lore("Current role: " + Ui.pretty(current.name()), NamedTextColor.AQUA).build());
            int slot = 10;
            for (TrustLevel role : TrustLevel.values()) {
                TrustLevel selected = role;
                set(slot, Icon.of(role == current ? Material.LIME_DYE : Material.PAPER)
                        .name(Ui.pretty(role.name()), role == current
                                ? NamedTextColor.GREEN : NamedTextColor.YELLOW)
                        .lore(roleAccess(role), NamedTextColor.GRAY).build(),
                        role == current ? null
                                : e -> confirmRole(target, name, current, selected));
                slot += 2;
            }
            set(16, Icon.of(Material.BARRIER).name("Remove Access", NamedTextColor.RED)
                    .lore("Review and remove all territory access", NamedTextColor.GRAY).build(),
                    e -> confirmRemove(target, name));
            backButton(22, "Trust", () -> new TrustMenu(viewer, gui).open());
        }
    }
}
