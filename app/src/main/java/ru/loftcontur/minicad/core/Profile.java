package ru.loftcontur.minicad.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Сечение в мм. loops.get(0) — внешний контур (против часовой),
 * остальные — отверстия (по часовой).
 */
public class Profile {
    public final List<List<Vec2>> loops = new ArrayList<>();

    public Profile(Vec2[] outer) { loops.add(new ArrayList<>(Arrays.asList(outer))); }
    public void addHole(List<Vec2> hole) { loops.add(hole); }

    public float area() {
        float a = Math.abs(Triangulator.area(loops.get(0)));
        for (int i = 1; i < loops.size(); i++) a -= Math.abs(Triangulator.area(loops.get(i)));
        return a;
    }

    private static List<Vec2> arc(List<Vec2> out, Vec2 c, float r, float a0, float a1, int seg) {
        for (int i = 0; i <= seg; i++) {
            float a = a0 + (a1 - a0) * i / seg;
            out.add(new Vec2(c.x + r * (float) Math.cos(a), c.y + r * (float) Math.sin(a)));
        }
        return out;
    }

    /** Лист w × h, r — радиус скругления углов (0 = без скруглений). */
    public static Profile sheet(float w, float h, float r) {
        List<Vec2> p = new ArrayList<>();
        if (r <= 0) {
            p.add(new Vec2(-w / 2, -h / 2)); p.add(new Vec2(w / 2, -h / 2));
            p.add(new Vec2(w / 2, h / 2));   p.add(new Vec2(-w / 2, h / 2));
        } else {
            r = Math.min(r, Math.min(w, h) / 2 - 0.01f);
            p.add(new Vec2(-w / 2 + r, -h / 2));
            arc(p, new Vec2(w / 2 - r, -h / 2 + r), r, -(float) Math.PI / 2, 0, 6);
            p.add(new Vec2(w / 2, h / 2 - r));
            arc(p, new Vec2(w / 2 - r, h / 2 - r), r, 0, (float) Math.PI / 2, 6);
            p.add(new Vec2(-w / 2 + r, h / 2));
            arc(p, new Vec2(-w / 2 + r, h / 2 - r), r, (float) Math.PI / 2, (float) Math.PI, 6);
            p.add(new Vec2(-w / 2, -h / 2 + r));
            arc(p, new Vec2(-w / 2 + r, -h / 2 + r), r, (float) Math.PI, 1.5f * (float) Math.PI, 6);
        }
        return new Profile(p.toArray(new Vec2[0]));
    }

    private static List<Vec2> roundedRect(float w, float h, float r) {
        List<Vec2> p = new ArrayList<>();
        if (r <= 0) {
            p.add(new Vec2(-w / 2, -h / 2)); p.add(new Vec2(w / 2, -h / 2));
            p.add(new Vec2(w / 2, h / 2));   p.add(new Vec2(-w / 2, h / 2));
        } else {
            r = Math.min(r, Math.min(w, h) / 2 - 0.01f);
            arc(p, new Vec2(-w / 2 + r, -h / 2 + r), r, (float) Math.PI, 1.5f * (float) Math.PI, 5);
            arc(p, new Vec2(w / 2 - r, -h / 2 + r), r, 1.5f * (float) Math.PI, 2f * (float) Math.PI, 5);
            arc(p, new Vec2(w / 2 - r, h / 2 - r), r, 0, (float) Math.PI / 2, 5);
            arc(p, new Vec2(-w / 2 + r, h / 2 - r), r, (float) Math.PI / 2, (float) Math.PI, 5);
        }
        return p;
    }

    /** Труба прямоугольная/квадратная: w × h, стенка t, радиус углов r. */
    public static Profile rectTube(float w, float h, float t, float r) {
        Profile p = new Profile(new Vec2[0]);
        p.loops.set(0, roundedRect(w, h, r));
        if (w > 2 * t && h > 2 * t)
            p.addHole(roundedRect(w - 2 * t, h - 2 * t, Math.max(0, r - t)));
        return p;
    }

