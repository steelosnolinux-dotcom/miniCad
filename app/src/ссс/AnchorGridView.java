package ru.loftcontur.minicad;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

import java.util.List;

import ru.loftcontur.minicad.core.Profile;
import ru.loftcontur.minicad.core.Vec2;

/**
 * Квадрат с сечением профиля и 13 точками размещения; тап выбирает точку.
 * Общий для редактора эскиза и 3D-редактирования профиля.
 */
public class AnchorGridView extends View {

    public int selected = 0;
    private final Profile profile;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float scale, ox, oy;

    public AnchorGridView(Context c, Profile p) {
        super(c);
        profile = p;
        setBackgroundColor(0xFF1B1F26);
    }

    private float sx(float x) { return ox + x * scale; }
    private float sy(float y) { return oy - y * scale; }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE;
        float x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        for (List<Vec2> loop : profile.loops)
            for (Vec2 v : loop) {
                if (v.x < x0) x0 = v.x; if (v.x > x1) x1 = v.x;
                if (v.y < y0) y0 = v.y; if (v.y > y1) y1 = v.y;
            }
        float bw = Math.max(1, x1 - x0), bh = Math.max(1, y1 - y0);
        scale = Math.min((w - 80) / bw, (h - 80) / bh);
        ox = w / 2f - (x0 + x1) / 2 * scale;
        oy = h / 2f + (y0 + y1) / 2 * scale;
    }

    @Override protected void onDraw(Canvas c) {
        stroke.setColor(0xFF8AB6FF);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2f);
        fill.setColor(0x558AB6FF);
        fill.setStyle(Paint.Style.FILL);
        for (List<Vec2> loop : profile.loops) {
            Path path = new Path();
            for (int i = 0; i < loop.size(); i++) {
                Vec2 p = loop.get(i);
                if (i == 0) path.moveTo(sx(p.x), sy(p.y));
                else path.lineTo(sx(p.x), sy(p.y));
            }
            path.close();
            c.drawPath(path, fill);
            c.drawPath(path, stroke);
        }
        for (int i = 0; i < 13; i++) {
            float[] pt = SketchBody.profilePoint(profile, i);
            dot.setColor(i == selected ? 0xFF44FF88 : 0xFFFFD54A);
            c.drawCircle(sx(pt[0]), sy(pt[1]), i == selected ? 11f : 7f, dot);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent ev) {
        int act = ev.getActionMasked();
        if (act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_UP) {
            float best = Float.MAX_VALUE;
            int bestI = selected;
            for (int i = 0; i < 13; i++) {
                float[] pt = SketchBody.profilePoint(profile, i);
                float d = (float) Math.hypot(ev.getX() - sx(pt[0]),
                        ev.getY() - sy(pt[1]));
                if (d < best) { best = d; bestI = i; }
            }
            selected = bestI;
            invalidate();
        }
        return true;
    }
}
