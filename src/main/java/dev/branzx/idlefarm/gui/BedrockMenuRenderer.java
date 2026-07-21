package dev.branzx.idlefarm.gui;

import dev.branzx.idlefarm.IdleFarmPlugin;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Converts every service-backed Menu into a native Bedrock form. Menu slot
 * handlers remain the single source of truth, so Java and Bedrock execute
 * exactly the same validated actions.
 */
public final class BedrockMenuRenderer implements MenuRenderer, ChatPrompt.NativeInput {

    private static final PlainTextComponentSerializer PLAIN =
            PlainTextComponentSerializer.plainText();

    private final IdleFarmPlugin plugin;
    private final AtomicLong sequence = new AtomicLong();
    private final Map<UUID, Long> activeView = new ConcurrentHashMap<>();

    public BedrockMenuRenderer(IdleFarmPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isBedrock(org.bukkit.entity.Player player) {
        try {
            return FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId());
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    @Override
    public boolean open(Menu menu) {
        if (!isBedrock(menu.viewer())) {
            return false;
        }
        long viewId = sequence.incrementAndGet();
        activeView.put(menu.viewer().getUniqueId(), viewId);
        if (menu instanceof ConfirmMenu confirm) {
            return openConfirmation(confirm);
        }

        List<Integer> slots = menu.actions().keySet().stream()
                .sorted(Comparator.naturalOrder()).toList();
        SimpleForm.Builder form = SimpleForm.builder()
                .title(clean(PLAIN.serialize(menu.menuTitle())))
                .content(summary(menu, slots));

        List<Integer> renderedSlots = new ArrayList<>();
        for (int slot : slots) {
            ItemStack item = menu.getInventory().getItem(slot);
            if (item == null || item.getType().isAir()) {
                continue;
            }
            renderedSlots.add(slot);
            form.button(buttonText(item));
        }
        form.validResultHandler(response ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    int index = response.clickedButtonId();
                    if (menu.viewer().isOnline() && index >= 0 && index < renderedSlots.size()) {
                        int slot = renderedSlots.get(index);
                        ItemStack selected = menu.getInventory().getItem(slot);
                        boolean closes = isCloseAction(selected);
                        menu.activate(slot);
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            if (!closes && menu.viewer().isOnline()
                                    && activeView.getOrDefault(menu.viewer().getUniqueId(), -1L)
                                    == viewId) {
                                menu.open();
                            }
                        });
                    }
                }));
        return FloodgateApi.getInstance().sendForm(menu.viewer().getUniqueId(), form);
    }

    private boolean openConfirmation(ConfirmMenu menu) {
        String content = String.join("\n", menu.details());
        ModalForm form = ModalForm.builder()
                .title(clean(menu.question()))
                .content(content.isBlank() ? menu.question() : content)
                .button1("ยืนยัน")
                .button2("ยกเลิก")
                .validResultHandler(response ->
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            if (!menu.viewer().isOnline()) {
                                return;
                            }
                            if (response.clickedFirst()) {
                                menu.confirm();
                            } else {
                                menu.cancel();
                            }
                        }))
                .closedOrInvalidResultHandler(() ->
                        plugin.getServer().getScheduler().runTask(plugin, menu::cancel))
                .build();
        return FloodgateApi.getInstance().sendForm(menu.viewer().getUniqueId(), form);
    }

    @Override
    public boolean request(org.bukkit.entity.Player player, String message,
                           Consumer<String> onInput, Runnable onCancel) {
        if (!isBedrock(player)) {
            return false;
        }
        activeView.put(player.getUniqueId(), sequence.incrementAndGet());
        CustomForm form = CustomForm.builder()
                .title("IdleFarm")
                .input(clean(message), "กรอกค่า")
                .validResultHandler(response ->
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            String value = response.asInput(0);
                            if (value == null || value.isBlank()) {
                                if (onCancel != null) onCancel.run();
                            } else {
                                onInput.accept(value.trim());
                            }
                        }))
                .closedOrInvalidResultHandler(() ->
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            if (onCancel != null) onCancel.run();
                        }))
                .build();
        return FloodgateApi.getInstance().sendForm(player.getUniqueId(), form);
    }

    private String summary(Menu menu, List<Integer> actionSlots) {
        List<String> lines = new ArrayList<>();
        for (int slot = 0; slot < menu.getInventory().getSize() && lines.size() < 8; slot++) {
            if (actionSlots.contains(slot)) continue;
            // Skip by blank name rather than by material: the border panes are
            // decoration and carry no name, while the Trade divider is a pane
            // that spells out both players' confirmation state and must show.
            ItemStack item = menu.getInventory().getItem(slot);
            if (item == null || item.getType().isAir()) continue;
            String name = itemName(item);
            if (!name.isBlank() && !lines.contains(name)) lines.add(name);
        }
        if (lines.isEmpty()) {
            lines.add("เลือกสิ่งที่ต้องการทำ");
        }
        return String.join("\n", lines);
    }

    private String buttonText(ItemStack item) {
        String name = itemName(item);
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.lore() != null) {
            for (var line : meta.lore()) {
                String text = clean(PLAIN.serialize(line));
                if (!text.isBlank() && !text.toLowerCase().contains("click")
                        && !text.toLowerCase().contains("shift")
                        && !text.equals(name)) {
                    return truncate(name, 48) + "\n" + truncate(text, 64);
                }
            }
        }
        return truncate(name, 48);
    }

    private String itemName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.displayName() != null) {
            return clean(PLAIN.serialize(meta.displayName()));
        }
        return Ui.pretty(item.getType().name());
    }

    private boolean isCloseAction(ItemStack item) {
        if (item == null || item.getType() != org.bukkit.Material.BARRIER) return false;
        String name = itemName(item).toLowerCase(java.util.Locale.ROOT);
        return name.contains("close") || name.contains("ปิด");
    }

    private String clean(String value) {
        return value.replace('§', ' ').replaceAll("\\s+", " ").trim();
    }

    private String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
