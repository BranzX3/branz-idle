package dev.branzx.idle.service.design;

import dev.branzx.idle.node.NodeRecord;
import dev.branzx.idle.node.NodeType;
import dev.branzx.idle.service.AuditService;
import dev.branzx.idle.service.GameDesignService.Result;
import dev.branzx.idle.storage.Database;
import dev.branzx.idle.storage.GameStateStore;
import dev.branzx.idle.storage.PlayerData;
import dev.branzx.idle.storage.PlayerDataStore;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Node specialization builds (Lv.25/50/75) and per-type perks
 * (Lv.15/35/60/85), plus every multiplier derived from those choices.
 * A paid respec commits the Coin cost and the new build in one transaction.
 */
public final class NodeBuildService {

    private final Database database;
    private final GameStateStore state;
    private final PlayerDataStore dataStore;
    private final AuditService audit;
    private final SeasonService seasons;

    public NodeBuildService(Database database, GameStateStore state, PlayerDataStore dataStore,
                            AuditService audit, SeasonService seasons) {
        this.database = database;
        this.state = state;
        this.dataStore = dataStore;
        this.audit = audit;
        this.seasons = seasons;
    }

    public String specialization(NodeRecord node) {
        return choice(node, "specialization");
    }

    public String refinement(NodeRecord node) {
        return choice(node, "refinement");
    }

    public String mastery(NodeRecord node) {
        return choice(node, "mastery");
    }

    private String choice(NodeRecord node, String tier) {
        return DesignText.valueOr(
                state.get(node.getOwnerUuid(), "NODE", String.valueOf(node.getId()), tier),
                "UNSELECTED");
    }

