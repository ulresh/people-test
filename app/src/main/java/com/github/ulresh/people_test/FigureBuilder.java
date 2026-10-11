package com.github.ulresh.people_test;

/** Строит фигурку по ключевым точкам человека; помнит предыдущие направления для сглаживания. */
final class FigureBuilder {
    private static final float NECK_LEN = 0.12f;
    private static final float UPPER_ARM = 0.6f, FORE_ARM = 0.55f, HAND = 0.2f;
    private static final float THIGH = 0.8f, SHIN = 0.75f, FOOT = 0.25f;
    /** Ширина плеч и таза в длинах корпуса при виде спереди. */
    private static final float SHOULDERS = 0.8f, HIPS = 0.5f;
    private static final float SMOOTH = 0.6f;

    // направления (единичные векторы в пикселях кадра): 0 корпус, затем сегменты конечностей
    private static final int TORSO = 0, SH_L = 1, SH_R = 2, HIP_L = 3, HIP_R = 4,
            UA_L = 5, UA_R = 6, FA_L = 7, FA_R = 8, TH_L = 9, TH_R = 10, SN_L = 11, SN_R = 12,
            HEAD_UP = 13, DIRS = 14;
    private final float[] dx = new float[DIRS];
    private final float[] dy = new float[DIRS];
    private float shoulderW = 1, hipW = 1;
    private float yaw, pitch;
    private boolean back;
    private float faceSide = 0;
    private boolean init;

    private void setDefaults() {
        for (int i = 0; i < DIRS; i++) {
            dx[i] = 0;
            dy[i] = 1;
        }
        dy[TORSO] = -1; // от таза к шее
        dy[HEAD_UP] = -1;
        dx[SH_L] = -1; dy[SH_L] = 0; // от шеи к левому плечу (левое — слева на картинке)
        dx[SH_R] = 1; dy[SH_R] = 0;
        dx[HIP_L] = -1; dy[HIP_L] = 0;
        dx[HIP_R] = 1; dy[HIP_R] = 0;
        shoulderW = SHOULDERS;
        hipW = HIPS;
        init = true;
    }

    /** Обновляет направление i по вектору (vx,vy), если он не вырожденный. */
    private void blend(int i, float vx, float vy) {
        float l = (float) Math.hypot(vx, vy);
        if (l < 1e-4f) return;
        vx /= l;
        vy /= l;
        float nx = SMOOTH * vx + (1 - SMOOTH) * dx[i];
        float ny = SMOOTH * vy + (1 - SMOOTH) * dy[i];
        float nl = (float) Math.hypot(nx, ny);
        if (nl < 1e-4f) {
            nx = vx;
            ny = vy;
            nl = 1;
        }
        dx[i] = nx / nl;
        dy[i] = ny / nl;
    }

