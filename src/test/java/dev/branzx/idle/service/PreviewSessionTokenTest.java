package dev.branzx.idle.service;

import dev.branzx.idle.IdlePlugin;
import dev.branzx.idle.node.ChunkKey;
import dev.branzx.idle.node.NodeType;
import dev.branzx.idle.schematic.SchematicDefinition;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A preview must never be able to authorize a second claim. These cover the
 * single-use token rules that make the chat confirm link safe to click late.
 */
class PreviewSessionTokenTest {

    private PreviewService preview;
    private Player player;
    private final ChunkKey chunk = new ChunkKey("world", 3, 7);
    // An empty blueprint keeps rendering to a no-op; the token rules under
    // test do not depend on what is drawn.
    private final SchematicDefinition definition = new SchematicDefinition("empty");

    @BeforeEach
    void setUp() {
        IdlePlugin plugin = mock(IdlePlugin.class);
        FileConfiguration config = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(config);
        when(config.getInt(eq("nodes.preview.timeout-seconds"), anyInt())).thenReturn(60);
        when(config.getString(eq("nodes.preview.ghost-material"), anyString()))
                .thenReturn("LIGHT_BLUE_STAINED_GLASS");
        when(config.getString(eq("nodes.preview.obstruction-material"), anyString()))
                .thenReturn("RED_STAINED_GLASS");

        SchematicService schematics = mock(SchematicService.class);
        when(schematics.survey(any(), any(), anyInt(), any()))
                .thenReturn(new SchematicService.SiteReport(0, 0, List.of()));
        when(schematics.resolveBlocks(any(), any(), anyInt())).thenReturn(List.of());

        World world = mock(World.class);
        when(world.getName()).thenReturn("world");

        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getWorld()).thenReturn(world);
        when(player.isOnline()).thenReturn(true);
        // Review previews are anchored to wherever the reviewer stands.
        when(player.getLocation()).thenReturn(new org.bukkit.Location(world, 56, 70, 120));

        preview = new PreviewService(plugin, schematics);
    }

    private PreviewService.Session open() {
        return preview.open(player, chunk, NodeType.MINING, definition, 64);
    }

    @Test
    void confirmingConsumesTheSessionExactlyOnce() {
        PreviewService.Session session = open();

        assertNotNull(preview.consume(player, session.token()));
        // The second click is the stale chat line; it must find nothing.
        assertNull(preview.consume(player, session.token()));
    }

    @Test
    void aWrongTokenNeverConsumesTheOpenSession() {
        PreviewService.Session session = open();

        assertNull(preview.consume(player, "not-the-token"));
        // The real session survives a bad guess rather than being cleared.
        assertNotNull(preview.consume(player, session.token()));
    }

    @Test
    void cancellingLeavesNothingToConfirm() {
        PreviewService.Session session = open();

        preview.end(player);

        assertNull(preview.get(player));
        assertNull(preview.consume(player, session.token()));
    }

    @Test
    void reopeningInvalidatesThePreviousToken() {
        PreviewService.Session first = open();
        PreviewService.Session second = open();

        assertNotEquals(first.token(), second.token());
        assertNull(preview.consume(player, first.token()));
        assertNotNull(preview.consume(player, second.token()));
    }

    /**
     * Rotating changes the offer, not whether it is live — re-issuing the
     * token would dead-link the [Confirm] already in the player's chat.
     */
    @Test
    void rotatingKeepsTheTokenAndDeadline() {
        PreviewService.Session session = open();

        PreviewService.Session turned = preview.rotate(player, session.token(), 1);

        assertNotNull(turned);
        assertEquals(session.token(), turned.token());
        assertEquals(session.expiresAt(), turned.expiresAt());
        assertEquals(1, turned.rotation());
        assertNotNull(preview.consume(player, session.token()));
    }

    @Test
    void rotationWrapsAfterFourTurns() {
        PreviewService.Session session = open();
        for (int i = 0; i < 3; i++) {
            preview.rotate(player, session.token(), 1);
        }
        assertEquals(3, preview.get(player).rotation());

        assertEquals(0, preview.rotate(player, session.token(), 1).rotation());
    }

    @Test
    void rotatingWithAStaleTokenDoesNothing() {
        open();

        assertNull(preview.rotate(player, "stale", 1));
    }

    /**
     * A session states what confirming it means. Without this the confirm
     * handlers would have to infer intent, and a review token could be spent
     * on a claim.
     */
    @Test
    void eachFlowOpensItsOwnKindOfSession() {
        assertEquals(PreviewService.Kind.CLAIM, open().kind());

        var review = preview.openReview(player, definition, 70);
        assertEquals(PreviewService.Kind.REVIEW, review.kind());
    }

    @Test
    void rotatingPreservesTheSessionKind() {
        var review = preview.openReview(player, definition, 70);

        var turned = preview.rotate(player, review.token(), 1);

        assertEquals(PreviewService.Kind.REVIEW, turned.kind(),
                "a rotated review must not become a claim");
    }

    /**
     * A Complex preview covers several chunks at one shared ground level, so
     * the building cannot step up and down a slope.
     */
    @Test
    void aMergePreviewCoversEveryChunkAtTheAnchorsLevel() {
        var anchor = mock(dev.branzx.idle.node.NodeRecord.class);
        when(anchor.getChunk()).thenReturn(chunk);
        when(anchor.getType()).thenReturn(NodeType.MINING);
        when(anchor.getOriginY()).thenReturn(64);
        when(anchor.getRotation()).thenReturn(0);
        when(anchor.getId()).thenReturn(42L);
        List<PreviewService.Part> parts = List.of(
                new PreviewService.Part(chunk, definition),
                new PreviewService.Part(new ChunkKey("world", 4, 7), definition));

        var session = preview.openMerge(player, anchor, parts);

        assertEquals(PreviewService.Kind.MERGE, session.kind());
        assertEquals(2, session.parts().size());
        assertEquals(64, session.originY());
        assertEquals(42L, session.nodeId());
    }

    /** Single-chunk flows are just a one-part preview. */
    @Test
    void aClaimPreviewHasExactlyOnePart() {
        var session = open();

        assertEquals(1, session.parts().size());
        assertEquals(chunk, session.parts().getFirst().chunk());
    }

    @Test
    void sessionCarriesTheGroundLevelTheConfirmWillReuse() {
        PreviewService.Session session = open();

        assertEquals(64, session.originY());
        assertEquals(chunk, session.chunk());
        assertEquals(NodeType.MINING, session.type());
    }
}
