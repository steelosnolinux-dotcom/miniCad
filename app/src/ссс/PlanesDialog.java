package ru.loftcontur.minicad;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import ru.loftcontur.minicad.core.Element;
import ru.loftcontur.minicad.core.Expr;
import ru.loftcontur.minicad.core.Scene;
import ru.loftcontur.minicad.gl.Renderer;

/**
 * Редактор плоскостей сцены: список, создание, редактирование, удаление,
 * общий тумблер показа и индивидуальное включение/выключение каждой плоскости.
 * После любого изменения геометрии плоскостей вызывается renderer.syncScene().
 */
public final class PlanesDialog {

    private static final int BG = 0xEE1B1F26;
    private static final int TEXT = 0xFFF2F2F2;
    private static final int TEXT_2 = 0xFF9FB3C8;
    private static final int ACCENT = 0xFF2A6BFF;

    private PlanesDialog() {}

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

    /** Старая сигнатура — без рендерера (тумблеры недоступны). */
    public static void show(Context ctx, Scene scene, Runnable onDone) {
        show(ctx, scene, null, onDone);
    }

    /** Главный диалог: список плоскостей, «＋ Плоскость», тумблеры показа. */
    public static void show(final Context ctx, final Scene scene,
                            final Renderer renderer, final Runnable onDone) {
        float density = ctx.getResources().getDisplayMetrics().density;
        int pad = (int) (12 * density);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(ctx);
        title.setText("Плоскости сцены");
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER);
        title.setTextColor(TEXT);
        root.addView(title);

        final List<String> ids = new ArrayList<>(scene.planeDefs.keySet());