    /** @param fw,fh размеры кадра в пикселях (чтобы направления считались без искажения пропорций) */
    FigurePose build(Person p, float fw, float fh) {
        if (!init) setDefaults();
        float[] x = new float[Person.N];
        float[] y = new float[Person.N];
        for (int k = 0; k < Person.N; k++) {
            x[k] = p.x[k] * fw;
            y[k] = p.y[k] * fh;
        }
        boolean lsh = p.has(Person.L_SHO), rsh = p.has(Person.R_SHO);
        boolean lhp = p.has(Person.L_HIP), rhp = p.has(Person.R_HIP);

        // середины плеч и таза
        float nx = 0, ny = 0, px = 0, py = 0;
        boolean hasNeck = lsh || rsh, hasPelvis = lhp || rhp;
        if (hasNeck) {
            int n = (lsh ? 1 : 0) + (rsh ? 1 : 0);
            nx = ((lsh ? x[Person.L_SHO] : 0) + (rsh ? x[Person.R_SHO] : 0)) / n;
            ny = ((lsh ? y[Person.L_SHO] : 0) + (rsh ? y[Person.R_SHO] : 0)) / n;
        }
        if (hasPelvis) {
            int n = (lhp ? 1 : 0) + (rhp ? 1 : 0);
            px = ((lhp ? x[Person.L_HIP] : 0) + (rhp ? x[Person.R_HIP] : 0)) / n;
            py = ((lhp ? y[Person.L_HIP] : 0) + (rhp ? y[Person.R_HIP] : 0)) / n;
        }
        float torsoLen = 0;
        if (hasNeck && hasPelvis) {
            torsoLen = (float) Math.hypot(nx - px, ny - py);
            blend(TORSO, nx - px, ny - py);
        }
        // масштаб для ширины плеч/таза: длина корпуса, а если её нет — размер рамки
        float scale = torsoLen > 1e-3f ? torsoLen : Math.max(1e-3f, (p.bottom - p.top) * fh * 0.3f);

        // плечи и таз: направление «слева направо» на картинке и видимая ширина
        if (lsh && rsh) {
            float vx = x[Person.R_SHO] - x[Person.L_SHO], vy = y[Person.R_SHO] - y[Person.L_SHO];
            blend(SH_R, vx, vy);
            blend(SH_L, -vx, -vy);
            shoulderW = lerp(shoulderW, clamp((float) Math.hypot(vx, vy) / scale, 0.2f, SHOULDERS), 0.5f);
        } else {
            keepPerpendicular(SH_L, SH_R);
        }
        if (lhp && rhp) {
            float vx = x[Person.R_HIP] - x[Person.L_HIP], vy = y[Person.R_HIP] - y[Person.L_HIP];
            blend(HIP_R, vx, vy);
            blend(HIP_L, -vx, -vy);
            hipW = lerp(hipW, clamp((float) Math.hypot(vx, vy) / scale, 0.15f, HIPS), 0.5f);
        } else {
            keepPerpendicular(HIP_L, HIP_R);
        }

        // конечности
        limb(p, x, y, Person.L_SHO, Person.L_ELB, UA_L);
        limb(p, x, y, Person.R_SHO, Person.R_ELB, UA_R);
        limb(p, x, y, Person.L_ELB, Person.L_WRI, FA_L);
        limb(p, x, y, Person.R_ELB, Person.R_WRI, FA_R);
        limb(p, x, y, Person.L_HIP, Person.L_KNE, TH_L);
        limb(p, x, y, Person.R_HIP, Person.R_KNE, TH_R);
        limb(p, x, y, Person.L_KNE, Person.L_ANK, SN_L);
        limb(p, x, y, Person.R_KNE, Person.R_ANK, SN_R);

        head(p, x, y, hasNeck, nx, ny);

        // сборка фигурки
        FigurePose f = new FigurePose();
        f.set(FigurePose.PELVIS, 0, 0);
        float tx = dx[TORSO], ty = dy[TORSO];
        f.set(FigurePose.NECK, tx, ty);
        float shw = shoulderW / 2, hpw = hipW / 2;
        f.set(FigurePose.L_SHO, tx + dx[SH_L] * shw, ty + dy[SH_L] * shw);
        f.set(FigurePose.R_SHO, tx + dx[SH_R] * shw, ty + dy[SH_R] * shw);
        f.set(FigurePose.L_HIP, dx[HIP_L] * hpw, dy[HIP_L] * hpw);
        f.set(FigurePose.R_HIP, dx[HIP_R] * hpw, dy[HIP_R] * hpw);
        chain(f, FigurePose.L_SHO, FigurePose.L_ELB, FigurePose.L_WRI, FigurePose.L_HAND,
                UA_L, FA_L, UPPER_ARM, FORE_ARM, HAND);
        chain(f, FigurePose.R_SHO, FigurePose.R_ELB, FigurePose.R_WRI, FigurePose.R_HAND,
                UA_R, FA_R, UPPER_ARM, FORE_ARM, HAND);
        chain(f, FigurePose.L_HIP, FigurePose.L_KNE, FigurePose.L_ANK, FigurePose.L_FOOT,
                TH_L, SN_L, THIGH, SHIN, FOOT);
        chain(f, FigurePose.R_HIP, FigurePose.R_KNE, FigurePose.R_ANK, FigurePose.R_FOOT,
                TH_R, SN_R, THIGH, SHIN, FOOT);
        // ступня: перпендикулярно голени, носком в сторону взгляда (или наружу, если неизвестно)
        foot(f, FigurePose.L_ANK, FigurePose.L_FOOT, SN_L, -1);
        foot(f, FigurePose.R_ANK, FigurePose.R_FOOT, SN_R, 1);

        float hux = dx[HEAD_UP], huy = dy[HEAD_UP];
        f.upX = hux;
        f.upY = huy;
        f.rightX = -huy;
        f.rightY = hux;
        f.set(FigurePose.HEAD, tx + hux * (NECK_LEN + FigurePose.HEAD_R), ty + huy * (NECK_LEN + FigurePose.HEAD_R));
        f.yaw = yaw;
        f.pitch = pitch;
        f.back = back;
        return f;
    }

    private void keepPerpendicular(int l, int r) {
        // направление плеч/таза без данных: перпендикуляр к корпусу, левое — налево
        float tx = dx[TORSO], ty = dy[TORSO];
        blend(r, -ty, tx);
        blend(l, ty, -tx);
    }