    private static List<Vec2> circle(float r, int seg) {
        List<Vec2> p = new ArrayList<>();
        for (int i = 0; i < seg; i++) {
            float a = 2f * (float) Math.PI * i / seg;
            p.add(new Vec2(r * (float) Math.cos(a), r * (float) Math.sin(a)));
        }
        return p;
    }

    /** Труба круглая: D — наружный диаметр, t — стенка. */
    public static Profile roundTube(float D, float t) {
        Profile p = new Profile(new Vec2[0]);
        p.loops.set(0, circle(D / 2, 32));
        if (D > 2 * t) p.addHole(circle(D / 2 - t, 32));
        return p;
    }

    private static List<Vec2> stadium(float w, float h, float R, int seg) {
        List<Vec2> p = new ArrayList<>();
        float dx = Math.abs(w - h) / 2;
        if (w >= h) {
            arc(p, new Vec2(-dx,  R), R, (float) Math.PI / 2, (float) Math.PI, seg);
            arc(p, new Vec2( dx,  R), R, (float) Math.PI, 1.5f * (float) Math.PI, seg);
            arc(p, new Vec2( dx, -R), R, 1.5f * (float) Math.PI, 2f * (float) Math.PI, seg);
            arc(p, new Vec2(-dx, -R), R, 0, (float) Math.PI / 2, seg);
        } else {
            arc(p, new Vec2( R, -dx), R, 0, (float) Math.PI / 2, seg);
            arc(p, new Vec2( R,  dx), R, (float) Math.PI / 2, (float) Math.PI, seg);
            arc(p, new Vec2(-R,  dx), R, (float) Math.PI, 1.5f * (float) Math.PI, seg);
            arc(p, new Vec2(-R, -dx), R, 1.5f * (float) Math.PI, 2f * (float) Math.PI, seg);
        }
        return p;
    }

    /** Труба овальная: a × b, стенка t. */
    public static Profile ovalTube(float a, float b, float t) {
        float R = Math.min(a, b) / 2;
        Profile p = new Profile(new Vec2[0]);
        p.loops.set(0, stadium(a, b, R, 16));
        if (a > 2 * t && b > 2 * t)
            p.addHole(stadium(a - 2 * t, b - 2 * t, Math.max(0.01f, R - t), 16));
        return p;
    }

    /** Уголок: полка b (по X), полка h (по Y), стенка t. */
    public static Profile angle(float b, float h, float t) {
        return new Profile(new Vec2[]{
                new Vec2(0, 0), new Vec2(b, 0), new Vec2(b, t),
                new Vec2(t, t), new Vec2(t, h), new Vec2(0, h)});
    }

    /** Швеллер: h — высота, b — вылет полок, t — стенка. */
    public static Profile channel(float h, float b, float t) {
        return new Profile(new Vec2[]{
                new Vec2(0, 0), new Vec2(b, 0), new Vec2(b, t),
                new Vec2(t, t), new Vec2(t, h - t), new Vec2(b, h - t),
                new Vec2(b, h), new Vec2(0, h)});
    }

    /** Тавр/двутавр: h — высота, b — ширина полки, tw — стенка, tf — полка. */
    public static Profile tee(float h, float b, float tw, float tf) {
        return new Profile(new Vec2[]{
                new Vec2(-b / 2, 0), new Vec2(b / 2, 0), new Vec2(b / 2, tf),
                new Vec2(tw / 2, tf), new Vec2(tw / 2, h - tf), new Vec2(b / 2, h - tf),
                new Vec2(b / 2, h), new Vec2(-b / 2, h), new Vec2(-b / 2, h - tf),
                new Vec2(-tw / 2, h - tf), new Vec2(-tw / 2, tf), new Vec2(-b / 2, tf)});
    }
}
