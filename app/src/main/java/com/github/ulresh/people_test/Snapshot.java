package com.github.ulresh.people_test;

import android.graphics.Bitmap;

import java.util.ArrayList;
import java.util.List;

/** Результат обработки одного кадра: то, что нужно показать на экране. */
final class Snapshot {
    static final class TrackInfo {
        int id;
        int slot;
        /** Сколько обработанных кадров подряд человек не находился. */
        int missed;
        Person person;
        FigurePose pose;
        /** Траектория: пары x,y (доли кадра). */
        float[] trail;
    }

    Bitmap bitmap;
    final List<TrackInfo> tracks = new ArrayList<>();
}
