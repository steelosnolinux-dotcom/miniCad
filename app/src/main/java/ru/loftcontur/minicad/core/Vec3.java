package ru.loftcontur.minicad.core;

public final class Vec3 {
    public float x, y, z;
    public Vec3(float x, float y, float z) { this.x = x; this.y = y; this.z = z; }
    public Vec3 add(Vec3 o)  { return new Vec3(x + o.x, y + o.y, z + o.z); }
    public Vec3 sub(Vec3 o)  { return new Vec3(x - o.x, y - o.y, z - o.z); }
    public Vec3 mul(float s) { return new Vec3(x * s, y * s, z * s); }
    public float dot(Vec3 o) { return x * o.x + y * o.y + z * o.z; }
    public float len() { return (float) Math.sqrt(dot(this)); }
    public Vec3 cross(Vec3 o) {
        return new Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x);
    }
    public Vec3 norm() {
        float l = len();
        return l < 1e-9f ? new Vec3(0, 0, 0) : mul(1f / l);
    }
    public static Vec3 lerp(Vec3 a, Vec3 b, float t) {
        return a.mul(1 - t).add(b.mul(t));
    }
}
