package dev.branzx.idle.service;

import dev.branzx.idle.IdlePlugin;
import dev.branzx.idle.storage.Database;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class AuditServiceTest {

    @Test
    void adminMutationRequiresIdAndReason() {
        AuditService audit = new AuditService(mock(IdlePlugin.class), mock(Database.class));
        UUID actor = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class,
                () -> audit.logAdmin(actor, "", "balance correction", "ADMIN_GIVE", "detail"));
        assertThrows(IllegalArgumentException.class,
                () -> audit.logAdmin(actor, "audit-1", " ", "ADMIN_GIVE", "detail"));
    }
}
