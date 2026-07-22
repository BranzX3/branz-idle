package dev.branzx.idle.skin;

import dev.branzx.idle.node.NodeType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkinDefinitionTest {

    private static SkinDefinition skin(String id) {
        return new SkinDefinition(id);
    }

    /** The shape every contest entry takes: one building for all five tiers. */
    @Test
    void aTierOneOnlySkinServesEveryTier() {
        SkinDefinition cottage = skin("cottage");
        cottage.getTiers().put(1, "cottage_t1");

        for (int tier = 1; tier <= 5; tier++) {
            assertEquals("cottage_t1", cottage.definitionIdFor(tier));
        }
    }

    @Test
    void tierLookupFallsBackDownwardNotUpward() {
        SkinDefinition keep = skin("keep");
        keep.getTiers().put(1, "keep_t1");
        keep.getTiers().put(3, "keep_t3");

        assertEquals("keep_t1", keep.definitionIdFor(1));
        assertEquals("keep_t1", keep.definitionIdFor(2), "tier 2 keeps the tier-1 building");
        assertEquals("keep_t3", keep.definitionIdFor(3));
        assertEquals("keep_t3", keep.definitionIdFor(5), "tier 5 keeps the highest defined");
    }

    /** Null lets the caller fall back to the server default rather than fail. */
    @Test
    void aSkinWithNoTiersResolvesToNull() {
        assertNull(skin("empty").definitionIdFor(3));
    }

    @Test
    void blankTierEntriesAreSkipped() {
        SkinDefinition broken = skin("broken");
        broken.getTiers().put(1, "broken_t1");
        broken.getTiers().put(2, "  ");

        assertEquals("broken_t1", broken.definitionIdFor(2));
    }

    @Test
    void anEmptyTypeListMeansEveryProductionType() {
        SkinDefinition any = skin("any");

        assertTrue(any.appliesTo(NodeType.MINING));
        assertTrue(any.appliesTo(NodeType.HUNTER));
    }

    @Test
    void aTypeListRestrictsTheSkin() {
        SkinDefinition farmOnly = skin("barn");
        farmOnly.getNodeTypes().add(NodeType.FARMING);

        assertTrue(farmOnly.appliesTo(NodeType.FARMING));
        assertFalse(farmOnly.appliesTo(NodeType.MINING));
    }

    /**
     * A contest winner's skin goes server-wide by shipping as a default
     * unlock, so that flag decides who may wear it.
     */
    @Test
    void unlockTagDecidesWhetherAGrantIsNeeded() {
        assertTrue(skin("starter").isDefaultUnlock(), "unset unlock defaults to open");

        SkinDefinition contest = skin("contest");
        contest.setUnlock("contest_s1");
        assertFalse(contest.isDefaultUnlock());

        contest.setUnlock("  ");
        assertTrue(contest.isDefaultUnlock(), "blank normalizes back to default");
    }

    // ---- Complex skins ----

    @Test
    void aSkinIsOnlyAComplexSkinOnceItDeclaresAShape() {
        SkinDefinition cottage = skin("cottage");
        assertFalse(cottage.isComplexSkin());

        cottage.setShape("3x3");
        assertTrue(cottage.isComplexSkin());

        cottage.setShape("  ");
        assertFalse(cottage.isComplexSkin(), "blank normalizes back to single-chunk");
    }

    @Test
    void piecesResolveByCell() {
        SkinDefinition keep = skin("keep");
        keep.setShape("2x2");
        keep.getPieces().put("0,0", "keep_c0_0");
        keep.getPieces().put("1,1", "keep_c1_1");

        assertEquals("keep_c0_0", keep.pieceIdFor(0, 0));
        assertEquals("keep_c1_1", keep.pieceIdFor(1, 1));
    }

    /** An undefined cell is a courtyard, authored by leaving it out. */
    @Test
    void anUndefinedCellResolvesToNull() {
        SkinDefinition keep = skin("keep");
        keep.setShape("3x3");
        keep.getPieces().put("0,0", "keep_c0_0");

        assertNull(keep.pieceIdFor(1, 1));
    }

    /** Tier lookup and piece lookup are independent; a Complex skin uses pieces. */
    @Test
    void complexSkinsCarryPiecesRatherThanTiers() {
        SkinDefinition keep = skin("keep");
        keep.setShape("2x1");
        keep.getPieces().put("0,0", "keep_c0_0");

        assertNull(keep.definitionIdFor(1));
        assertEquals("keep_c0_0", keep.pieceIdFor(0, 0));
    }

    @Test
    void displayFallsBackToTheIdSoAListingIsNeverBlank() {
        assertEquals("cottage", skin("cottage").getDisplay());

        SkinDefinition named = skin("cottage");
        named.setDisplay("Cozy Cottage");
        assertEquals("Cozy Cottage", named.getDisplay());
    }
}
