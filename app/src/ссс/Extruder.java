package ru.loftcontur.minicad.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Выдавливание профиля по пути. Торцы могут быть срезаны плоскостью
 * под наклон (miter cut): кольцо торца смещается вдоль оси до пересечения
 * с режущей плоскостью — труба стыкуется с гранью под любым углом.
 */
public final class Extruder {

    private Extruder() {}

    public static class MeshData {
        public float[] pos, nrm;
        public int[] idx; // Заменили short[] на int[]
        public float[] min = new float[3];
        public float[] max = new float[3];
    }

    /** Старый вход — без косых срезов. */
    public static MeshData extrude(Profile profile, List<Vec3> path, Plane3D frame) {
        return extrude(profile, path, frame, null, null);
    }

    /**
     * cutStart/cutEnd — режущие плоскости торцов в виде
     * {nx, ny, nz, cx, cy, cz} или null (торец перпендикулярен оси).
     */
    public static MeshData extrude(Profile profile, List<Vec3> path, Plane3D frame,
                                   float[] cutStart, float[] cutEnd) {
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
        List<Short> idx = new ArrayList<>();

        int perRing = 0;
        for (List<Vec2> l : profile.loops) perRing += l.size();

        for (int i = 0; i < n; i++)
            for (List<Vec2> loop : profile.loops)
                for (Vec2 p : loop) {
                    verts.add(path.get(i).add(Us[i].mul(p.x)).add(Vs[i].mul(p.y)));
                    norms.add(tang[i]);
                }

        if (cutStart != null) miterRing(verts, 0, perRing, tang[0], cutStart);
        if (cutEnd != null)
            miterRing(verts, (n - 1) * perRing, perRing, tang[n - 1], cutEnd);

        // стенки
        for (int i = 0; i < n - 1; i++) {
            int baseA = i * perRing, baseB = (i + 1) * perRing;
            int offset = 0;
            for (int li = 0; li < profile.loops.size(); li++) {
                int m = profile.loops.get(li).size();
                for (int j = 0; j < m; j++) {
                    int j2 = (j + 1) % m;
                    int a = baseA + offset + j, b = baseA + offset + j2;
                    int c = baseB + offset + j2, d = baseB + offset + j;
                    Vec3 nn = verts.get(b).sub(verts.get(a))
                            .cross(verts.get(d).sub(verts.get(a))).norm();
                    if (nn.len() < 1e-9f) continue;
                    if (li > 0) nn = nn.mul(-1);
                    pushQuad(verts, norms, idx, a, b, c, d, nn);
                }
                offset += m;
            }
        }

        // торцы
        float[] capStart = cutStart != null ? cutStart
                : planeOf(tang[0].mul(-1), path.get(0));
        float[] capEnd = cutEnd != null ? cutEnd
                : planeOf(tang[n - 1], path.get(n - 1));
        capMiter(verts, norms, idx, profile, 0, perRing,
                capStart, capNormal(capStart, tang[0], false));
        capMiter(verts, norms, idx, profile, (n - 1) * perRing, perRing,
                capEnd, capNormal(capEnd, tang[n - 1], true));

        if (verts.size() != norms.size())
            throw new IllegalStateException("verts/norms mismatch: "
                    + verts.size() + " vs " + norms.size());

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
        // Вычисляем AABB детали один раз при генерации геометрии
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

    /** Смещение кольца вдоль оси t до пересечения с режущей плоскостью. */
    private static void miterRing(List<Vec3> verts, int base, int count,
                                  Vec3 t, float[] cut) {
        Vec3 n = new Vec3(cut[0], cut[1], cut[2]).norm();
        Vec3 c = new Vec3(cut[3], cut[4], cut[5]);
        float dn = n.dot(t);
        if (Math.abs(dn) < 1e-9f) return;
        for (int k = 0; k < count; k++) {
            Vec3 v = verts.get(base + k);
            float s = n.dot(c.sub(v)) / dn;
            if (s > 100000f) s = 100000f;
            if (s < -100000f) s = -100000f;
            verts.set(base + k, v.add(t.mul(s)));
        }
    }

    /** Нормаль торца: наружу от тела. */
    private static Vec3 capNormal(float[] plane, Vec3 t, boolean atEnd) {
        Vec3 n = new Vec3(plane[0], plane[1], plane[2]).norm();
        boolean ok = atEnd ? n.dot(t) > 0 : n.dot(t) < 0;
        return ok ? n : n.mul(-1);
    }

    /** Торец по фактическим (возможно косым) вершинам кольца. */
    private static void capMiter(List<Vec3> verts, List<Vec3> norms, List<Short> idx,
                                 Profile profile, int base, int perRing,
                                 float[] plane, Vec3 normal) {
        Vec3 n = new Vec3(plane[0], plane[1], plane[2]).norm();
        Vec3 cp = new Vec3(plane[3], plane[4], plane[5]);
        Vec3 ref = Math.abs(n.y) < 0.9f ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 Uc = ref.sub(n.mul(ref.dot(n))).norm();
        if (Uc.len() < 1e-6f) Uc = new Vec3(1, 0, 0);
        Vec3 Vc = n.cross(Uc).norm();

        // 2D-координаты вершин кольца ОТНОСИТЕЛЬНО точки торца
        List<List<Vec2>> loops2d = new ArrayList<>();
        int off = 0;
        for (List<Vec2> l : profile.loops) {
            List<Vec2> lp = new ArrayList<>();
            for (int j = 0; j < l.size(); j++) {
                Vec3 d = verts.get(base + off + j).sub(cp);
                lp.add(new Vec2(d.dot(Uc), d.dot(Vc)));
            }
            loops2d.add(lp);
            off += l.size();
        }
        List<Vec2> outer = new ArrayList<>(loops2d.get(0));
        if (Triangulator.area(outer) < 0) Collections.reverse(outer);
        List<List<Vec2>> holes = new ArrayList<>();
        for (int i = 1; i < loops2d.size(); i++) {
            List<Vec2> h = new ArrayList<>(loops2d.get(i));
            if (Triangulator.area(h) > 0) Collections.reverse(h);
            holes.add(h);
        }
        List<Vec2> merged = new ArrayList<>();
        short[] tri = Triangulator.triangulateWithHoles(outer, holes, merged);

        int bs = verts.size();
        for (Vec2 p : merged) {
            verts.add(cp.add(Uc.mul(p.x)).add(Vc.mul(p.y)));
            norms.add(normal);
        }
        for (short t : tri) idx.add((short) (bs + t));
    }

    private static void pushQuad(List<Vec3> verts, List<Vec3> norms, List<Short> idx,
                                 int a, int b, int c, int d, Vec3 n) {
        int s = verts.size();
        verts.add(verts.get(a)); verts.add(verts.get(b));
        verts.add(verts.get(c)); verts.add(verts.get(d));
        for (int k = 0; k < 4; k++) norms.add(n);
        idx.add((short) s); idx.add((short) (s + 1)); idx.add((short) (s + 2));
        idx.add((short) s); idx.add((short) (s + 2)); idx.add((short) (s + 3));
    }
}
