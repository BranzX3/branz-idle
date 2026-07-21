package dev.branzx.idle.gui;

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
    private final boolean confirmSelection;

    public AdminOptionMenu(Player viewer, String heading, List<String> options,
                           Consumer<String> onSelect, Runnable onBack) {
        this(viewer, heading, options, onSelect, onBack, false);
    }

    /**
     * @param confirmSelection when true each option needs a second click, for
     *                         pickers whose choice is permanent.
     */
    public AdminOptionMenu(Player viewer, String heading, List<String> options,
                           Consumer<String> onSelect, Runnable onBack,
                           boolean confirmSelection) {
        super(viewer);
        this.heading = heading;
        this.options = options;
        this.onSelect = onSelect;
        this.onBack = onBack;
        this.confirmSelection = confirmSelection;
    }

    @Override protected int rows() { return 3; }
    @Override protected Component title() {
        return Component.text(heading, NamedTextColor.DARK_AQUA);
    }

    @Override
    protected void build() {
        int start = Math.max(0, 13 - options.size() / 2);
        for (int i = 0; i < options.size() && start + i < 18; i++) {
            String option = options.get(i);
            var icon = Icon.of(Material.PAPER).name(Ui.pretty(option), NamedTextColor.YELLOW)
                    .lore("เลือก " + option, NamedTextColor.GRAY).build();
            if (confirmSelection) {
                setConfirm(start + i, icon, () -> onSelect.accept(option));
            } else {
                set(start + i, icon, event -> onSelect.accept(option));
            }
        }
        set(22, Icon.of(Material.ARROW).name("กลับ", NamedTextColor.GREEN).build(),
                event -> onBack.run());
    }
}
