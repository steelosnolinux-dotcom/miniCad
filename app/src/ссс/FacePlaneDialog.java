package ru.loftcontur.minicad;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;
import java.util.Map;

import ru.loftcontur.minicad.core.Element;
import ru.loftcontur.minicad.core.Expr;
import ru.loftcontur.minicad.core.Scene;
import ru.loftcontur.minicad.gl.Renderer;

/**
 * Создание плоскости по выбранной грани детали.
 * Точка начала плоскости хранится ВЫРАЖЕНИЯМИ от параметров сцены.
 * Автоподбор ранжирует кандидатов: точное совпадение с параметром
 * всегда сильнее «параметр ± число» — иначе плоскость привязывается
 * к чужому параметру (например, width вместо height) и уплывает.
 */
public final class FacePlaneDialog {

    private static final int BG = 0xEE1B1F26;
    private static final int TEXT = 0xFFF2F2F2;
    private static final int TEXT_2 = 0xFF9FB3C8;
    private static final int ACCENT = 0xFF2A6BFF;

    private static int seq = 1;

    private FacePlaneDialog() {}

    /** Колбэк создания плоскости: отдаёт имя новой плоскости. */
    public interface OnCreated { void onCreated(String planeId); }

    private static GradientDrawable dialogBg(Context ctx) {
        float density = ctx.getResources().getDisplayMetrics().density;
        GradientDrawable g = new GradientDrawable();
        g.setColor(BG);
        g.setCornerRadius(16 * density);
        return g;
    }

    private static TextView pillButton(Context ctx, String text, String color) {
        float density = ctx.getResources().getDisplayMetrics().density;
        TextView b = new TextView(ctx);
        b.setText(text);
        b.setTextSize(14);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(20 * density);
        bg.setColor(Color.parseColor(color));
        b.setBackground(bg);
        int pv = (int) (10 * density);
        b.setPadding(pv, pv, pv, pv);
        return b;
    }

