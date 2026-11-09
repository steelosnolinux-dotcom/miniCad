package ru.loftcontur.minicad;

import ru.loftcontur.minicad.core.Sketch;
import ru.loftcontur.minicad.gl.Renderer;

import java.util.ArrayList;
import java.util.List;

/**
 * Сбор контуров и цепочек из примитивов эскиза. Тап по звену — обход
 * цепочки по совпадению концов (допуск JOIN). Круг, прямоугольник и
 * замкнутая полилиния возвращаются как готовый контур целиком; из
 * отдельных линий контур собирается обходом, который предпочитает
 * продолжение цепочки замыканию на хорде.
 */
public final class SketchContour {

    /** Допуск смыкания концов, мм. */
    private static final float JOIN = 0.5f;

    private SketchContour() {}

    private static class Found {
        float[] seg;            // стартовый сегмент (для линии/полилинии)
        boolean circle;
        List<float[]> direct;   // готовая последовательность точек
        boolean closed;         // direct — замкнутый контур?
        Sketch.Entity owner;
    }

    /** Замкнутый контур (петля) от звена у точки (u, v); null — не замкнулся. */
    public static List<float[]> findLoop(Sketch sk, float u, float v, float grab) {
        Found f = findStart(sk, u, v, grab);
        if (f == null) return null;
        if (f.circle || (f.direct != null && f.closed)) return f.direct;

        List<float[]> chain;
        if (f.direct != null) {
            // открытая полилиния целиком — её точки уже в цепочке
            chain = new ArrayList<>(f.direct);
        } else {
            chain = new ArrayList<>();
            chain.add(new float[]{f.seg[0], f.seg[1]});
            chain.add(new float[]{f.seg[2], f.seg[3]});
        }
        List<float[]> pool = poolExcluding(sk, f.owner);
        walk(chain, pool);
        dedupe(chain);
        if (!isClosed(chain)) return null;
        chain.remove(chain.size() - 1);
        return chain.size() >= 3 ? chain : null;
    }

    /** Открытая цепочка от звена у точки (u, v) — для профиля вдоль кривой. */
    public static List<float[]> findChain(Sketch sk, float u, float v, float grab) {
        Found f = findStart(sk, u, v, grab);
        if (f == null) return null;
        if (f.circle || f.direct != null) return f.direct;

        List<float[]> chain = new ArrayList<>();
        chain.add(new float[]{f.seg[0], f.seg[1]});
        chain.add(new float[]{f.seg[2], f.seg[3]});
        List<float[]> pool = poolExcluding(sk, f.owner);
        walk(chain, pool);
        dedupe(chain);
        return chain.size() >= 2 ? chain : null;
    }

    private static Found findStart(Sketch sk, float u, float v, float grab) {
        Found best = null;
        float bestD = grab * 10f;
        for (Sketch.Entity e : sk.entities) {
            if (e.preview) continue;
            if ("circle".equals(e.type)) {
                float d = Math.abs((float) Math.sqrt(
                        sqr(e.pts[0] - u) + sqr(e.pts[1] - v)) - e.pts[2]);
                if (d < bestD) {
                    bestD = d;
                    Found f = new Found();
                    f.circle = true;
                    f.closed = true;
                    f.direct = circleLoop(e.pts[0], e.pts[1], e.pts[2]);
                    f.owner = e;
                    best = f;
                }
                continue;
            }
            if ("point".equals(e.type)) continue;
            if ("rect".equals(e.type)) {
                // прямоугольник — сам по себе замкнутый контур
                for (float[] s : Renderer.entitySegments(e)) {
                    float d = distToSeg(u, v, s);
                    if (d < bestD) {
                        bestD = d;
                        Found f = new Found();
                        f.closed = true;
                        f.direct = rectLoop(e);
                        f.owner = e;
                        best = f;
                    }
                }
                continue;
            }
            if ("polyline".equals(e.type)) {
                float[] p = e.pts;
                boolean ring = p.length >= 6
                        && Math.abs(p[0] - p[p.length - 2]) <= JOIN
                        && Math.abs(p[1] - p[p.length - 1]) <= JOIN;
                for (float[] s : Renderer.entitySegments(e)) {
                    float d = distToSeg(u, v, s);
                    if (d < bestD) {
                        bestD = d;
                        Found f = new Found();
                        f.owner = e;
                        f.closed = ring;
                        List<float[]> pts = new ArrayList<>();
                        // без замыкающей точки, если кольцо
                        int last = ring ? p.length - 2 : p.length;
                        for (int i = 0; i + 1 < last; i += 2)
                            pts.add(new float[]{p[i], p[i + 1]});
                        f.direct = pts;
                        if (!ring) f.seg = s;
                        best = f;
                    }
                }
                continue;
            }
            for (float[] s : Renderer.entitySegments(e)) {
                float d = distToSeg(u, v, s);
                if (d < bestD) {
                    bestD = d;
                    Found f = new Found();
                    f.seg = s;
                    f.owner = e;
                    best = f;
                }
            }
        }
        return best;
    }

