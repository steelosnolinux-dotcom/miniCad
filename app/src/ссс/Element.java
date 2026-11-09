package ru.loftcontur.minicad.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Element {
    public final String id;
    public String name;
    public String typeName;
    public Material material;
    public Profile profile;                      // сечение, мм
    public Anchor anchor;                        // привязка
    public final List<Vec3> path = new ArrayList<>(); // ось тела в мире

    public java.util.Map<String, String> dimExprs;
    public int anchorPoint = 0;

    // --- НОВОЕ: Структура для параметрических вырезов ---
    // Обновленная структура выреза в Element.java
    public static class Cutout {
        public String id;
        public String planeId;
        public List<float[]> loop = new ArrayList<>(); // Хранит массив точек [u, v]
        public String depthExpr;
        public boolean isThrough;
    }


    public final List<Cutout> cutouts = new ArrayList<>();
    // ----------------------------------------------------

    public Element(String id) { this.id = id; }
}
