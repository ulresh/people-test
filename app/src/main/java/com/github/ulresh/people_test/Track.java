package com.github.ulresh.people_test;

import java.util.ArrayDeque;

/** Трек одного человека: положение, траектория и состояние его фигурки. */
final class Track {
    static final int TRAIL = 120;

    final int id;
    final FigureBuilder figure = new FigureBuilder();
    /** Последняя найденная поза; в пропущенных кадрах остаётся прежней. */
    Person person;
    FigurePose pose;
    /** Центр и скорость (доли кадра за обработанный кадр). */
    float cx, cy, vx, vy;
    int hits = 1;
    int missed;
    /** Номер ячейки фигурки или −1, пока трек не подтверждён. */
    int slot = -1;
    final ArrayDeque<float[]> trail = new ArrayDeque<>();

    Track(int id, Person p) {
        this.id = id;
        cx = p.centerX();
        cy = p.centerY();
        update(p, false);
    }

    /** Положение, ожидаемое в следующем кадре. */
    float predX() {
        return cx + vx;
    }

    float predY() {
        return cy + vy;
    }

    float size() {
        return (float) Math.hypot(person.right - person.left, person.bottom - person.top);
    }

    void update(Person p, boolean existing) {
        float nx = p.centerX(), ny = p.centerY();
        if (existing) {
            vx = 0.5f * vx + 0.5f * (nx - cx);
            vy = 0.5f * vy + 0.5f * (ny - cy);
        }
        cx = nx;
        cy = ny;
        person = p;
        missed = 0;
        trail.addLast(new float[]{cx, cy});
        if (trail.size() > TRAIL) trail.removeFirst();
    }
}
