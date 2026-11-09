package ru.loftcontur.minicad;

import java.util.LinkedHashMap;
import java.util.Map;

import android.app.Activity;
import android.graphics.Color;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;

import ru.loftcontur.minicad.core.Anchor;
import ru.loftcontur.minicad.core.Element;
import ru.loftcontur.minicad.core.Material;
import ru.loftcontur.minicad.core.Profile;
import ru.loftcontur.minicad.core.Scene;
import ru.loftcontur.minicad.gl.Renderer;

public class MainActivity extends Activity {

    private GLSurfaceView glView;
    private Renderer renderer;
    private final Scene scene = new Scene();
    private ElementMenu elementsMenu;
    private Button btnHeight, btnToggleUi;
    private View bottomBar;
    private boolean immersive = false;

    private static final int NONE = 0, ROTATE = 1, ZOOM = 2;
    private int mode = NONE;
    private float lastX, lastY, pinchDist;
    private float midX, midY;          // центр двух пальцев — для пана

    // данные для распознавания тапа
    private float downX, downY;
    private long downTime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildDemo();

        setContentView(R.layout.activity_main);

        glView = findViewById(R.id.gl_view);
        glView.setEGLContextClientVersion(2);
        renderer = new Renderer(this);
        renderer.scene = scene;
        glView.setRenderer(renderer);
        elementsMenu = new ElementMenu(this, scene, renderer, this::afterModelChange);

        btnHeight = findViewById(R.id.btn_height);
        btnToggleUi = findViewById(R.id.btn_toggle_ui);
        bottomBar = findViewById(R.id.bottom_bar);

        btnHeight.setOnClickListener(v -> {
            float h = scene.params.get("height") < 820f ? 900f : 750f;
            scene.setParam("height", h); // Внутри setParam уже есть rebuild()
            renderer.syncScene();
            btnHeight.setText(getString(R.string.btn_height, (int) h));
            elementsMenu.refresh(); // ОБЯЗАТЕЛЬНО обновляем список, чтобы "Длина: ... мм" пересчиталась!
        });

        // «Сортамент»: открытие диалога с выбором элемента
        findViewById(R.id.btn_sortament).setOnClickListener(v ->
                SortamentDialog.show(this, scene.elements, null, scene,
                        (el, profile, type, dims, newName) -> afterModelChange()));

        // «Элементы»: открытие/закрытие панели меню
        findViewById(R.id.btn_elements).setOnClickListener(v -> elementsMenu.toggle());

        findViewById(R.id.btn_params).setOnClickListener(v ->
                ParamsDialog.show(this, scene, this::afterModelChange));

        btnToggleUi.setOnClickListener(v -> applyImmersiveMode(!immersive));

        // скрываем системные панели сразу при запуске
        immersive = true;
        btnToggleUi.setTextColor(Color.parseColor("#4DA3FF"));
        btnToggleUi.post(() -> applyImmersiveMode(true));

