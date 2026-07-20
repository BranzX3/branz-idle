package dev.branzx.idlefarm.service;

import dev.branzx.idlefarm.command.CommandLinks;
import dev.branzx.idlefarm.node.NodeRecord;
import dev.branzx.idlefarm.node.TrustLevel;
import dev.branzx.idlefarm.storage.NodeStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

public final class TrustService {

    private final NodeStore nodeStore;

    public TrustService(NodeStore nodeStore) {
        this.nodeStore = nodeStore;
    }

    /** True if {@code actor} may build/interact inside {@code node}'s chunk. */
    public boolean canBuild(UUID actor, NodeRecord node) {
        if (node.getOwnerUuid().equals(actor)) {
            return true;
        }
        TrustLevel level = nodeStore.getTrust(node.getOwnerUuid(), actor);
        return level != null && level.atLeast(TrustLevel.HELPER);
    }

    /** True if {@code actor} may collect buffers / view GUIs of the territory. */
    public boolean canHelp(UUID actor, UUID owner) {
        if (owner.equals(actor)) {
            return true;
        }
        TrustLevel level = nodeStore.getTrust(owner, actor);
        return level != null && level.atLeast(TrustLevel.HELPER);
    }

    /** True if {@code actor} may manage workers / start exploration events. */
    public boolean canManage(UUID actor, UUID owner) {
        if (owner.equals(actor)) {
            return true;
        }
        TrustLevel level = nodeStore.getTrust(owner, actor);
        return level != null && level.atLeast(TrustLevel.MANAGER);
    }

    public void setTrust(UUID owner, UUID trusted, TrustLevel level) {
        nodeStore.setTrust(owner, trusted, level);
        Player target = Bukkit.getPlayer(trusted);
        if (target == null) {
            return;
        }
        String ownerName = Bukkit.getOfflinePlayer(owner).getName();
        var line = Component.text()
                .append(Component.text("[Trust] ", NamedTextColor.GOLD))
                .append(Component.text((ownerName == null ? "A player" : ownerName)
                        + " granted you " + level + " access to their territory. ",
                        NamedTextColor.GREEN));
        if (ownerName != null) {
            line.append(CommandLinks.run("[Visit]", "/idle visit " + ownerName));
        }
        target.sendMessage(line.build());
    }

    public void removeTrust(UUID owner, UUID trusted) {
        nodeStore.removeTrust(owner, trusted);
        Player target = Bukkit.getPlayer(trusted);
        if (target != null) {
            String ownerName = Bukkit.getOfflinePlayer(owner).getName();
            target.sendMessage(Component.text("[Trust] "
                    + (ownerName == null ? "A player" : ownerName)
                    + " removed your territory access.", NamedTextColor.GRAY));
        }
    }
}
