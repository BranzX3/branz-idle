package dev.branzx.idle.service;

import dev.branzx.idle.IdlePlugin;
import dev.branzx.idle.node.ChunkKey;
import dev.branzx.idle.node.NodeRecord;
import dev.branzx.idle.node.NodeType;
import dev.branzx.idle.schematic.RelPos;
import dev.branzx.idle.schematic.SchematicDefinition;
import dev.branzx.idle.storage.NodeStore;
import dev.branzx.idle.storage.WorkerStore;
import dev.branzx.idle.worker.WorkerRecord;
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

    private static final class SpawnedNpc {
        final NPC npc;
        final WorkerRecord worker;
        final int slotIndex;
        /** True once the NPC walked off-screen for WORKING (departed state). */
        boolean departed;
        /** Where the NPC despawned; it reappears there and walks home. */
        Location departLocation;

        SpawnedNpc(NPC npc, WorkerRecord worker, int slotIndex) {
            this.npc = npc;
            this.worker = worker;
            this.slotIndex = slotIndex;
        }

        NPC npc() {
            return npc;
        }

        WorkerRecord worker() {
            return worker;
        }

        int slotIndex() {
            return slotIndex;
        }
    }

    private final IdlePlugin plugin;
    private final NodeStore nodeStore;
    private final SchematicService schematicService;
    private final WorkerStore workerStore;
    private final NodeAnchorStore anchorStore;
    private NPCRegistry registry;
    private final Map<Long, List<SpawnedNpc>> npcsByNode = new ConcurrentHashMap<>();
    private final Map<Long, String> overrideState = new ConcurrentHashMap<>();
    private BukkitRunnable animationTask;

    /** Spawn location: worker's own override → tier preset → auto-layout. */
    private org.bukkit.Location spawnLocation(NodeRecord node, World world,
                                              SchematicDefinition definition,
                                              WorkerRecord worker, int slot) {
        if (anchorStore != null) {
            var override = anchorStore.get(worker.getWorkerUuid());
            if (override != null) {
                return override.spawn(world);
            }
        }
        return schematicService.resolve(node, world, definition.spawnAnchorOrFallback(slot));
    }

    /** Work location: worker's own override → tier preset → spawn fallback. */
    private org.bukkit.Location workLocation(NodeRecord node, World world,
                                             SchematicDefinition definition,
                                             WorkerRecord worker, int slot) {
        if (anchorStore != null) {
            var override = anchorStore.get(worker.getWorkerUuid());
            if (override != null) {
                return override.work(world);
            }
        }
        return schematicService.resolve(node, world, definition.workAnchorOrFallback(slot));
    }

    public WorkerNpcManager(IdlePlugin plugin, NodeStore nodeStore,
                            SchematicService schematicService, WorkerStore workerStore,
                            NodeAnchorStore anchorStore) {
        this.plugin = plugin;
        this.nodeStore = nodeStore;
        this.schematicService = schematicService;
        this.workerStore = workerStore;
        this.anchorStore = anchorStore;
    }

    public void init() {
        this.registry = CitizensAPI.createInMemoryNPCRegistry("idle");
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
        SchematicDefinition definition = schematicService.getRegistry()
                .forNodeType(node.getType(), node.getTier());
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
            npc.spawn(spawnLocation(node, world, definition, worker, slot));
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
                if (node == null) {
                    Location ref = npc.isSpawned() ? npc.getEntity().getLocation() : spawned.departLocation;
                    if (ref == null) {
                        continue;
                    }
                    node = nodeStore.getByChunk(new ChunkKey(
                            ref.getWorld().getName(), ref.getBlockX() >> 4, ref.getBlockZ() >> 4));
                    if (node == null) {
                        break;
                    }
                }
                // Departed NPC whose state no longer wants "depart": bring it
                // back at the point it left and let return-behaviors walk it home.
                if (spawned.departed && !npc.isSpawned()) {
                    String state = effectiveState(nodeId, spawned.worker());
                    if (!currentBehaviors(node, state).contains("depart")) {
                        npc.spawn(spawned.departLocation);
                        spawned.departed = false;
                        spawned.departLocation = null;
                    } else {
                        continue; // still away working
                    }
                }
                if (!npc.isSpawned()) {
                    continue;
                }
                applyBehaviors(node, spawned);
            }
        }
    }

    private List<String> currentBehaviors(NodeRecord node, String state) {
        SchematicDefinition definition = schematicService.getRegistry()
                .forNodeType(node.getType(), node.getTier());
        String profileName = definition.getProfiles().getOrDefault(state,
                "default_" + state.toLowerCase(Locale.ROOT));
        List<String> behaviors = plugin.getConfig().getStringList("npc.animation-profiles." + profileName);
        return behaviors.isEmpty() ? List.of("stand") : behaviors;
    }

    private void applyBehaviors(NodeRecord node, SpawnedNpc spawned) {
        String state = effectiveState(node.getId(), spawned.worker());
        SchematicDefinition definition = schematicService.getRegistry()
                .forNodeType(node.getType(), node.getTier());
        List<String> behaviors = currentBehaviors(node, state);

        NPC npc = spawned.npc();
        World world = npc.getEntity().getWorld();
        boolean sneaking = false;
        for (String behavior : behaviors) {
            switch (behavior.toLowerCase(Locale.ROOT)) {
                case "wander" -> wander(node, definition, npc, world);
                case "walk_worksite" -> walkWorksite(node, definition, spawned, world);
                case "return_spawn" -> returnSpawn(node, definition, spawned, world);
                case "depart" -> depart(node, definition, spawned, world);
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
        // Alternate between this slot's own work point and its spawn point.
        Location target = ThreadLocalRandom.current().nextBoolean()
                ? workLocation(node, world, definition, spawned.worker(), spawned.slotIndex())
                : spawnLocation(node, world, definition, spawned.worker(), spawned.slotIndex());
        npc.getNavigator().setTarget(target);
    }

    /** Walk back to this worker's own spawn anchor and stay there. */
    private void returnSpawn(NodeRecord node, SchematicDefinition definition, SpawnedNpc spawned, World world) {
        NPC npc = spawned.npc();
        Location home = spawnLocation(node, world, definition, spawned.worker(), spawned.slotIndex());
        if (npc.getEntity().getLocation().distanceSquared(home) > 2.25) {
            if (!npc.getNavigator().isNavigating()) {
                npc.getNavigator().setTarget(home);
            }
        } else {
            npc.getNavigator().cancelNavigation();
        }
    }

    /**
     * WORKING visual: walk off toward this slot's work anchor and despawn on
     * arrival — "gone out to work". The NPC reappears at that point and
     * walks home when the state changes.
     */
    private void depart(NodeRecord node, SchematicDefinition definition, SpawnedNpc spawned, World world) {
        NPC npc = spawned.npc();
        Location target = workLocation(node, world, definition, spawned.worker(), spawned.slotIndex());
        if (npc.getEntity().getLocation().distanceSquared(target) <= 4.0) {
            spawned.departed = true;
            spawned.departLocation = npc.getEntity().getLocation().clone();
            npc.despawn();
            return;
        }
        if (!npc.getNavigator().isNavigating()) {
            npc.getNavigator().setTarget(target);
        }
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