        glView.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mode = ROTATE;
                    lastX = ev.getX();
                    lastY = ev.getY();
                    downX = ev.getX();
                    downY = ev.getY();
                    downTime = ev.getEventTime();
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (ev.getPointerCount() >= 2) {
                        mode = ZOOM;
                        pinchDist = dist(ev);
                        midX = (ev.getX(0) + ev.getX(1)) / 2;
                        midY = (ev.getY(0) + ev.getY(1)) / 2;
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (mode == ROTATE && ev.getPointerCount() == 1) {
                        renderer.orbit((ev.getX() - lastX) * 0.4f,
                                (ev.getY() - lastY) * 0.4f);
                        lastX = ev.getX();
                        lastY = ev.getY();
                    } else if (mode == ZOOM && ev.getPointerCount() >= 2) {
                        float d = dist(ev);
                        if (d > 0 && pinchDist > 0) renderer.zoom(d / pinchDist);
                        pinchDist = d;
                        // пан: сдвиг центра двух пальцев двигает сцену
                        float mx = (ev.getX(0) + ev.getX(1)) / 2;
                        float my = (ev.getY(0) + ev.getY(1)) / 2;
                        renderer.pan3D(mx - midX, my - midY);
                        midX = mx;
                        midY = my;
                    }
                    break;
                case MotionEvent.ACTION_UP: {
                    // короткое касание без сдвига — выбор детали
                    float density = getResources().getDisplayMetrics().density;
                    boolean tap = ev.getEventTime() - downTime < 300
                            && Math.hypot(ev.getX() - downX, ev.getY() - downY)
                            < 12 * density;
                    if (tap) onTap(ev);
                    mode = NONE;
                    break;
                }
                case MotionEvent.ACTION_POINTER_UP:
                    // палец от щипка поднят: оставшийся палец не вращает
                    // и не панорамирует — иначе вид дёрнется
                    mode = NONE;
                    break;
            }
            return true;
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && immersive) applySystemBars(true);
    }

    /** Тап по модели: выделить деталь (режим подгонки перехватывает первым). */
    /** Тап по модели: выделить деталь в 3D и автоматически синхронизировать с боковым списком */
    private void onTap(MotionEvent e) {
        String exclude = elementsMenu.fitTargetId();
        // Честный Moller-Trumbore находит точное имя детали по полигонам
        String id = renderer.pickFace(e.getX(), e.getY(), glView.getWidth(), glView.getHeight(), exclude);

        if (elementsMenu.onFacePicked(renderer.getLastFace())) return;

        if (id != null) {
            scene.selectedId = id;
            // АВТОМАТИЧЕСКИЙ СИНХРОН: передаем id в меню, чтобы список проскроллился к выбранной балке
            elementsMenu.selectId(id);
            renderer.clearHighlight();
        }
    }

    private float dist(MotionEvent e) {
        float dx = e.getX(0) - e.getX(1), dy = e.getY(0) - e.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** Скрыть/показать только системные шторку и кнопки навигации. */
    private void applyImmersiveMode(boolean on) {
        immersive = on;
        applySystemBars(on);
        btnToggleUi.setTextColor(on ? Color.parseColor("#4DA3FF") : Color.parseColor("#F2F2F2"));
    }

    private void applySystemBars(boolean hide) {
        View decor = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = decor.getWindowInsetsController();
            if (c == null) return;
            int bars = WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars();
            if (hide) {
                c.hide(bars);
                c.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else {
                c.show(bars);
            }
        } else {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            decor.setSystemUiVisibility(hide ? flags : View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    /** Демо: полноценный стол. Ножки до низа столешницы, рама между ними. */
    private void buildDemo() {
        scene.params.put("width", 1200f);
        scene.params.put("depth", 600f);
        scene.params.put("height", 750f);

        Scene.PlaneDef bottom = new Scene.PlaneDef("0", "0", "0", "0", "1", "0");
        bottom.u = "1,0,0";
        scene.planeDefs.put("BOTTOM", bottom);

        Scene.PlaneDef top = new Scene.PlaneDef("0", "height - 10", "0", "0", "1", "0");
        top.u = "1,0,0";
        scene.planeDefs.put("TOP", top);

        Scene.PlaneDef frame = new Scene.PlaneDef("0", "height - 30", "0", "0", "1", "0");
        frame.u = "1,0,0";
        scene.planeDefs.put("FRAME", frame);

        Map<String, String> sheetDims = new LinkedHashMap<>();
        sheetDims.put("Ширина W", "width");
        sheetDims.put("Глубина H", "depth");
        sheetDims.put("Скругление R", "0");
        Map<String, String> tubeDims = new LinkedHashMap<>();
        tubeDims.put("Ширина W", "40");
        tubeDims.put("Высота H", "40");
        tubeDims.put("Стенка T", "2");
        tubeDims.put("Радиус R", "3");

        Element topEl = new Element("TOP_SHEET");
        topEl.name = "Столешница";
        topEl.material = Material.ST3;
        topEl.profile = Profile.sheet(1200, 600, 0);
        topEl.typeName = "Лист";
        topEl.dimExprs = sheetDims;
        topEl.anchor = new Anchor("TOP", "0", "0", "10", "NORMAL");
        scene.elements.add(topEl);

        String[] su = {"-(width - 140)/2 + 20", "(width - 140)/2 - 20"};
        String[] sv = {"-(depth - 140)/2 + 20", "(depth - 140)/2 - 20"};
        for (int i = 0; i < 4; i++) {
            Element leg = new Element("LEG_" + i);
            leg.name = "Ножка " + (i + 1);
            leg.material = Material.ST3;
            leg.profile = Profile.rectTube(40, 40, 2, 3);
            leg.typeName = "Труба прямоугольная";
            leg.dimExprs = new LinkedHashMap<>(tubeDims);
            leg.anchor = new Anchor("BOTTOM", su[i / 2], sv[i % 2], "height - 10", "0,1,0");
            scene.elements.add(leg);
        }

        String[][] rails = {
                {"RAIL_A", "Продольная рейка 1", "-(width - 140)/2 + 40", "-(depth - 140)/2 + 20", "width - 220", "U"},
                {"RAIL_B", "Продольная рейка 2", "-(width - 140)/2 + 40", "(depth - 140)/2 - 20", "width - 220", "U"},
                {"RAIL_C", "Поперечная рейка 1", "-(width - 140)/2 + 20", "-(depth - 140)/2 + 40", "depth - 220", "V"},
                {"RAIL_D", "Поперечная рейка 2", "(width - 140)/2 - 20", "-(depth - 140)/2 + 40", "depth - 220", "V"},
        };
        for (String[] r : rails) {
            Element rail = new Element(r[0]);
            rail.name = r[1];
            rail.material = Material.ST3;
            rail.profile = Profile.rectTube(40, 40, 2, 3);
            rail.typeName = "Труба прямоугольная";
            rail.dimExprs = new LinkedHashMap<>(tubeDims);
            rail.anchor = new Anchor("FRAME", r[2], r[3], r[4], r[5]);
            scene.elements.add(rail);
        }

        scene.rebuild();
    }

    private void afterModelChange() {
        elementsMenu.refresh(); // Перерисовывает ListView, вызывая новые динамические labelOf()
        scene.rebuild();
        renderer.syncScene();
    }
}