    public Result selectBuild(UUID owner, NodeRecord node, String tier, String choice) {
        if (node == null || !node.getOwnerUuid().equals(owner)) return Result.fail("You do not own that node.");
        String normalizedTier = tier.toLowerCase(Locale.ROOT);
        String normalized = choice.toUpperCase(Locale.ROOT);
        int required = switch (normalizedTier) {
            case "specialization" -> 25;
            case "refinement" -> 50;
            case "mastery" -> 75;
            default -> -1;
        };
        if (required < 0) return Result.fail("Use specialization, refinement, or mastery.");
        if (node.getExplorationLevel() < required) return Result.fail("Unlocks at Node Lv." + required + ".");
        if (!validBuildChoice(normalizedTier, normalized, specialization(node))) {
            return Result.fail("That choice is not valid for the current branch.");
        }
        String scope = String.valueOf(node.getId());
        String old = state.get(owner, "NODE", scope, normalizedTier);
        if (old != null && !old.equals(normalized)) {
            long cooldown = state.getLong(owner, "NODE", scope, "respec_cooldown", 0);
            if (cooldown > System.currentTimeMillis()) {
                return Result.fail("Respec available in "
                        + DesignText.formatDuration(cooldown - System.currentTimeMillis()) + ".");
            }
            boolean free = !"1".equals(state.get(owner, "NODE", scope, "free_respec_used"));
            double cost = free ? 0 : 500 + node.getExplorationLevel() * 25;
            PlayerData data = dataStore.getOnline(owner);
            if (cost > 0 && (data == null || data.getBalance() < cost)) {
                return Result.fail("Respec costs " + (long) cost + " Coins.");
            }
            long nextCooldown = System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000;
            if (cost > 0) {
                // The Coin charge and the respec state must settle together;
                // the new build is only visible after the durable commit.
                List<GameStateStore.Row> rows = List.of(
                        new GameStateStore.Row(owner, "NODE", scope, "free_respec_used", "1"),
                        new GameStateStore.Row(owner, "NODE", scope, "respec_cooldown",
                                String.valueOf(nextCooldown)),
                        new GameStateStore.Row(owner, "NODE", scope, normalizedTier, normalized));
                double balanceAfter = data.getBalance() - cost;
                boolean committed = database.executeTransaction("node respec " + node.getId(),
                        connection -> {
                    for (GameStateStore.Row row : rows) {
                        GameStateStore.write(connection, row);
                    }
                    try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE idle_players SET balance = ? WHERE uuid = ?")) {
                        update.setDouble(1, balanceAfter);
                        update.setString(2, owner.toString());
                        if (update.executeUpdate() != 1) throw new SQLException("Player row is missing");
                    }
                });
                if (!committed) {
                    return Result.fail("Respec could not be settled; no Coins were charged.");
                }
                data.addBalance(-cost);
                rows.forEach(state::applyCommitted);
            } else {
                state.put(owner, "NODE", scope, "free_respec_used", "1");
                state.put(owner, "NODE", scope, "respec_cooldown", String.valueOf(nextCooldown));
                state.put(owner, "NODE", scope, normalizedTier, normalized);
            }
        } else {
            state.put(owner, "NODE", scope, normalizedTier, normalized);
        }
        audit.log(owner, "NODE_BUILD", "{\"node\":" + node.getId() + ",\"tier\":\""
                + normalizedTier + "\",\"choice\":\"" + normalized + "\"}");
        return Result.ok("Node " + normalizedTier + " set to " + DesignText.pretty(normalized) + ".");
    }

    public Result selectTypePerk(UUID owner, NodeRecord node, int level, String choice) {
        if (node == null || !node.getOwnerUuid().equals(owner)) return Result.fail("You do not own that node.");
        if (!(level == 15 || level == 35 || level == 60 || level == 85) || node.getExplorationLevel() < level) {
            return Result.fail("That type-perk milestone is not unlocked.");
        }
        List<String> choices = typePerkChoices(node.getType(), level);
        String normalized = choice.toUpperCase(Locale.ROOT);
        if (choices.stream().noneMatch(normalized::equals)) {
            return Result.fail("Choose one of: " + String.join(", ", choices));
        }
        state.put(owner, "NODE", String.valueOf(node.getId()), "type_perk_" + level, normalized);
        audit.log(owner, "TYPE_PERK", "{\"node\":" + node.getId() + ",\"level\":" + level
                + ",\"choice\":\"" + normalized + "\"}");
        return Result.ok("Type Perk selected: " + DesignText.pretty(normalized) + ".");
    }

    public List<String> typePerkChoices(NodeType type, int level) {
        List<String> choices = switch (type) {
            case MINING -> switch (level) {
                case 15 -> List.of("STONE_MASON", "ORE_SENSE", "REINFORCED_SHAFT");
                case 35 -> List.of("SEISMIC_MAP", "RICH_VEIN", "CRYSTAL_ECHO");
                case 60 -> List.of("METAL_STRATUM", "REDSTONE_STRATUM", "GEM_STRATUM");
                case 85 -> List.of("DEEP_CORE", "EXCAVATION_CREW", "GEOLOGICAL_ARCHIVE");
                default -> List.of();
            };
            case FARMING -> switch (level) {
                case 15 -> List.of("CROP_ROTATION", "SEED_KEEPER", "IRRIGATION_CHANNELS");
                case 35 -> List.of("GREENHOUSE", "MARKET_GARDEN", "POLLINATOR_ROUTE");
                case 60 -> List.of("STAPLE_HARVEST", "EXOTIC_GARDEN", "ALCHEMISTS_PLOT");
                case 85 -> List.of("PERENNIAL_FIELD", "HARVEST_FESTIVAL", "LIVING_SOIL");
                default -> List.of();
            };
            case WOODCUTTING -> switch (level) {
                case 15 -> List.of("SUSTAINABLE_GROVE", "LUMBER_STACKS", "SAPLING_KEEPER");
                case 35 -> List.of("TRAILBLAZER", "CARPENTERS_MEASURE", "FOREST_MEMORY");
                case 60 -> List.of("TEMPERATE_GROVE", "WILD_GROVE", "OTHERWORLD_GROVE");
                case 85 -> List.of("ELDER_GROVE", "MASTER_CARPENTER", "HEARTWOOD");
                default -> List.of();
            };
            case LIVESTOCK -> switch (level) {
                case 15 -> List.of("BALANCED_HERD", "FEED_RESERVE", "RANCH_HAND");
                case 35 -> List.of("BREEDING_RECORDS", "FISHERY_ROUTE", "SHEPHERDS_CALL");
                case 60 -> List.of("RANCH_TABLE", "WEAVERS_PASTURE", "RIVER_KEEPER");
                case 85 -> List.of("LEGENDARY_HERD", "SANCTUARY", "RANCH_LEGACY");
                default -> List.of();
            };
            case HUNTER -> switch (level) {
                case 15 -> List.of("BOUNTY_BOARD", "SCAVENGER", "NIGHT_WATCH");
                case 35 -> List.of("TRAIL_MARKS", "TROPHY_SENSE", "PREPARED_AMBUSH");
                case 60 -> List.of("GRAVE_WARDEN", "NEST_BREAKER", "RIFT_STALKER");
                case 85 -> List.of("APEX_CONTRACT", "CLEAN_HUNT", "DREAD_MARK");
                default -> List.of();
            };
            default -> List.of();
        };
        return choices.stream().map(value -> value.toUpperCase(Locale.ROOT)).toList();
    }

    public String typePerk(NodeRecord node, int level) {
        return DesignText.valueOr(state.get(node.getOwnerUuid(), "NODE",
                String.valueOf(node.getId()), "type_perk_" + level), "UNSELECTED");
    }

    public void markFrontierEligible(NodeRecord node) {
        state.put(node.getOwnerUuid(), "NODE", String.valueOf(node.getId()), "frontier_eligible", "1");
    }

    // ---- derived multipliers --------------------------------------------------

    public double productionMultiplier(NodeRecord node) {
        double result = 1.0;
        if ("INDUSTRY".equals(specialization(node))) result *= 1.15;
        if ("MASS_PRODUCTION".equals(refinement(node))) result *= 1.10;
        return result;
    }

    public double bufferMultiplier(NodeRecord node) {
        double result = 1.0;
        if ("LOGISTICS".equals(specialization(node))) result += 0.50;
        if ("DEEP_STORAGE".equals(refinement(node))) result += 0.50;
        if (Set.of("REINFORCED_SHAFT", "IRRIGATION_CHANNELS", "FEED_RESERVE")
                .contains(typePerk(node, 15))) result += 0.25;
        if ("LUMBER_STACKS".equals(typePerk(node, 15))) result += 0.35;
        return result;
    }

    public double fullBufferResearchMultiplier(NodeRecord node) {
        if ("QUARTERMASTER".equals(mastery(node)) || "GREENHOUSE".equals(typePerk(node, 35))) {
            return 0.50;
        }
        if ("REINFORCED_SHAFT".equals(typePerk(node, 15))) return 0.30;
        return 0.25;
    }

    public double eventExpMultiplier(NodeRecord node) {
        double result = "DEEP_SURVEY".equals(refinement(node)) ? 1.15 : 1.0;
        if ("DREAD_MARK".equals(typePerk(node, 85))) result *= 1.10;
        if ("RESEARCH_WEEK".equals(seasons.modifier())) result *= 1.15;
        return result;
    }

    /** Direction perks alter relative weight by the locked maximum of 20%. */
    public double resourceWeightMultiplier(NodeRecord node, String material) {
        String perk = typePerk(node, 60);
        String id = material.toUpperCase(Locale.ROOT);
        boolean favored = switch (perk) {
            case "METAL_STRATUM" -> contains(id, "COPPER", "IRON", "GOLD");
            case "REDSTONE_STRATUM" -> contains(id, "REDSTONE", "LAPIS", "QUARTZ", "AMETHYST");
            case "GEM_STRATUM" -> contains(id, "DIAMOND", "EMERALD");
            case "STAPLE_HARVEST" -> contains(id, "WHEAT", "CARROT", "POTATO", "BEETROOT", "PUMPKIN", "MELON");
            case "EXOTIC_GARDEN" -> contains(id, "COCOA", "BERR", "MUSHROOM", "BAMBOO", "CHORUS");
            case "ALCHEMISTS_PLOT" -> contains(id, "NETHER_WART", "HONEY", "GLOW_BERRIES");
            case "TEMPERATE_GROVE" -> contains(id, "OAK", "BIRCH", "SPRUCE");
            case "WILD_GROVE" -> contains(id, "JUNGLE", "ACACIA", "MANGROVE", "CHERRY", "BAMBOO");
            case "OTHERWORLD_GROVE" -> contains(id, "CRIMSON", "WARPED");
            case "RANCH_TABLE" -> contains(id, "BEEF", "PORK", "CHICKEN", "EGG", "MILK");
            case "WEAVERS_PASTURE" -> contains(id, "LEATHER", "WOOL", "FEATHER", "RABBIT_HIDE");
            case "RIVER_KEEPER" -> contains(id, "COD", "SALMON", "FISH", "INK", "NAUTILUS");
            case "GRAVE_WARDEN" -> contains(id, "ROTTEN", "BONE", "PHANTOM");
            case "NEST_BREAKER" -> contains(id, "STRING", "SPIDER", "SLIME", "MAGMA");
            case "RIFT_STALKER" -> contains(id, "ENDER", "BLAZE", "GHAST", "PRISMARINE");
            default -> false;
        };
        return favored ? 1.20 : 1.0;
    }

    private boolean validBuildChoice(String tier, String choice, String branch) {
        return switch (tier) {
            case "specialization" -> Set.of("INDUSTRY", "DISCOVERY", "LOGISTICS").contains(choice);
            case "refinement" -> switch (branch) {
                case "INDUSTRY" -> Set.of("MASS_PRODUCTION", "FINE_PROCESSING").contains(choice);
                case "DISCOVERY" -> Set.of("DEEP_SURVEY", "LUCKY_ROUTE").contains(choice);
                case "LOGISTICS" -> Set.of("DEEP_STORAGE", "SMART_ROUTING").contains(choice);
                default -> false;
            };
            case "mastery" -> Set.of("FOREMAN", "PATHFINDER", "QUARTERMASTER").contains(choice);
            default -> false;
        };
    }

    private boolean contains(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) return true;
        }
        return false;
    }
}
