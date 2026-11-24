package io.github.glrp.nanocore.quest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerQuestsTest {

    @Test
    void takeUpToTheLimitOnce() {
        PlayerQuests pq = new PlayerQuests();
        assertEquals(PlayerQuests.TakeResult.OK, pq.take("a", 3));
        assertEquals(PlayerQuests.TakeResult.ALREADY, pq.take("a", 3));
        assertEquals(PlayerQuests.TakeResult.OK, pq.take("b", 3));
        assertEquals(PlayerQuests.TakeResult.OK, pq.take("c", 3));
        assertEquals(PlayerQuests.TakeResult.FULL, pq.take("d", 3));
        assertEquals(3, pq.activeCount());
    }

    @Test
    void progressFinishesAndCaps() {
        PlayerQuests pq = new PlayerQuests();
        pq.take("a", 3);
        assertFalse(pq.add("a", 4, 10));
        assertEquals(6, pq.missing("a", 10));
        assertTrue(pq.add("a", 50, 10));
        assertEquals(10, pq.progress("a"));
        assertEquals(PlayerQuests.State.DONE, pq.state("a"));
        assertFalse(pq.add("a", 1, 10), "a done quest doesn't count further");
        assertFalse(pq.add("untaken", 5, 10), "quests that weren't taken don't count");
        assertEquals(0, pq.activeCount(), "a done quest frees its spot");
    }

    @Test
    void claimOnlyOnceAndOnlyWhenDone() {
        PlayerQuests pq = new PlayerQuests();
        pq.take("a", 3);
        assertFalse(pq.claim("a"));
        pq.add("a", 10, 10);
        assertTrue(pq.claim("a"));
        assertFalse(pq.claim("a"));
        assertEquals(PlayerQuests.State.CLAIMED, pq.state("a"));
        assertEquals(PlayerQuests.TakeResult.ALREADY, pq.take("a", 3), "each quest once per board");
    }

    @Test
    void abandonDropsProgress() {
        PlayerQuests pq = new PlayerQuests();
        pq.take("a", 1);
        pq.add("a", 5, 10);
        assertTrue(pq.abandon("a"));
        assertNull(pq.state("a"));
        assertEquals(PlayerQuests.TakeResult.OK, pq.take("a", 1));
        assertEquals(0, pq.progress("a"));
        pq.add("a", 10, 10);
        assertFalse(pq.abandon("a"), "a finished quest can't be abandoned");
    }
}
