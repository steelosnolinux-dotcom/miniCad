package ru.loftcontur.minicad;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import ru.loftcontur.minicad.core.Scene;

/**
 * Библиотека моделей: сохранение с названием и миниатюрой,
 * список с превью, загрузка по тапу, удаление по долгому тапу.
 * Хранилище: files/models/<имя>.json + <имя>.png.
 * Диалоги — в тёмном стиле приложения.
 */
public final class ModelLibrary {

    private static final int BG = 0xEE1B1F26;
    private static final int TEXT = 0xFFF2F2F2;
    private static final int TEXT_2 = 0xFF9FB3C8;
    private static final int ACCENT = 0xFF2A6BFF;

    public static class Item {
        public String name;      // человекочитаемое имя
        public File modelFile;   // .json
        public File thumbFile;   // .png (может не быть)
        public long time;        // время сохранения
    }

    public interface OnDone { void onDone(); }

    private ModelLibrary() {}

    private static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "models");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^а-яА-ЯёЁa-zA-Z0-9 _-]", "_").trim();
    }

    /** Список сохранённых моделей, свежие сверху. */
    public static List<Item> list(Context ctx) {
        List<Item> out = new ArrayList<>();
        File d = dir(ctx);
        File[] files = d.listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (!f.getName().endsWith(".json")) continue;
            Item it = new Item();
            it.name = f.getName().substring(0, f.getName().length() - 5);
            it.modelFile = f;
            it.time = f.lastModified();
            File thumb = new File(d, it.name + ".png");
            it.thumbFile = thumb.exists() ? thumb : null;
            out.add(it);
        }
        Collections.sort(out, (a, b) -> Long.compare(b.time, a.time));
        return out;
    }

    /** Сохранение: модель и миниатюра. Совпадение имени — суффикс (2), (3)… */
    public static boolean save(Context ctx, String name, Scene scene, Bitmap thumb) {
        try {
            String base = sanitize(name);
            if (base.length() == 0) base = "Модель";
            File d = dir(ctx);
            String fn = base;
            int n = 2;
            while (new File(d, fn + ".json").exists())
                fn = base + " (" + (n++) + ")";
            File model = new File(d, fn + ".json");
            if (!ModelIO.saveToFile(model, scene)) return false;
            if (thumb != null) {
                FileOutputStream fo = null;
                try {
                    fo = new FileOutputStream(new File(d, fn + ".png"));
                    thumb.compress(Bitmap.CompressFormat.PNG, 90, fo);
                } finally {
                    if (fo != null) fo.close();
                }
            }
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    /** Загрузка модели по элементу списка. */
    public static boolean load(Scene scene, Item item) {
        return item != null && ModelIO.loadFromFile(item.modelFile, scene);
    }

    /** Тёмная подложка диалога с закруглениями (радиус в dp). */
    private static GradientDrawable dialogBg(Context ctx) {
        float density = ctx.getResources().getDisplayMetrics().density;
        GradientDrawable g = new GradientDrawable();
        g.setColor(BG);
        g.setCornerRadius(16 * density);
        return g;
    }

    /** Кнопка-пилюля в тёмном стиле. */
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

    /** Диалог сохранения: превью + имя. */
    public static void showSaveDialog(final Context ctx, final Scene scene,
                                      final Bitmap thumb, final OnDone cb) {
        float density = ctx.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(ctx);
        title.setText("Сохранить модель");
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER);
        title.setTextColor(TEXT);
        root.addView(title);

        if (thumb != null) {
            ImageView iv = new ImageView(ctx);
            iv.setImageBitmap(thumb);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (int) (140 * density));
            int m = (int) (8 * density);
            lp.setMargins(0, m, 0, m);
            iv.setLayoutParams(lp);
            root.addView(iv);
        }

        TextView lbl = new TextView(ctx);
        lbl.setText("Название модели");
        lbl.setTextColor(TEXT_2);
        root.addView(lbl);

        final EditText ed = new EditText(ctx);
        ed.setSingleLine(true);
        ed.setHint("Например: Стол 1200×600");
        ed.setHintTextColor(0xFF55606B);
        ed.setTextColor(TEXT);
        ed.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(ACCENT));
        root.addView(ed);

        // кнопки внизу — не зависим от темы AlertDialog
        LinearLayout buttons = new LinearLayout(ctx);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        int bm = (int) (8 * density);
        buttons.setPadding(0, bm, 0, 0);

        TextView btnCancel = pillButton(ctx, "Отмена", "#3A4250");
        buttons.addView(btnCancel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView btnOk = pillButton(ctx, "Сохранить", "#2E7D32");
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        okLp.leftMargin = bm;
        btnOk.setLayoutParams(okLp);
        buttons.addView(btnOk);
        root.addView(buttons);

        final Dialog dlg = new AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog)
                .setView(root)
                .create();
        btnCancel.setOnClickListener(v -> dlg.dismiss());
        btnOk.setOnClickListener(v -> {
            String name = ed.getText().toString().trim();
            boolean ok = save(ctx, name.length() > 0 ? name : "Модель", scene, thumb);
            Toast.makeText(ctx, ok ? "Модель сохранена" : "Ошибка сохранения",
                    Toast.LENGTH_SHORT).show();
            dlg.dismiss();
            if (ok && cb != null) cb.onDone();
        });

        if (dlg.getWindow() != null)
            dlg.getWindow().setBackgroundDrawable(dialogBg(ctx));
        dlg.show();
    }

    /** Диалог открытия: список моделей с миниатюрами. Тап — загрузить, долгий тап — удалить. */
    public static void showLoadDialog(final Context ctx, final Scene scene, final OnDone cb) {
        final List<Item> items = list(ctx);
        if (items.isEmpty()) {
            Toast.makeText(ctx, "Сохранённых моделей нет", Toast.LENGTH_SHORT).show();
            return;
        }

        float density = ctx.getResources().getDisplayMetrics().density;
        int pad = (int) (8 * density);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(ctx);
        title.setText("Открыть модель");
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER);
        title.setTextColor(TEXT);
        title.setPadding(0, pad, 0, pad);
        root.addView(title);

        ListView list = new ListView(ctx);
        list.setVerticalScrollBarEnabled(false);
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return items.size(); }
            @Override public Object getItem(int position) { return items.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                Item it = items.get(position);
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                int p = (int) (8 * density);
                row.setPadding(p, p, p, p);

                ImageView iv = new ImageView(ctx);
                Bitmap bmp = it.thumbFile != null
                        ? android.graphics.BitmapFactory.decodeFile(it.thumbFile.getAbsolutePath())
                        : placeholder();
                iv.setImageBitmap(bmp);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        (int) (72 * density), (int) (72 * density));
                lp.rightMargin = p;
                iv.setLayoutParams(lp);
                row.addView(iv);

                LinearLayout col = new LinearLayout(ctx);
                col.setOrientation(LinearLayout.VERTICAL);
                TextView name = new TextView(ctx);
                name.setText(it.name);
                name.setTextSize(16);
                name.setTextColor(TEXT);
                TextView date = new TextView(ctx);
                date.setText(new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                        .format(new Date(it.time)));
                date.setTextSize(13);
                date.setTextColor(TEXT_2);
                col.addView(name);
                col.addView(date);
                row.addView(col, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                return row;
            }
        });

        final Dialog[] ref = new Dialog[1];
        list.setOnItemClickListener((p, v, pos, id) -> {
            boolean ok = load(scene, items.get(pos));
            Toast.makeText(ctx, ok ? "Модель загружена: " + items.get(pos).name
                    : "Ошибка загрузки", Toast.LENGTH_SHORT).show();
            if (ref[0] != null) ref[0].dismiss();
            if (ok && cb != null) cb.onDone();
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            final Item it = items.get(pos);
            new AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog)
                    .setTitle("Удалить модель?")
                    .setMessage("«" + it.name + "» будет удалена безвозвратно.")
                    .setPositiveButton("Удалить", (d, w) -> {
                        if (it.modelFile != null) it.modelFile.delete();
                        if (it.thumbFile != null) it.thumbFile.delete();
                        if (ref[0] != null) ref[0].dismiss();
                        showLoadDialog(ctx, scene, cb); // перерисовать список
                    })
                    .setNegativeButton("Отмена", null)
                    .show();
            return true;
        });

        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (360 * density)));

        Dialog dlg = new AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog)
                .setView(root)
                .create();
        ref[0] = dlg;
        if (dlg.getWindow() != null)
            dlg.getWindow().setBackgroundDrawable(dialogBg(ctx));
        dlg.show();
    }

    /** Заглушка-миниатюра, если снимок не сохранился. */
    private static Bitmap placeholder() {
        Bitmap bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(0xFF22262C);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF55606B);
        p.setTextSize(40);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText("?", 48, 62, p);
        return bmp;
    }
}
