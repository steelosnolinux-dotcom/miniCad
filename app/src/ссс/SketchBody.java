package ru.loftcontur.minicad;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ru.loftcontur.minicad.core.Anchor;
import ru.loftcontur.minicad.core.Element;
import ru.loftcontur.minicad.core.Expr;
import ru.loftcontur.minicad.core.Material;
import ru.loftcontur.minicad.core.Profile;
import ru.loftcontur.minicad.core.Scene;
import ru.loftcontur.minicad.core.Vec2;
import ru.loftcontur.minicad.core.Vec3;
import ru.loftcontur.minicad.core.Plane3D;

public final class SketchBody {

    private static int seq = 1;

    private SketchBody() {}

    // ---------- листовое тело ----------

    public static Element createSheet(Scene scene, String planeId,
                                      List<float[]> loop,
                                      String thicknessExpr, boolean up) {
        Element e = new Element("BODY_" + (seq++));
        e.name = "Лист по эскизу";
        e.material = Material.ST3;
        e.profile = profileFromLoop(loop);
        e.anchor = new Anchor(planeId, "0", "0", thicknessExpr,
                up ? "NORMAL" : "-NORMAL");
        scene.elements.add(e);
        scene.rebuild();
        return e;
    }

    static Profile profileFromLoop(List<float[]> loop) {
        List<Vec2> pts = new ArrayList<>();
        for (float[] q : loop) pts.add(new Vec2(q[0], q[1]));
        if (signedArea(pts) < 0) java.util.Collections.reverse(pts);
        return new Profile(pts.toArray(new Vec2[0]));
    }

    private static float signedArea(List<Vec2> p) {
        float a = 0;
        for (int i = 0; i < p.size(); i++) {
            Vec2 c = p.get(i), d = p.get((i + 1) % p.size());
            a += c.x * d.y - d.x * c.y;
        }
        return a / 2;
    }

    // ---------- профиль по одной кривой ----------

    public static Element createProfileAlong(Scene scene, String planeId,
                                             List<float[]> chain,
                                             Profile profile, String typeName,
                                             Map<String, String> dims,
                                             int pointIdx) {
        float[] P = profilePoint(profile, pointIdx);
        float len = chainLength(chain);

        Element e = new Element("BODY_" + (seq++));
        e.name = typeName != null ? typeName : "Профиль по кривой";
        e.material = Material.ST3;
        e.profile = profile;
        e.typeName = typeName;
        e.dimExprs = dims;
        e.anchor = new Anchor(planeId, num(P[0]), num(P[1]), num(len),
                encodeCurve(chain));
        e.anchorPoint = pointIdx;

        scene.elements.add(e);
        scene.rebuild();
        return e;
    }

    /**
     * 13 точек профиля: 0 — центр; 1–4 — внешние углы; 5–8 — середины
     * внешних сторон; 9–12 — середины стенок.
     */
    public static float[] profilePoint(Profile p, int idx) {
        float[] o = bbox(p.loops.get(0));
        float cx = (o[0] + o[2]) / 2, cy = (o[1] + o[3]) / 2;
        float[] inner;
        if (p.loops.size() > 1) {
            inner = bbox(p.loops.get(1));
        } else {
            float ins = Math.min(o[2] - o[0], o[3] - o[1]) * 0.25f;
            inner = new float[]{o[0] + ins, o[1] + ins, o[2] - ins, o[3] - ins};
        }
        float ix = (inner[0] + inner[2]) / 2, iy = (inner[1] + inner[3]) / 2;
        switch (idx) {
            case 1:  return new float[]{o[0], o[1]};
            case 2:  return new float[]{o[2], o[1]};
            case 3:  return new float[]{o[2], o[3]};
            case 4:  return new float[]{o[0], o[3]};
            case 5:  return new float[]{cx, o[1]};
            case 6:  return new float[]{o[2], cy};
            case 7:  return new float[]{cx, o[3]};
            case 8:  return new float[]{o[0], cy};
            case 9:  return new float[]{ix, inner[1]};
            case 10: return new float[]{inner[2], iy};
            case 11: return new float[]{ix, inner[3]};
            case 12: return new float[]{inner[0], iy};
            default: return new float[]{cx, cy};
        }
    }

    private static float[] bbox(List<Vec2> pts) {
        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE;
        float x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        for (Vec2 v : pts) {
            if (v.x < x0) x0 = v.x; if (v.x > x1) x1 = v.x;
            if (v.y < y0) y0 = v.y; if (v.y > y1) y1 = v.y;
        }
        return new float[]{x0, y0, x1, y1};
    }

    // ---------- косой срез и удлинение по грани ----------