        ListView list = new ListView(ctx);
        list.setVerticalScrollBarEnabled(false);
        list.setDivider(new ColorDrawable(0x22FFFFFF));
        list.setDividerHeight(1);
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return ids.size(); }
            @Override public Object getItem(int position) { return ids.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                String id = ids.get(position);
                Scene.PlaneDef d = scene.planeDefs.get(id);

                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                int p = (int) (10 * density);
                row.setPadding(p, p, p, p);

                LinearLayout col = new LinearLayout(ctx);
                col.setOrientation(LinearLayout.VERTICAL);
                TextView name = new TextView(ctx);
                name.setText(id);
                name.setTextColor(TEXT);
                name.setTextSize(16);
                TextView desc = new TextView(ctx);
                desc.setText("O(" + d.ox + ", " + d.oy + ", " + d.oz + ")  N("
                        + d.nx + ", " + d.ny + ", " + d.nz + ")");
                desc.setTextColor(TEXT_2);
                desc.setTextSize(12);
                col.addView(name);
                col.addView(desc);
                row.addView(col, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                // индивидуальный тумблер видимости
                if (renderer != null) {
                    boolean visible = !renderer.hiddenPlanes.contains(id);
                    TextView eye = new TextView(ctx);
                    eye.setText(visible ? "◉" : "○");
                    eye.setTextSize(20);
                    eye.setTextColor(Color.parseColor(visible ? "#2E7D32" : "#3A4250"));
                    eye.setGravity(Gravity.CENTER);
                    GradientDrawable ring = new GradientDrawable();
                    ring.setShape(GradientDrawable.OVAL);
                    ring.setColor(Color.parseColor(visible ? "#1A2E7D32" : "#1A3A4250"));
                    eye.setBackground(ring);
                    LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                            (int) (40 * density), (int) (40 * density));
                    elp.leftMargin = (int) (8 * density);
                    eye.setLayoutParams(elp);
                    eye.setOnClickListener(v -> {
                        boolean vis = !renderer.hiddenPlanes.contains(id);
                        if (vis) renderer.hiddenPlanes.add(id);
                        else renderer.hiddenPlanes.remove(id);
                        boolean nowVisible = !renderer.hiddenPlanes.contains(id);
                        eye.setText(nowVisible ? "◉" : "○");
                        eye.setTextColor(Color.parseColor(
                                nowVisible ? "#2E7D32" : "#3A4250"));
                        GradientDrawable r = new GradientDrawable();
                        r.setShape(GradientDrawable.OVAL);
                        r.setColor(Color.parseColor(
                                nowVisible ? "#1A2E7D32" : "#1A3A4250"));
                        eye.setBackground(r);
                    });
                    row.addView(eye);
                }
                return row;
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (300 * density)));

        TextView btnAdd = pillButton(ctx, "＋ Плоскость", "#2A6BFF");
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addLp.topMargin = pad;
        btnAdd.setLayoutParams(addLp);
        root.addView(btnAdd);

        // общий тумблер показа плоскостей на сцене
        if (renderer != null) {
            final TextView btnShow = pillButton(ctx,
                    renderer.showPlanes ? "Показ плоскостей: вкл" : "Показ плоскостей: выкл",
                    renderer.showPlanes ? "#2E7D32" : "#3A4250");
            LinearLayout.LayoutParams shLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            shLp.topMargin = (int) (8 * density);
            btnShow.setLayoutParams(shLp);
            root.addView(btnShow);
            btnShow.setOnClickListener(v -> {
                renderer.showPlanes = !renderer.showPlanes;
                btnShow.setText(renderer.showPlanes
                        ? "Показ плоскостей: вкл" : "Показ плоскостей: выкл");
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(20 * density);
                bg.setColor(Color.parseColor(
                        renderer.showPlanes ? "#2E7D32" : "#3A4250"));
                btnShow.setBackground(bg);
            });
        }

        final Dialog dlg = new AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog)
                .setView(root)
                .create();
        if (dlg.getWindow() != null)
            dlg.getWindow().setBackgroundDrawable(dialogBg(ctx));
        dlg.show();

        btnAdd.setOnClickListener(v -> {
            editPlane(ctx, scene, renderer, null, () -> {
                if (dlg.isShowing()) dlg.dismiss();
                show(ctx, scene, renderer, onDone); // перерисовать список
            });
        });

        list.setOnItemClickListener((p, v, pos, id) -> {
            editPlane(ctx, scene, renderer, ids.get(pos), () -> {
                if (dlg.isShowing()) dlg.dismiss();
                show(ctx, scene, renderer, onDone);
            });
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            deletePlane(ctx, scene, ids.get(pos), renderer, () -> {
                if (dlg.isShowing()) dlg.dismiss();
                show(ctx, scene, renderer, onDone);
            });
            return true;
        });
    }

    /** Сколько деталей стоят на плоскости (для защиты удаления). */
    private static int usedBy(Scene scene, String planeId) {
        int n = 0;
        for (Element e : scene.elements)
            if (planeId.equals(e.anchor.planeId)) n++;
        return n;
    }

    /** Удаление плоскости: занятую деталями не даём снести. */
    private static void deletePlane(Context ctx, Scene scene, String id,
                                    Renderer renderer, Runnable onDone) {
        int used = usedBy(scene, id);
        if (used > 0) {
            Toast.makeText(ctx, "Плоскость занята деталями: " + used,
                    Toast.LENGTH_SHORT).show();
            onDone.run();
            return;
        }
        new AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog)
                .setTitle("Удалить плоскость?")
                .setMessage("«" + id + "» будет удалена.")
                .setPositiveButton("Удалить", (d, w) -> {
                    scene.planeDefs.remove(id);
                    if (renderer != null) {
                        renderer.hiddenPlanes.remove(id);
                        renderer.syncScene();   // перестроить листы и рамки
                    }
                    onDone.run();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    /**
     * Редактор плоскости. id == null — создание. Поля: имя, точка начала
     * X/Y/Z (выражения), нормаль, ось U. Сохранение с проверкой выражений;
     * после сохранения — renderer.syncScene(), чтобы лист перерисовался.
     */
    private static void editPlane(Context ctx, final Scene scene,
                                  final Renderer renderer,
                                  final String id, final Runnable onDone) {
        float density = ctx.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(ctx);
        title.setText(id == null ? "Новая плоскость" : "Плоскость: " + id);
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER);
        title.setTextColor(TEXT);
        root.addView(title);

        final EditText edId = field(ctx, "Имя, например: SHELF");
        if (id != null) {
            edId.setText(id);
            edId.setEnabled(false); // имя — ключ, его не меняем
        }
        root.addView(label(ctx, "Имя плоскости"));
        root.addView(edId);

        root.addView(label(ctx, "Точка начала O (выражения)"));
        final Scene.PlaneDef def = id != null ? scene.planeDefs.get(id) : null;
        final EditText edX = field(ctx, "X, например: 0");
        final EditText edY = field(ctx, "Y, например: height - 400");
        final EditText edZ = field(ctx, "Z, например: 0");
        edX.setText(def != null ? def.ox : "0");
        edY.setText(def != null ? def.oy : "0");
        edZ.setText(def != null ? def.oz : "0");
        root.addView(edX);
        root.addView(edY);
        root.addView(edZ);

        root.addView(label(ctx, "Нормаль плоскости"));
        final String[] normals = {
                "0,1,0 (вверх)", "0,-1,0 (вниз)",
                "1,0,0 (вправо)", "-1,0,0 (влево)",
                "0,0,1 (к нам)", "0,0,-1 (от нас)"
        };
        final int[] normalIdx = {indexOfNormal(def)};
        final SpinnerLike normSpin = new SpinnerLike(ctx, normals, normalIdx[0]);
        root.addView(normSpin.view());

        root.addView(label(ctx, "Ось U плоскости"));
        final String[] us = {"1,0,0 (вдоль X)", "0,0,1 (вдоль Z)"};
        final int[] uIdx = {indexOfU(def)};
        final SpinnerLike uSpin = new SpinnerLike(ctx, us, uIdx[0]);
        root.addView(uSpin.view());

        LinearLayout buttons = new LinearLayout(ctx);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        int bm = (int) (8 * density);
        buttons.setPadding(0, bm, 0, 0);

        TextView btnCancel = pillButton(ctx, "Отмена", "#3A4250");
        buttons.addView(btnCancel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView btnOk = pillButton(ctx, id == null ? "Создать" : "Сохранить", "#2E7D32");
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
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

        btnCancel.setOnClickListener(v -> {
            dlg.dismiss();
            onDone.run();
        });
        btnOk.setOnClickListener(v -> {
            String name = edId.getText().toString().trim().toUpperCase();
            if (name.length() == 0) {
                Toast.makeText(ctx, "Введите имя плоскости", Toast.LENGTH_SHORT).show();
                return;
            }
            String normal = normals[normSpin.selected()];
            String nx = normal.split(" ")[0].split(",")[0];
            String ny = normal.split(" ")[0].split(",")[1];
            String nz = normal.split(" ")[0].split(",")[2];
            String u = us[uSpin.selected()].startsWith("0,0,1") ? "0,0,1" : "1,0,0";

            // проверка выражений: должны вычисляться без ошибок
            try {
                Expr.eval(edX.getText().toString(), scene.params);
                Expr.eval(edY.getText().toString(), scene.params);
                Expr.eval(edZ.getText().toString(), scene.params);
            } catch (Exception ex) {
                Toast.makeText(ctx, "Ошибка в выражении: " + ex.getMessage(),
                        Toast.LENGTH_SHORT).show();
                return;
            }

            Scene.PlaneDef d = new Scene.PlaneDef(
                    edX.getText().toString().trim(),
                    edY.getText().toString().trim(),
                    edZ.getText().toString().trim(), nx, ny, nz);
            d.u = u;
            boolean replacing = scene.planeDefs.containsKey(name);
            scene.planeDefs.put(name, d);
            scene.rebuild();
            if (renderer != null) renderer.syncScene(); // листы и рамки заново
            Toast.makeText(ctx, replacing ? "Плоскость обновлена" : "Плоскость создана",
                    Toast.LENGTH_SHORT).show();
            dlg.dismiss();
            onDone.run();
        });
    }

    private static int indexOfNormal(Scene.PlaneDef d) {
        if (d == null) return 0;
        String n = d.nx + "," + d.ny + "," + d.nz;
        if ("0,1,0".equals(n)) return 0;
        if ("0,-1,0".equals(n)) return 1;
        if ("1,0,0".equals(n)) return 2;
        if ("-1,0,0".equals(n)) return 3;
        if ("0,0,1".equals(n)) return 4;
        if ("0,0,-1".equals(n)) return 5;
        return 0;
    }

    private static int indexOfU(Scene.PlaneDef d) {
        if (d == null || d.u == null) return 0;
        return d.u.startsWith("0,0,1") ? 1 : 0;
    }

    /** Простой выпадающий список в тёмном стиле (замена Spinner). */
    private static final class SpinnerLike {
        private final Context ctx;
        private final String[] options;
        private int selected;
        private final TextView view;

        SpinnerLike(Context ctx, String[] options, int initial) {
            this.ctx = ctx;
            this.options = options;
            this.selected = initial;
            float density = ctx.getResources().getDisplayMetrics().density;
            view = new TextView(ctx);
            view.setTextSize(15);
            view.setTextColor(TEXT);
            view.setPadding(0, (int) (8 * density), 0, (int) (8 * density));
            view.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(ACCENT));
            view.setText(options[selected]);
            view.setOnClickListener(v ->
                    new AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog)
                            .setItems(options, (d, w) -> {
                                selected = w;
                                view.setText(options[w]);
                            })
                            .show());
        }

        View view() { return view; }
        int selected() { return selected; }
    }
}
