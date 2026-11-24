package io.github.glrp.nanocore.quest;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CommunitySplitTest {

    @Test
    void splitByContribution() {
        Map<String, Long> c = new LinkedHashMap<>();
        c.put("a", 600L);
        c.put("b", 300L);
        c.put("c", 100L);
        assertEquals(Map.of("a", 900L, "b", 450L, "c", 150L), CommunitySplit.split(c, 1500, 25));
    }

    @Test
    void smallHelpersGetTheMinimum() {
        Map<String, Long> c = new LinkedHashMap<>();
        c.put("big", 9990L);
        c.put("tiny", 10L);
        c.put("none", 0L);
        Map<String, Long> s = CommunitySplit.split(c, 1500, 25);
        assertEquals(25, s.get("tiny"));
        assertEquals(1498, s.get("big"));
        assertFalse(s.containsKey("none"), "players who didn't help get nothing");
        assertEquals(Map.of(), CommunitySplit.split(Map.<String, Long>of(), 1500, 25));
    }

    @Test
    void unfinishedQuestPaysWhatGotDone() {
        assertEquals(1500, CommunitySplit.potFor(40000, 40000, 1500));
        assertEquals(1500, CommunitySplit.potFor(50000, 40000, 1500));
        assertEquals(750, CommunitySplit.potFor(20000, 40000, 1500));
        assertEquals(0, CommunitySplit.potFor(0, 40000, 1500));
    }
}
