package dev.branzx.idlefarm.service;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.node.ChunkKey;
import dev.branzx.idlefarm.node.NodeRecord;
import dev.branzx.idlefarm.node.NodeType;
import dev.branzx.idlefarm.storage.NodeStore;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spawns decorative Citizens NPCs for production nodes in loaded chunks.
 * NPCs live in a non-persistent in-memory registry: they are respawned from
 * node state on every chunk load, so Citizens never saves them itself and a
 * stale save file can never desync from the node database.
 *
 * Until the worker-item system (Phase 3) lands, each production node shows
 * {@code tier} generic workers.
 */
public final class WorkerNpcManager implements Listener {

    private final IdleFarmPlugin plugin;
    private final NodeStore nodeStore;
    private final SchematicService schematicService;
    private NPCRegistry registry;
    private final Map<Long, List<NPC>> npcsByNode = new ConcurrentHashMap<>();

    public WorkerNpcManager(IdleFarmPlugin plugin, NodeStore nodeStore, SchematicService schematicService) {
        this.plugin = plugin;
        this.nodeStore = nodeStore;
        this.schematicService = schematicService;
    }

    public void init() {
        this.registry = CitizensAPI.createInMemoryNPCRegistry("idlefarm");
        // Cover chunks that were already loaded before the plugin enabled.
        for (World world : plugin.getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                spawnForChunk(chunk);
            }
        }
    }

    public void shutdown() {
        for (List<NPC> npcs : npcsByNode.values()) {
            for (NPC npc : npcs) {
                npc.destroy();
            }
        }
        npcsByNode.clear();
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        spawnForChunk(event.getChunk());
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        NodeRecord node = nodeAt(event.getChunk());
        if (node != null) {
            despawnNode(node.getId());
        }
    }

    /** Called after a claim builds housing. */
    public void spawnForNode(NodeRecord node, World world) {
        if (!node.getType().isProduction()) {
            return;
        }
        despawnNode(node.getId());
        Location front = schematicService.housingFront(node, world);
        List<NPC> npcs = new ArrayList<>();
        for (int i = 0; i < node.getTier(); i++) {
            NPC npc = registry.createNPC(EntityType.VILLAGER, workerName(node, i));
            npc.setProtected(true);
            npc.data().set(NPC.Metadata.NAMEPLATE_VISIBLE, true);
            Location spot = front.clone().add((i % 3) - 1, 0, i / 3);
            npc.spawn(spot);
            if (npc.getEntity() instanceof Villager villager) {
                villager.setProfession(professionFor(node.getType()));
                villager.setAI(false);
                villager.setSilent(true);
            }
            npcs.add(npc);
        }
        npcsByNode.put(node.getId(), npcs);
    }

    public void despawnNode(long nodeId) {
        List<NPC> npcs = npcsByNode.remove(nodeId);
        if (npcs != null) {
            for (NPC npc : npcs) {
                npc.destroy();
            }
        }
    }

    private void spawnForChunk(Chunk chunk) {
        NodeRecord node = nodeAt(chunk);
        if (node != null && node.getType().isProduction() && !npcsByNode.containsKey(node.getId())) {
            spawnForNode(node, chunk.getWorld());
        }
    }

    private NodeRecord nodeAt(Chunk chunk) {
        return nodeStore.getByChunk(new ChunkKey(chunk.getWorld().getName(), chunk.getX(), chunk.getZ()));
    }

    private String workerName(NodeRecord node, int index) {
        return "Worker #" + (index + 1);
    }

    private Villager.Profession professionFor(NodeType type) {
        return switch (type) {
            case MINING -> Villager.Profession.ARMORER;
            case FARMING -> Villager.Profession.FARMER;
            case WOODCUTTING -> Villager.Profession.FLETCHER;
            case LIVESTOCK -> Villager.Profession.BUTCHER;
            case HUNTER -> Villager.Profession.LEATHERWORKER;
            default -> Villager.Profession.NONE;
        };
    }
}