    /** Все сегменты эскиза, кроме сегментов сущности-владельца тапа. */
    private static List<float[]> poolExcluding(Sketch sk, Sketch.Entity owner) {
        List<float[]> pool = new ArrayList<>();
        for (Sketch.Entity e : sk.entities) {
            if (e.preview || e == owner) continue;
            if ("circle".equals(e.type) || "point".equals(e.type)) continue;
            pool.addAll(Renderer.entitySegments(e));
        }
        return pool;
    }

    /** Обход: расширяем то хвост, то голову, пока не сомкнётся
     *  или кончатся подходящие сегменты. */
    private static void walk(List<float[]> chain, List<float[]> pool) {
        for (int guard = 0; guard <= pool.size() + 2; guard++) {
            if (isClosed(chain)) return;
            boolean moved = step(chain, pool, true);
            if (isClosed(chain)) return;
            if (!moved) moved = step(chain, pool, false);
            if (!moved) return;
        }
    }

    /**
     * Один шаг от конца цепочки (atTail — хвост, иначе голова).
     * Сначала ищем сегмент-продолжение (его дальний конец НЕ совпадает
     * с противоположным концом цепочки), и только если продолжений нет —
     * замыкающий. Так контур не схлопывается в треугольник по хорде,
     * если рядом лежит перекладина.
     */
    private static boolean step(List<float[]> chain, List<float[]> pool,
                                boolean atTail) {
        float[] tip = atTail ? chain.get(chain.size() - 1) : chain.get(0);
        float[] other = atTail ? chain.get(0) : chain.get(chain.size() - 1);
        int pick = -1;
        boolean pickViaStart = false;
        boolean pickCloses = false;
        for (int i = 0; i < pool.size(); i++) {
            float[] s = pool.get(i);
            boolean viaStart = near(tip, s[0], s[1]);
            boolean viaEnd = !viaStart && near(tip, s[2], s[3]);
            if (!viaStart && !viaEnd) continue;
            float[] far = viaStart ? new float[]{s[2], s[3]}
                    : new float[]{s[0], s[1]};
            boolean closes = near(far, other[0], other[1]);
            if (pick < 0) {
                pick = i; pickViaStart = viaStart; pickCloses = closes;
            } else if (pickCloses && !closes) {
                // замыкающий кандидат заменяем продолжением
                pick = i; pickViaStart = viaStart; pickCloses = closes;
            }
            if (!pickCloses) break;
        }
        if (pick < 0) return false;
        float[] s = pool.remove(pick);
        float[] far = pickViaStart ? new float[]{s[2], s[3]}
                : new float[]{s[0], s[1]};
        if (atTail) chain.add(far);
        else chain.add(0, far);
        return true;
    }

    private static boolean isClosed(List<float[]> chain) {
        if (chain.size() < 3) return false;
        float[] a = chain.get(0), b = chain.get(chain.size() - 1);
        return near(a, b[0], b[1]);
    }

    /** Убирает подряд идущие повторы точек (вырожденные сегменты). */
    private static void dedupe(List<float[]> pts) {
        for (int i = pts.size() - 1; i > 0; i--)
            if (near(pts.get(i), pts.get(i - 1)[0], pts.get(i - 1)[1]))
                pts.remove(i);
    }

    private static List<float[]> circleLoop(float cu, float cv, float r) {
        List<float[]> out = new ArrayList<>();
        int seg = 32;
        for (int i = 0; i < seg; i++) {
            float a = 2f * (float) Math.PI * i / seg;
            out.add(new float[]{cu + r * (float) Math.cos(a),
                    cv + r * (float) Math.sin(a)});
        }
        return out;
    }

    private static List<float[]> rectLoop(Sketch.Entity e) {
        float[] p = e.pts;
        List<float[]> out = new ArrayList<>();
        out.add(new float[]{p[0], p[1]});
        out.add(new float[]{p[2], p[3]});
        out.add(new float[]{p[4], p[5]});
        out.add(new float[]{p[6], p[7]});
        return out;
    }

    private static boolean near(float[] p, float u, float v) {
        return Math.abs(p[0] - u) <= JOIN && Math.abs(p[1] - v) <= JOIN;
    }

    private static float distToSeg(float px, float py, float[] s) {
        float rx = s[2] - s[0], ry = s[3] - s[1];
        float len2 = rx * rx + ry * ry;
        if (len2 < 1e-9f)
            return (float) Math.sqrt(sqr(px - s[0]) + sqr(py - s[1]));
        float t = ((px - s[0]) * rx + (py - s[1]) * ry) / len2;
        t = Math.max(0, Math.min(1, t));
        return (float) Math.sqrt(sqr(px - (s[0] + rx * t))
                + sqr(py - (s[1] + ry * t)));
    }

    private static float sqr(float x) { return x * x; }
}
