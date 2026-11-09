package ru.loftcontur.minicad;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.CheckBox;
import ru.loftcontur.minicad.core.Anchor;
import ru.loftcontur.minicad.core.Element;
import ru.loftcontur.minicad.core.Expr;
import ru.loftcontur.minicad.core.Extruder;
import ru.loftcontur.minicad.core.Material;
import ru.loftcontur.minicad.core.Profile;
import java.util.ArrayList;
import java.util.List;

import android.view.ViewGroup;
import java.util.Map;
import ru.loftcontur.minicad.core.Vec2;

import ru.loftcontur.minicad.core.Plane3D;
import ru.loftcontur.minicad.core.Scene;
import ru.loftcontur.minicad.core.Sketch;
import ru.loftcontur.minicad.core.Vec3;
import ru.loftcontur.minicad.gl.Renderer;

/**
 * Режим эскиза: оверлей перехватывает касания, панель инструментов внизу.
 * Примитивы хранятся в локальных координатах плоскости (u, v), начало —
 * центр плоскости, поэтому эскиз следует за плоскостью при изменении
 * параметров. Инструменты: точка, линия, круг, прямоугольник, полилиния,
 * усечение (до ближайших пересечений) и удлинение (до ближайшего
 * пересечения с линией, кругом или силуэтом детали), создание тел
 * по контуру (лист) и по кривой (профиль с 13 точками размещения).
 * Привязки: центр плоскости, вершины примитивов, углы и рёбра силуэтов
 * деталей, сетка 10 мм. Одиночный тап — рисование, один палец — пан
 * вида, два пальца — зум.
 */
public final class SketchEditor {

    private final Activity activity;
    private final Scene scene;
    private final Renderer renderer;
    private final float density;

    private final Runnable onChanged;               // обновление списка деталей
    // инструмент «Профиль»: запомненный выбор сечения и точки
    private Profile pendingProfile;
    private String pendingProfileType;
    private Map<String, String> pendingProfileDims;
    private int pendingPointIdx;
    // инструмент «Сдвинуть»: захваченный примитив и точка захвата
    private Sketch.Entity grabEntity;
    private float[] grabUv;

    private SketchOverlay overlay;
    private LinearLayout panel;
    private TextView hint;

    private String tool = "line";
    private float[] firstPoint;                     // первая точка примитива (u, v)

    /** Режим размещения: тип и размеры примитива задаются один раз,
     *  затем каждый тап ставит примитив по центру в точке привязки. */
    private String pendingType;     // "circle" или "rect"
    private float pendingW, pendingH;
    private final List<float[]> polyPoints = new ArrayList<>();
    private int previewIdx = -1;
    private float lastPinch;
    private float downX, downY;
    private static final float TAP_SLOP = 24f;      // максимальный сдвиг за тап, dp
    private static final float GRAB = 25f;          // радиус захвата примитива, мм

    public SketchEditor(Activity activity, Scene scene, Renderer renderer,
                        Runnable onChanged) {
        this.activity = activity;
        this.scene = scene;
        this.renderer = renderer;
        this.onChanged = onChanged;
        this.density = activity.getResources().getDisplayMetrics().density;
    }


