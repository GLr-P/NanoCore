package io.github.glrp.nanocore.event;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Places from scores. Equal scores share a place (1, 2, 2, 4). Only scores above zero are placed. Pure logic. */
public final class Ranking {

    private Ranking() {
    }

    public record Place<K>(K who, int place, long score) {
    }

    public static <K> List<Place<K>> rank(Map<K, Long> scores) {
        List<Map.Entry<K, Long>> list = new ArrayList<>();
        for (var e : scores.entrySet()) if (e.getValue() != null && e.getValue() > 0) list.add(e);
        list.sort(Map.Entry.<K, Long>comparingByValue(Comparator.reverseOrder()));
        List<Place<K>> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            long score = list.get(i).getValue();
            int place = i > 0 && score == list.get(i - 1).getValue() ? out.get(i - 1).place() : i + 1;
            out.add(new Place<>(list.get(i).getKey(), place, score));
        }
        return out;
    }
}
