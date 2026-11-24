package io.github.glrp.nanocore.event;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventRulesTest {

    @Test
    void durations() {
        assertEquals(30 * 60_000L, Durations.parse("30m"));
        assertEquals(2 * 3_600_000L, Durations.parse("2h"));
        assertEquals(90_000L, Durations.parse("90s"));
        assertEquals(90 * 60_000L, Durations.parse("1h30m"));
        assertEquals(45 * 60_000L, Durations.parse("45"));
        assertThrows(IllegalArgumentException.class, () -> Durations.parse("soon"));
        assertThrows(IllegalArgumentException.class, () -> Durations.parse("5x"));
        assertEquals("1h 5m", Durations.format(65 * 60_000L));
        assertEquals("2h", Durations.format(2 * 3_600_000L));
        assertEquals("4m 30s", Durations.format(270_000L));
        assertEquals("12m", Durations.format(12 * 60_000L + 30_000));
        assertEquals("45s", Durations.format(44_200));
        assertEquals("0s", Durations.format(-5));
    }

    @Test
    void rankingSharesPlacesOnTies() {
        Map<String, Long> s = new LinkedHashMap<>();
        s.put("a", 10L);
        s.put("b", 7L);
        s.put("c", 7L);
        s.put("d", 3L);
        s.put("e", 0L);
        List<Ranking.Place<String>> r = Ranking.rank(s);
        assertEquals(4, r.size(), "zero scores aren't placed");
        assertEquals("a", r.get(0).who());
        assertEquals(1, r.get(0).place());
        assertEquals(2, r.get(1).place());
        assertEquals(2, r.get(2).place());
        assertEquals(4, r.get(3).place());
    }

    @Test
    void votesIgnoreSelfAndRepeatsAndBreakTiesByFirstPicks() {
        Map<String, List<String>> ballots = new HashMap<>();
        ballots.put("amy", List.of("amy", "bob", "bob", "cat")); // self and repeat skipped: bob 3, cat 2
        ballots.put("bob", List.of("cat", "amy"));                // cat 3, amy 2
        ballots.put("cat", List.of("bob", "amy", "dan"));         // bob 3, amy 2, dan not in the event
        Map<String, Long> t = VoteTally.tally(ballots, Set.of("amy", "bob", "cat"));
        assertEquals(6, VoteTally.shownPoints(t.get("bob")));
        assertEquals(5, VoteTally.shownPoints(t.get("cat")));
        assertEquals(4, VoteTally.shownPoints(t.get("amy")));
        assertFalse(t.containsKey("dan"));

        Map<String, List<String>> tie = new HashMap<>();
        tie.put("x", List.of("a", "b"));  // a 3, b 2
        tie.put("y", List.of("b"));       // b 3 -> b 5, a 3
        tie.put("z", List.of("a", "c"));  // a 6, c 2
        tie.put("w", List.of("c", "b"));  // c 5, b 7
        Map<String, Long> t2 = VoteTally.tally(tie, Set.of("a", "b", "c", "x", "y", "z", "w"));
        assertEquals(7, VoteTally.shownPoints(t2.get("b")));
        assertEquals(6, VoteTally.shownPoints(t2.get("a")));
        Map<String, Long> t3 = VoteTally.tally(Map.of("p", List.of("a"), "q", List.of("b"), "r", List.of("c", "b")), Set.of("a", "b", "c"));
        // a: 3 (1 first) · b: 3 + 2 = 5 · c: 3
        assertEquals(5, VoteTally.shownPoints(t3.get("b")));
        assertEquals(VoteTally.shownPoints(t3.get("a")), VoteTally.shownPoints(t3.get("c")));
        assertEquals(t3.get("a"), t3.get("c"), "equal points and equal first picks stay a true tie");
        Map<String, Long> t4 = VoteTally.tally(Map.of("p", List.of("a"), "q", List.of("x", "c")), Set.of("a", "c", "x"));
        // a: 3 points, 1 first · c: 2 points
        assertTrue(t4.get("a") > t4.get("c"));
        Map<String, Long> t5 = VoteTally.tally(Map.of("p", List.of("a", "c"), "q", List.of("c", "x"), "r", List.of("x", "a")), Set.of("a", "c", "x"));
        // a: 3 + 2 = 5 (1 first) · c: 2 + 3 = 5 (1 first) · x: 2 + 3 = 5 (1 first)
        assertEquals(t5.get("a"), t5.get("c"));
        assertTrue(t5.get("x") > 0);
    }

    @Test
    void readyChecks() {
        assertTrue(ReadyRule.valid("all"));
        assertTrue(ReadyRule.valid("online"));
        assertTrue(ReadyRule.valid("75%"));
        assertFalse(ReadyRule.valid("most"));
        assertFalse(ReadyRule.valid("150%"));
        assertTrue(ReadyRule.met("all", 4, 4, 4, 4));
        assertFalse(ReadyRule.met("all", 3, 3, 4, 3));
        assertTrue(ReadyRule.met("online", 3, 3, 4, 3), "the one who went offline doesn't hold it up");
        assertTrue(ReadyRule.met("75%", 3, 3, 4, 4));
        assertFalse(ReadyRule.met("75%", 2, 2, 4, 4));
        assertFalse(ReadyRule.met("all", 0, 0, 0, 0), "an empty event never counts as done");
        assertEquals(3, ReadyRule.needed("75%", 4, 4));
        assertEquals(4, ReadyRule.needed("all", 4, 2));
        assertEquals(2, ReadyRule.needed("online", 4, 2));
    }

    @Test
    void prizes() {
        EventDef.Prizes p = new EventDef.Prizes(List.of(250L, 150L, 100L), 25, true, "gold_ingot", 400, 50, 20, 0, 0);
        List<Ranking.Place<String>> places = List.of(
                new Ranking.Place<>("a", 1, 30), new Ranking.Place<>("b", 2, 20), new Ranking.Place<>("c", 2, 20),
                new Ranking.Place<>("d", 4, 5));
        List<String> all = List.of("a", "b", "c", "d", "afk");
        Map<String, PrizePlan.Payout> pay = PrizePlan.plan(places, all, Set.of("a", "b", "c", "d"), p, false);
        assertEquals(250, pay.get("a").coins());
        assertTrue(pay.get("a").trophy());
        assertEquals(150, pay.get("b").coins(), "a tie for 2nd pays 2nd to both");
        assertEquals(150, pay.get("c").coins());
        assertEquals(25, pay.get("d").coins(), "4th isn't a prize place, so they get the participation coins");
        assertFalse(pay.get("d").trophy());
        assertNull(pay.get("afk"), "AFK players get nothing");
        Map<String, PrizePlan.Payout> goal = PrizePlan.plan(places, all, Set.of("a", "d"), p, true);
        assertEquals(300, goal.get("a").coins());
        assertEquals(75, goal.get("d").coins());
    }

    @Test
    void autoPickRespectsDaysRepeatsAndWeights() {
        EventDef a = def("a", true, Set.of(), 1);
        EventDef weekend = def("weekend", true, Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), 1);
        EventDef manual = def("manual", false, Set.of(), 1);
        LocalDate friday = LocalDate.of(2026, 12, 4);
        for (int i = 0; i < 50; i++) {
            EventDef pick = EventPicks.pick(List.of(a, weekend, manual), Map.of(), friday, 5, new Random(i));
            assertEquals("a", pick.id(), "only a fits on a Friday");
        }
        assertNull(EventPicks.pick(List.of(a), Map.of("a", friday.minusDays(2)), friday, 5, new Random(1)), "ran 2 days ago");
        assertEquals("a", EventPicks.pick(List.of(a), Map.of("a", friday.minusDays(5)), friday, 5, new Random(1)).id());
        EventDef heavy = def("heavy", true, Set.of(), 9);
        int heavyCount = 0;
        Random rng = new Random(7);
        for (int i = 0; i < 1000; i++) if (EventPicks.pick(List.of(a, heavy), Map.of(), friday, 5, rng).id().equals("heavy")) heavyCount++;
        assertTrue(heavyCount > 850 && heavyCount < 950, "weight 9 vs 1: " + heavyCount);
    }

    @Test
    void teamsAreEven() {
        List<Integer> players = new ArrayList<>();
        for (int i = 0; i < 7; i++) players.add(i);
        Map<Integer, Integer> t = EventPicks.teams(players, 2);
        long one = t.values().stream().filter(x -> x == 1).count();
        assertEquals(4, one);
        assertEquals(2, EventPicks.smallestTeam(t, 2));
        assertEquals(1, EventPicks.smallestTeam(Map.<Integer, Integer>of(), 3));
    }

    @Test
    void stateSurvivesSaving() {
        EventState s = new EventState();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        s.eventId = "mining_rush";
        s.phase = 1;
        s.autoStarted = true;
        s.endsAt = 123456789L;
        s.pausedRemaining = 5000;
        s.participants.add(a);
        s.participants.add(b);
        s.scores.put(a, 11L);
        s.actions.put(b, 3L);
        s.teams.put(a, 2);
        s.lives.put(b, 1);
        s.ready.add(a);
        s.eliminated.add(b);
        s.kitted.add(a);
        s.votes.put(a, List.of(b));
        s.remindersSent.add(10);
        s.restore.put("difficulty.world", "NORMAL");
        EventState r = EventState.fromYaml(s.toYaml());
        assertEquals("mining_rush", r.eventId);
        assertEquals(1, r.phase);
        assertTrue(r.autoStarted);
        assertEquals(123456789L, r.endsAt);
        assertTrue(r.paused());
        assertEquals(List.of(a, b), new ArrayList<>(r.participants), "join order is kept");
        assertEquals(11, r.score(a));
        assertEquals(3, r.actions.get(b));
        assertEquals(2, r.teams.get(a));
        assertEquals(1, r.lives.get(b));
        assertEquals(Set.of(a), r.ready);
        assertEquals(Set.of(b), r.eliminated);
        assertEquals(Set.of(a), r.kitted);
        assertEquals(List.of(b), r.votes.get(a));
        assertEquals(Set.of(10), r.remindersSent);
        assertEquals("NORMAL", r.restore.get("difficulty.world"));
        assertEquals(11, r.totalPoints());
    }

    @Test
    void timeOfDayCrossing() {
        assertTrue(EventService.crossed(12000, 13100, 13000));
        assertFalse(EventService.crossed(13100, 13200, 13000));
        assertTrue(EventService.crossed(23500, 100, 0), "wraps past midnight");
        assertTrue(EventService.crossed(22000, 500, 23000));
        assertFalse(EventService.crossed(1000, 1000, 1000));
    }

    @Test
    void discordJsonIsEscaped() {
        String j = DiscordPayload.json("Bot", "Say \"hi\"", "line1\nline2 \\ end", 0xF1C40F, List.of(new DiscordPayload.Field("1st", "Sam (11)")));
        assertTrue(j.contains("\"title\":\"Say \\\"hi\\\"\""), j);
        assertTrue(j.contains("line1\\nline2 \\\\ end"), j);
        assertTrue(j.contains("\"color\":15844367"), j);
        assertTrue(j.contains("\"parse\":[]"), "never pings anyone");
        assertTrue(j.contains("{\"name\":\"1st\",\"value\":\"Sam (11)\",\"inline\":true}"), j);
    }

    static EventDef def(String id, boolean auto, Set<DayOfWeek> days, int weight) {
        return new EventDef(id, id, "clock", List.of(), false, true, 0, 0, null, List.of(), auto, weight, 0, days,
                List.of(new EventDef.Phase("p", "P", new EventDef.End(60_000, 0, -1, null, false, false, false, false, 0), Map.of(), EventDef.Rules.NONE, null, false, 0, 0, PartsDef.NONE)),
                new EventDef.Prizes(List.of(1L), 0, false, "gold_ingot", 0, 0, 0, 0, 0), null, EventDef.Winner.SCORE, null);
    }
}
