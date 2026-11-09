package ru.loftcontur.minicad;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ru.loftcontur.minicad.core.Anchor;
import ru.loftcontur.minicad.core.Element;
import ru.loftcontur.minicad.core.Expr;
import ru.loftcontur.minicad.core.Profile;
import ru.loftcontur.minicad.core.ProfileFactory;
import ru.loftcontur.minicad.core.Scene;

/**
 * Диалог «Сортамент»: выбор элемента, название, тип профиля, размеры,
 * привязка (плоскость, направление, смещения, длина).
 * Размеры, смещения и длина принимают выражения: width - 220, height - 10.
 */
public final class SortamentDialog {

    private static final String[] TYPES = {
            "Труба прямоугольная", "Труба круглая", "Труба овальная",
            "Уголок", "Швеллер", "Тавр", "Лист"
    };
    private static final String[] DIRS = {"NORMAL", "U", "V"};

    private static final Map<String, String[]> FIELDS = new LinkedHashMap<>();
    private static final Map<String, float[]> DEFAULTS = new LinkedHashMap<>();

    static {
        FIELDS.put(TYPES[0], new String[]{"Ширина W", "Высота H", "Стенка T", "Радиус R"});
        FIELDS.put(TYPES[1], new String[]{"Диаметр D", "Стенка T"});
        FIELDS.put(TYPES[2], new String[]{"Ось A", "Ось B", "Стенка T"});
        FIELDS.put(TYPES[3], new String[]{"Полка B", "Высота H", "Стенка T"});
        FIELDS.put(TYPES[4], new String[]{"Высота H", "Полка B", "Стенка T"});
        FIELDS.put(TYPES[5], new String[]{"Высота H", "Полка B", "Стенка TW", "Полка TF"});
        FIELDS.put(TYPES[6], new String[]{"Ширина W", "Глубина H", "Скругление R"});

        DEFAULTS.put(TYPES[0], new float[]{40, 40, 2, 3});
        DEFAULTS.put(TYPES[1], new float[]{42.4f, 2});
        DEFAULTS.put(TYPES[2], new float[]{60, 30, 1.5f});
        DEFAULTS.put(TYPES[3], new float[]{40, 40, 3});
        DEFAULTS.put(TYPES[4], new float[]{100, 50, 4});
        DEFAULTS.put(TYPES[5], new float[]{100, 100, 6, 8});
        DEFAULTS.put(TYPES[6], new float[]{1200, 600, 0});
    }

    public interface OnApply {
        void onApply(Element element, Profile profile, String typeName,
                     String dims, String newName);
    }

    private SortamentDialog() {}

