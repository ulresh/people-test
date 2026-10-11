package com.github.ulresh.people_test;

import java.util.ArrayList;
import java.util.List;

/** Сопоставляет найденных людей с треками между обработанными кадрами. */
final class Tracker {
    /** Сколько обработанных кадров подряд трек может не находиться. */
    private static final int MAX_MISSED = 8;
    private static final int CONFIRM_HITS = 2;
    private static final float MAX_COST = 1.0f;
    static final int MAX_SLOTS = 6;

    private final List<Track> tracks = new ArrayList<>();
    private int nextId = 1;

    List<Track> tracks() {
        return tracks;
    }

    void update(List<Person> people) {
        int nt = tracks.size(), np = people.size();
        // жадное сопоставление по расстоянию между ожидаемым центром трека и центром человека
        List<float[]> pairs = new ArrayList<>();
        for (int t = 0; t < nt; t++) {
            Track tr = tracks.get(t);
            float norm = Math.max(0.1f, tr.size());
            for (int d = 0; d < np; d++) {
                Person p = people.get(d);
                float cost = (float) Math.hypot(p.centerX() - tr.predX(), p.centerY() - tr.predY()) / norm;
                if (cost < MAX_COST) pairs.add(new float[]{cost, t, d});
            }
        }
        pairs.sort((a, b) -> Float.compare(a[0], b[0]));
        boolean[] tUsed = new boolean[nt], dUsed = new boolean[np];
        for (float[] pr : pairs) {
            int t = (int) pr[1], d = (int) pr[2];
            if (tUsed[t] || dUsed[d]) continue;
            tUsed[t] = dUsed[d] = true;
            Track tr = tracks.get(t);
            tr.update(people.get(d), true);
            tr.hits++;
        }
        for (int t = nt - 1; t >= 0; t--) {
            if (tUsed[t]) continue;
            Track tr = tracks.get(t);
            tr.missed++;
            // неподтверждённый трек пропадает сразу, подтверждённый — после паузы
            if (tr.missed > MAX_MISSED || tr.slot < 0 && tr.hits < CONFIRM_HITS) tracks.remove(t);
        }
        for (int d = 0; d < np; d++) {
            if (!dUsed[d]) tracks.add(new Track(nextId++, people.get(d)));
        }
        // подтверждение и назначение ячеек фигурок
        boolean[] busy = new boolean[MAX_SLOTS];
        for (Track tr : tracks) if (tr.slot >= 0) busy[tr.slot] = true;
        for (Track tr : tracks) {
            if (tr.slot >= 0 || tr.hits < CONFIRM_HITS) continue;
            for (int s = 0; s < MAX_SLOTS; s++) {
                if (!busy[s]) {
                    busy[s] = true;
                    tr.slot = s;
                    break;
                }
            }
        }
    }
}