    /** Запуск: выбор плоскости из списка → режим эскиза с авто-поворотом камеры. */
    public void start() {
        if (scene.planeDefs.isEmpty()) {
            Toast.makeText(activity, "Сначала создайте плоскость по грани",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (renderer.sketchMode) {
            ensureOverlay();
            overlay.setVisibility(View.VISIBLE);
            panel.setVisibility(View.VISIBLE);
            setTool(tool);
            return;
        }
        List<String> names = new ArrayList<>(scene.planeDefs.keySet());
        new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog)
                .setTitle("Плоскость для эскиза")
                .setItems(names.toArray(new String[0]), (d, w) -> begin(names.get(w)))
                .show();
    }

    private void begin(String planeId) {
        renderer.beginSketch(planeId);
        ensureOverlay();
        overlay.setVisibility(View.VISIBLE);
        panel.setVisibility(View.VISIBLE);
        setTool("line");
    }

    private void ensureOverlay() {
        if (overlay != null) return;
        FrameLayout root = (FrameLayout) activity.findViewById(android.R.id.content);

        overlay = new SketchOverlay(activity);
        root.addView(overlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable pb = new GradientDrawable();
        pb.setColor(0xEE1B1F26);
        panel.setBackground(pb);
        panel.setPadding((int) (8 * density), (int) (6 * density),
                (int) (8 * density), (int) (6 * density));
        panel.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        panel.setVisibility(View.GONE);
        root.addView(panel);

        hint = new TextView(activity);
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER);
        hint.setSingleLine(true);
        panel.addView(hint);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        // Находим строку с объявлением массива labels и добавляем "ESC"
        String[] labels = {"Точка", "Линия", "Круг", "Прямоуг", "Тело", "Вырезать", // Добавили кнопку
                "Профиль", "Разместить", "Полилиния", "Сдвинуть", "Удалить", "Усечь", "Удлить", "⤺", "ESC", "Готово", "Выход"};



        for (String s : labels) {
            TextView b = pill(s);
            final String t = s;
            b.setOnClickListener(v -> onTool(t));
            row.addView(b, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        panel.addView(row);
    }

    private TextView pill(String text) {
        TextView b = new TextView(activity);
        b.setText(text);
        b.setTextSize(12);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(16 * density);
        bg.setColor(0xFF2A6BFF);
        b.setBackground(bg);
        int p = (int) (7 * density);
        b.setPadding(p, p, p, p);
        return b;
    }

    private void onTool(String t) {
        switch (t) {
            case "⤺": undo(); return;
            case "ESC": handleEsc(); return; // Обработка нажатия кнопки ESC
            case "Готово": finishPoly(); return;
            case "Выход": exit(); return;
            default: setTool(t);
        }
    }
    /**
     * Кнопка ESC: сбрасывает накопленные точки текущего инструмента (непрерывное рисование),
     * очищает preview-примитивы, а если инструмент пуст — возвращает к базовому инструменту "Линия".
     */
    private void handleEsc() {
        boolean stateChanged = false;

        // 1. Если рисовалась линия/круг/прямоугольник и была поставлена первая точка
        if (firstPoint != null) {
            removePreview();
            firstPoint = null;
            stateChanged = true;
        }

        // 2. Если рисовалась полилиния и уже были поставлены точки
        if (!polyPoints.isEmpty()) {
            removePreview();
            polyPoints.clear();
            stateChanged = true;
        }

        // 3. Если был захвачен примитив в режиме "Сдвинуть"
        if (grabEntity != null) {
            grabEntity = null;
            grabUv = null;
            stateChanged = true;
        }

        // 4. Если было заготовлено тело для размещения
        if (pendingType != null) {
            pendingType = null;
            stateChanged = true;
        }

        if (stateChanged) {
            // Переинициализируем текущий инструмент, чтобы обновить подсказку и сбросить внутренности
            setTool(tool);
            Toast.makeText(activity, "Ввод сброшен (инструмент " + tool + " активен)", Toast.LENGTH_SHORT).show();
        } else {
            // Если ничего не происходило, просто сбрасываем инструмент на дефолтную "Линию"
            setTool("Линия");
        }
    }


    private void setTool(String t) {
        tool = t;
        removePreview();
        firstPoint = null;
        grabEntity = null;
        grabUv = null;
        if (!"Профиль".equals(t)) { /* выбор профиля сохраняется до смены
            инструмента на другой; повторный тап по «Профиль» сбрасывает */ }
        if ("Профиль".equals(t) && pendingProfile != null && toolJustRechosen(t)) {
            pendingProfile = null;
        }
        if (!"Разместить".equals(t)) pendingType = null;
        String msg;
        switch (t) {
            case "Точка":     msg = "Точка: тап — поставить точку"; break;
            case "Линия":     msg = "Линия: два тапа — начало и конец"; break;
            case "Круг":      msg = "Круг: центр, затем радиус"; break;
            case "Прямоуг":   msg = "Прямоугольник: два противоположных угла"; break;
            case "Тело":      msg = "Тело: тап по замкнутому контуру — лист"; break;
            case "Профиль":
                msg = pendingProfile == null
                        ? "Профиль: тап по кривой — выбрать сечение"
                        : "Профиль: тап по кривой — поставить тот же профиль";
                break;
            case "Разместить":
                msg = pendingType == null
                        ? "Разместить: тап — задать размеры примитива"
                        : "Разместить: тап — поставить примитив по центру";
                break;
            case "Полилиния": msg = "Полилиния: серия тапов, «Готово» — завершить"; break;
            case "Сдвинуть":  msg = "Сдвинуть: тап по примитиву, затем тап — куда"; break;
            case "Удалить":   msg = "Удалить: тап по примитиву"; break;
            case "Усечь":     msg = "Усечение: тап по линии"; break;
            case "Удлить":    msg = "Удлинение: тап возле конца линии"; break;
            case "Вырезать": msg = "Вырезать: тап по замкнутому контуру для создания отверстия"; break;

            default: msg = "";
        }
        hint.setText(msg);
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show();
    }

    /** Повторное нажатие кнопки того же инструмента (сброс запомненного). */
    private String lastTool;
    private boolean toolJustRechosen(String t) {
        boolean again = t.equals(lastTool);
        lastTool = t;
        return again;
    }


    private Sketch activeSketch() {
        return renderer.sketchPlaneId != null
                ? scene.sketchFor(renderer.sketchPlaneId) : null;
    }

    private void addEntity(Sketch.Entity e) {
        Sketch sk = activeSketch();
        if (sk != null) sk.entities.add(e);
    }

    private void removePreview() {
        Sketch sk = activeSketch();
        if (sk != null && previewIdx >= 0 && previewIdx < sk.entities.size())
            sk.entities.remove(previewIdx);
        previewIdx = -1;
    }

    private void undo() {
        Sketch sk = activeSketch();
        if (sk == null) return;
        if (previewIdx >= 0) { removePreview(); firstPoint = null; return; }
        if (!polyPoints.isEmpty()) {
            polyPoints.remove(polyPoints.size() - 1);
            updatePolyPreview();
            return;
        }
        if (!sk.entities.isEmpty())
            sk.entities.remove(sk.entities.size() - 1);
    }

    private void finishPoly() {
        if (!"Полилиния".equals(tool)) {
            Toast.makeText(activity, "«Готово» завершает полилинию",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (polyPoints.size() >= 2) {
            float[] p = new float[polyPoints.size() * 2];
            for (int i = 0; i < polyPoints.size(); i++) {
                p[i * 2] = polyPoints.get(i)[0];
                p[i * 2 + 1] = polyPoints.get(i)[1];
            }
            removePreview();
            addEntity(new Sketch.Entity("polyline", p, false));
            polyPoints.clear();
            firstPoint = null;
            Toast.makeText(activity, "Полилиния создана", Toast.LENGTH_SHORT).show();
        }
        removePreview();
        setTool(tool);
    }

    private void exit() {
        removePreview();
        polyPoints.clear();
        firstPoint = null;
        renderer.endSketch();
        if (panel != null) panel.setVisibility(View.GONE);
        if (overlay != null) overlay.setVisibility(View.GONE);
    }

    // ---- ввод: тап и щипок ----

    private void tapAt(float sx, float sy) {
        int w = renderer.getSurfaceW(), h = renderer.getSurfaceH();
        float[] uv = renderer.screenToPlane(sx, sy, w, h);
        if (uv == null) return;
        float[] snapped = renderer.sketchSnap(uv[0], uv[1]);
        onTap(snapped[0], snapped[1]);
    }

    private void onTap(float u, float v) {
        Sketch sk = activeSketch();
        if (sk == null) return;
        switch (tool) {
            case "Точка":
                addEntity(new Sketch.Entity("point", new float[]{u, v}, false));
                break;
            case "Линия":
            case "Круг":
            case "Прямоуг":
                if (firstPoint == null) {
                    firstPoint = new float[]{u, v};
                    updatePreview(firstPoint);
                } else {
                    Sketch.Entity e = finalEntity(u, v);
                    removePreview();
                    if (e != null) {
                        addEntity(e);
                        // Если вы хотите, чтобы диалог размеров НЕ прерывал рисование,
                        // строчку ниже (showSizeDialog) можно закомментировать.
                        // showSizeDialog(e);
                    }
                    // ВАЖНО: Мы НЕ сбрасываем инструмент, а оставляем firstPoint = null.
                    // Пользователь может СРАЗУ же ставить первую точку СЛЕДУЮЩЕЙ линии/круга.
                    firstPoint = null;
                }
                break;
            case "Тело":
                createBody(u, v);
                break;
            case "Разместить":
                if (pendingType == null) { showPlacementDialog(); return; }
                if ("circle".equals(pendingType)) {
                    addEntity(new Sketch.Entity("circle",
                            new float[]{u, v, pendingW}, false));
                } else {
                    addEntity(new Sketch.Entity("rect",
                            rectPts(new float[]{u - pendingW / 2, v - pendingH / 2},
                                    new float[]{u + pendingW / 2, v + pendingH / 2}),
                            false));
                }
                break;
            case "Профиль":
                profileAt(u, v);
                break;
            case "Сдвинуть":
                moveAt(u, v);
                break;
            case "Удалить":
                deleteAt(u, v);
                break;
            case "Вырезать":
                createCutout(u, v);
                break;

            case "Полилиния":
                polyPoints.add(new float[]{u, v});
                updatePolyPreview();
                break;
            case "Усечь":
                trimAt(u, v);
                break;
            case "Удлить":
                extendAt(u, v);
                break;
        }
    }
    /**
     * Инструмент создания параметрического отверстия методом выреза выдавливанием.
     */
    /**
     * Инструмент создания параметрического отверстия методом выреза выдавливанием.
     */
    /**
     * Инструмент создания параметрического отверстия методом выреза выдавливанием.
     */
    private void createCutout(float u, float v) {
        Sketch sk = activeSketch(); //
        if (sk == null) return; //

        // Находим замкнутый контур под тапом пальца
        final List<float[]> loop = SketchContour.findLoop(sk, u, v, GRAB); //
        if (loop == null) { //
            Toast.makeText(activity, "Тапните внутри замкнутого контура (круга или прямоугольника)", Toast.LENGTH_SHORT).show(); //
            return; //
        } //

        // Выясняем, какую деталь пересекает этот эскиз
        Plane3D sketchPlane = renderer.sketchPlane(); //
        if (sketchPlane == null) return; //

        Element targetElement = null; //
        // Используем точный экранный pickFace по сохраненным координатам нажатия пальца
        String pickedId = renderer.pickFace(downX, downY, renderer.getSurfaceW(), renderer.getSurfaceH(), null); //

        if (pickedId != null) { //
            for (Element e : scene.elements) { //
                if (e.id.equals(pickedId)) { //
                    targetElement = e; //
                    break; //
                } //
            } //
        } //

        // Если ничего не выбрано тапом прямо сейчас, но есть выделенная в списке деталь — применяем к ней
        if (targetElement == null && scene.selectedId != null) { //
            for (Element e : scene.elements) { //
                if (e.id.equals(scene.selectedId)) { //
                    targetElement = e; //
                    break; //
                } //
            } //
        } //

        if (targetElement == null) { //
            Toast.makeText(activity, "Сначала выделите деталь в списке, в которой нужно сделать вырез", Toast.LENGTH_LONG).show(); //
            return; //
        } //

        final Element finalTarget = targetElement; //

        // Создаем UI диалога для указания глубины выреза
        LinearLayout box = new LinearLayout(activity); //
        box.setOrientation(LinearLayout.VERTICAL); //
        int pad = (int) (16 * density); //
        box.setPadding(pad, pad, pad, pad); //

        box.addView(labelView("Глубина выреза, мм (число или выражение)")); //
        final EditText edDepth = numField("2"); //
        box.addView(edDepth); //

        final CheckBox cbThrough = new CheckBox(activity); //
        cbThrough.setText("Сквозное отверстие (насквозь)"); //
        cbThrough.setTextColor(Color.WHITE); //
        cbThrough.setChecked(true); //
        box.addView(cbThrough); //

        cbThrough.setOnCheckedChangeListener((buttonView, isChecked) -> { //
            edDepth.setEnabled(!isChecked); //
            if (isChecked) edDepth.setText("Насквозь"); //
            else edDepth.setText("2"); //
        }); //
        edDepth.setText("Насквозь"); //
        edDepth.setEnabled(false); //

        new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog) //
                .setTitle("Вырезать выдавливанием") //
                .setView(box) //
                .setPositiveButton("Вырезать", (d, w) -> { //
                    Element.Cutout cutout = new Element.Cutout(); //
                    cutout.id = "CUT_" + (System.currentTimeMillis() % 10000); //
                    cutout.planeId = renderer.sketchPlaneId; //

                    // ИСПРАВЛЕНО: Глубокое копирование двумерных массивов float[] [u, v]
                    cutout.loop = new ArrayList<>(); //
                    for (float[] pt : loop) { //
                        cutout.loop.add(new float[]{pt[0], pt[1]}); //
                    } //

                    // ИСПРАВЛЕНО: Расчет знака площади контура выреза по формуле Гаусса.
                    // Алгоритм трассировки в Extruder требует, чтобы отверстие шло строго ПО часовой стрелке (CW, площадь < 0).
                    // Если контур эскиза закручен против часовой стрелки (площадь > 0), мы принудительно разворачиваем его.
                    float area = 0; //
                    int numPts = cutout.loop.size(); //
                    for (int i = 0; i < numPts; i++) { //
                        float[] pCurr = cutout.loop.get(i); //
                        float[] pNext = cutout.loop.get((i + 1) % numPts); //
                        area += pCurr[0] * pNext[1] - pNext[0] * pCurr[1]; //
                    } //

                    if (area > 0) { //
                        java.util.Collections.reverse(cutout.loop); // Разворачиваем в CW
                    } //

                    cutout.isThrough = cbThrough.isChecked(); //
                    cutout.depthExpr = cbThrough.isChecked() ? "THROUGH" : edDepth.getText().toString().trim(); //

                    finalTarget.cutouts.add(cutout); //

                    scene.rebuild(); //
                    renderer.syncScene(); //

                    if (onChanged != null) onChanged.run(); //
                    Toast.makeText(activity, "Отверстие успешно вырезано!", Toast.LENGTH_SHORT).show(); //
                }) //
                .setNegativeButton("Отмена", null) //
                .show(); //
    }




    private void updatePreview(float[] uv) {
        Sketch sk = activeSketch();
        if (sk == null || firstPoint == null) return;
        Sketch.Entity e;
        switch (tool) {
            case "Линия":
                e = new Sketch.Entity("line",
                        new float[]{firstPoint[0], firstPoint[1], uv[0], uv[1]}, true);
                break;
            case "Круг":
                e = new Sketch.Entity("circle",
                        new float[]{firstPoint[0], firstPoint[1],
                                (float) Math.sqrt(sqr(uv[0] - firstPoint[0])
                                        + sqr(uv[1] - firstPoint[1]))}, true);
                break;
            case "Прямоуг":
                e = new Sketch.Entity("rect", rectPts(firstPoint, uv), true);
                break;
            case "Полилиния":
                updatePolyPreviewAt(uv);
                return;
            default:
                return;
        }
        if (previewIdx < 0) {
            sk.entities.add(e);
            previewIdx = sk.entities.size() - 1;
        } else {
            sk.entities.set(previewIdx, e);
        }
    }

    /** Углы прямоугольника по двум противоположным точкам: c0,c1,c2,c3 по кругу. */
    private static float[] rectPts(float[] a, float[] b) {
        return new float[]{a[0], a[1], b[0], a[1], b[0], b[1], a[0], b[1]};
    }

    private static float sqr(float x) { return x * x; }

    private void updatePolyPreview() {
        if (polyPoints.size() >= 2) {
            updatePolyPreviewAt(polyPoints.get(polyPoints.size() - 1));
        } else if (previewIdx >= 0) {
            removePreview();
        }
    }

    private void updatePolyPreviewAt(float[] uv) {
        Sketch sk = activeSketch();
        if (sk == null || polyPoints.isEmpty()) return;
        float[] p = new float[(polyPoints.size() + 1) * 2];
        for (int i = 0; i < polyPoints.size(); i++) {
            p[i * 2] = polyPoints.get(i)[0];
            p[i * 2 + 1] = polyPoints.get(i)[1];
        }
        p[p.length - 2] = uv[0];
        p[p.length - 1] = uv[1];
        Sketch.Entity e = new Sketch.Entity("polyline", p, true);
        if (previewIdx < 0) {
            sk.entities.add(e);
            previewIdx = sk.entities.size() - 1;
        } else {
            sk.entities.set(previewIdx, e);
        }
    }

    /** Финальный примитив по двум точкам (в отличие от preview — завершённый). */
    private Sketch.Entity finalEntity(float u, float v) {
        if (firstPoint == null) return null;
        switch (tool) {
            case "Линия":
                return new Sketch.Entity("line",
                        new float[]{firstPoint[0], firstPoint[1], u, v}, false);
            case "Круг":
                return new Sketch.Entity("circle",
                        new float[]{firstPoint[0], firstPoint[1],
                                (float) Math.sqrt(sqr(u - firstPoint[0])
                                        + sqr(v - firstPoint[1]))}, false);
            case "Прямоуг":
                return new Sketch.Entity("rect",
                        rectPts(firstPoint, new float[]{u, v}), false);
        }
        return null;
    }

    // ---- диалог размеров ----

    private EditText numField(String value) {
        EditText ed = new EditText(activity);
        ed.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        ed.setText(value);
        ed.setSingleLine(true);
        return ed;
    }

    private TextView labelView(String s) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextSize(14);
        t.setTextColor(0xFF9FB3C8);
        return t;
    }

    private static String round2(float v) {
        return String.valueOf(Math.round(v * 100f) / 100f);
    }

    /** Диалог размещения: тип примитива и размеры; затем каждый тап ставит
     *  примитив по центру в точку привязки, пока не сменён инструмент. */
    private void showPlacementDialog() {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * density);
        box.setPadding(pad, pad, pad, pad);

        final String[] types = {"Круг (по радиусу)", "Прямоугольник (по центру)"};
        final int[] typeIdx = {0};
        TextView typeView = new TextView(activity);
        typeView.setText(types[0]);
        typeView.setTextColor(Color.WHITE);
        typeView.setTextSize(15);
        typeView.setPadding(0, (int) (8 * density), 0, (int) (8 * density));
        typeView.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(0xFF2A6BFF));
        typeView.setOnClickListener(v -> new AlertDialog.Builder(activity,
                android.R.style.Theme_Material_Dialog)
                .setItems(types, (d, w) -> {
                    typeIdx[0] = w;
                    typeView.setText(types[w]);
                }).show());
        box.addView(labelView("Тип"));
        box.addView(typeView);

        box.addView(labelView("Радиус, мм (для круга)"));
        final EditText edR = numField("50");
        box.addView(edR);
        box.addView(labelView("Ширина, мм (для прямоугольника)"));
        final EditText edW = numField("100");
        box.addView(edW);
        box.addView(labelView("Высота, мм (для прямоугольника)"));
        final EditText edH = numField("100");
        box.addView(edH);

        new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog)
                .setTitle("Размещение")
                .setView(box)
                .setPositiveButton("Готов", (d, w) -> {
                    try {
                        if (typeIdx[0] == 0) {
                            pendingType = "circle";
                            pendingW = Float.parseFloat(
                                    edR.getText().toString().replace(',', '.'));
                            pendingH = 0;
                        } else {
                            pendingType = "rect";
                            pendingW = Float.parseFloat(
                                    edW.getText().toString().replace(',', '.'));
                            pendingH = Float.parseFloat(
                                    edH.getText().toString().replace(',', '.'));
                        }
                        setTool("Разместить");
                    } catch (Exception ex) {
                        Toast.makeText(activity, "Некорректное значение",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    /** Диалог размеров: линия — длина и угол, круг — радиус, прямоугольник —
     *  ширина и высота. Начальная точка сохраняется. */
    private void showSizeDialog(final Sketch.Entity e) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * density);
        box.setPadding(pad, pad, pad, pad);

        final EditText edA, edB;
        float[] p = e.pts;
        if ("line".equals(e.type)) {
            float dx = p[2] - p[0], dy = p[3] - p[1];
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            float ang = (float) Math.toDegrees(Math.atan2(dy, dx));
            box.addView(labelView("Длина, мм"));
            edA = numField(round2(len));
            box.addView(edA);
            box.addView(labelView("Угол, ° (от оси U, против часовой)"));
            edB = numField(round2(ang));
            box.addView(edB);
        } else if ("circle".equals(e.type)) {
            box.addView(labelView("Радиус, мм"));
            edA = numField(round2(p[2]));
            box.addView(edA);
            edB = null;
        } else if ("rect".equals(e.type)) {
            box.addView(labelView("Ширина, мм (вдоль U)"));
            edA = numField(round2(Math.abs(p[2] - p[0])));
            box.addView(edA);
            box.addView(labelView("Высота, мм (вдоль V)"));
            edB = numField(round2(Math.abs(p[5] - p[1])));
            box.addView(edB);
        } else {
            return;
        }

        new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog)
                .setTitle("Размеры")
                .setView(box)
                .setPositiveButton("Применить", (d, w) -> {
                    Sketch sk = activeSketch();
                    if (sk == null) return;
                    try {
                        if ("line".equals(e.type)) {
                            float len = Float.parseFloat(
                                    edA.getText().toString().replace(',', '.'));
                            float ang = Float.parseFloat(
                                    edB.getText().toString().replace(',', '.'));
                            float a = (float) Math.toRadians(ang);
                            sk.entities.set(sk.entities.indexOf(e),
                                    new Sketch.Entity("line",
                                            new float[]{p[0], p[1],
                                                    p[0] + len * (float) Math.cos(a),
                                                    p[1] + len * (float) Math.sin(a)}, false));
                        } else if ("circle".equals(e.type)) {
                            float r = Float.parseFloat(
                                    edA.getText().toString().replace(',', '.'));
                            sk.entities.set(sk.entities.indexOf(e),
                                    new Sketch.Entity("circle",
                                            new float[]{p[0], p[1], r}, false));
                        } else if ("rect".equals(e.type)) {
                            float bw = Float.parseFloat(
                                    edA.getText().toString().replace(',', '.'));
                            float bh = Float.parseFloat(
                                    edB.getText().toString().replace(',', '.'));
                            float su = p[2] - p[0] >= 0 ? 1 : -1;
                            float sv = p[5] - p[1] >= 0 ? 1 : -1;
                            sk.entities.set(sk.entities.indexOf(e),
                                    new Sketch.Entity("rect",
                                            rectPts(new float[]{p[0], p[1]},
                                                    new float[]{p[0] + su * bw,
                                                            p[1] + sv * bh}), false));
                        }
                    } catch (Exception ex) {
                        Toast.makeText(activity, "Некорректное значение",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Оставить", null)
                .show();
    }

    // ---- усечение и удлинение ----

    /** Расстояние от точки до примитива; MAX_VALUE, если не касается. */
    private static float distTo(Sketch.Entity e, float u, float v) {
        if ("point".equals(e.type))
            return (float) Math.sqrt(sqr(e.pts[0] - u) + sqr(e.pts[1] - v));
        if ("circle".equals(e.type))
            return (float) Math.abs(
                    Math.sqrt(sqr(e.pts[0] - u) + sqr(e.pts[1] - v)) - e.pts[2]);
        float best = Float.MAX_VALUE;
        for (float[] s : Renderer.entitySegments(e)) {
            float d = distToSeg(u, v, s[0], s[1], s[2], s[3]);
            if (d < best) best = d;
        }
        return best;
    }

    private static float distToSeg(float px, float py, float ax, float ay,
                                   float bx, float by) {
        float rx = bx - ax, ry = by - ay;
        float len2 = rx * rx + ry * ry;
        if (len2 < 1e-9f)
            return (float) Math.sqrt(sqr(px - ax) + sqr(py - ay));
        float t = ((px - ax) * rx + (py - ay) * ry) / len2;
        t = Math.max(0, Math.min(1, t));
        return (float) Math.sqrt(sqr(px - (ax + rx * t)) + sqr(py - (ay + ry * t)));
    }

    private Sketch.Entity nearestEntity(Sketch sk, float u, float v) {
        Sketch.Entity best = null;
        float bestD = GRAB;
        for (Sketch.Entity e : sk.entities) {
            if (e.preview) continue;
            float d = distTo(e, u, v);
            if (d < bestD) { bestD = d; best = e; }
        }
        return best;
    }

    /** Параметр проекции точки на сегмент (0..1). */
    private static float paramOnSeg(float px, float py, float ax, float ay,
                                    float bx, float by) {
        float rx = bx - ax, ry = by - ay;
        float len2 = rx * rx + ry * ry;
        if (len2 < 1e-9f) return 0;
        return Math.max(0, Math.min(1,
                ((px - ax) * rx + (py - ay) * ry) / len2));
    }

    /** Пересечение сегментов: t на AB (0..1) или null. */
    private static Float segSeg(float ax, float ay, float bx, float by,
                                float cx, float cy, float dx, float dy) {
        float rx = bx - ax, ry = by - ay;
        float sx = dx - cx, sy = dy - cy;
        float denom = rx * sy - ry * sx;
        if (Math.abs(denom) < 1e-9f) return null;
        float t = ((cx - ax) * sy - (cy - ay) * sx) / denom;
        float up = ((cx - ax) * ry - (cy - ay) * rx) / denom;
        if (t < -1e-6f || t > 1 + 1e-6f || up < -1e-6f || up > 1 + 1e-6f) return null;
        return t;
    }

    /** Пересечение луча P + t·D с сегментом CD: t > eps или null. */
    private static Float raySeg(float px, float py, float dxx, float dyy,
                                float cx, float cy, float dx2, float dy2) {
        float sx = dx2 - cx, sy = dy2 - cy;
        float denom = dxx * sy - dyy * sx;
        if (Math.abs(denom) < 1e-9f) return null;
        float qx = cx - px, qy = cy - py;
        float t = (qx * sy - qy * sx) / denom;
        float up = (qx * dyy - qy * dxx) / denom;
        if (t < 1e-6f || up < -1e-6f || up > 1 + 1e-6f) return null;
        return t;
    }

    /** Ближайшее пересечение луча с окружностью: t > eps или null. */
    private static Float rayCircle(float px, float py, float dxx, float dyy,
                                   float cx, float cy, float r) {
        float ox = px - cx, oy = py - cy;
        float b = dxx * ox + dyy * oy;
        float c = ox * ox + oy * oy - r * r;
        float disc = b * b - c;
        if (disc < 0) return null;
        float sq = (float) Math.sqrt(disc);
        float t = -b - sq;
        if (t < 1e-6f) t = -b + sq;
        return t < 1e-6f ? null : t;
    }

    /** Пересечение сегмента AB с окружностью: {t0, t1} или null; t1 = -1,
     *  если выход лежит за пределами сегмента. */
    private static float[] segCircle(float ax, float ay, float bx, float by,
                                     float cx, float cy, float r) {
        float rx = bx - ax, ry = by - ay;
        float ox = ax - cx, oy = ay - cy;
        float a = rx * rx + ry * ry;
        if (a < 1e-12f) return null;
        float b = 2f * (ox * rx + oy * ry);
        float c = ox * ox + oy * oy - r * r;
        float disc = b * b - 4 * a * c;
        if (disc < 0) return null;
        float sq = (float) Math.sqrt(disc);
        float t0 = (-b - sq) / (2 * a), t1 = (-b + sq) / (2 * a);
        float lo = Math.min(t0, t1), hi = Math.max(t0, t1);
        if (hi < -1e-6f || lo > 1 + 1e-6f) return null;
        float h0 = Math.max(lo, 0f);
        float h1 = hi <= 1f ? hi : -1f;
        if (h1 >= 0 && h1 < h0) return null;
        return new float[]{h0, h1};
    }

    /** Сегменты-препятствия: линии, прямоугольники, полилинии других
     *  примитивов + рёбра силуэтов деталей (сечение плоскостью). */
    private List<float[]> obstacles(Sketch sk, Sketch.Entity except) {
        List<float[]> segs = new ArrayList<>();
        for (Sketch.Entity e : sk.entities) {
            if (e.preview || e == except) continue;
            if ("circle".equals(e.type)) continue;
            if ("point".equals(e.type)) continue;
            segs.addAll(Renderer.entitySegments(e));
        }
        Plane3D pl = renderer.sketchPlane();
        if (pl != null) {
            List<float[]> corners = renderer.silhouetteCorners(pl);
            for (int i = 0; i + 3 < corners.size(); i += 4) {
                float[] a = corners.get(i), b = corners.get(i + 1);
                float[] c2 = corners.get(i + 2), d = corners.get(i + 3);
                segs.add(new float[]{a[0], a[1], b[0], b[1]});
                segs.add(new float[]{b[0], b[1], c2[0], c2[1]});
                segs.add(new float[]{c2[0], c2[1], d[0], d[1]});
                segs.add(new float[]{d[0], d[1], a[0], a[1]});
            }
        }
        return segs;
    }

    private List<float[]> obstacleCircles(Sketch sk, Sketch.Entity except) {
        List<float[]> out = new ArrayList<>();
        for (Sketch.Entity e : sk.entities) {
            if (e.preview || e == except) continue;
            if ("circle".equals(e.type))
                out.add(new float[]{e.pts[0], e.pts[1], e.pts[2]});
        }
        return out;
    }

    /** Усечение линии: тап по линии — она обрезается до ближайших
     *  пересечений слева и справа от точки тапа. */
    private void trimAt(float u, float v) {
        Sketch sk = activeSketch();
        if (sk == null) return;
        Sketch.Entity target = nearestEntity(sk, u, v);
        if (target == null || !"line".equals(target.type)) {
            Toast.makeText(activity, "Тап по линии для усечения",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        float[] p = target.pts;
        float ax = p[0], ay = p[1], bx = p[2], by = p[3];
        float tTap = paramOnSeg(u, v, ax, ay, bx, by);

        List<Float> ts = new ArrayList<>();
        for (float[] s : obstacles(sk, target)) {
            Float t = segSeg(ax, ay, bx, by, s[0], s[1], s[2], s[3]);
            if (t != null) ts.add(t);
        }
        for (float[] c : obstacleCircles(sk, target)) {
            float[] hits = segCircle(ax, ay, bx, by, c[0], c[1], c[2]);
            if (hits != null) {
                ts.add(hits[0]);
                if (hits[1] >= 0) ts.add(hits[1]);
            }
        }
        if (ts.isEmpty()) {
            Toast.makeText(activity, "Нет пересечений — усекать нечего",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        float tLeft = 0f, tRight = 1f;
        for (float t : ts) {
            if (t < tTap && t > tLeft) tLeft = t;
            if (t > tTap && t < tRight) tRight = t;
        }
        if (tRight - tLeft < 1e-4f) {
            Toast.makeText(activity, "Слишком короткий участок",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        float[] np = {ax + (bx - ax) * tLeft, ay + (by - ay) * tLeft,
                ax + (bx - ax) * tRight, ay + (by - ay) * tRight};
        sk.entities.set(sk.entities.indexOf(target),
                new Sketch.Entity("line", np, false));
        Toast.makeText(activity, "Линия усечена", Toast.LENGTH_SHORT).show();
    }

    /** Удлинение линии: тап возле конца — конец продлевается вдоль линии
     *  до первого пересечения с препятствием. */
    private void extendAt(float u, float v) {
        Sketch sk = activeSketch();
        if (sk == null) return;
        Sketch.Entity target = nearestEntity(sk, u, v);
        if (target == null || !"line".equals(target.type)) {
            Toast.makeText(activity, "Тап возле конца линии",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        float[] p = target.pts;
        float dA = sqr(p[0] - u) + sqr(p[1] - v);
        float dB = sqr(p[2] - u) + sqr(p[3] - v);
        boolean fromEnd = dB <= dA;         // какой конец ближе к тапу
        float ex = fromEnd ? p[2] : p[0];
        float ey = fromEnd ? p[3] : p[1];
        float dxx = fromEnd ? p[2] - p[0] : p[0] - p[2];
        float dyy = fromEnd ? p[3] - p[1] : p[1] - p[3];
        float len = (float) Math.sqrt(sqr(dxx) + sqr(dyy));
        if (len < 1e-6f) return;
        dxx /= len; dyy /= len;

        float bestT = Float.MAX_VALUE;
        for (float[] s : obstacles(sk, target)) {
            Float t = raySeg(ex, ey, dxx, dyy, s[0], s[1], s[2], s[3]);
            if (t != null && t < bestT) bestT = t;
        }
        for (float[] c : obstacleCircles(sk, target)) {
            Float t = rayCircle(ex, ey, dxx, dyy, c[0], c[1], c[2]);
            if (t != null && t < bestT) bestT = t;
        }
        if (bestT == Float.MAX_VALUE) {
            Toast.makeText(activity, "Нет пересечений — не к чему вести",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        float[] np = fromEnd
                ? new float[]{p[0], p[1], ex + dxx * bestT, ey + dyy * bestT}
                : new float[]{ex + dxx * bestT, ey + dyy * bestT, p[2], p[3]};
        sk.entities.set(sk.entities.indexOf(target),
                new Sketch.Entity("line", np, false));
        Toast.makeText(activity, "Линия удлинена", Toast.LENGTH_SHORT).show();
    }

    // ---- создание тел по эскизу ----

    private static final String[] ANCHOR_POINTS = {
            "Центр",
            "Угол: (X-, Y-)", "Угол: (X+, Y-)",
            "Угол: (X+, Y+)", "Угол: (X-, Y+)",
            "Середина стороны: Y-", "Середина стороны: X+",
            "Середина стороны: Y+", "Середина стороны: X-",
            "Середина стенки: Y-", "Середина стенки: X+",
            "Середина стенки: Y+", "Середина стенки: X-"};

//    private void createBody(float u, float v) {
//        Sketch sk = activeSketch();
//        if (sk == null) return;
//        List<float[]> loop = SketchContour.findLoop(sk, u, v, GRAB);
//        List<float[]> chain = SketchContour.findChain(sk, u, v, GRAB);
//        if (loop == null && chain == null) {
//            Toast.makeText(activity, "Тап по замкнутому контуру или кривой",
//                    Toast.LENGTH_SHORT).show();
//            return;
//        }
//        final List<float[]> fLoop = loop, fChain = chain;
//        if (loop != null && chain != null) {
//            new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog)
//                    .setTitle("Тело по эскизу")
//                    .setItems(new String[]{
//                                    "Листовое тело (выдавить контур)",
//                                    "Профиль вдоль кривой"},
//                            (d, w) -> {
//                                if (w == 0) showSheetDialog(fLoop);
//                                else showProfileFlow(fChain);
//                            })
//                    .show();
//        } else if (loop != null) {
//            showSheetDialog(loop);
//        } else {
//            showProfileFlow(chain);
//        }
//    }

    /** Диалог листового тела: толщина и направление вверх/вниз. */
    private void showSheetDialog(final List<float[]> loop) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * density);
        box.setPadding(pad, pad, pad, pad);

        box.addView(labelView("Толщина, мм (число или выражение)"));
        final EditText edT = numField("3");
        box.addView(edT);

        final boolean[] up = {true};
        final TextView dirView = new TextView(activity);
        dirView.setText("Направление: вверх");
        dirView.setTextColor(Color.WHITE);
        dirView.setTextSize(15);
        dirView.setPadding(0, (int) (8 * density), 0, (int) (8 * density));
        dirView.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(0xFF2A6BFF));
        dirView.setOnClickListener(v -> {
            up[0] = !up[0];
            dirView.setText(up[0] ? "Направление: вверх" : "Направление: вниз");
        });
        box.addView(labelView("Направление выдавливания"));
        box.addView(dirView);

        new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog)
                .setTitle("Листовое тело")
                .setView(box)
                .setPositiveButton("Создать", (d, w) -> {
                    String t = edT.getText().toString().trim().replace(',', '.');
                    try {
                        Float.parseFloat(t);
                    } catch (Exception ex) {
                        try { Expr.eval(t, scene.params); }
                        catch (Exception e2) {
                            Toast.makeText(activity, "Не вычислить толщину",
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                    }
                    SketchBody.createSheet(scene, renderer.sketchPlaneId,
                            loop, t, up[0]);
                    renderer.syncScene();
                    renderer.syncScene();
                    if (onChanged != null) onChanged.run();
                    setTool("Линия");   // один тап — одно тело, без случайных копий
                    Toast.makeText(activity, "Тело создано", Toast.LENGTH_SHORT).show();

                })
                .setNegativeButton("Отмена", null)
                .show();
    }

//    /** Профиль по кривой: сначала сортамент, затем 13 точек и подгонка. */
//    private void showProfileFlow(final List<float[]> chain) {
//        Element tmp = new Element("BODY_TMP");
//        tmp.name = "Профиль по кривой";
//        tmp.material = Material.ST3;
//        tmp.profile = Profile.rectTube(40, 40, 2, 3);
//        tmp.typeName = "Труба прямоугольная";
//        tmp.anchor = new Anchor(renderer.sketchPlaneId, "0", "0", "0", "NORMAL");
//        scene.elements.add(tmp);
//        scene.rebuild();
//
//        SortamentDialog.show(activity, scene.elements, tmp, scene,
//                (el, profile, type, dims, newName) -> {
//                    // SortamentDialog уже применил выбор к el: profile,
//                    // typeName и dimExprs заполнены. Параметр dims — строка
//                    // для подписи («40 × 40 × 2»), в dimExprs её писать нельзя.
//                    if (profile != null) el.profile = profile;
//                    if (type != null) el.typeName = type;
//                    scene.rebuild();
//                    showProfilePointDialog(el, chain);
//                });
//    }


//    /** 13 точек размещения профиля + подгонка концов по граням. */
//    private void showProfilePointDialog(final Element el, final List<float[]> chain) {
//        LinearLayout box = new LinearLayout(activity);
//        box.setOrientation(LinearLayout.VERTICAL);
//        int pad = (int) (16 * density);
//        box.setPadding(pad, pad, pad, pad);
//
//        final CheckBox fit = new CheckBox(activity);
//        fit.setText("Подогнать концы по граням (усечь/удлинить)");
//        fit.setTextColor(Color.WHITE);
//        fit.setChecked(true);
//        box.addView(fit);
//
//        final int[] pointIdx = {0};
//        final TextView ptView = new TextView(activity);
//        ptView.setText("Точка размещения: " + ANCHOR_POINTS[0]);
//        ptView.setTextColor(Color.WHITE);
//        ptView.setTextSize(15);
//        ptView.setPadding(0, (int) (10 * density), 0, (int) (10 * density));
//        ptView.setBackgroundTintList(
//                android.content.res.ColorStateList.valueOf(0xFF2A6BFF));
//        ptView.setOnClickListener(v -> new AlertDialog.Builder(activity,
//                android.R.style.Theme_Material_Dialog)
//                .setTitle("Точка размещения профиля")
//                .setSingleChoiceItems(ANCHOR_POINTS, pointIdx[0],
//                        (d, w) -> pointIdx[0] = w)
//                .setPositiveButton("ОК", (d, w) ->
//                        ptView.setText("Точка размещения: "
//                                + ANCHOR_POINTS[pointIdx[0]]))
//                .show());
//        box.addView(labelView("Положение профиля относительно кривой"));
//        box.addView(ptView);
//
//        new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog)
//                .setTitle("Профиль вдоль кривой")
//                .setView(box)
//                .setPositiveButton("Создать", (d, w) -> {
//                    // временный элемент-заготовку убираем — создаётся настоящий
//                    scene.elements.remove(el);
//                    SketchBody.createProfileAlong(scene, renderer.sketchPlaneId,
//                            chain, el.profile, el.typeName, el.dimExprs,
//                            pointIdx[0], fit.isChecked(), silhouetteSegs());
//                    renderer.syncScene();
//                    Toast.makeText(activity, "Профиль создан", Toast.LENGTH_SHORT).show();
//                })
//                .setNegativeButton("Отмена", (d, w) -> {
//                    scene.elements.remove(el);
//                    scene.rebuild();
//                })
//                .show();
//    }


    // ---- создание тел по эскизу ----

    private void createBody(float u, float v) {
        Sketch sk = activeSketch();
        if (sk == null) return;
        List<float[]> loop = SketchContour.findLoop(sk, u, v, GRAB);
        if (loop == null) {
            Toast.makeText(activity, "Тап по замкнутому контуру",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        showSheetDialog(loop);
    }

    private void profileAt(float u, float v) {
        Sketch sk = activeSketch();
        if (sk == null) return;
        final List<float[]> curve = tappedCurve(sk, u, v);
        if (curve == null) {
            Toast.makeText(activity,
                    "Тап по кривой: линия, полилиния, сторона, круг",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (pendingProfile == null) showProfileFlow(curve);
        else placeProfile(curve, pendingProfile, pendingProfileType,
                pendingProfileDims, pendingPointIdx);
    }

    private void placeProfile(List<float[]> chain, Profile profile,
                              String typeName, Map<String, String> dims,
                              int pointIdx) {
        if (chain == null || chain.size() < 2) {
            Toast.makeText(activity, "Кривая слишком короткая — профиль не создан",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        SketchBody.createProfileAlong(scene, renderer.sketchPlaneId,
                chain, profile, typeName, dims, pointIdx);
        renderer.syncScene();
        if (onChanged != null) onChanged.run();
        Toast.makeText(activity, "Профиль поставлен — тап по другой кривой: ещё один",
                Toast.LENGTH_SHORT).show();
    }


    /** Профиль по кривой: сортамент, затем квадрат из 13 точек. */
    private void showProfileFlow(final List<float[]> chain) {
        Element tmp = new Element("BODY_TMP");
        tmp.name = "Профиль по кривой";
        tmp.material = Material.ST3;
        tmp.profile = Profile.rectTube(40, 40, 2, 3);
        tmp.typeName = "Труба прямоугольная";
        tmp.anchor = new Anchor(renderer.sketchPlaneId, "0", "0", "0", "NORMAL");
        scene.elements.add(tmp);
        scene.rebuild();

        SortamentDialog.show(activity, scene.elements, tmp, scene,
                (el, profile, type, dims, newName) -> {
                    if (profile != null) el.profile = profile;
                    if (type != null) el.typeName = type;
                    scene.rebuild();
                    showAnchorGrid(el, chain);
                });
    }
    /** Ровно одна кривая под тапом: линия целиком, сторона прямоугольника,
     *  полилиния целиком или круг. */
    /** Ровно одна кривая под тапом: линия целиком, сторона прямоугольника,
     *  полилиния целиком или круг. Каждый элемент списка — точка (u, v). */
    private List<float[]> tappedCurve(Sketch sk, float u, float v) {
        Sketch.Entity best = null;
        int bestSeg = -1;
        float bestD = GRAB;
        for (Sketch.Entity e : sk.entities) {
            if (e.preview) continue;
            if ("point".equals(e.type)) continue;
            if ("circle".equals(e.type)) {
                float d = Math.abs((float) Math.sqrt(sqr(e.pts[0] - u)
                        + sqr(e.pts[1] - v)) - e.pts[2]);
                if (d < bestD) { bestD = d; best = e; bestSeg = -1; }
                continue;
            }
            List<float[]> segs = Renderer.entitySegments(e);
            for (int i = 0; i < segs.size(); i++) {
                float d = distToSeg(u, v, segs.get(i)[0], segs.get(i)[1],
                        segs.get(i)[2], segs.get(i)[3]);
                if (d < bestD) { bestD = d; best = e; bestSeg = i; }
            }
        }
        if (best == null) return null;
        float[] p = best.pts;
        if ("circle".equals(best.type)) {
            List<float[]> out = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                float a = 2f * (float) Math.PI * i / 32;
                out.add(new float[]{p[0] + p[2] * (float) Math.cos(a),
                        p[1] + p[2] * (float) Math.sin(a)});
            }
            return out;
        }
        if ("rect".equals(best.type)) {
            // тап по стороне i — ровно эта сторона: две ТОЧКИ, каждая float[2]
            int j = (bestSeg + 1) % 4;
            List<float[]> out = new ArrayList<>();
            out.add(new float[]{p[bestSeg * 2], p[bestSeg * 2 + 1]});
            out.add(new float[]{p[j * 2], p[j * 2 + 1]});
            return out;
        }
        if ("polyline".equals(best.type)) {
            List<float[]> out = new ArrayList<>();
            for (int i = 0; i + 1 < p.length; i += 2)
                out.add(new float[]{p[i], p[i + 1]});
            return out;
        }
        List<float[]> out = new ArrayList<>();
        out.add(new float[]{p[0], p[1]});
        out.add(new float[]{p[2], p[3]});
        return out;
    }


    /** Диалог-квадрат: 13 точек профиля, тап выбирает точку размещения. */
    private void showAnchorGrid(final Element el, final List<float[]> chain) {
        final AnchorGridView grid = new AnchorGridView(activity, el.profile);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (12 * density), (int) (8 * density),
                (int) (12 * density), (int) (8 * density));
        box.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (260 * density)));

        new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog)
                .setTitle("Точка профиля на кривой")
                .setView(box)
                .setPositiveButton("Создать", (d, w) -> {
                    scene.elements.remove(el);
                    Profile p = el.profile;
                    String tn = el.typeName;
                    Map<String, String> dm = el.dimExprs;
                    int idx = grid.selected;
                    pendingProfile = p;
                    pendingProfileType = tn;
                    pendingProfileDims = dm;
                    pendingPointIdx = idx;
                    placeProfile(chain, p, tn, dm, idx);
                })

                .setNegativeButton("Отмена", (d, w) -> {
                    scene.elements.remove(el);
                    scene.rebuild();
                })
                .show();
    }

    /** Квадрат с сечением профиля и 13 точками; тап — выбор точки. */
//

    // ---- сдвиг и удаление примитивов ----

    private void moveAt(float u, float v) {
        Sketch sk = activeSketch();
        if (sk == null) return;
        if (grabEntity == null) {
            Sketch.Entity target = nearestAny(sk, u, v);
            if (target == null) {
                Toast.makeText(activity, "Тап по примитиву",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            grabEntity = target;
            grabUv = new float[]{u, v};
            Toast.makeText(activity, "Захвачен — тап, куда сдвинуть",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        float du = u - grabUv[0], dv = v - grabUv[1];
        float[] p = grabEntity.pts;
        for (int i = 0; i + 1 < p.length; i += 2) { p[i] += du; p[i + 1] += dv; }
        Toast.makeText(activity, "Сдвинуто", Toast.LENGTH_SHORT).show();
        grabEntity = null;
        grabUv = null;
    }

    private void deleteAt(float u, float v) {
        Sketch sk = activeSketch();
        if (sk == null) return;
        Sketch.Entity target = nearestAny(sk, u, v);
        if (target == null) {
            Toast.makeText(activity, "Тап по примитиву", Toast.LENGTH_SHORT).show();
            return;
        }
        sk.entities.remove(target);
        Toast.makeText(activity, "Примитив удалён", Toast.LENGTH_SHORT).show();
    }

    /** Ближайший примитив любого типа (точка, круг, линия, контур). */
    private Sketch.Entity nearestAny(Sketch sk, float u, float v) {
        Sketch.Entity best = null;
        float bestD = GRAB;
        for (Sketch.Entity e : sk.entities) {
            if (e.preview) continue;
            float d;
            if ("point".equals(e.type))
                d = (float) Math.sqrt(sqr(e.pts[0] - u) + sqr(e.pts[1] - v));
            else if ("circle".equals(e.type))
                d = Math.abs((float) Math.sqrt(sqr(e.pts[0] - u)
                        + sqr(e.pts[1] - v)) - e.pts[2]);
            else {
                d = Float.MAX_VALUE;
                for (float[] s : Renderer.entitySegments(e)) {
                    float dd = distToSeg(u, v, s[0], s[1], s[2], s[3]);
                    if (dd < d) d = dd;
                }
            }
            if (d < bestD) { bestD = d; best = e; }
        }
        return best;
    }

    /** Рёбра силуэтов деталей в локальных координатах плоскости. */
    private List<float[]> silhouetteSegs() {
        List<float[]> out = new ArrayList<>();
        Plane3D pl = renderer.sketchPlane();
        if (pl == null) return out;
        List<float[]> corners = renderer.silhouetteCorners(pl);
        for (int i = 0; i + 3 < corners.size(); i += 4) {
            float[] a = corners.get(i), b = corners.get(i + 1);
            float[] c2 = corners.get(i + 2), d2 = corners.get(i + 3);
            out.add(new float[]{a[0], a[1], b[0], b[1]});
            out.add(new float[]{b[0], b[1], c2[0], c2[1]});
            out.add(new float[]{c2[0], c2[1], d2[0], d2[1]});
            out.add(new float[]{d2[0], d2[1], a[0], a[1]});
        }
        return out;
    }

    // ---- оверлей ввода ----

    /** Прозрачный слой над сценой: одиночный тап — рисование,
     *  перетаскивание одним пальцем — пан вида, два пальца — зум. */
    private class SketchOverlay extends View {

        private boolean panning;        // текущее касание — это пан
        private boolean skipTap;        // после щипка один палец не тапает
        private float lastX, lastY;
        private float[] lastUv;         // локальные координаты пальца (u, v)

        SketchOverlay(Context c) { super(c); }

        private float pinchDist(MotionEvent ev) {
            float dx = ev.getX(0) - ev.getX(1);
            float dy = ev.getY(0) - ev.getY(1);
            return (float) Math.sqrt(dx * dx + dy * dy);
        }

        @Override public boolean onTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getX();
                    downY = ev.getY();
                    lastX = downX;
                    lastY = downY;
                    panning = false;
                    lastPinch = 0;
                    lastUv = null;
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (ev.getPointerCount() >= 2) {
                        lastPinch = pinchDist(ev);
                        panning = false;    // второй палец отменяет пан
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (ev.getPointerCount() >= 2) {
                        float d = pinchDist(ev);
                        if (lastPinch > 0 && d > 0)
                            renderer.zoom(d / lastPinch);
                        lastPinch = d;
                        return true;
                    }
                    if (lastPinch > 0) return true;

                    float totalDx = ev.getX() - downX;
                    float totalDy = ev.getY() - downY;
                    float slop = TAP_SLOP * density;

                    if (panning || totalDx * totalDx + totalDy * totalDy > slop * slop) {
                        panning = true;
                        float[] uvNow = renderer.screenToPlane(ev.getX(), ev.getY(), renderer.getSurfaceW(), renderer.getSurfaceH());
                        if (uvNow != null && lastUv != null) {
                            renderer.panByUv(lastUv[0] - uvNow[0], lastUv[1] - uvNow[1]);
                        }
                        lastUv = uvNow;
                    } else {
                        // ИСПРАВЛЕНО: Динамический Preview подсветки контура при наведении
                        if ("Вырезать".equals(tool)) {
                            float[] uvHover = renderer.screenToPlane(ev.getX(), ev.getY(), renderer.getSurfaceW(), renderer.getSurfaceH());
                            if (uvHover != null) {
                                Sketch sk = activeSketch();
                                if (sk != null) {
                                    // Передаем точные float координаты uvHover[0] и uvHover[1]
                                    List<float[]> previewLoop = SketchContour.findLoop(sk, uvHover[0], uvHover[1], GRAB);
                                    renderer.setCutoutPreview(previewLoop);
                                }
                            }
                        }
                    }
                    return true;
                case MotionEvent.ACTION_POINTER_UP:
                    // поднят один из пальцев щипка — одиночные движения игнорируем
                    skipTap = true;
                    lastPinch = 0;
                    return true;
                case MotionEvent.ACTION_UP:
                    renderer.setCutoutPreview(null);
                    if (!panning && !skipTap && ev.getPointerCount() == 1) {
                        float ddx = ev.getX() - downX, ddy = ev.getY() - downY;
                        if (ddx * ddx + ddy * ddy < TAP_SLOP * TAP_SLOP
                                * density * density)
                            tapAt(ev.getX(), ev.getY());
                    }
                    panning = false;
                    skipTap = false;
                    lastPinch = 0;
                    lastUv = null;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    renderer.setCutoutPreview(null);
                    return true;
            }
            return true;
        }
    }
}