    public static int fitByFace(Scene scene, Element el, Plane3D pl,
                                Vec3 n, Vec3 c) {
        if (el.anchor == null || el.path == null || el.path.size() < 2)
            return 0;

        Vec3 start = el.path.get(0);
        Vec3 end = el.path.get(el.path.size() - 1);
        Vec3 axisDir = end.sub(start).norm();

        // 1. Проверяем угол между осью профиля и нормалью режущей грани балки
        float dn = n.dot(axisDir);
        if (Math.abs(dn) < 1e-5f) {
            return -1; // Ось профиля строго параллельна плоскости грани — резать нельзя
        }

        // 2. Находим математическую точку пересечения бесконечной прямой оси балки с плоскостью грани
        // Формула луча: t = (n · (c - start)) / (n · axisDir)
        float tIntersect = n.dot(c.sub(start)) / dn;

        float currentLen = end.sub(start).len();
        boolean atEnd = tIntersect > (currentLen / 2f);

        // Формируем строковое выражение секущей плоскости
        String cutExpr = num(n.x) + "," + num(n.y) + "," + num(n.z) + ";"
                + num(c.x) + "," + num(c.y) + "," + num(c.z);

        if (atEnd) {
            // ТОРЕЦ КОНЦА (atEnd)
            if (tIntersect > currentLen) {
                // Точка пересечения дальше текущего конца балки -> это УДЛИНЕНИЕ
                el.anchor.cutEndExpr = null; // Сбрасываем скос, так как торец отодвинулся
                el.anchor = new Anchor(el.anchor.planeId, el.anchor.offsetU, el.anchor.offsetV,
                        num(tIntersect), el.anchor.dirExpr, el.anchor.cutStartExpr, null);
            } else {
                // Точка пересечения внутри балки -> это УСЕЧЕНИЕ (косой срез)
                el.anchor.cutEndExpr = cutExpr;
            }
        } else {
            // ТОРЕЦ НАЧАЛА (atStart)
            if (tIntersect < 0) {
                // Точка пересечения позади старта -> это УДЛИНЕНИЕ начала в обратную сторону
                float deltaLen = Math.abs(tIntersect);
                float newLen = currentLen + deltaLen;

                // Пересчитываем смещения U и V точки привязки на плоскости эскиза
                float currentOffU = Expr.eval(el.anchor.offsetU, scene.params);
                float currentOffV = Expr.eval(el.anchor.offsetV, scene.params);

                // Сдвигаем мировую точку старта назад вдоль направления оси балки
                Vec3 shiftWorld = axisDir.mul(tIntersect);
                Vec3 newStartWorld = start.add(shiftWorld);
                Vec2 newStartLocal = pl.toLocal(newStartWorld);

                el.anchor.cutStartExpr = null; // Сбрасываем скос удлиненного начала
                el.anchor = new Anchor(el.anchor.planeId,
                        num(newStartLocal.x), num(newStartLocal.y),
                        num(newLen), el.anchor.dirExpr, null, el.anchor.cutEndExpr);
            } else {
                // Точка пересечения внутри балки со стороны старта -> это УСЕЧЕНИЕ
                el.anchor.cutStartExpr = cutExpr;
            }
        }

        scene.rebuild();
        return 1;
    }

    private static float chainLength(List<float[]> c) {
        float s = 0;
        for (int i = 1; i < c.size(); i++) {
            float dx = c.get(i)[0] - c.get(i - 1)[0];
            float dy = c.get(i)[1] - c.get(i - 1)[1];
            s += (float) Math.sqrt(dx * dx + dy * dy);
        }
        return s;
    }

    public static void setAnchorPoint(Scene scene, Element el, int idx) {
        float[] P = profilePoint(el.profile, idx);
        el.anchor = new Anchor(el.anchor.planeId, num(P[0]), num(P[1]),
                el.anchor.lengthExpr, el.anchor.dirExpr,
                el.anchor.cutStartExpr, el.anchor.cutEndExpr);
        el.anchorPoint = idx;
        scene.rebuild();
    }

    static String encodeCurve(List<float[]> c) {
        StringBuilder sb = new StringBuilder("CURVE:");
        for (int i = 0; i < c.size(); i++) {
            if (i > 0) sb.append(";");
            sb.append(num(c.get(i)[0])).append(",").append(num(c.get(i)[1]));
        }
        return sb.toString();
    }

    static String encodeCut(Vec3 n, Vec3 c) {
        return num(n.x) + "," + num(n.y) + "," + num(n.z) + ";"
                + num(c.x) + "," + num(c.y) + "," + num(c.z);
    }

    private static String num(float v) {
        return String.format(Locale.US, "%.4f", v)
                .replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
