package dev.branzx.idlefarm.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.function.Consumer;

/** Shared reason + confirmation workflow for admin mutations. */
public final class AdminUiFlow {

    private AdminUiFlow() {
    }

    public static void requireReason(Player player, GuiManager gui, String question,
                                     List<String> details, Consumer<String> action,
                                     Runnable onCancel) {
        gui.chatPrompt().request(player, "ระบุเหตุผลสำหรับ audit", raw -> {
            String reason = raw.trim();
            if (reason.isBlank()) {
                player.sendMessage(Component.text("ต้องระบุเหตุผล", NamedTextColor.RED));
                onCancel.run();
                return;
            }
            List<String> confirmedDetails = new java.util.ArrayList<>(details);
            confirmedDetails.add("Reason: " + reason);
            new ConfirmMenu(player, question, confirmedDetails,
                    () -> action.accept(reason), onCancel).open();
        }, onCancel);
    }
}
