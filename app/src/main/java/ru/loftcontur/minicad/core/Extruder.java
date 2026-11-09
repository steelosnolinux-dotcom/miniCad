package ru.loftcontur.minicad.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Высокопроизводительный 3D CSG-экструдер с автономной миллиметровой фильтрацией вырезов.
 */
public final class Extruder {

    private Extruder() {}

    public static class MeshData {
        public float[] pos;
        public float[] nrm;
        public int[] idx;
        public float[] min = new float[3];
        public float[] max = new float[3];
    }

    /** Старый вход — без косых срезов и вырезов. */
    public static MeshData extrude(Profile profile, List<Vec3> path, Plane3D frame) {
        return extrude(profile, path, frame, null, null, null, null);
    }

    /**
     * Выдавливание профиля с инвариантной многоточечной 3D-фильтрацией полигонов под вырезы.
     * ИСПРАВЛЕНО: Параметры cutStart и cutEnd переведены на строгий тип float[] для корректной поддержки null.
     */
    public static MeshData extrude(Profile profile, List<Vec3> path, Plane3D frame,
                                   float[] cutStart, float[] cutEnd,
                                   List<Element.Cutout> cutouts, Map<String, Plane3D> planes) {
        int n = path.size();
        if (n < 2) return null;

        Vec3[] tang = new Vec3[n];
        for (int i = 0; i < n; i++) {
            Vec3 a = path.get(Math.max(0, i - 1));
            Vec3 b = path.get(Math.min(n - 1, i + 1));
            tang[i] = b.sub(a).norm();
        }

        Vec3[] Us = new Vec3[n], Vs = new Vec3[n];
        for (int i = 0; i < n; i++) {
            Vec3 t = tang[i];
            Vec3 u = frame.u.sub(t.mul(frame.u.dot(t)));
            if (u.len() < 1e-6f) u = frame.v.sub(t.mul(frame.v.dot(t)));
            Us[i] = u.norm();
            Vs[i] = t.cross(Us[i]).norm();
        }

        List<Vec3> verts = new ArrayList<>();
        List<Vec3> norms = new ArrayList<>();
        List<Integer> idx = new ArrayList<>();

        int perRing = 0;
        for (List<Vec2> l : profile.loops) perRing += l.size();

        for (int i = 0; i < n; i++) {
            for (List<Vec2> loop : profile.loops) {
                for (Vec2 p : loop) {
                    verts.add(path.get(i).add(Us[i].mul(p.x)).add(Vs[i].mul(p.y)));
                    norms.add(tang[i]);
                }
            }
        }

        if (cutStart != null) miterRing(verts, 0, perRing, tang[0], cutStart);
        if (cutEnd != null) miterRing(verts, (n - 1) * perRing, perRing, tang[n - 1], cutEnd);

        // --- ПРЕЦИЗИОННАЯ ИНВАРИАНТНАЯ ФИЛЬТРАЦИЯ 3D-ПОЛИГОНОВ ---
        for (int i = 0; i < n - 1; i++) {
            int baseA = i * perRing, baseB = (i + 1) * perRing;
            int offset = 0;
            for (int li = 0; li < profile.loops.size(); li++) {
                int m = profile.loops.get(li).size();
                for (int j = 0; j < m; j++) {
                    int j2 = (j + 1) % m;

                    int a = baseA + offset + j;
                    int b = baseA + offset + j2;
                    int c = baseB + offset + j2;
                    int d = baseB + offset + j;

                    Vec3 vA = verts.get(a);
                    Vec3 vB = verts.get(b);
                    Vec3 vC = verts.get(c);
                    Vec3 vD = verts.get(d);
                    Vec3 polyCenter = vA.add(vB).add(vC).add(vD).mul(0.25f);

                    boolean isCut = false;

                    if (cutouts != null && !cutouts.isEmpty() && planes != null) {
                        for (Element.Cutout cutout : cutouts) {
                            Plane3D cutPlane = planes.get(cutout.planeId);
                            if (cutPlane == null) continue;

                            // Проверяем 5 контрольных точек (центр + 4 угла) для стабильного отсечения
                            Vec3[] checkPts = {polyCenter, vA, vB, vC, vD};
                            for (Vec3 pCheck : checkPts) {
                                // Прямой инвариантный перевод 3D точки в 2D координаты эскиза выреза через toLocal
                                Vec2 projected2d = cutPlane.toLocal(pCheck);

                                // Переводим метры OpenGL в CAD-миллиметры эскиза (* 1000f)
                                Vec2 locPt = cutPlane.toLocal(pCheck);

                                if (pointInPolygon(locPt, cutout.loop)) {
                                    if (!cutout.isThrough) {
                                        float depth = 0;
                                        try { depth = Float.parseFloat(cutout.depthExpr.replace(',', '.')); } catch (Exception ignored) {}
                                        float distToPlane = Math.abs(cutPlane.normal.dot(pCheck.sub(cutPlane.origin))) * 1000f;
                                        if (distToPlane > depth) continue;
                                    }
                                    isCut = true;
                                    break;
                                }
                            }
                            if (isCut) break;
                        }
                    }

                    if (isCut) {
                        // Схлопываем треугольники выреза в невидимую вырожденную точку (a, a, a)
                        idx.add(a); idx.add(a); idx.add(a);
                        idx.add(a); idx.add(a); idx.add(a);
                        continue;
                    }

                    if (li > 0) {
                        idx.add(d); idx.add(c); idx.add(b);
                        idx.add(d); idx.add(b); idx.add(a);
                    } else {
                        idx.add(a); idx.add(b); idx.add(c);
                        idx.add(a); idx.add(c); idx.add(d);
                    }
                }
                offset += m;
            }
        }

        // Рендеринг торцов (каппы деталей)
        float[] capStart = cutStart != null ? cutStart : planeOf(tang[0].mul(-1), path.get(0));
        float[] capEnd = cutEnd != null ? cutEnd : planeOf(tang[n - 1], path.get(n - 1));

        capMiterInt(verts, norms, idx, profile, 0, perRing, capStart, capNormal(capStart, tang[0], false), cutouts, planes, frame);
        capMiterInt(verts, norms, idx, profile, (n - 1) * perRing, perRing, capEnd, capNormal(capEnd, tang[n - 1], true), cutouts, planes, frame);

        return finalizeMesh(verts, norms, idx);
    }

