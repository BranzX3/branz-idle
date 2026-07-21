package dev.branzx.idle.gui;

import dev.branzx.idle.node.TrustLevel;
import dev.branzx.idle.service.PerkService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Social hub. The trust roster used to live one click deeper in its own screen
 * while this page held nothing but four signposts; the roster is now the page,
 * and Trade / Visits / Leaderboard sit on the bottom bar.
 *
 * <p>Left half lists the people you already trust, right half the online
 * players you could add, so granting and revoking read as one decision.
 */
public final class SocialMenu extends Menu {

    /** Left half of the content area: people already trusted. */
    private static final int[] TRUSTED = {
            10, 11, 12, 13,
            19, 20, 21, 22,
            28, 29, 30, 31,
            37, 38, 39, 40};

    /** Right half: online players not yet trusted. */
    private static final int[] CANDIDATES = {
            14, 15, 16, 17,
            23, 24, 25, 26,
            32, 33, 34, 35,
            41, 42, 43, 44};

    private final GuiManager gui;

    public SocialMenu(Player viewer, GuiManager gui) {
        super(viewer);
        this.gui = gui;
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected Component title() {
        return Component.text(Lang.get("menu.social.title"), NamedTextColor.DARK_GREEN);
    }

    @Override
    protected void build() {
        Map<UUID, TrustLevel> trusted = gui.nodeStore().getTrustedOf(viewer.getUniqueId());

        set(SUMMARY_SLOT, Icon.head(gui.skinHeadCache(), viewer.getName())
                .name(Lang.get("menu.social.summary"), NamedTextColor.GREEN)
                .loreComponents(List.of(
                        Lang.line("menu.social.trusted-count", NamedTextColor.AQUA,
                                "count", trusted.size()),
                        Lang.line("menu.social.no-build", NamedTextColor.GRAY)))
                .build());

        drawTrusted(trusted);
        drawCandidates(trusted);
        drawBottomBar();
        navBarToHub(gui);
    }

    private void drawTrusted(Map<UUID, TrustLevel> trusted) {
        int index = 0;
        for (Map.Entry<UUID, TrustLevel> entry : trusted.entrySet()) {
            if (index >= TRUSTED.length) {
                break;
            }
            var offline = Bukkit.getOfflinePlayer(entry.getKey());
            String name = offline.getName() == null ? "?" : offline.getName();
            TrustLevel level = entry.getValue();
            List<Component> lore = new ArrayList<>();
            lore.add(Lang.line("menu.social.role", NamedTextColor.AQUA,
                    "role", Ui.pretty(level.name())));
            roleAccess(level).forEach(line -> lore.add(Ui.line(line, NamedTextColor.GRAY)));
            lore.add(Lang.click("menu.social.click-manage"));
            set(TRUSTED[index++], Icon.head(gui.skinHeadCache(), name)
                    .name(name, NamedTextColor.GREEN)
                    .loreComponents(lore).build(),
                    event -> new TrustedPlayerMenu(entry.getKey(), name, level).open());
        }
        if (trusted.isEmpty()) {
            set(TRUSTED[0], Icon.of(Material.BARRIER)
                    .name(Lang.get("menu.social.none.name"), NamedTextColor.GRAY)
                    .lore(Lang.get("menu.social.none.hint"), NamedTextColor.DARK_GRAY).build());
        }
    }

    private void drawCandidates(Map<UUID, TrustLevel> trusted) {
        int index = 0;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getUniqueId().equals(viewer.getUniqueId())
                    || trusted.containsKey(online.getUniqueId())) {
                continue;
            }
            if (index >= CANDIDATES.length) {
                break;
            }
            set(CANDIDATES[index++], Icon.head(gui.skinHeadCache(), online.getName())
                    .name(Lang.get("menu.social.add", "player", online.getName()),
                            NamedTextColor.YELLOW)
                    .loreComponents(List.of(
                            Lang.line("menu.social.add-hint", NamedTextColor.GRAY),
                            Lang.click("menu.social.click-add")))
                    .build(), event -> {
                        gui.trustService().setTrust(viewer.getUniqueId(),
                                online.getUniqueId(), TrustLevel.VISITOR);
                        redraw();
                    });
        }
    }

    private void drawBottomBar() {
        set(navRow() + 1, Icon.of(Material.CHEST)
                .name(Lang.get("menu.social.trade.name"), NamedTextColor.GOLD)
                .loreComponents(List.of(
                        Lang.line("menu.social.trade.hint", NamedTextColor.GRAY),
                        Lang.line("menu.social.trade.start", NamedTextColor.DARK_GRAY),
                        Lang.click("menu.social.trade.click")))
                .build(), event -> gui.openTrade(viewer));

        boolean closed = gui.perkService() != null
                && gui.perkService().has(viewer.getUniqueId(), PerkService.NO_VISITS);
        set(navRow() + 6, Icon.of(closed ? Material.IRON_DOOR : Material.OAK_DOOR)
                .name(Lang.get(closed ? "menu.social.visits.closed"
                        : "menu.social.visits.open"),
                        closed ? NamedTextColor.RED : NamedTextColor.GREEN)
                .loreComponents(List.of(
                        Lang.line("menu.social.visits.hint", NamedTextColor.GRAY),
                        Lang.click("menu.social.visits.click")))
                .build(), event -> {
                    viewer.performCommand("idle visit toggle");
                    redraw();
                });

        set(navRow() + 7, Icon.of(Material.GOLD_INGOT)
                .name(Lang.get("menu.leaderboard.title"), NamedTextColor.GOLD)
                .lore(Lang.get("menu.social.leaderboard-hint"), NamedTextColor.GRAY)
                .build(), event -> gui.openLeaderboard(viewer));
    }

    private List<String> roleAccess(TrustLevel level) {
        return switch (level) {
            case VISITOR -> List.of(Lang.get("menu.social.access.visitor-1"),
                    Lang.get("menu.social.access.visitor-2"));
            case HELPER -> List.of(Lang.get("menu.social.access.helper-1"),
                    Lang.get("menu.social.access.helper-2"));
            case MANAGER -> List.of(Lang.get("menu.social.access.manager-1"),
                    Lang.get("menu.social.access.manager-2"));
        };
    }

    /** Role picker for one trusted player, opened from their head in the roster. */
    private final class TrustedPlayerMenu extends Menu {
        private final UUID target;
        private final String name;
        private final TrustLevel current;

        private TrustedPlayerMenu(UUID target, String name, TrustLevel current) {
            super(SocialMenu.this.viewer);
            this.target = target;
            this.name = name;
            this.current = current;
        }

        @Override
        protected int rows() {
            return 5;
        }

        @Override
        protected Component title() {
            return Component.text(Lang.get("menu.social.access.title", "player", name),
                    NamedTextColor.DARK_GREEN);
        }

        @Override
        protected void build() {
            set(SUMMARY_SLOT, Icon.head(gui.skinHeadCache(), name)
                    .name(name, NamedTextColor.GREEN)
                    .lore(Lang.get("menu.social.access.current",
                            "role", Ui.pretty(current.name())), NamedTextColor.AQUA).build());

            int slot = 19;
            for (TrustLevel role : TrustLevel.values()) {
                TrustLevel selected = role;
                var icon = Icon.of(role == current ? Material.LIME_DYE : Material.PAPER)
                        .name(Ui.pretty(role.name()), role == current
                                ? NamedTextColor.GREEN : NamedTextColor.YELLOW)
                        .lore(roleAccess(role), NamedTextColor.GRAY).build();
                if (role == current) {
                    set(slot, icon);
                } else {
                    setConfirm(slot, icon, () -> {
                        gui.trustService().setTrust(viewer.getUniqueId(), target, selected);
                        gui.openSocial(viewer);
                    });
                }
                slot += 2;
            }

            setDangerConfirm(31, Icon.of(Material.BARRIER)
                    .name(Lang.get("menu.social.remove.name"), NamedTextColor.RED)
                    .loreComponents(List.of(
                            Lang.line("menu.social.remove.hint", NamedTextColor.GRAY),
                            Lang.click("menu.social.remove.click")))
                    .build(), () -> {
                        gui.trustService().removeTrust(viewer.getUniqueId(), target);
                        gui.openSocial(viewer);
                    });

            navBar(Lang.get("menu.social.title"), () -> gui.openSocial(viewer));
        }
    }

    @Override
    protected Material frameMaterial() {
        return Material.BLUE_STAINED_GLASS_PANE;
    }
}
