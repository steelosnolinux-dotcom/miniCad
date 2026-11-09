package ru.loftcontur.minicad.core;

/**
 * Привязка элемента. Позиция — смещения на плоскости (выражения),
 * направление тела — NORMAL / U / V плоскости или "x,y,z" (или "CURVE:...").
 * cutStartExpr/cutEndExpr — косой срез торцов: "nx,ny,nz;cx,cy,cz" (выражения)
 * или null — торец перпендикулярен оси.
 */
public class Anchor {
    public final String planeId;
    public final String offsetU, offsetV;
    public final String lengthExpr;
    public final String dirExpr;
    public String cutStartExpr, cutEndExpr;

    public Anchor(String planeId, String offsetU, String offsetV,
                  String lengthExpr, String dirExpr) {
        this(planeId, offsetU, offsetV, lengthExpr, dirExpr, null, null);
    }

    public Anchor(String planeId, String offsetU, String offsetV,
                  String lengthExpr, String dirExpr,
                  String cutStartExpr, String cutEndExpr) {
        this.planeId = planeId;
        this.offsetU = offsetU;
        this.offsetV = offsetV;
        this.lengthExpr = lengthExpr;
        this.dirExpr = dirExpr;
        this.cutStartExpr = cutStartExpr;
        this.cutEndExpr = cutEndExpr;
    }
}