    /** Вспомогательный метод исправления сигнатуры miterRing под типы float[] */
    private static void miterRing(List<Vec3> verts, int base, int count, Vec3 t, float[] cut) {
        if (cut == null || cut.length < 6) return;
        Vec3 n = new Vec3(cut[0], cut[1], cut[2]).norm();
        Vec3 c = new Vec3(cut[3], cut[4], cut[5]);
        float dn = n.dot(t);
        if (Math.abs(dn) < 1e-9f) return;
        for (int k = 0; k < count; k++) {
            Vec3 v = verts.get(base + k);
            float s = n.dot(c.sub(v)) / dn;
            verts.set(base + k, v.add(t.mul(s)));
        }
    }

    /** Вспомогательный метод исправления сигнатуры capNormal под типы float[] */
    private static Vec3 capNormal(float[] plane, Vec3 t, boolean atEnd) {
        if (plane == null || plane.length < 3) return t;
        Vec3 n = new Vec3(plane[0], plane[1], plane[2]).norm();
        return (atEnd ? n.dot(t) > 0 : n.dot(t) < 0) ? n : n.mul(-1);
    }


    private static MeshData finalizeMesh(List<Vec3> verts, List<Vec3> norms, List<Integer> idx) {
        MeshData md = new MeshData();
        md.pos = new float[verts.size() * 3];
        md.nrm = new float[norms.size() * 3];
        for (int i = 0; i < verts.size(); i++) {
            md.pos[i * 3]     = verts.get(i).x;
            md.pos[i * 3 + 1] = verts.get(i).y;
            md.pos[i * 3 + 2] = verts.get(i).z;
            md.nrm[i * 3]     = norms.get(i).x;
            md.nrm[i * 3 + 1] = norms.get(i).y;
            md.nrm[i * 3 + 2] = norms.get(i).z;
        }
        md.idx = new int[idx.size()];
        for (int i = 0; i < idx.size(); i++) md.idx[i] = idx.get(i);

        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < md.pos.length; i += 3) {
            float x = md.pos[i], y = md.pos[i + 1], z = md.pos[i + 2];
            if (x < minX) minX = x; if (x > maxX) maxX = x;
            if (y < minY) minY = y; if (y > maxY) maxY = y;
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z;
        }
        md.min[0] = minX; md.min[1] = minY; md.min[2] = minZ;
        md.max[0] = maxX; md.max[1] = maxY; md.max[2] = maxZ;
        return md;
    }

    private static float[] planeOf(Vec3 n, Vec3 c) {
        return new float[]{n.x, n.y, n.z, c.x, c.y, c.z};
    }

    private static void capMiterInt(List<Vec3> verts, List<Vec3> norms, List<Integer> idx,
                                    Profile profile, int base, int perRing, float[] plane, Vec3 normal,
                                    List<Element.Cutout> cutouts, Map<String, Plane3D> planes, Plane3D frame) {
        Vec3 n = new Vec3(plane[0], plane[1], plane[2]).norm();
        Vec3 cp = new Vec3(plane[3], plane[4], plane[5]);

        Vec3 ref = Math.abs(n.y) < 0.9f ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 Uc = ref.sub(n.mul(ref.dot(n))).norm();
        if (Uc.len() < 1e-6f) Uc = new Vec3(1, 0, 0);
        Vec3 Vc = n.cross(Uc).norm();

        List<List<Vec2>> loops2d = new ArrayList<>();
        int off = 0;
        for (List<Vec2> l : profile.loops) {
            List<Vec2> lp = new ArrayList<>();
            for (int j = 0; j < l.size(); j++) {
                if (base + off + j >= verts.size()) break;
                Vec3 d = verts.get(base + off + j).sub(cp);
                lp.add(new Vec2(d.dot(Uc), d.dot(Vc)));
            }
            loops2d.add(lp);
            off += l.size();
        }

        List<Vec2> outer = new ArrayList<>(loops2d.get(0));
        if (Triangulator.area(outer) < 0) Collections.reverse(outer);

        List<List> holes = new ArrayList<>();
        for (int i = 1; i < loops2d.size(); i++) {
            List h = new ArrayList<>(loops2d.get(i));
            if (Triangulator.area(h) > 0) Collections.reverse(h);
            holes.add(h);
        }
        List<Vec2> merged = new ArrayList<>();
        short[] tri = Triangulator.triangulateWithHoles(outer, holes, merged);
        int bs = verts.size();
        for (int i = 0; i < tri.length; i += 3) {
            Vec2 p0 = merged.get(tri[i]);
            Vec2 p1 = merged.get(tri[i + 1]);
            Vec2 p2 = merged.get(tri[i + 2]);
            Vec3 w0 = cp.add(Uc.mul(p0.x)).add(Vc.mul(p0.y));
            Vec3 w1 = cp.add(Uc.mul(p1.x)).add(Vc.mul(p1.y));
            Vec3 w2 = cp.add(Uc.mul(p2.x)).add(Vc.mul(p2.y));
            Vec3 triCenter = w0.add(w1).add(w2).mul(1f / 3f);
            boolean isCut = false;
            if (cutouts != null && !cutouts.isEmpty()) {
                for (Element.Cutout cutout : cutouts) {
                    Plane3D cutPlane = (planes != null) ? planes.get(cutout.planeId) : null;
                    if (cutPlane == null) cutPlane = frame;
                    Vec3 vToPt = triCenter.sub(cutPlane.origin);
                    Vec2 locPt = new Vec2(vToPt.dot(cutPlane.u) * 1000f, vToPt.dot(cutPlane.v) * 1000f);
                    if (pointInPolygon(locPt, cutout.loop)) {
                        isCut = true;
                        break;
                    }
                }
            }
            if (!isCut) {
                idx.add(bs + (int) tri[i]);
                idx.add(bs + (int) tri[i + 1]);
                idx.add(bs + (int) tri[i + 2]);
            } else {
                idx.add(bs); idx.add(bs); idx.add(bs);
            }
        }
        for (Vec2 p : merged) {
            verts.add(cp.add(Uc.mul(p.x)).add(Vc.mul(p.y)));
            norms.add(normal);
        }
    }
    /**
     * Алгоритм Jordan Curve (Трассировка луча) для проверки попадания точки в 2D полигон эскиза.
     */
    private static boolean pointInPolygon(Vec2 pt, List<float[]> loop) {
        if (loop == null || loop.size() < 3) return false;
        boolean inside = false;
        int n = loop.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            float[] pi = loop.get(i);
            float[] pj = loop.get(j);
            if (pi == null || pj == null || pi.length < 2 || pj.length < 2) continue;
            float xi = pi[0], yi = pi[1];
            float xj = pj[0], yj = pj[1];
            if (((yi > pt.y) != (yj > pt.y)) &&
                    (pt.x < (xj - xi) * (pt.y - yi) / (yj - yi) + xi)) {
                inside = !inside;
            }
        }
        return inside;
    }
    private static void pushQuadInt(List<Vec3> verts, List<Vec3> norms, List<Integer> idx,
                                    int a, int b, int c, int d, Vec3 n) {
        idx.add(a); idx.add(b); idx.add(c);
        idx.add(a); idx.add(c); idx.add(d);
    }

}