    public static void show(Context ctx, List<Element> elements, Element initial,
                            Scene scene, OnApply cb) {
        if (elements == null || elements.isEmpty()) return;
        if (scene == null) throw new IllegalArgumentException("scene required");

        final Element[] sel = {initial != null ? initial : elements.get(0)};

        ScrollView scroll = new ScrollView(ctx);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * ctx.getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(ctx);
        title.setText("Сортамент");
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView nameLbl = new TextView(ctx);
        nameLbl.setText("Название (своё: «Царга», «Ножка»…)");
        root.addView(nameLbl);

        EditText edName = new EditText(ctx);
        edName.setSingleLine(true);
        edName.setText(sel[0].name != null ? sel[0].name : sel[0].id);
        root.addView(edName);

        // выбор элемента
        List<String> names = new ArrayList<>();
        for (Element e : elements) names.add(e.name == null ? e.id : e.name);
        Spinner spEl = new Spinner(ctx);
        ArrayAdapter<String> ae = new ArrayAdapter<>(ctx,
                android.R.layout.simple_spinner_item, names);
        ae.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spEl.setAdapter(ae);
        spEl.setSelection(elements.indexOf(sel[0]));
        root.addView(spEl);

        // тип профиля
        Spinner spType = new Spinner(ctx);
        ArrayAdapter<String> at = new ArrayAdapter<>(ctx,
                android.R.layout.simple_spinner_item, TYPES);
        at.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spType.setAdapter(at);
        // показать сохранённый тип детали, если он известен
        if (sel[0].typeName != null) {
            for (int i = 0; i < TYPES.length; i++)
                if (TYPES[i].equals(sel[0].typeName)) { spType.setSelection(i); break; }
        }
        root.addView(spType);

        LinearLayout fieldsBox = new LinearLayout(ctx);
        fieldsBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(fieldsBox);

        final Map<String, EditText>[] holder = new Map[1];
        // Передаем scene четвертым параметром для валидации формул
        holder[0] = buildFields(ctx, fieldsBox, TYPES[spType.getSelectedItemPosition()], scene);


        // привязка
        TextView anchLbl = new TextView(ctx);
        anchLbl.setText("Привязка");
        anchLbl.setTextSize(16);
        anchLbl.setPadding(0, pad, 0, 0);
        root.addView(anchLbl);

        List<String> planeIds = new ArrayList<>(scene.planeDefs.keySet());
        if (planeIds.isEmpty()) planeIds.add("BOTTOM");
        Spinner spPlane = new Spinner(ctx);
        ArrayAdapter<String> ap = new ArrayAdapter<>(ctx,
                android.R.layout.simple_spinner_item, planeIds);
        ap.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spPlane.setAdapter(ap);
        int pIdx = sel[0].anchor != null ? planeIds.indexOf(sel[0].anchor.planeId) : -1;
        if (pIdx >= 0) spPlane.setSelection(pIdx);
        root.addView(spPlane);

        Spinner spDir = new Spinner(ctx);
        ArrayAdapter<String> ad = new ArrayAdapter<>(ctx,
                android.R.layout.simple_spinner_item, DIRS);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spDir.setAdapter(ad);
        int dIdx = sel[0].anchor != null ? indexIn(DIRS, sel[0].anchor.dirExpr) : -1;
        if (dIdx >= 0) spDir.setSelection(dIdx);
        root.addView(spDir);

        final Anchor a0 = sel[0].anchor != null
                ? sel[0].anchor : new Anchor(planeIds.get(0), "0", "0", "100", "NORMAL");
        final EditText edOffU = addField(ctx, root, "Смещение U (можно выражение)", a0.offsetU);
        final EditText edOffV = addField(ctx, root, "Смещение V (можно выражение)", a0.offsetV);
        final EditText edLen  = addField(ctx, root, "Длина (можно выражение)", a0.lengthExpr);

        spEl.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                sel[0] = elements.get(pos);
                edName.setText(sel[0].name != null ? sel[0].name : sel[0].id);
                if (sel[0].typeName != null) {
                    for (int i = 0; i < TYPES.length; i++)
                        if (TYPES[i].equals(sel[0].typeName)) {
                            if (spType.getSelectedItemPosition() != i) spType.setSelection(i);
                            break;
                        }
                }
                if (sel[0].anchor != null) {
                    int pi = planeIds.indexOf(sel[0].anchor.planeId);
                    if (pi >= 0 && spPlane.getSelectedItemPosition() != pi) spPlane.setSelection(pi);
                    int di = indexIn(DIRS, sel[0].anchor.dirExpr);
                    if (di >= 0 && spDir.getSelectedItemPosition() != di) spDir.setSelection(di);
                    edOffU.setText(sel[0].anchor.offsetU);
                    edOffV.setText(sel[0].anchor.offsetV);
                    edLen.setText(sel[0].anchor.lengthExpr);
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });

