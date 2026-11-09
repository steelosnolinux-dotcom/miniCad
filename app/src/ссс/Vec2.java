package ru.loftcontur.minicad.core;

public final class Vec2 {
    public float x, y;
    public Vec2(float x, float y) { this.x = x; this.y = y; }
    public Vec2 sub(Vec2 o)  { return new Vec2(x - o.x, y - o.y); }
    public Vec2 add(Vec2 o)  { return new Vec2(x + o.x, y + o.y); }
    public Vec2 mul(float s) { return new Vec2(x * s, y * s); }
    public float cross(Vec2 o) { return x * o.y - y * o.x; }
    public float dot(Vec2 o)   { return x * o.x + y * o.y; }
    public float len() { return (float) Math.sqrt(dot(this)); }
    public Vec2 norm() {
        float l = len();
        return l < 1e-9f ? new Vec2(0, 0) : mul(1f / l);
    }
}
