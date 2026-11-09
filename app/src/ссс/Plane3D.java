package ru.loftcontur.minicad.core;

/** Плоскость: точка + нормаль + базис (u, v). */
public class Plane3D {
    public final Vec3 origin, normal, u, v;

    public Plane3D(Vec3 origin, Vec3 normal, Vec3 uHint) {
        this.origin = origin;
        this.normal = normal.norm();

        Vec3 uDir = uHint.sub(this.normal.mul(uHint.dot(this.normal))).norm();
        if (uDir.len() < 1e-6f) {
            Vec3 helper = Math.abs(this.normal.y) < 0.9f ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
            uDir = helper.sub(this.normal.mul(helper.dot(this.normal))).norm();
        }

        this.u = uDir;
        this.v = this.normal.cross(this.u);
    }

    public static Plane3D fromNormal(Vec3 origin, Vec3 normal) {
        Vec3 helper = Math.abs(normal.y) < 0.9f ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        return new Plane3D(origin, normal, helper);
    }

    /** Локальные координаты (u, v) → точка в мире. */
    public Vec3 toWorld(Vec2 p) {
        return origin.add(u.mul(p.x)).add(v.mul(p.y));
    }

    /** Точка мира → локальные координаты (u, v). */
    public Vec2 toLocal(Vec3 p) {
        Vec3 d = p.sub(origin);
        return new Vec2(d.dot(u), d.dot(v));
    }
}