        spType.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                fieldsBox.removeAllViews();
                // Добавили scene для корректной работы TextWatcher'а при смене типа трубы
                holder[0] = buildFields(ctx, fieldsBox, TYPES[pos], scene);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });


        Button ok = new Button(ctx);
        ok.setText("Применить");
        final Dialog[] dlgRef = new Dialog[1];
        ok.setOnClickListener(v -> {
            // читаем размеры: число или выражение от параметров сцены
            Map<String, Float> vals = new LinkedHashMap<>();
            Map<String, String> exprs = new LinkedHashMap<>();
            boolean valid = true;
            for (Map.Entry<String, EditText> en : holder[0].entrySet()) {
                String key = en.getKey();
                String raw = en.getValue().getText().toString().trim().replace(',', '.');
                if (raw.length() == 0) { valid = false; break; }
                float num;
                try {
                    num = Float.parseFloat(raw);
                } catch (NumberFormatException nf) {
                    try {
                        num = Expr.eval(raw, scene.params);
                    } catch (Exception ex) {
                        valid = false;
                        break;
                    }
                }
                vals.put(key, num);
                exprs.put(key, raw);
            }
            if (!valid) {
                Toast.makeText(ctx, "Заполните все размеры (число или выражение)",
                        Toast.LENGTH_SHORT).show();
                if (dlgRef[0] != null) dlgRef[0].dismiss();
                return;
            }
            if (sel[0] != null) {
                String type = spType.getSelectedItem().toString();
                Profile profile = ProfileFactory.build(type, vals);
                if (profile != null) {
                    String newName = edName.getText().toString().trim();
                    if (newName.length() > 0) sel[0].name = newName;
                    sel[0].typeName = type;
                    sel[0].dimExprs = exprs;
                    sel[0].profile = profile;
                    sel[0].anchor = new Anchor(
                            spPlane.getSelectedItem().toString(),
                            edOffU.getText().toString().trim(),
                            edOffV.getText().toString().trim(),
                            edLen.getText().toString().trim(),
                            spDir.getSelectedItem().toString());
                    cb.onApply(sel[0], profile, shortType(type), dimsString(vals), newName);
                }
            }
            if (dlgRef[0] != null) dlgRef[0].dismiss();
        });
        root.addView(ok);

        Dialog dlg = new AlertDialog.Builder(ctx)
                .setView(scroll)
                .create();
        dlgRef[0] = dlg;
        dlg.show();
    }

    private static int indexIn(String[] arr, String v) {
        if (v == null) return -1;
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(v)) return i;
        return -1;
    }

    /** Строка «подпись + поле ввода», возвращает поле. */
    private static EditText addField(Context ctx, LinearLayout box, String label, String value) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextSize(15);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tv);
        EditText ed = new EditText(ctx);
        ed.setSingleLine(true);
        ed.setText(value);
        ed.setTextSize(15);
        ed.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(ed);
        box.addView(row);
        return ed;
    }

    /** Короткое имя типа: «Труба прямоугольная» → «Труба». */
    private static String shortType(String type) {
        switch (type) {
            case "Труба прямоугольная":
            case "Труба круглая":
            case "Труба овальная":
                return "Труба";
            default:
                return type;
        }
    }

    /** «40×40×2 R3» из вычисленных значений. */
    private static String dimsString(Map<String, Float> f) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Float> en : f.entrySet()) {
            String key = en.getKey();
            if (key.equals("Радиус R") || key.equals("Скругление R")) {
                if (en.getValue() > 0) sb.append(" R").append(fmt(en.getValue()));
                continue;
            }
            if (sb.length() > 0) sb.append("×");
            sb.append(fmt(en.getValue()));
        }
        return sb.toString();
    }

    private static String fmt(float v) {
        if (v == Math.round(v)) return String.valueOf((int) v);
        return String.valueOf(v).replace('.', ',');
    }

    private static Map<String, EditText> buildFields(Context ctx, LinearLayout box, String type, Scene scene) {
        Map<String, EditText> map = new LinkedHashMap<>();
        String[] labels = FIELDS.get(type);
        float[] defs = DEFAULTS.get(type);

        for (int i = 0; i < labels.length; i++) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView tv = new TextView(ctx);
            tv.setText(labels[i]);
            tv.setTextSize(15);
            tv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(tv);

            final EditText ed = new EditText(ctx);
            ed.setSingleLine(true);
            ed.setText(String.valueOf(defs[i]));
            ed.setTextSize(15);
            ed.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            // НОВОЕ: Слушатель для моментальной валидации формулы
            ed.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(android.text.Editable s) {
                    validateField(ed, scene.params);
                }
            });

            row.addView(ed);
            box.addView(row);
            map.put(labels[i], ed);
        }
        return map;
    }

    /** Визуальная подсветка поля ввода при синтаксической ошибке в математическом выражении */
    private static void validateField(EditText ed, Map<String, Float> params) {
        String raw = ed.getText().toString().trim().replace(',', '.');
        if (raw.isEmpty()) {
            ed.setBackgroundColor(0x33FF0000); // Полупрозрачный красный
            return;
        }
        try {
            Float.parseFloat(raw);
            ed.setBackgroundColor(Color.TRANSPARENT); // Ошибки нет — сброс фона
        } catch (NumberFormatException nf) {
            try {
                // Пытаемся распарсить выражение по текущим переменным сцены
                Expr.eval(raw, params);
                ed.setBackgroundColor(Color.TRANSPARENT); // Выражение валидно!
            } catch (Exception ex) {
                ed.setBackgroundColor(0x33FF0000); // Ошибка в синтаксисе выражения (красный фон)
            }
        }
    }


    private static float num(Map<String, EditText> m, String key) {
        EditText e = m.get(key);
        if (e == null) return 0;
        try {
            return Float.parseFloat(e.getText().toString().trim().replace(',', '.'));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