    private void limb(Person p, float[] x, float[] y, int a, int b, int dir) {
        if (!p.has(a) || !p.has(b)) return;
        blend(dir, x[b] - x[a], y[b] - y[a]);
    }

    private void chain(FigurePose f, int j0, int j1, int j2, int j3, int d0, int d1,
                       float l0, float l1, float l2) {
        float x = f.x(j0), y = f.y(j0);
        x += dx[d0] * l0;
        y += dy[d0] * l0;
        f.set(j1, x, y);
        x += dx[d1] * l1;
        y += dy[d1] * l1;
        f.set(j2, x, y);
        // кисть/ступня дорисовываются приближённо: продолжение голени/предплечья
        f.set(j3, x + dx[d1] * l2, y + dy[d1] * l2);
    }

    private void foot(FigurePose f, int ank, int foot, int dir, int outward) {
        float sx = dx[dir], sy = dy[dir];
        // два перпендикуляра к голени; выбираем тот, что смотрит в нужную сторону по горизонтали
        float ox = -sy, oy = sx;
        float want = faceSide != 0 ? faceSide : outward;
        if (ox * want < 0) {
            ox = -ox;
            oy = -oy;
        }
        f.set(foot, f.x(ank) + ox * FOOT, f.y(ank) + oy * FOOT);
    }

    private void head(Person p, float[] x, float[] y, boolean hasNeck, float nx, float ny) {
        boolean ne = p.has(Person.NOSE);
        boolean le = p.has(Person.L_EYE), re = p.has(Person.R_EYE);
        boolean lr = p.has(Person.L_EAR), rr = p.has(Person.R_EAR);
        // верх головы: от шеи к глазам (или ушам), иначе — продолжение корпуса
        float hx = 0, hy = 0;
        int n = 0;
        if (le) { hx += x[Person.L_EYE]; hy += y[Person.L_EYE]; n++; }
        if (re) { hx += x[Person.R_EYE]; hy += y[Person.R_EYE]; n++; }
        if (n == 0) {
            if (lr) { hx += x[Person.L_EAR]; hy += y[Person.L_EAR]; n++; }
            if (rr) { hx += x[Person.R_EAR]; hy += y[Person.R_EAR]; n++; }
        }
        if (n > 0 && hasNeck) {
            hx /= n;
            hy /= n;
            blend(HEAD_UP, hx - nx, hy - ny);
        } else {
            blend(HEAD_UP, dx[TORSO], dy[TORSO]);
        }

        // поворот: смещение носа относительно середины глаз вдоль оси «вправо» головы
        float ux = dx[HEAD_UP], uy = dy[HEAD_UP];
        float rx = -uy, ry = ux;
        float newYaw = yaw, newPitch = pitch;
        boolean newBack;
        if (ne && le && re) {
            float mx = (x[Person.L_EYE] + x[Person.R_EYE]) / 2, my = (y[Person.L_EYE] + y[Person.R_EYE]) / 2;
            float ed = (float) Math.hypot(x[Person.R_EYE] - x[Person.L_EYE], y[Person.R_EYE] - y[Person.L_EYE]);
            if (ed > 1e-3f) {
                float dxn = x[Person.NOSE] - mx, dyn = y[Person.NOSE] - my;
                newYaw = clamp((dxn * rx + dyn * ry) / ed / 0.6f, -1, 1);
                float down = -(dxn * ux + dyn * uy); // вдоль «вниз» головы
                newPitch = clamp((down / ed - 0.7f) / 0.5f, -1, 1);
            }
            newBack = false;
        } else if (ne && (le || re || lr || rr)) {
            // профиль: виден один глаз/ухо — нос смотрит в сторону от него
            float ex = le ? x[Person.L_EYE] : re ? x[Person.R_EYE] : lr ? x[Person.L_EAR] : x[Person.R_EAR];
            newYaw = x[Person.NOSE] >= ex ? 1 : -1;
            newBack = false;
        } else if (ne) {
            newBack = false;
        } else {
            // носа нет: если видны уши — затылок или профиль, лица всё равно не видно
            newBack = lr || rr || le || re || hasNeck;
        }
        yaw = lerp(yaw, newYaw, 0.6f);
        pitch = lerp(pitch, newPitch, 0.6f);
        back = newBack;
        if (!newBack && Math.abs(yaw) > 0.3f) faceSide = Math.signum(yaw);
        else if (newBack) faceSide = 0;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
