package dev.branzx.idlefarm.storage;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerDataTest {

    @Test
    void olderAsyncSnapshotCannotClearNewerDirtyMutation() {
        PlayerData data = new PlayerData(UUID.randomUUID(), "Player", 0, 0);
        data.addBalance(10);
        long oldRevision = data.getRevision();
        data.addBalance(5);

        data.markPersisted(oldRevision);

        assertTrue(data.isDirty());
        data.markPersisted(data.getRevision());
        assertFalse(data.isDirty());
    }
}
