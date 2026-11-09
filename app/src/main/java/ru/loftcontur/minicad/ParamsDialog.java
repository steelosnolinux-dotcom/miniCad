package ru.loftcontur.minicad;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import ru.loftcontur.minicad.core.Scene;
import ru.loftcontur.minicad.gl.Renderer;

/**
 * Диалог «Параметры»: редактирование существующих (width, depth, height…)
 * и добавление новых. Имена — латиницей: они подставляются в выражения
 * привязок и размеров. Значения — числа.
 * После применения: scene.rebuild() пересчитывает плоскости и детали,
 * renderer.syncScene() переносит новую геометрию в GL-буферы.
 */
public final class ParamsDialog {

    public interface OnApply { void onApply(); }

    private ParamsDialog() {}

    /** Старая сигнатура — без рендерера (только ядро, без перерисовки GL). */
    public static void show(Context ctx, Scene scene, OnApply cb) {
        show(ctx, scene, null, cb);
    }

    public static void show(final Context ctx, final Scene scene,
                            final Renderer renderer, final OnApply cb) {
        ScrollView scroll = new ScrollView(ctx);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * ctx.getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(ctx);
        title.setText("Параметры");
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        // контейнер строк — новые параметры вставляются сюда же
        LinearLayout rowsBox = new LinearLayout(ctx);
        rowsBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(rowsBox);

        List<String> keys = new ArrayList<>(scene.params.keySet());
        List<EditText> valueEds = new ArrayList<>();

        for (String k : keys) {
            rowsBox.addView(buildRow(ctx, k, numText(scene.params.get(k)), valueEds));
        }

        // добавление нового параметра
        final List<EditText[]> added = new ArrayList<>();
        Button add = new Button(ctx);
        add.setText("＋ Параметр");
        add.setOnClickListener(v -> {
            EditText nameEd = new EditText(ctx);
            nameEd.setHint("имя (латиница)");
            nameEd.setSingleLine(true);
            EditText valueEd = new EditText(ctx);
            valueEd.setHint("значение");
            valueEd.setInputType(InputType.TYPE_CLASS_NUMBER
                    | InputType.TYPE_NUMBER_FLAG_DECIMAL
                    | InputType.TYPE_NUMBER_FLAG_SIGNED);
            valueEd.setSingleLine(true);

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            nameEd.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            valueEd.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(nameEd);
            row.addView(valueEd);
            rowsBox.addView(row);
            added.add(new EditText[]{nameEd, valueEd});
        });
        root.addView(add);

        Button ok = new Button(ctx);
        ok.setText("Применить");
        final Dialog[] dlgRef = new Dialog[1];
        ok.setOnClickListener(v -> {
            for (int i = 0; i < keys.size(); i++) {
                Float val = parseFloat(valueEds.get(i).getText().toString());
                if (val != null) scene.params.put(keys.get(i), val);
            }
            for (EditText[] pair : added) {
                String name = pair[0].getText().toString().trim();
                Float val = parseFloat(pair[1].getText().toString());
                if (name.length() > 0 && val != null) scene.params.put(name, val);
            }
            // пересчитать ядро и перерисовать GL: плоскости и детали поедут
            scene.rebuild();
            if (renderer != null) renderer.syncScene();
            if (dlgRef[0] != null) dlgRef[0].dismiss();
            if (cb != null) cb.onApply();
        });
        root.addView(ok);

        Dialog dlg = new AlertDialog.Builder(ctx).setView(scroll).create();
        dlgRef[0] = dlg;
        dlg.show();
    }

    /** Строка «имя (не редактируется) + значение». */
    private static LinearLayout buildRow(Context ctx, String key, String value,
                                         List<EditText> out) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView tv = new TextView(ctx);
        tv.setText(key);
        tv.setTextSize(15);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tv);

        EditText ed = new EditText(ctx);
        ed.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        ed.setText(value);
        ed.setSingleLine(true);
        ed.setTextSize(15);
        ed.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(ed);

        out.add(ed);
        return row;
    }

    private static String numText(Float v) {
        if (v == null) return "0";
        return v == Math.round(v) ? String.valueOf((int) (float) v) : String.valueOf(v);
    }

    private static Float parseFloat(String s) {
        try {
            return Float.parseFloat(s.trim().replace(',', '.'));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
