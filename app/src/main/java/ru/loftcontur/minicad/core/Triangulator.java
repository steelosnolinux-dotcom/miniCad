package ru.loftcontur.minicad.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Triangulator {

    private Triangulator() {}

    public static float area(List<Vec2> poly) {
        float a = 0;
        for (int i = 0, n = poly.size(); i < n; i++) {
            Vec2 p = poly.get(i), q = poly.get((i + 1) % n);
            a += p.x * q.y - q.x * p.y;
        }
        return a / 2;
    }

    /** Standard Ear Clipping для монолитного полигона. */
    public static short[] triangulate(List<Vec2> poly) {
        int n = poly.size();
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < n; i++) idx.add(i);
        if (area(poly) < 0) Collections.reverse(idx);
        List<Short> out = new ArrayList<>();
        int guard = 0;
        while (idx.size() > 3 && guard++ < 4 * n * n) {
            boolean cut = false;
            for (int k = 0; k < idx.size(); k++) {
                int i0 = idx.get((k + idx.size() - 1) % idx.size());
                int i1 = idx.get(k);
                int i2 = idx.get((k + 1) % idx.size());
                Vec2 a = poly.get(i0), b = poly.get(i1), c = poly.get(i2);
                if (b.sub(a).cross(c.sub(b)) <= 1e-9f) continue;
                boolean inside = false;
                for (int j : idx) {
                    if (j == i0 || j == i1 || j == i2) continue;
                    if (pointInTriangle(poly.get(j), a, b, c)) { inside = true; break; }
                }
                if (inside) continue;
                out.add((short) i0); out.add((short) i1); out.add((short) i2);
                idx.remove(k);
                cut = true;
                break;
            }
            if (!cut) break;
        }
        if (idx.size() == 3) {
            out.add(idx.get(0).shortValue());
            out.add(idx.get(1).shortValue());
            out.add(idx.get(2).shortValue());
        }
        short[] r = new short[out.size()];
        for (int i = 0; i < out.size(); i++) r[i] = out.get(i);
        return r;
    }

    private static boolean pointInTriangle(Vec2 p, Vec2 a, Vec2 b, Vec2 c) {
        float d1 = b.sub(a).cross(p.sub(a));
        float d2 = c.sub(b).cross(p.sub(b));
        float d3 = a.sub(c).cross(p.sub(c));
        return (d1 >= 0 && d2 >= 0 && d3 >= 0) || (d1 <= 0 && d2 <= 0 && d3 <= 0);
    }

    /**
     * Сшивание отверстий с внешним контуром через математические мостики.
     * ИСПРАВЛЕНО: Изменен тип возвращаемого значения со short на массив short[]
     * для полного соответствия вызову из Extruder.java!
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static short[] triangulateWithHoles(List outer, List holes, List mergedOut) {
        if (outer == null || outer.isEmpty()) return new short[0];

        // Безопасно приводим внешний контур к типу Vec2
        List<Vec2> m = new ArrayList<>();
        for (Object obj : outer) {
            if (obj instanceof Vec2) m.add((Vec2) obj);
        }
        if (area(m) < 0) Collections.reverse(m);

        // Послойно подшиваем внутренние пустоты
        if (holes != null) {
            for (Object hObj : holes) {
                if (!(hObj instanceof List)) continue;
                List rawHole = (List) hObj;
                if (rawHole.size() < 3) continue;

                List<Vec2> h = new ArrayList<>();
                for (Object p : rawHole) {
                    if (p instanceof Vec2) h.add((Vec2) p);
                }

                if (area(h) > 0) Collections.reverse(h);

                int bestO = 0, bestH = 0;
                float best = Float.MAX_VALUE;

                for (int i = 0; i < m.size(); i++) {
                    for (int j = 0; j < h.size(); j++) {
                        float d = m.get(i).sub(h.get(j)).len();
                        if (d < best) {
                            best = d;
                            bestO = i;
                            bestH = j;
                        }
                    }
                }

                List<Vec2> nm = new ArrayList<>();
                for (int i = 0; i <= bestO; i++) nm.add(m.get(i));
                for (int j = 0; j <= h.size(); j++) nm.add(h.get((bestH + j) % h.size()));
                nm.add(m.get(bestO));
                for (int i = bestO + 1; i < m.size(); i++) nm.add(m.get(i));
                m = nm;
            }
        }

        // Наполняем Raw-буфер mergedOut
        if (mergedOut != null) {
            mergedOut.clear();
            for (Vec2 v : m) {
                mergedOut.add(v);
            }
        }

        return triangulate(m);
    }

    /** Проверка попадания 2D точки в контур отверстия (Ray-casting) */
    private static boolean pointInPolygon2D(Vec2 pt, List<Vec2> loop) {
        boolean inside = false;
        int n = loop.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            Vec2 pi = loop.get(i);
            Vec2 db = loop.get(j);
            if (((pi.y > pt.y) != (db.y > pt.y)) &&
                    (pt.x < (db.x - pi.x) * (pt.y - pi.y) / (db.y - pi.y) + pi.x)) {
                inside = !inside;
            }
        }
        return inside;
    }
}
