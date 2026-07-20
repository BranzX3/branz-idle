package dev.branzx.idlefarm.service.design;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.node.NodeRecord;
import dev.branzx.idlefarm.node.NodeType;
import dev.branzx.idlefarm.service.GameDesignService.Project;
import dev.branzx.idlefarm.storage.NodeStore;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Renders Settlement Project progress as non-destructive Block Display
 * structures beside the owner's primary Residential Node.
 */
public final class ProjectWorldService implements Listener {
    private record Block(int x, int y, int z, String data) {}
    private record Definition(int offsetX, int offsetY, int offsetZ,
                              Map<Integer, List<Block>> stages) {}

    private final IdleFarmPlugin plugin;
    private final NodeStore nodes;
    private final Function<UUID, List<Project>> projectLookup;
    private final Supplier<Project> serverProjectLookup;
    private final NamespacedKey ownerKey;
    private final NamespacedKey projectKey;
    private final Map<String, Definition> definitions = new HashMap<>();

    public ProjectWorldService(IdleFarmPlugin plugin, NodeStore nodes,
                               Function<UUID, List<Project>> projectLookup,
                               Supplier<Project> serverProjectLookup) {
        this.plugin = plugin;
        this.nodes = nodes;
        this.projectLookup = projectLookup;
        this.serverProjectLookup = serverProjectLookup;
        this.ownerKey = new NamespacedKey(plugin, "project_owner");
        this.projectKey = new NamespacedKey(plugin, "project_id");
        loadDefinitions();
    }

    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getScheduler().runTask(plugin, this::reconcileLoadedWorlds);
    }

    public void render(UUID owner, String projectId, int stage) {
        NodeRecord anchor = anchor(owner);
        remove(owner, projectId);
        if (anchor == null || stage <= 0) return;
        World world = plugin.getServer().getWorld(anchor.getChunk().world());
        if (world == null || !world.isChunkLoaded(anchor.getChunk().x(), anchor.getChunk().z())) return;
        Definition definition = definitions.get(projectId.toLowerCase(Locale.ROOT));
        if (definition == null) return;
        List<Block> blocks = definition.stages().get(stage);
        if (blocks == null) return;
        int originX = (anchor.getChunk().x() << 4) + 8 + definition.offsetX();
        int originY = anchor.getOriginY() + definition.offsetY();
        int originZ = (anchor.getChunk().z() << 4) + 8 + definition.offsetZ();
        for (Block block : blocks) {
            Location location = new Location(world, originX + block.x(),
                    originY + block.y(), originZ + block.z());
            try {
                world.spawn(location, BlockDisplay.class, display -> {
                    display.setBlock(plugin.getServer().createBlockData(block.data()));
                    display.setPersistent(true);
                    display.getPersistentDataContainer().set(ownerKey,
                            PersistentDataType.STRING, owner.toString());
                    display.getPersistentDataContainer().set(projectKey,
                            PersistentDataType.STRING, projectId);
                });
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Invalid project block data for "
                        + projectId + ": " + block.data());
            }
        }
    }

    public void relocateAfterResidentialRemoved(UUID owner) {
        for (Project project : projectLookup.apply(owner)) {
            render(owner, project.id(), constructionStage(project));
        }
    }

    public void renderServer(Project project) {
        remove(new UUID(0, 0), "server_monument");
        int stage = constructionStage(project);
        if (stage <= 0 || plugin.getServer().getWorlds().isEmpty()) return;
        String configuredWorld = plugin.getConfig().getString("projects.server.world", "");
        World world = configuredWorld == null || configuredWorld.isBlank()
                ? plugin.getServer().getWorlds().getFirst()
                : plugin.getServer().getWorld(configuredWorld);
        if (world == null) return;
        Location spawn = world.getSpawnLocation();
        Definition definition = definitions.get("server_monument");
        if (definition == null) return;
        List<Block> blocks = definition.stages().get(stage);
        if (blocks == null) return;
        int originX = spawn.getBlockX() + definition.offsetX();
        int originY = spawn.getBlockY() + definition.offsetY();
        int originZ = spawn.getBlockZ() + definition.offsetZ();
        if (!world.isChunkLoaded(originX >> 4, originZ >> 4)) return;
        spawnBlocks(new UUID(0, 0), "server_monument", world,
                originX, originY, originZ, blocks);
    }

    public static int constructionStage(Project project) {
        if (project.target() <= 0 || project.current() <= 0) return 0;
        double ratio = project.current() / (double) project.target();
        if (ratio >= 1.0) return 4;
        if (ratio >= 0.75) return 3;
        if (ratio >= 0.50) return 2;
        if (ratio >= 0.25) return 1;
        return 0;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        nodes.getAll().stream()
                .filter(node -> node.getType() == NodeType.RESIDENTIAL)
                .filter(node -> node.getChunk().world().equals(event.getWorld().getName()))
                .filter(node -> node.getChunk().x() == event.getChunk().getX()
                        && node.getChunk().z() == event.getChunk().getZ())
                .map(NodeRecord::getOwnerUuid).distinct().forEach(this::reconcile);
        Location spawn = event.getWorld().getSpawnLocation();
        Definition server = definitions.get("server_monument");
        if (server != null
                && (spawn.getBlockX() + server.offsetX()) >> 4 == event.getChunk().getX()
                && (spawn.getBlockZ() + server.offsetZ()) >> 4 == event.getChunk().getZ()) {
            renderServer(serverProjectLookup.get());
        }
    }

    private void reconcileLoadedWorlds() {
        nodes.getAll().stream()
                .filter(node -> node.getType() == NodeType.RESIDENTIAL)
                .map(NodeRecord::getOwnerUuid).distinct().forEach(this::reconcile);
        renderServer(serverProjectLookup.get());
    }

    private void reconcile(UUID owner) {
        for (Project project : projectLookup.apply(owner)) {
            render(owner, project.id(), constructionStage(project));
        }
    }

    private NodeRecord anchor(UUID owner) {
        return nodes.getByOwner(owner).stream()
                .filter(node -> node.getType() == NodeType.RESIDENTIAL)
                .min(Comparator.comparingLong(NodeRecord::getId)).orElse(null);
    }

    private void remove(UUID owner, String projectId) {
        String ownerText = owner.toString();
        for (World world : plugin.getServer().getWorlds()) {
            for (BlockDisplay display : world.getEntitiesByClass(BlockDisplay.class)) {
                String taggedOwner = display.getPersistentDataContainer()
                        .get(ownerKey, PersistentDataType.STRING);
                String taggedProject = display.getPersistentDataContainer()
                        .get(projectKey, PersistentDataType.STRING);
                if (ownerText.equals(taggedOwner) && projectId.equalsIgnoreCase(taggedProject)) {
                    display.remove();
                }
            }
        }
    }

    private void spawnBlocks(UUID owner, String projectId, World world,
                             int originX, int originY, int originZ, List<Block> blocks) {
        for (Block block : blocks) {
            Location location = new Location(world, originX + block.x(),
                    originY + block.y(), originZ + block.z());
            try {
                world.spawn(location, BlockDisplay.class, display -> {
                    display.setBlock(plugin.getServer().createBlockData(block.data()));
                    display.setPersistent(true);
                    display.getPersistentDataContainer().set(ownerKey,
                            PersistentDataType.STRING, owner.toString());
                    display.getPersistentDataContainer().set(projectKey,
                            PersistentDataType.STRING, projectId);
                });
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Invalid project block data for "
                        + projectId + ": " + block.data());
            }
        }
    }

    private void loadDefinitions() {
        File file = new File(plugin.getDataFolder(), "project-stages.yml");
        if (!file.exists()) plugin.saveResource("project-stages.yml", false);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection projects = yaml.getConfigurationSection("projects");
        if (projects == null) return;
        for (String id : projects.getKeys(false)) {
            ConfigurationSection section = projects.getConfigurationSection(id);
            if (section == null) continue;
            List<Integer> offset = section.getIntegerList("offset");
            int x = offset.size() > 0 ? offset.get(0) : 0;
            int y = offset.size() > 1 ? offset.get(1) : 0;
            int z = offset.size() > 2 ? offset.get(2) : 0;
            Map<Integer, List<Block>> stages = new HashMap<>();
            for (int stage = 1; stage <= 4; stage++) {
                List<Block> blocks = new ArrayList<>();
                for (String encoded : section.getStringList("stages." + stage)) {
                    int pipe = encoded.indexOf('|');
                    if (pipe < 0) continue;
                    String[] coords = encoded.substring(0, pipe).split(",");
                    if (coords.length != 3) continue;
                    try {
                        blocks.add(new Block(Integer.parseInt(coords[0]),
                                Integer.parseInt(coords[1]), Integer.parseInt(coords[2]),
                                encoded.substring(pipe + 1)));
                    } catch (NumberFormatException ignored) {
                        plugin.getLogger().warning("Invalid project-stage coordinate: " + encoded);
                    }
                }
                stages.put(stage, List.copyOf(blocks));
            }
            definitions.put(id.toLowerCase(Locale.ROOT),
                    new Definition(x, y, z, Map.copyOf(stages)));
        }
    }
}