    private static EditText field(Context ctx, String hint) {
        float density = ctx.getResources().getDisplayMetrics().density;
        EditText ed = new EditText(ctx);
        ed.setHint(hint);
        ed.setHintTextColor(0xFF55606B);
        ed.setTextColor(TEXT);
        ed.setTextSize(15);
        ed.setSingleLine(true);
        ed.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(ACCENT));
        int m = (int) (4 * density);
        ed.setPadding(0, m, 0, m);
        return ed;
    }

    private static TextView label(Context ctx, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(TEXT_2);
        t.setTextSize(13);
        return t;
    }

    private static String fmt(float v) {
        String s = String.format(Locale.US, "%.4f", v);
        if (s.contains(".")) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s;
    }

    /** Поворот вектора вокруг мировой оси ("X"/"Y"/"Z") на угол в градусах. */
    private static float[] rotateAroundAxis(float[] v, String axis, float deg) {
        double r = Math.toRadians(deg);
        float c = (float) Math.cos(r), s = (float) Math.sin(r);
        float x = v[0], y = v[1], z = v[2];
        if ("X".equals(axis))  return new float[]{x, c * y - s * z, s * y + c * z};
        if ("Y".equals(axis))  return new float[]{s * z + c * x, y, c * z - s * x};
        return new float[]{c * x - s * y, s * x + c * y, z}; // Z
    }

    /**
     * Автоподбор выражения для значения по параметрам сцены — с ранжированием:
     * ранг 0 — точное совпадение (p или -p),
     * ранг 1 — точное p/2 или -p/2,
     * ранг 2 — p ± k,
     * ранг 3 — p/2 ± k.
     * Побеждает минимальный ранг; при равенстве — минимальное |k|.
     * Ноль заменяется литералом «0»: центр симметричной модели лежит на
     * осях при любых параметрах, а выражение вида «depth - 600»,
     * подобранное под ноль, уедет вместе с параметром.
     */
    private static String axisExpr(Scene scene, float value) {
        if (Math.abs(value) < 0.01f) return "0";

        String best = null;
        int bestRank = Integer.MAX_VALUE;
        float bestErr = Float.MAX_VALUE;

        for (Map.Entry<String, Float> e : scene.params.entrySet()) {
            float v = e.getValue();
            String p = e.getKey();
            String[] cands = {p, "-" + p, p + " / 2", "-" + p + " / 2"};
            float[] bases = {v, -v, v / 2, -v / 2};

            for (int i = 0; i < 4; i++) {
                float base = bases[i];
                float r = value - base;
                float rr = Math.round(r);
                String expr;
                float err;
                int rank;
                if (Math.abs(r) < 0.01f) {
                    expr = cands[i];
                    err = 0;
                    rank = i < 2 ? 0 : 1;
                } else if (Math.abs(r - rr) < 0.01f
                        && rr != 0 && Math.abs(rr) <= 5000) {
                    expr = cands[i] + (rr > 0 ? " + " : " - ") + (int) Math.abs(rr);
                    err = Math.abs(rr);
                    rank = i < 2 ? 2 : 3;
                } else {
                    continue;
                }
                if (rank < bestRank
                        || (rank == bestRank && err < bestErr)) {
                    best = expr;
                    bestRank = rank;
                    bestErr = err;
                }
            }
        }
        return best != null ? best : fmt(value);
    }

    /** Старая сигнатура — только Runnable. */
    public static void show(Context ctx, Scene scene,
                            Renderer.FaceHit hit, Runnable onDone) {
        show(ctx, scene, hit, p -> { if (onDone != null) onDone.run(); });
    }

    /**
     * Диалог. hit — выбранная грань (Renderer.getLastFace()); cb — после
     * успешного создания, отдаёт имя плоскости. Поля O X/Y/Z автозаполняются
     * выражениями и следят за полем смещения, пока их не отредактировали вручную.
     */
    public static void show(final Context ctx, final Scene scene,
                            final Renderer.FaceHit hit, final OnCreated cb) {
        if (hit == null) return;
        float density = ctx.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(ctx);
        title.setText("Плоскость по грани");
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER);
        title.setTextColor(TEXT);
        root.addView(title);

        String elName = hit.elementId;
        for (Element e : scene.elements)
            if (e.id.equals(hit.elementId) && e.name != null) elName = e.name;
        TextView info = new TextView(ctx);
        info.setText("Грань «" + elName + "», нормаль ("
                + fmt(hit.normal[0]) + ", " + fmt(hit.normal[1]) + ", "
                + fmt(hit.normal[2]) + ")");
        info.setTextColor(TEXT_2);
        info.setTextSize(13);
        int im = (int) (6 * density);
        info.setPadding(0, im, 0, im);
        root.addView(info);

        final EditText edName = field(ctx, "Имя плоскости");
        edName.setText("FACE_" + (seq++));
        root.addView(label(ctx, "Имя плоскости"));
        root.addView(edName);

        root.addView(label(ctx, "Смещение вдоль нормали (число или выражение)"));
        final EditText edOffset = field(ctx, "0, 5, -3, t / 2…");
        edOffset.setText("0");
        root.addView(edOffset);

        // поля origin: значения-выражения; следуют за смещением, пока не правлены
        final boolean[] dirty = new boolean[3];
        final float[] base = {hit.center[0], hit.center[1], hit.center[2]};
        final String[] hints = {"X, например: width / 2",
                "Y, например: height - 10", "Z, например: 0"};
        final EditText[] edO = new EditText[3];
        TextWatcher[] watchers = new TextWatcher[3];

        for (int i = 0; i < 3; i++) {
            root.addView(label(ctx, "О" + "XYZ".charAt(i) + " (выражение)"));
            final int idx = i;
            edO[i] = field(ctx, hints[i]);
            edO[i].setText(axisExpr(scene, base[i]));
            watchers[i] = new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {
                    dirty[idx] = true;
                }
            };
            edO[i].addTextChangedListener(watchers[i]);
            root.addView(edO[i]);
        }

        // при изменении смещения обновляем непомеченные поля origin
        edOffset.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                float off;
                try {
                    off = Float.parseFloat(s.toString().trim().replace(',', '.'));
                } catch (Exception ex) {
                    return; // выражение — не пересчитываем автоматически
                }
                for (int i = 0; i < 3; i++) {
                    if (dirty[i]) continue;
                    edO[i].removeTextChangedListener(watchers[i]);
                    edO[i].setText(axisExpr(scene,
                            base[i] + hit.normal[i] * off));
                    edO[i].addTextChangedListener(watchers[i]);
                }
            }
        });

        // режим плоскости
        final String[] modes = {"По нормали грани", "Касательная (углом)"};
        final int[] modeIdx = {0};
        final TextView modeView = new TextView(ctx);
        modeView.setTextSize(15);
        modeView.setTextColor(TEXT);
        modeView.setPadding(0, (int) (8 * density), 0, (int) (8 * density));
        modeView.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(ACCENT));
        modeView.setText(modes[0]);
        modeView.setOnClickListener(v -> new AlertDialog.Builder(
                ctx, android.R.style.Theme_Material_Dialog)
                .setItems(modes, (d, w) -> {
                    modeIdx[0] = w;
                    modeView.setText(modes[w]);
                }).show());
        root.addView(label(ctx, "Тип плоскости"));
        root.addView(modeView);

        // угол и ось (для касательной)
        final EditText edAngle = field(ctx, "Угол, °");
        edAngle.setText("30");
        root.addView(label(ctx, "Угол наклона (для касательной)"));
        root.addView(edAngle);

        final String[] axes = {"Вокруг X", "Вокруг Y", "Вокруг Z"};
        final int[] axisIdx = {2};
        final TextView axisView = new TextView(ctx);
        axisView.setTextSize(15);
        axisView.setTextColor(TEXT);
        axisView.setPadding(0, (int) (8 * density), 0, (int) (8 * density));
        axisView.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(ACCENT));
        axisView.setText(axes[2]);
        axisView.setOnClickListener(v -> new AlertDialog.Builder(
                ctx, android.R.style.Theme_Material_Dialog)
                .setItems(axes, (d, w) -> {
                    axisIdx[0] = w;
                    axisView.setText(axes[w]);
                }).show());
        root.addView(label(ctx, "Ось поворота (для касательной)"));
        root.addView(axisView);

        LinearLayout buttons = new LinearLayout(ctx);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        int bm = (int) (8 * density);
        buttons.setPadding(0, bm, 0, 0);

        TextView btnCancel = pillButton(ctx, "Отмена", "#3A4250");
        buttons.addView(btnCancel, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView btnOk = pillButton(ctx, "Создать", "#2E7D32");
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        okLp.leftMargin = bm;
        btnOk.setLayoutParams(okLp);
        buttons.addView(btnOk);
        root.addView(buttons);

        final Dialog dlg = new AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog)
                .setView(root)
                .create();
        if (dlg.getWindow() != null)
            dlg.getWindow().setBackgroundDrawable(dialogBg(ctx));
        dlg.show();

        btnCancel.setOnClickListener(v -> dlg.dismiss());
        btnOk.setOnClickListener(v -> {
            String name = edName.getText().toString().trim().toUpperCase();
            if (name.length() == 0) {
                Toast.makeText(ctx, "Введите имя плоскости", Toast.LENGTH_SHORT).show();
                return;
            }

            // смещение: число или выражение от параметров
            String offText = edOffset.getText().toString().trim().replace(',', '.');
            float off;
            try {
                try {
                    off = Float.parseFloat(offText);
                } catch (NumberFormatException nf) {
                    off = Expr.eval(offText, scene.params);
                }
            } catch (Exception ex) {
                Toast.makeText(ctx, "Не вычислить смещение: " + offText,
                        Toast.LENGTH_SHORT).show();
                return;
            }

            // нормаль: базовая или повёрнутая для касательной
            float[] n = {hit.normal[0], hit.normal[1], hit.normal[2]};
            if (modeIdx[0] == 1) {
                float angle;
                try {
                    angle = Float.parseFloat(
                            edAngle.getText().toString().trim().replace(',', '.'));
                } catch (Exception ex) { angle = 0; }
                n = rotateAroundAxis(n,
                        axes[axisIdx[0]].replace("Вокруг ", ""), angle);
            }
            float len = (float) Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
            if (len < 1e-6f) len = 1;
            n[0] /= len; n[1] /= len; n[2] /= len;

            // ось U: перпендикуляр к нормали
            float[] up = Math.abs(n[1]) > 0.99f
                    ? new float[]{1, 0, 0} : new float[]{0, 1, 0};
            float ux = up[1] * n[2] - up[2] * n[1];
            float uy = up[2] * n[0] - up[0] * n[2];
            float uz = up[0] * n[1] - up[1] * n[0];
            float ul = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
            if (ul < 1e-6f) { ux = 1; uy = 0; uz = 0; ul = 1; }

            // точка начала: смещённый центр, выражением по каждой оси.
            // Отправленные пользователем поля сохраняются как есть.
            final String[] exprs = new String[3];
            for (int i = 0; i < 3; i++) {
                if (dirty[i] && edO[i].getText().toString().trim().length() > 0) {
                    exprs[i] = edO[i].getText().toString().trim();
                } else {
                    exprs[i] = axisExpr(scene, base[i] + n[i] * off);
                }
            }

            String base2 = name;
            int k = 2;
            while (scene.planeDefs.containsKey(name))
                name = base2 + "_" + (k++);

            Scene.PlaneDef d = new Scene.PlaneDef(exprs[0], exprs[1], exprs[2],
                    fmt(n[0]), fmt(n[1]), fmt(n[2]));
            d.u = fmt(ux / ul) + "," + fmt(uy / ul) + "," + fmt(uz / ul);
            scene.planeDefs.put(name, d);
            scene.rebuild();

            Toast.makeText(ctx, "Плоскость " + name + " создана", Toast.LENGTH_SHORT).show();
            dlg.dismiss();
            if (cb != null) cb.onCreated(name);
        });
    }
}
