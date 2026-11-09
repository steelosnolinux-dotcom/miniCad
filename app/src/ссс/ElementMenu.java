package ru.loftcontur.minicad;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import ru.loftcontur.minicad.core.Plane3D;
import ru.loftcontur.minicad.core.Vec3;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import ru.loftcontur.minicad.core.Anchor;
import ru.loftcontur.minicad.core.Element;
import ru.loftcontur.minicad.core.Expr;
import ru.loftcontur.minicad.core.Material;
import ru.loftcontur.minicad.core.Profile;
import ru.loftcontur.minicad.core.Scene;
import ru.loftcontur.minicad.gl.Renderer;

/**
 * Меню элементов: список деталей (редактирование и удаление), «＋ Деталь»,
 * «Новая модель», «Сохранить»/«Открыть», «Плоскости» + удаление плоскостей.
 * Редактирование детали: профиль по кривой — 3D-редактор (сечение + 13 точек),
 * обычные детали — сортамент.
 * Справа стопка кнопок: «✎» эскиз, «▦» плоскости по грани, «≣» список,
 * «⌖» вид, «✂» подгонка концов профиля по грани.
 */
public final class ElementMenu {

    public interface OnChanged { void onChanged(); }

    private final Renderer renderer;
    private final Activity activity;
    private final Scene scene;
    private final OnChanged onChanged;
    private final Map<String, String> labels = new HashMap<>();
    private final FrameLayout panel;
    private final ElementAdapter adapter;
    private final float density;
    private boolean open = false;
    private int selectedIdx = -1;
    private ListView list;
    private SketchEditor sketchEditor;
    private static int seq = 1;

    /** Последняя созданная/выбранная плоскость — на неё встанет новая деталь. */
    private String lastPlaneId;

    /** Режим подгонки профиля по грани. */
    private boolean fitMode = false;

    public ElementMenu(Activity activity, Scene scene,
                       Renderer renderer, OnChanged onChanged) {
        this.activity = activity;
        this.scene = scene;
        this.renderer = renderer;
        this.onChanged = onChanged;
        this.density = activity.getResources().getDisplayMetrics().density;

        ViewGroup root = (ViewGroup) activity.findViewById(android.R.id.content);

        LinearLayout side = new LinearLayout(activity);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams sideLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL);
        sideLp.rightMargin = (int) (16 * density);
        side.setLayoutParams(sideLp);

        TextView btnPlanesRound = roundButton("▦", 48, "#8A5A2B");
        btnPlanesRound.setOnClickListener(v -> {
            Renderer.FaceHit f = renderer.getLastFace();
            if (f == null) {
                PlanesDialog.show(activity, scene, renderer,
                        () -> {
                            renderer.syncScene();
                            if (onChanged != null) onChanged.onChanged();
                        });
            } else {
                FacePlaneDialog.show(activity, scene, f, planeId -> {
                    lastPlaneId = planeId;
                    renderer.syncScene();
                    if (onChanged != null) onChanged.onChanged();
                });
            }
        });

        TextView btnSketch = roundButton("✎", 48, "#6A4F9C");
        btnSketch.setOnClickListener(v -> {
            closePanel();
            sketchEditor().start();
        });

        TextView fab = roundButton("≣", 56, "#2A6BFF");
        fab.setTextSize(24);
        LinearLayout.LayoutParams fabLp = new LinearLayout.LayoutParams(
                (int) (56 * density), (int) (56 * density));
        fabLp.topMargin = (int) (10 * density);
        fabLp.bottomMargin = (int) (10 * density);
        fab.setLayoutParams(fabLp);
        fab.setOnClickListener(v -> toggle());

        TextView btnViewRound = roundButton("⌖", 48, "#2A6BFF");
        btnViewRound.setOnClickListener(v -> showViewDialog());

        TextView btnFit = roundButton("✂", 48, "#2E7D32");
        btnFit.setOnClickListener(v -> startFitMode());

