package ru.loftcontur.minicad.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Сцена: параметры + плоскости + элементы.
 * Пересчёт: плоскости из выражений → пути элементов из привязок → геометрия.
 * dirExpr поддерживает: NORMAL, -NORMAL, U, V, -U, -V, "x,y,z" и
 * "CURVE:u1,v1;u2,v2;..." — путь по кривой эскиза (локальные координаты
 * плоскости), смещение сечения задаёт offsetU/offsetV (точка профиля).
 */
public class Scene {

    public static class PlaneDef {
        public final String ox, oy, oz;
        public final String nx, ny, nz;
        public String u;
        public String sizeU, sizeV;
        public PlaneDef(String ox, String oy, String oz, String nx, String ny, String nz) {
            this.ox = ox; this.oy = oy; this.oz = oz;
            this.nx = nx; this.ny = ny; this.nz = nz;
        }
    }


    public final Map<String, Float> params = new LinkedHashMap<>();
    public final Map<String, PlaneDef> planeDefs = new LinkedHashMap<>();
    public final Map<String, Plane3D> planes = new LinkedHashMap<>();
    public final List<Element> elements = new ArrayList<>();
    public final Map<String, Extruder.MeshData> meshes = new LinkedHashMap<>();
    public String selectedId;

    /** Эскизы: по одному на плоскость. */
    public final List<Sketch> sketches = new ArrayList<>();

    /** Эскиз плоскости; создаётся при первом обращении. */
    public Sketch sketchFor(String planeId) {
        for (Sketch s : sketches)
            if (planeId.equals(s.planeId)) return s;
        Sketch s = new Sketch(planeId);
        sketches.add(s);
        return s;
    }

    public void setParam(String key, float value) {
        params.put(key, value);
        rebuild();
    }

    /** Разбор "nx,ny,nz;cx,cy,cz" в плоскость среза {n..., c...} или null. */
    private static float[] evalCut(String s, Map<String, Float> params) {
        if (s == null || s.isEmpty()) return null;
        try {
            String[] parts = s.split(";");
            if (parts.length < 2) return null;
            float[] out = new float[6];
            String[] nn = parts[0].split(",");
            String[] cc = parts[1].split(",");
            for (int i = 0; i < 3; i++) out[i] = Expr.eval(nn[i].trim(), params);
            for (int i = 0; i < 3; i++) out[3 + i] = Expr.eval(cc[i].trim(), params);
            return out;
        } catch (Exception e) {
            return null;
        }
    }



    public void rebuild() {
        planes.clear();
        for (Map.Entry<String, PlaneDef> e : planeDefs.entrySet()) {
            PlaneDef d = e.getValue();
            Vec3 o = new Vec3(Expr.eval(d.ox, params), Expr.eval(d.oy, params), Expr.eval(d.oz, params));
            Vec3 n = new Vec3(Expr.eval(d.nx, params), Expr.eval(d.ny, params), Expr.eval(d.nz, params));
            if (n.len() < 1e-6f) n = new Vec3(0, 1, 0);

            planes.put(e.getKey(),
                    d.u != null ? new Plane3D(o, n, parseVec(d.u)) : Plane3D.fromNormal(o, n));
        }

        // --- ИСПРАВЛЕНО: Безопасная сборка мешей без потери cutouts ---
        meshes.clear();
        for (Element el : elements) {
            try {
                if (el.dimExprs != null && el.typeName != null) {
                    java.util.Map<String, Float> dv = new java.util.LinkedHashMap<>();
                    for (java.util.Map.Entry<String, String> en : el.dimExprs.entrySet())
                        dv.put(en.getKey(), Expr.eval(en.getValue(), params));
                    Profile np = ProfileFactory.build(el.typeName, dv);
                    if (np != null) el.profile = np;
                }
                Plane3D pl = planes.get(el.anchor.planeId);
                if (pl == null) continue;

                if (el.anchor.dirExpr.startsWith("CURVE:")) {
                    buildCurvePath(el, pl);
                } else {
                    Vec3 start = pl.toWorld(new Vec2(
                            Expr.eval(el.anchor.offsetU, params),
                            Expr.eval(el.anchor.offsetV, params)));
                    Vec3 dir = dirOf(el.anchor.dirExpr, pl);
                    float len = Expr.eval(el.anchor.lengthExpr, params);
                    el.path.clear();
                    el.path.add(start);
                    el.path.add(start.add(dir.mul(len)));
                }

                float[] cs = evalCut(el.anchor.cutStartExpr, params);
                float[] ce = evalCut(el.anchor.cutEndExpr, params);

                // Передаем список cutouts, хранящийся в самом объекте Element, напрямую в Extruder
                Extruder.MeshData md = Extruder.extrude(el.profile, el.path, pl, cs, ce, el.cutouts, planes);
                if (md != null) meshes.put(el.id, md);
            } catch (Exception ex) {
                android.util.Log.e("Scene", "rebuild fail: " + el.id + " / " + el.name, ex);
            }
        }
    }



    /**
     * Путь по кривой эскиза. Точка размещения профиля (offsetU, offsetV) —
     * координаты точки профиля в его сечении: путь сдвигается так, чтобы
     * выбранная точка профиля легла на кривую.
     */
    private void buildCurvePath(Element el, Plane3D pl) {
        String body = el.anchor.dirExpr.substring("CURVE:".length());
        String[] pts = body.split(";");
        int n = pts.length;
        if (n < 2) return;
        float px = Expr.eval(el.anchor.offsetU, params);
        float py = Expr.eval(el.anchor.offsetV, params);

        Vec3[] world = new Vec3[n];
        for (int i = 0; i < n; i++) {
            String[] uv = pts[i].split(",");
            world[i] = pl.toWorld(new Vec2(
                    Expr.eval(uv[0].trim(), params),
                    Expr.eval(uv[1].trim(), params)));
        }

        el.path.clear();
        for (int i = 0; i < n; i++) {
            Vec3 a = world[Math.max(0, i - 1)];
            Vec3 b = world[Math.min(n - 1, i + 1)];
            Vec3 t = b.sub(a).norm();
            Vec3 U = pl.u.sub(t.mul(pl.u.dot(t)));
            if (U.len() < 1e-6f) U = pl.v.sub(t.mul(pl.v.dot(t)));
            U = U.norm();
            Vec3 V = t.cross(U).norm();
            el.path.add(world[i].sub(U.mul(px)).sub(V.mul(py)));
        }
    }

    private static Vec3 dirOf(String d, Plane3D pl) {
        boolean neg = d.startsWith("-");
        String s = neg ? d.substring(1) : d;
        Vec3 v;
        if ("NORMAL".equals(s)) v = pl.normal;
        else if ("U".equals(s)) v = pl.u;
        else if ("V".equals(s)) v = pl.v;
        else v = parseVec(s);
        return neg ? v.mul(-1) : v;
    }

    private static Vec3 parseVec(String s) {
        String[] p = s.split(",");
        return new Vec3(Float.parseFloat(p[0].trim()),
                Float.parseFloat(p[1].trim()),
                Float.parseFloat(p[2].trim())).norm();
    }
}
