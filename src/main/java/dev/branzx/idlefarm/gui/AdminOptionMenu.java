package dev.branzx.idlefarm.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.function.Consumer;

/** Small reusable picker for enum-like admin options. */
public final class AdminOptionMenu extends Menu {

    private final String heading;
    private final List<String> options;
    private final Consumer<String> onSelect;
    private final Runnable onBack;

    public AdminOptionMenu(Player viewer, String heading, List<String> options,
                           Consumer<String> onSelect, Runnable onBack) {
        super(viewer);
        this.heading = heading;
        this.options = options;
        this.onSelect = onSelect;
        this.onBack = onBack;
    }

    @Override protected int rows() { return 3; }
    @Override protected Component title() {
        return Component.text(heading, NamedTextColor.DARK_AQUA);
    }

    @Override
    protected void build() {
        fill();
        int start = Math.max(0, 13 - options.size() / 2);
        for (int i = 0; i < options.size() && start + i < 18; i++) {
            String option = options.get(i);
            set(start + i, Icon.of(Material.PAPER).name(Ui.pretty(option), NamedTextColor.YELLOW)
                    .lore("เลือก " + option, NamedTextColor.GRAY).build(),
                    event -> onSelect.accept(option));
        }
        set(22, Icon.of(Material.ARROW).name("กลับ", NamedTextColor.GREEN).build(),
                event -> onBack.run());
    }
}
