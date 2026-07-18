package dev.branzx.idlefarm.service;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.node.ChunkKey;
import dev.branzx.idlefarm.node.NodeRecord;
import dev.branzx.idlefarm.node.NodeType;
import dev.branzx.idlefarm.schematic.RelPos;
import dev.branzx.idlefarm.schematic.SchematicDefinition;
import dev.branzx.idlefarm.storage.NodeStore;
import dev.branzx.idlefarm.storage.WorkerStore;
import dev.branzx.idlefarm.worker.WorkerRecord;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.trait.LookClose;
import net.citizensnpcs.trait.SkinTrait;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Spawns decorative Citizens NPCs for production nodes in loaded chunks and
 * drives their state-driven animation behaviors.
 *
 * NPCs live in a non-persistent in-memory registry: they are respawned from
 * node state on every chunk load, so Citizens never saves them itself and a
 * stale save file can never desync from the node database.
 *
 * Animation model: worker state (WORKING/IDLE/STOP) -> profile name from the
 * node's schematic definition (or "default_<state>") -> behavior list from
 * config "npc.animation-profiles". Admins can force a state per node for
 * previewing via {@link #overrideState}.
 */
public final class WorkerNpcManager implements Listener {

    private record SpawnedNpc(NPC npc, WorkerRecord worker, int slotIndex) {
    }

    private final IdleFarmPlugin plugin;
    private final NodeStore nodeStore;
    private final SchematicService schematicService;
    private WorkerStore workerStore;
    private NPCRegistry registry;
    private final Map<Long, List<SpawnedNpc>> npcsByNode = new ConcurrentHashMap<>();
    private final Map<Long, String> overrideState = new ConcurrentHashMap<>();
    private BukkitRunnable animationTask;

    public WorkerNpcManager(IdleFarmPlugin plugin, NodeStore nodeStore, SchematicService schematicService) {
        this.plugin = plugin;
        this.nodeStore = nodeStore;
        this.schematicService = schematicService;
    }

    public void setWorkerStore(WorkerStore workerStore) {
        this.workerStore = workerStore;
    }

    public void init() {
        this.registry = CitizensAPI.createInMemoryNPCRegistry("idlefarm");
        for (World world : plugin.getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                spawnForChunk(chunk);
            }
        }
        long interval = plugin.getConfig().getLong("npc.animation-interval-ticks", 60L);
        this.animationTask = new BukkitRunnable() {
            @Override
            public void run() {
                tickAnimations();
            }
        };
        this.animationTask.runTaskTimer(plugin, interval, interval);
    }

    public void shutdown() {
        if (animationTask != null) {
            animationTask.cancel();
        }
        for (List<SpawnedNpc> npcs : npcsByNode.values()) {
            for (SpawnedNpc spawned : npcs) {
                spawned.npc().destroy();
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

    // ---- spawning ----

    public void spawnForNode(NodeRecord node, World world) {
        if (!node.getType().isProduction()) {
            return;
        }
        despawnNode(node.getId());
        SchematicDefinition definition = schematicService.getRegistry().forNodeType(node.getType());
        List<WorkerRecord> assigned = workerStore == null ? List.of() : workerStore.getAssigned(node.getId());
        List<SpawnedNpc> npcs = new ArrayList<>();
        int slot = 0;
        for (WorkerRecord worker : assigned) {
            if (WorkerRecord.STATE_EXPLORING.equals(worker.getState())) {
                slot++;
                continue; // away on an exploration run — not visible
            }
            NPC npc = registry.createNPC(EntityType.PLAYER,
                    worker.getName() + " [" + worker.getRarity() + "]");
            npc.setProtected(true);
            npc.data().set(NPC.Metadata.NAMEPLATE_VISIBLE, true);
            npc.getOrAddTrait(SkinTrait.class).setSkinName(worker.getSkin());
            RelPos anchor = definition.spawnAnchorOrFallback(slot);
            npc.spawn(schematicService.resolve(node, world, anchor));
            npcs.add(new SpawnedNpc(npc, worker, slot));
            slot++;
        }
        npcsByNode.put(node.getId(), npcs);
    }

    /** Re-sync NPCs after assignment changes. */
    public void refreshNode(NodeRecord node, World world) {
        spawnForNode(node, world);
    }

    public void despawnNode(long nodeId) {
        List<SpawnedNpc> npcs = npcsByNode.remove(nodeId);
        if (npcs != null) {
            for (SpawnedNpc spawned : npcs) {
                spawned.npc().destroy();
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

    // ---- admin hooks ----

    /** Force a visual state for a node (null clears). Preview tool only. */
    public void setStateOverride(long nodeId, String state) {
        if (state == null) {
            overrideState.remove(nodeId);
        } else {
            overrideState.put(nodeId, state.toUpperCase(Locale.ROOT));
        }
    }

    public List<String> describeNode(long nodeId) {
        List<String> lines = new ArrayList<>();
        for (SpawnedNpc spawned : npcsByNode.getOrDefault(nodeId, List.of())) {
            lines.add("slot " + (spawned.slotIndex() + 1) + ": " + spawned.worker().getName()
                    + " [" + spawned.worker().getRarity() + "] state=" + effectiveState(nodeId, spawned.worker()));
        }
        return lines;
    }

    // ---- animation engine ----

    private String effectiveState(long nodeId, WorkerRecord worker) {
        String override = overrideState.get(nodeId);
        return override != null ? override : worker.getState();
    }

    private void tickAnimations() {
        for (Map.Entry<Long, List<SpawnedNpc>> entry : npcsByNode.entrySet()) {
            long nodeId = entry.getKey();
            if (entry.getValue().isEmpty()) {
                continue;
            }
            NodeRecord node = null;
            for (SpawnedNpc spawned : entry.getValue()) {
                NPC npc = spawned.npc();
                if (!npc.isSpawned()) {
                    continue;
                }
                if (node == null) {
                    node = nodeStore.getByChunk(new ChunkKey(
                            npc.getEntity().getWorld().getName(),
                            npc.getEntity().getLocation().getBlockX() >> 4,
                            npc.getEntity().getLocation().getBlockZ() >> 4));
                    if (node == null) {
                        break;
                    }
                }
                applyBehaviors(node, spawned);
            }
        }
    }

    private void applyBehaviors(NodeRecord node, SpawnedNpc spawned) {
        String state = effectiveState(node.getId(), spawned.worker());
        SchematicDefinition definition = schematicService.getRegistry().forNodeType(node.getType());
        String profileName = definition.getProfiles().getOrDefault(state,
                "default_" + state.toLowerCase(Locale.ROOT));
        List<String> behaviors = plugin.getConfig().getStringList("npc.animation-profiles." + profileName);
        if (behaviors.isEmpty()) {
            behaviors = List.of("stand");
        }

        NPC npc = spawned.npc();
        World world = npc.getEntity().getWorld();
        boolean sneaking = false;
        for (String behavior : behaviors) {
            switch (behavior.toLowerCase(Locale.ROOT)) {
                case "wander" -> wander(node, definition, npc, world);
                case "walk_worksite" -> walkWorksite(node, definition, spawned, world);
                case "hold_tool" -> holdTool(node, npc);
                case "clear_tool" -> npc.getOrAddTrait(Equipment.class)
                        .set(Equipment.EquipmentSlot.HAND, null);
                case "swing" -> swing(npc);
                case "look_close" -> npc.getOrAddTrait(LookClose.class).lookClose(true);
                case "sneak" -> sneaking = true;
                case "stand" -> npc.getNavigator().cancelNavigation();
                default -> { }
            }
        }
        if (npc.getEntity() instanceof Player playerEntity) {
            playerEntity.setSneaking(sneaking);
        }
    }

    private void wander(NodeRecord node, SchematicDefinition definition, NPC npc, World world) {
        if (npc.getNavigator().isNavigating() || ThreadLocalRandom.current().nextDouble() > 0.4) {
            return;
        }
        int radius = definition.getWanderRadius();
        Location origin = schematicService.origin(node, world);
        Location target = origin.add(
                ThreadLocalRandom.current().nextInt(-radius, radius + 1) + 0.5,
                0,
                ThreadLocalRandom.current().nextInt(-radius, radius + 1) + 0.5);
        target.setY(world.getHighestBlockYAt(target.getBlockX(), target.getBlockZ()) + 1);
        npc.getNavigator().setTarget(target);
    }

    private void walkWorksite(NodeRecord node, SchematicDefinition definition, SpawnedNpc spawned, World world) {
        NPC npc = spawned.npc();
        if (npc.getNavigator().isNavigating() || ThreadLocalRandom.current().nextDouble() > 0.5) {
            return;
        }
        List<RelPos> sites = definition.getWorkAnchors();
        RelPos target;
        // Alternate between a work anchor and the worker's own spawn anchor.
        if (!sites.isEmpty() && ThreadLocalRandom.current().nextBoolean()) {
            target = sites.get(ThreadLocalRandom.current().nextInt(sites.size()));
        } else {
            target = definition.spawnAnchorOrFallback(spawned.slotIndex());
        }
        npc.getNavigator().setTarget(schematicService.resolve(node, world, target));
    }

    private void holdTool(NodeRecord node, NPC npc) {
        Material tool = switch (node.getType()) {
            case MINING -> Material.IRON_PICKAXE;
            case FARMING -> Material.IRON_HOE;
            case WOODCUTTING -> Material.IRON_AXE;
            case LIVESTOCK -> Material.SHEARS;
            case HUNTER -> Material.IRON_SWORD;
            default -> null;
        };
        if (tool != null) {
            Equipment equipment = npc.getOrAddTrait(Equipment.class);
            ItemStack current = equipment.get(Equipment.EquipmentSlot.HAND);
            if (current == null || current.getType() != tool) {
                equipment.set(Equipment.EquipmentSlot.HAND, new ItemStack(tool));
            }
        }
    }

    private void swing(NPC npc) {
        if (npc.getEntity() instanceof Player playerEntity
                && ThreadLocalRandom.current().nextDouble() < 0.7) {
            playerEntity.swingMainHand();
        }
    }
}
