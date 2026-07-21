package dev.branzx.idle.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** Generic yes/no gate for destructive actions (unclaim, convert, ...). */
public final class ConfirmMenu extends Menu {

    private final String question;
    private final List<String> details;
    private final Runnable onConfirm;
    private final Runnable onCancel;

    public ConfirmMenu(Player viewer, String question, List<String> details,
                       Runnable onConfirm, Runnable onCancel) {
        super(viewer);
        this.question = question;
        this.details = details;
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
    }

    @Override
    protected int rows() {
        return 3;
    }

    @Override
    protected Component title() {
        return Component.text(question, NamedTextColor.DARK_RED);
    }

    @Override
    protected void build() {
        set(13, Icon.of(Material.PAPER).name(question, NamedTextColor.WHITE)
                .lore(details, NamedTextColor.GRAY).build());
        set(11, Icon.of(Material.LIME_WOOL).name("Confirm", NamedTextColor.GREEN).build(),
                e -> {
                    viewer.closeInventory();
                    onConfirm.run();
                });
        set(15, Icon.of(Material.RED_WOOL).name("Cancel", NamedTextColor.RED).build(),
                e -> {
                    if (onCancel != null) {
                        onCancel.run();
                    } else {
                        viewer.closeInventory();
                    }
                });
    }

    String question() {
        return question;
    }

    List<String> details() {
        return details;
    }

    void confirm() {
        viewer.closeInventory();
        onConfirm.run();
    }

    void cancel() {
        if (onCancel != null) {
            onCancel.run();
        } else {
            viewer.closeInventory();
        }
    }
}
