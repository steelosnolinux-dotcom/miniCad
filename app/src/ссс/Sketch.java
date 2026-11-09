package ru.loftcontur.minicad.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Эскиз на плоскости сцены. Координаты примитивов — локальные (u, v)
 * плоскости в мм, начало — центр плоскости. Плоскость параметрическая,
 * поэтому эскиз следует за моделью: плоскость пересчитывается в
 * Scene.rebuild(), а примитивы рисуются в её локальных координатах.
 * Типы примитивов:
 *   point    — pts: [u, v]
 *   line     — pts: [u1, v1, u2, v2]
 *   circle   — pts: [cu, cv, r]
 *   rect     — pts: [u1, v1, u2, v2] (углы)
 *   polyline — pts: [u1, v1, u2, v2, …] (вершины по порядку)
 * preview = true — незавершённый примитив (рисуется жёлтым).
 */
public class Sketch {

    public static class Entity {
        public String type;
        public float[] pts;
        public boolean preview;

        public Entity(String type, float[] pts, boolean preview) {
            this.type = type;
            this.pts = pts;
            this.preview = preview;
        }
    }

    /** Плоскость, на которой лежит эскиз. */
    public final String planeId;
    public final List<Entity> entities = new ArrayList<>();

    public Sketch(String planeId) { this.planeId = planeId; }
}
