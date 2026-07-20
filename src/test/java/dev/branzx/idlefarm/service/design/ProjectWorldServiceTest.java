package dev.branzx.idlefarm.service.design;

import dev.branzx.idlefarm.service.GameDesignService.Project;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectWorldServiceTest {

    @Test
    void constructionStagesChangeAtQuarterThresholds() {
        assertEquals(0, stage(0));
        assertEquals(0, stage(255));
        assertEquals(1, stage(256));
        assertEquals(1, stage(511));
        assertEquals(2, stage(512));
        assertEquals(2, stage(767));
        assertEquals(3, stage(768));
        assertEquals(3, stage(1023));
        assertEquals(4, stage(1024));
    }

    private int stage(int progress) {
        return ProjectWorldService.constructionStage(
                new Project("hall", "Hall", "STONE", progress, 1024, progress >= 1024));
    }
}
