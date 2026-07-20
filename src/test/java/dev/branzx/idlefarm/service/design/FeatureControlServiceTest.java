package dev.branzx.idlefarm.service.design;

import dev.branzx.idlefarm.IdleFarmPlugin;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FeatureControlServiceTest {

    @Test
    void killSwitchAndExperimentAssignmentAreDeterministic() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("live-ops.features.seasonal-chronicle.enabled", true);
        config.set("live-ops.features.seasonal-chronicle.rollout-percent", 100);
        config.set("live-ops.features.disabled.enabled", false);
        config.set("live-ops.experiments.layout.enabled", true);
        config.set("live-ops.experiments.layout.rollout-percent", 100);
        config.set("live-ops.experiments.layout.variants.control", 50);
        config.set("live-ops.experiments.layout.variants.objectives-first", 50);
        IdleFarmPlugin plugin = mock(IdleFarmPlugin.class);
        when(plugin.getConfig()).thenReturn(config);
        FeatureControlService controls = new FeatureControlService(plugin);
        UUID owner = UUID.randomUUID();

        assertTrue(controls.enabled("seasonal-chronicle", owner));
        assertFalse(controls.enabled("disabled", owner));
        String assigned = controls.variant("layout", owner, "fallback");
        assertTrue(assigned.equals("control") || assigned.equals("objectives-first"));
        assertEquals(assigned, controls.variant("layout", owner, "fallback"));
    }
}
