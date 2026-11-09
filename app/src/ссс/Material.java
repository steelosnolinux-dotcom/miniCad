package ru.loftcontur.minicad.core;

public class Material {
    public final String name;
    public final float density; // кг/м³
    public final float yield;   // МПа

    public Material(String name, float density, float yield) {
        this.name = name; this.density = density; this.yield = yield;
    }
    public static Material byName(String name) {
        if (name == null) return ST3;
        if ("09Г2С".equals(name)) return M09G2S;   // замените на ваши поля
        if ("AISI 304".equals(name)) return AISI304;
        return ST3;
    }

    public static final Material ST3     = new Material("Ст3", 7850, 245);
    public static final Material M09G2S  = new Material("09Г2С", 7850, 345);
    public static final Material AISI304 = new Material("AISI 304", 7900, 205);
}