        side.addView(btnSketch);
        side.addView(btnPlanesRound);
        side.addView(fab);
        side.addView(btnViewRound);
        side.addView(btnFit);
        root.addView(side);

        panel = new FrameLayout(activity);
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(Color.parseColor("#EE1B1F26"));
        panel.setBackground(panelBg);
        int third = Math.max(
                (int) (220 * density),
                activity.getResources().getDisplayMetrics().widthPixels / 3);
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                third, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START);
        panel.setLayoutParams(panelLp);
        panel.setVisibility(View.GONE);
        root.addView(panel);

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        panel.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        int hp = (int) (8 * density);

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setPadding(hp, hp, hp, hp);

        TextView btnAdd = smallButton("＋ Деталь", "#2A6BFF");
        btnAdd.setOnClickListener(v -> addElement());
        header.addView(btnAdd, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView btnNew = smallButton("Новая модель", "#5A4A3A");
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = hp;
        btnNew.setLayoutParams(nlp);
        btnNew.setOnClickListener(v -> confirmClear());
        header.addView(btnNew);

        content.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header2 = new LinearLayout(activity);
        header2.setOrientation(LinearLayout.HORIZONTAL);
        header2.setPadding(hp, 0, hp, hp);

        TextView btnSave = smallButton("Сохранить", "#2E7D32");
        btnSave.setOnClickListener(v -> saveModel());
        header2.addView(btnSave, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView btnLoad = smallButton("Открыть", "#6A4F9C");
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        llp.leftMargin = hp;
        btnLoad.setLayoutParams(llp);
        btnLoad.setOnClickListener(v -> loadModel());
        header2.addView(btnLoad);

        // --- НОВАЯ КНОПКА: ЭКСПОРТ СПЕЦИФИКАЦИИ ---
        TextView btnBom = smallButton("Отчет BOM", "#8A5A2B");
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        blp.leftMargin = hp;
        btnBom.setLayoutParams(blp);
        btnBom.setOnClickListener(v -> showBomDialog());
        header2.addView(btnBom);
        // ------------------------------------------

        content.addView(header2, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));


        // «Плоскости» и удаление плоскости
        LinearLayout planesRow = new LinearLayout(activity);
        planesRow.setOrientation(LinearLayout.HORIZONTAL);
        planesRow.setPadding(hp, 0, hp, 0);
        TextView btnPlanes = smallButton("Плоскости", "#8A5A2B");
        btnPlanes.setOnClickListener(v -> {
            panel.setVisibility(View.GONE);
            open = false;
            PlanesDialog.show(activity, scene, renderer, () -> {
                renderer.syncScene();
                if (onChanged != null) onChanged.onChanged();
            });
        });
        planesRow.addView(btnPlanes, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView btnDelPlanes = smallButton("🗑", "#8A3A3A");
        btnDelPlanes.setOnClickListener(v -> deletePlaneDialog());
        LinearLayout.LayoutParams dpl = new LinearLayout.LayoutParams(
                (int) (64 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
        dpl.leftMargin = hp;
        btnDelPlanes.setLayoutParams(dpl);
        planesRow.addView(btnDelPlanes);
        content.addView(planesRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        list = new ListView(activity);
        list.setVerticalScrollBarEnabled(false);
        list.setDivider(new ColorDrawable(Color.parseColor("#22FFFFFF")));
        list.setDividerHeight(1);
        adapter = new ElementAdapter();
        list.setAdapter(adapter);
        content.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }
    /**
     * Показывает диалоговое окно с готовой спецификацией и ведомостью распила материалов.
     */
    private void showBomDialog() {
        if (scene.elements.isEmpty()) {
            Toast.makeText(activity, "Проект пуст, нечего детализировать", Toast.LENGTH_SHORT).show();
            return;
        }

        String report = ru.loftcontur.minicad.core.BomExporter.generateReport(scene);

        android.widget.ScrollView scroll = new android.widget.ScrollView(activity);
        TextView tv = new TextView(activity);
        tv.setText(report);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE); // Моноширинный шрифт для красивых таблиц
        tv.setTextSize(13);
        tv.setTextColor(Color.WHITE);
        int pad = (int) (16 * density);
        tv.setPadding(pad, pad, pad, pad);
        scroll.addView(tv);

        new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog)
                .setTitle("Деталировка проекта")
                .setView(scroll)
                .setPositiveButton("Поделиться", (dialog, which) -> {
                    // Системная отправка текста спецификации в мессенджеры/почту
                    android.content.Intent sendIntent = new android.content.Intent();
                    sendIntent.setAction(android.content.Intent.ACTION_SEND);
                    sendIntent.putExtra(android.content.Intent.EXTRA_TEXT, report);
                    sendIntent.setType("text/plain");
                    activity.startActivity(android.content.Intent.createChooser(sendIntent, "Отправить спецификацию"));
                })
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private SketchEditor sketchEditor() {
        if (sketchEditor == null)
            sketchEditor = new SketchEditor(activity, scene, renderer,
                    onChanged == null ? null : onChanged::onChanged);
        return sketchEditor;
    }

    private TextView roundButton(String glyph, int dpSize, String color) {
        TextView b = new TextView(activity);
        b.setText(glyph);
        b.setTextSize(20);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Color.parseColor(color));
        b.setBackground(circle);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                (int) (dpSize * density), (int) (dpSize * density)));
        return b;
    }

    private void showViewDialog() {
        java.util.List<String> items = new ArrayList<>();
        items.add("Спереди"); items.add("Сзади");
        items.add("Справа"); items.add("Слева");
        items.add("Сверху"); items.add("Снизу");
        items.add("3D");
        Renderer.FaceHit f = renderer.getLastFace();
        if (f != null) items.add("Вид с выбранной грани");

        new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog)
                .setTitle("Вид")
                .setItems(items.toArray(new String[0]), (d, w) -> {
                    String s = items.get(w);
                    switch (s) {
                        case "Спереди": renderer.setView("front"); break;
                        case "Сзади":   renderer.setView("back"); break;
                        case "Справа":  renderer.setView("right"); break;
                        case "Слева":   renderer.setView("left"); break;
                        case "Сверху":  renderer.setView("top"); break;
                        case "Снизу":   renderer.setView("bottom"); break;
                        case "3D":      renderer.setView("3d"); break;
                        default:
                            if (f != null)
                                renderer.viewFromNormal(
                                        f.normal[0], f.normal[1], f.normal[2]);
                            break;
                    }
                })
                .show();
    }

    private TextView smallButton(String text, String color) {
        TextView b = new TextView(activity);
        b.setText(text);
        b.setTextSize(13);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(20 * density);
        bg.setColor(Color.parseColor(color));
        b.setBackground(bg);
        int pv = (int) (8 * density);
        b.setPadding(pv, pv, pv, pv);
        return b;
    }

    public void addElement() {
        Element e = new Element("EL_" + (seq++));
        e.material = Material.ST3;
        e.profile = Profile.rectTube(40, 40, 2, 3);
        e.typeName = "Труба прямоугольная";
        String plane = lastPlaneId != null && scene.planeDefs.containsKey(lastPlaneId)
                ? lastPlaneId : firstPlane();
        e.anchor = new Anchor(plane, "0", "0", "100", "NORMAL");
        scene.elements.add(e);
        scene.rebuild();
        if (onChanged != null) onChanged.onChanged();

        panel.setVisibility(View.GONE);
        open = false;
        SortamentDialog.show(activity, scene.elements, e, scene,
                (el, profile, type, dims, newName) -> {
                    if (newName.length() == 0) setLabel(el, type + " " + dims);
                    if (onChanged != null) onChanged.onChanged();
                });
    }

    private String firstPlane() {
        for (String k : scene.planeDefs.keySet()) return k;
        return "BOTTOM";
    }

    private void confirmClear() {
        new AlertDialog.Builder(activity)
                .setMessage("Очистить модель? Все детали будут удалены.")
                .setPositiveButton("Очистить", (d, w) -> {
                    scene.elements.clear();
                    scene.selectedId = null;
                    labels.clear();
                    selectedIdx = -1;
                    renderer.clearHighlight();
                    refresh();
                    if (onChanged != null) onChanged.onChanged();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void saveModel() {
        renderer.requestCapture(bmp ->
                ModelLibrary.showSaveDialog(activity, scene, bmp,
                        () -> { if (onChanged != null) onChanged.onChanged(); }));
    }

    private void loadModel() {
        ModelLibrary.showLoadDialog(activity, scene, () -> {
            selectedIdx = -1;
            labels.clear();
            renderer.clearHighlight();
            refresh();
            if (onChanged != null) onChanged.onChanged();
        });
    }

    private void closePanel() {
        if (panel != null) panel.setVisibility(View.GONE);
        open = false;
    }

    public void toggle() {
        open = !open;
        panel.setVisibility(open ? View.VISIBLE : View.GONE);
        refresh();
    }

    public void refresh() {
        adapter.notifyDataSetChanged();
    }

    public void setLabel(String elementId, String label) {
        labels.put(elementId, label);
    }

    public void setLabel(Element e, String label) {
        labels.put(e.id, label);
    }

    private static String idOf(Element e) {
        return e.name != null ? e.name : e.id;
    }

    // Изменяем логику получения названия детали в ElementMenu.java
    private String labelOf(Element e) {
        // Если пользователь вручную ввел осмысленное имя (Царга, Столешница, Ножка), берем его
        if (e.name != null && !e.name.isEmpty()) {
            return e.name;
        }

        // Если имени нет, автоматически собираем его по типу и текущим размерам
        if (e.typeName != null && e.dimExprs != null) {
            StringBuilder sb = new StringBuilder(e.typeName);
            sb.append(" ");
            boolean first = true;

            for (Map.Entry<String, String> entry : e.dimExprs.entrySet()) {
                String key = entry.getKey();
                if (key.contains("Радиус") || key.contains("Скругление")) {
                    continue; // Радиусы скругления в основную строку габаритов обычно не пишут
                }
                try {
                    // Вычисляем текущее значение выражения размера (например, "width" -> 1200)
                    float val = Expr.eval(entry.getValue(), scene.params);
                    if (!first) sb.append("×");

                    // Форматируем: убираем .0 если число целое
                    sb.append(val == Math.round(val) ? String.valueOf((int) val) : String.valueOf(val));
                    first = false;
                } catch (Exception ignored) {
                    // Если выражение еще недовычислено, подставим сырой текст формулы
                    if (!first) sb.append("×");
                    sb.append(entry.getValue());
                    first = false;
                }
            }
            return sb.toString();
        }

        return idOf(e);
    }

    // Изменяем логику получения параметров длины/толщины
    private String sizeText(Element e) {
        String label = labelOf(e).toLowerCase();
        // Если это лист, то главный параметр - Толщина, для профилей - Длина
        String what = label.contains("лист") ? "Толщина" : "Длина";
        String value;
        try {
            // Честно вычисляем текущую длину/толщину по выражению из анкера
            float v = Expr.eval(e.anchor.lengthExpr, scene.params);
            value = (v == Math.round(v) ? String.valueOf((int) v) : String.valueOf(v)) + " мм";
        } catch (Exception ex) {
            value = "—";
        }
        return what + ": " + value;
    }


    private boolean isSelected(Element e) {
        if (scene.selectedId == null) return false;
        return scene.selectedId.equals(e.id) || scene.selectedId.equals(idOf(e));
    }

    public void selectId(String id) {
        if (id == null || scene.elements == null) return;
        for (int i = 0; i < scene.elements.size(); i++) {
            Element e = scene.elements.get(i);
            if (id.equals(e.id) || id.equals(idOf(e))) {
                selectedIdx = i;
                if (open && list != null) list.smoothScrollToPosition(i);
                break;
            }
        }
        refresh();
    }


    private Bitmap drawIcon(Element e) {
        Bitmap bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setColor(Color.parseColor("#8AB6FF"));
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(8);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(Color.parseColor("#8AB6FF"));
        fill.setStyle(Paint.Style.FILL);
        fill.setAlpha(70);

        String t = e.typeName != null ? e.typeName.toLowerCase() : "";
        String l = t.length() == 0 ? labelOf(e).toLowerCase() : t;

        if (l.contains("кругл")) {
            c.drawCircle(48, 48, 32, stroke);
        } else if (l.contains("овальн")) {
            c.drawOval(new RectF(8, 20, 88, 76), stroke);
        } else if (l.contains("уголок")) {
            c.drawLine(16, 82, 84, 82, stroke);
            c.drawLine(16, 82, 16, 14, stroke);
        } else if (l.contains("швеллер")) {
            c.drawLine(24, 14, 24, 82, stroke);
            c.drawLine(24, 14, 84, 14, stroke);
            c.drawLine(24, 82, 84, 82, stroke);
        } else if (l.contains("тавр")) {
            c.drawLine(14, 22, 82, 22, stroke);
            c.drawLine(48, 22, 48, 84, stroke);
        } else if (l.contains("лист")) {
            c.drawRect(10, 30, 86, 66, fill);
            c.drawRect(10, 30, 86, 66, stroke);
        } else {
            c.drawRoundRect(new RectF(12, 12, 84, 84), 10, 10, stroke);
        }
        return bmp;
    }

    private Bitmap pencilIcon() {
        Bitmap bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.parseColor("#2A6BFF"));
        c.drawCircle(48, 48, 46, p);
        p.setColor(Color.WHITE);
        c.save();
        c.rotate(45, 48, 48);
        c.drawRect(40, 14, 56, 60, p);
        Path tip = new Path();
        tip.moveTo(40, 60);
        tip.lineTo(56, 60);
        tip.lineTo(48, 80);
        tip.close();
        c.drawPath(tip, p);
        c.restore();
        return bmp;
    }

    // ---- удаление деталей и плоскостей ----

    private Bitmap deleteIcon() {
        Bitmap bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.parseColor("#D04848"));
        c.drawCircle(48, 48, 46, p);
        p.setColor(Color.WHITE);
        p.setStrokeWidth(8);
        c.drawLine(30, 30, 66, 66, p);
        c.drawLine(66, 30, 30, 66, p);
        return bmp;
    }

    private void confirmDelete(Element e) {
        new AlertDialog.Builder(activity)
                .setMessage("Удалить деталь «" + labelOf(e) + "»?")
                .setPositiveButton("Удалить", (d, w) -> {
                    scene.elements.remove(e);
                    if (scene.selectedId != null
                            && (scene.selectedId.equals(e.id)
                            || scene.selectedId.equals(idOf(e))))
                        scene.selectedId = null;
                    renderer.clearHighlight();
                    scene.rebuild();
                    renderer.syncScene();
                    if (onChanged != null) onChanged.onChanged();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void deletePlaneDialog() {
        if (scene.planeDefs.isEmpty()) {
            Toast.makeText(activity, "Плоскостей нет", Toast.LENGTH_SHORT).show();
            return;
        }
        final java.util.List<String> names =
                new ArrayList<>(scene.planeDefs.keySet());
        new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog)
                .setTitle("Удалить плоскость")
                .setItems(names.toArray(new String[0]), (d, w) -> {
                    final String id = names.get(w);
                    int used = 0;
                    for (Element e : scene.elements)
                        if (id.equals(e.anchor.planeId)) used++;
                    String msg = "Удалить плоскость " + id
                            + (used > 0 ? " и " + used + " деталей на ней?" : "?");
                    new AlertDialog.Builder(activity)
                            .setMessage(msg)
                            .setPositiveButton("Удалить", (d2, w2) -> {
                                scene.planeDefs.remove(id);
                                for (int i = scene.elements.size() - 1; i >= 0; i--)
                                    if (id.equals(scene.elements.get(i).anchor.planeId))
                                        scene.elements.remove(i);
                                if (id.equals(renderer.sketchPlaneId))
                                    renderer.endSketch();
                                scene.rebuild();
                                renderer.syncScene();
                                if (onChanged != null) onChanged.onChanged();
                            })
                            .setNegativeButton("Отмена", null)
                            .show();
                })
                .show();
    }

    // ---- подгонка концов профиля по грани ----

    private void startFitMode() {
        Element el = findSelectedElement();
        if (el == null || el.anchor == null) {
            Toast.makeText(activity,
                    "Выделите деталь в списке", Toast.LENGTH_SHORT).show();
            return;
        }
        fitMode = true;
        Toast.makeText(activity,
                "Тап по грани другого объекта — торец обрежется или удлинится",
                Toast.LENGTH_LONG).show();
    }

    /** true — тап по грани обработан режимом подгонки. */
    public boolean onFacePicked(Renderer.FaceHit f) {
        if (!fitMode) return false;
        fitMode = false;
        if (f == null) {
            Toast.makeText(activity,
                    "Грань не выбрана — режим подгонки выключен",
                    Toast.LENGTH_SHORT).show();
            return true;
        }
        Element el = findSelectedElement();
        if (el == null) return true;
        Plane3D pl = scene.planes.get(el.anchor.planeId);
        if (pl == null) return true;
        Vec3 n = new Vec3(f.normal[0], f.normal[1], f.normal[2]);
        Vec3 c = new Vec3(f.center[0], f.center[1], f.center[2]);
        int done = SketchBody.fitByFace(scene, el, pl, n, c);
        renderer.syncScene();
        if (onChanged != null) onChanged.onChanged();
        String msg = done == 1 ? "Концы подогнаны по грани"
                : done == -1 ? "Грань параллельна оси профиля — выберите боковую или торцевую грань"
                : "Плоскость грани слишком далеко от профиля";
        Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
        return true;
    }

    /** id выделенного профиля в режиме подгонки — исключается из выбора грани. */
    public String fitTargetId() {
        if (!fitMode) return null;
        if (scene == null || scene.selectedId == null) return null;
        for (Element e : scene.elements) {
            String id = e.name != null ? e.name : e.id;
            if (scene.selectedId.equals(e.id) || scene.selectedId.equals(id))
                return e.id;
        }
        return null;
    }

    private Element findSelectedElement() {
        if (scene.selectedId == null) return null;
        for (Element e : scene.elements) {
            String id = e.name != null ? e.name : e.id;
            if (scene.selectedId.equals(e.id) || scene.selectedId.equals(id))
                return e;
        }
        return null;
    }

    // ---- редактирование профиля в 3D: сечение + 13 точек ----

    /** 3D-редактирование профиля по кривой: сечение и точка размещения из 13. */
    private void editProfilePlacement(final Element e) {
        final AnchorGridView grid = new AnchorGridView(activity, e.profile);
        grid.selected = e.anchorPoint;
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (12 * density);
        box.setPadding(pad, pad, pad, pad);

        final TextView typeView = new TextView(activity);
        typeView.setText("Сечение: " + (e.typeName != null ? e.typeName : "—"));
        typeView.setTextColor(Color.WHITE);
        typeView.setTextSize(15);
        int vp = (int) (8 * density);
        typeView.setPadding(vp, vp, vp, vp);
        GradientDrawable chip = new GradientDrawable();
        chip.setCornerRadius(8 * density);
        chip.setColor(Color.parseColor("#2A6BFF"));
        typeView.setBackground(chip);
        typeView.setOnClickListener(v -> SortamentDialog.show(activity,
                scene.elements, e, scene, (el, profile, type, dims, newName) -> {
                    if (type != null) {
                        el.typeName = type;
                        typeView.setText("Сечение: " + type);
                    }
                    if (profile != null) el.profile = profile;
                    scene.rebuild();
                }));

        TextView hint = new TextView(activity);
        hint.setText("Точка размещения профиля");
        hint.setTextColor(Color.parseColor("#9FB3C8"));
        hint.setTextSize(14);
        hint.setPadding(0, vp, 0, vp);

        box.addView(typeView);
        box.addView(hint);
        box.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (260 * density)));

        new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog)
                .setTitle("Профиль по кривой")
                .setView(box)
                .setPositiveButton("Применить", (d, w) -> {
                    SketchBody.setAnchorPoint(scene, e, grid.selected);
                    renderer.syncScene();
                    if (onChanged != null) onChanged.onChanged();
                    refresh();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private class ElementAdapter extends BaseAdapter {
        @Override public int getCount() {
            return scene.elements == null ? 0 : scene.elements.size();
        }
        @Override public Object getItem(int position) {
            return scene.elements.get(position);
        }
        @Override public long getItemId(int position) {
            return position;
        }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            Element e = scene.elements.get(position);

            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int pad = (int) (12 * density);
            row.setPadding(pad, pad, pad, pad);
            if (isSelected(e)) {
                GradientDrawable selBg = new GradientDrawable();
                selBg.setColor(Color.parseColor("#3300CC44"));
                selBg.setCornerRadius(8 * density);
                row.setBackground(selBg);
            }

            ImageView icon = new ImageView(activity);
            icon.setImageBitmap(drawIcon(e));
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                    (int) (48 * density), (int) (48 * density));
            ilp.rightMargin = (int) (12 * density);
            icon.setLayoutParams(ilp);
            row.addView(icon);

            LinearLayout col = new LinearLayout(activity);
            col.setOrientation(LinearLayout.VERTICAL);
            // Внутри метода getView класса ElementAdapter:
            TextView title = new TextView(activity);
            title.setText(labelOf(e)); // Теперь вернет "Царга" или "Труба 20×20×2"
            title.setTextColor(Color.WHITE);
            title.setTextSize(15);

            TextView sub = new TextView(activity);
            sub.setText(sizeText(e)); // Теперь вернет "Длина: 400 мм" или "Толщина: 18 мм"
            sub.setTextColor(Color.parseColor("#9FB3C8"));
            sub.setTextSize(13);

            col.addView(title);
            col.addView(sub);
            row.addView(col, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            row.setOnClickListener(v -> {
                selectedIdx = position;
                scene.selectedId = e.id;
                refresh();
                if (onChanged != null) onChanged.onChanged();
            });

            ImageView edit = new ImageView(activity);
            edit.setImageBitmap(pencilIcon());
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                    (int) (40 * density), (int) (40 * density));
            elp.leftMargin = (int) (8 * density);
            edit.setLayoutParams(elp);
            edit.setOnClickListener(v -> {
                panel.setVisibility(View.GONE);
                open = false;
                if (e.anchor != null && e.anchor.dirExpr != null
                        && e.anchor.dirExpr.startsWith("CURVE:"))
                    editProfilePlacement(e);
                else
                    SortamentDialog.show(activity, scene.elements, e, scene,
                            (el, profile, type, dims, newName) -> {
                                if (newName.length() == 0) setLabel(el, type + " " + dims);
                                if (onChanged != null) onChanged.onChanged();
                            });
            });
            row.addView(edit);

            ImageView del = new ImageView(activity);
            del.setImageBitmap(deleteIcon());
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    (int) (40 * density), (int) (40 * density));
            dlp.leftMargin = (int) (8 * density);
            del.setLayoutParams(dlp);
            del.setOnClickListener(v -> confirmDelete(e));
            row.addView(del);

            return row;
        }
    }
}
