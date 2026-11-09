package ru.loftcontur.minicad.gl;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import ru.loftcontur.minicad.core.Element;
import ru.loftcontur.minicad.core.Expr;
import ru.loftcontur.minicad.core.Extruder;
import ru.loftcontur.minicad.core.Plane3D;
import ru.loftcontur.minicad.core.Scene;
import ru.loftcontur.minicad.core.Sketch;
import ru.loftcontur.minicad.core.Vec2;
import ru.loftcontur.minicad.core.Vec3;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

public class Renderer implements GLSurfaceView.Renderer {

    /** Результат выбора грани: деталь, индекс грани, её нормаль и центр. */
    public static class FaceHit {
        public String elementId;
        public int face;          // 0:-X 1:+X 2:-Y 3:+Y 4:-Z 5:+Z
        public float[] normal;    // единичный вектор нормали
        public float[] center;    // центр грани в мировых координатах
    }
    // Постоянный буфер для динамической служебной геометрии (оси, маркеры, подсвеченная грань)
    private final FloatBuffer dynamicGeometryBuffer = ByteBuffer.allocateDirect(1000 * 3 * 4) // запас на 1000 вершин
            .order(ByteOrder.nativeOrder()).asFloatBuffer();

    public Scene scene;
    private final Context ctx;
    // Изменяем цвета: грань теперь оранжевая прозрачная, а не глухая синяя
    private static final float[] FACE_COLOR = {1.0f, 0.55f, 0.16f};
    private static final float[] CUTOUT_PREVIEW_COLOR = {1.0f, 0.25f, 0.25f}; // Красный контур для отверстия

    private volatile List<float[]> cutoutPreviewLoop = null;

    public void setCutoutPreview(List<float[]> loop) {
        this.cutoutPreviewLoop = loop;
    }

    // основная программа (детали, сетка, подсветка граней)
    private int program, hMVP, hModel, hColor, hAlpha;

    // программа плоскостей и эскизов (aPos, uColor, uAlpha, uPointSize, uRound)
    private int planeProgram, phMVP, phPos, phColor, phAlpha, phPointSize, phRound;

    private final float[] proj = new float[16], view = new float[16],
            mvp = new float[16], model = new float[16];

    // позиция камеры (обновляется в computeCamera)
    private float camX, camY, camZ;

    public float camYaw = 45, camPitch = 30, camDist = 2500;
    public float targetX = 0, targetY = 350, targetZ = 0;

    /** GL-буферы. Карты не мутируются: syncScene/buildPlanes подменяют ссылку. */
    private volatile Map<String, Mesh> meshes = new LinkedHashMap<>();
    private volatile Map<String, float[]> planeQuads = new LinkedHashMap<>();
    private volatile Map<String, float[]> planeBorders = new LinkedHashMap<>();
    private Mesh grid;

    /** Показывать плоскости на сцене. */
    public boolean showPlanes = true;

    /** Скрытые по отдельности плоскости. Потокобезопасно. */
    public final Set<String> hiddenPlanes = new CopyOnWriteArraySet<>();

    // ---- режим эскиза ----
    /** Режим эскиза: камера на плоскости, ввод перехватывает оверлей. */
    public volatile boolean sketchMode = false;
    /** Плоскость активного эскиза. */
    public volatile String sketchPlaneId = null;

    private static final float[] SELECTED_COLOR = {0.25f, 0.95f, 0.35f};
   // private static final float[] FACE_COLOR = {0.16f, 0.42f, 1f};
    private static final float[] BORDER_COLOR = {0.96f, 0.92f, 0.72f};
    private static final float PLANE_ALPHA = 0.18f;
    private static final float[][] PLANE_COLORS = {
            {0.16f, 0.75f, 1f}, {1f, 0.62f, 0.16f}, {0.35f, 1f, 0.55f},
            {1f, 0.4f, 0.75f},  {0.85f, 0.8f, 0.2f}, {0.6f, 0.45f, 1f}};
    /** Цвет примитивов эскиза. */
    private static final float[] SKETCH_COLOR = {0.95f, 0.95f, 0.98f};
    /** Цвет незавершённого примитива. */
    private static final float[] PREVIEW_COLOR = {1f, 0.85f, 0.2f};

    private String hlElementId;
    private int hlFace = -1;
    private FaceHit lastFace;

    private boolean capturePending;
    private CaptureCallback captureCb;
    private int surfaceW, surfaceH;

    public interface CaptureCallback { void onCaptured(android.graphics.Bitmap bmp); }

    public void requestCapture(final CaptureCallback cb) {
        captureCb = cb;
        capturePending = true;
    }

    public Renderer(Context ctx) { this.ctx = ctx; }

    public void orbit(float dYaw, float dPitch) {
        camYaw -= dYaw;
        camPitch = Math.max(-89.9f, Math.min(89.9f, camPitch + dPitch));
    }

    public void zoom(float factor) {
        camDist = Math.max(50, Math.min(100000, camDist / factor));
    }

    /** Пан в 3D: сдвиг цели по экрану (dx, dy в пикселях экрана). */
    public void pan3D(float dX, float dY) {
        float k = camDist * 0.0015f;
        float rx = view[0], ry = view[4], rz = view[8];   // right камеры
        float ux = view[1], uy = view[5], uz = view[9];   // up камеры
        targetX += -dX * k * rx + dY * k * ux;
        targetY += -dX * k * ry + dY * k * uy;
        targetZ += -dX * k * rz + dY * k * uz;
    }


    // ---- виды ----

    public void setView(String v) {
        if ("top".equals(v))        { camYaw = 0;   camPitch = 89; }
        else if ("bottom".equals(v)){ camYaw = 0;   camPitch = -89; }
        else if ("right".equals(v)) { camYaw = 90;  camPitch = 0; }
        else if ("left".equals(v))  { camYaw = -90; camPitch = 0; }
        else if ("front".equals(v)) { camYaw = 0;   camPitch = 0; }
        else if ("back".equals(v))  { camYaw = 180; camPitch = 0; }
        else                        { camYaw = 45;  camPitch = 30; }
    }

    public void viewFromNormal(float nx, float ny, float nz) {
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-6f) return;
        camYaw = (float) Math.toDegrees(Math.atan2(nx, nz));
        // клампим, чтобы матрица вида не вырождалась (взгляд вдоль up)
        camPitch = Math.max(-89.5f, Math.min(89.5f,
                (float) Math.toDegrees(Math.asin(ny / len))));
    }

    // ---- выбор грани ----

    public FaceHit getLastFace() { return lastFace; }

    public void setHighlight(String elementId, int face) {
        hlElementId = elementId;
        hlFace = face;
    }

    public void clearHighlight() {
        hlElementId = null;
        hlFace = -1;
    }

    private static final float[][] FACE_NORMALS = {
            {-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};

    private static float cornerX(float[] b, int i) { return (i & 1) != 0 ? b[3] : b[0]; }
    private static float cornerY(float[] b, int i) { return (i & 2) != 0 ? b[4] : b[1]; }
    private static float cornerZ(float[] b, int i) { return (i & 4) != 0 ? b[5] : b[2]; }

    // ---- камера и лучи ----

    /** Пересчёт view/mvp по текущим углам; сохраняет позицию камеры. */
    private void computeCamera() {
        float yr = (float) Math.toRadians(camYaw), pr = (float) Math.toRadians(camPitch);
        camX = targetX + camDist * (float) Math.cos(pr) * (float) Math.sin(yr);
        camY = targetY + camDist * (float) Math.sin(pr);
        camZ = targetZ + camDist * (float) Math.cos(pr) * (float) Math.cos(yr);
        Matrix.setLookAtM(view, 0, camX, camY, camZ,
                targetX, targetY, targetZ, 0, 1, 0);
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0);
    }

    /** Направление луча из камеры через точку экрана (нормированный). */
    private float[] screenRay(float x, float y, int w, int h) {
        float ndcX = x / w * 2f - 1f;
        float ndcY = 1f - y / h * 2f;
        float[] inv = new float[16];
        Matrix.invertM(inv, 0, mvp, 0);
        float[] nearP = new float[4], farP = new float[4];
        Matrix.multiplyMV(nearP, 0, inv, 0, new float[]{ndcX, ndcY, -1f, 1f}, 0);
        Matrix.multiplyMV(farP, 0, inv, 0, new float[]{ndcX, ndcY, 1f, 1f}, 0);
        if (Math.abs(nearP[3]) < 1e-9f || Math.abs(farP[3]) < 1e-9f) return null;
        float dx = farP[0] / farP[3] - nearP[0] / nearP[3];
        float dy = farP[1] / farP[3] - nearP[1] / nearP[3];
        float dz = farP[2] / farP[3] - nearP[2] / nearP[3];
        float dl = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dl < 1e-9f) return null;
        return new float[]{dx / dl, dy / dl, dz / dl};
    }

    /**
     * Выбор грани касанием — честным raycast: луч из камеры через точку
     * тапа пересекается с шестью плоскостями AABB каждой детали; берётся
     * первое попадание вдоль луча среди граней, повёрнутых к камере.
     * Торец короткой трубы и верх столешницы выбираются точно.
     * Диагностика: тост показывает, какая грань реально выбрана.
     */
    public String pickFace(float x, float y, int w, int h) {
        return pickFace(x, y, w, h, null);
    }

    /** excludeId — элемент, чей бокс не участвует в выборе грани
     *  (режим подгонки: сам профиль не должен перехватывать тап). */
    /**
     * Выбор грани касанием — честный raycast по треугольникам меша.
     * Находит точное пересечение луча из камеры с геометрией любой детали (включая наклонные).
     */
    /**
     * Выбор грани касанием — честный raycast по треугольникам меша с использованием
     * алгоритма Моллера — Трамбора. Находит точное аналитическое пересечение луча из камеры
     * с геометрией любой детали (включая косые срезы, овальные и круглые трубы).
     */
    public String pickFace(float x, float y, int w, int h, String excludeId) {
        lastFace = null;
        clearHighlight();
        if (scene == null) return null;
        computeCamera();

        // 1. Генерируем луч из камеры через точку экрана
        float[] ray = screenRay(x, y, w, h);
        if (ray == null) return null;
        Vec3 rayDir = new Vec3(ray[0], ray[1], ray[2]).norm();
        Vec3 camOrigin = new Vec3(camX, camY, camZ);

        String bestElement = null;
        float bestT = Float.MAX_VALUE;
        Vec3 bestNormal = null;
        Vec3 bestCenter = null;
        int bestFaceIndex = 0;

        // 2. Итерируем по всем элементам сцены
        // 2. Итерируем по всем элементам сцены
        for (Element e : snapshot(scene)) {
            if (excludeId != null && e.id.equals(excludeId)) continue;
            Extruder.MeshData md = scene.meshes.get(e.id);
            if (md == null || md.pos == null || md.idx == null) continue;

            // --- BVH УРОВЕНЬ: Быстрое отсечение по закэшированному AABB ---
            if (!rayAABBIntersection(camOrigin, rayDir, md.min, md.max)) {
                continue; // Луч пролетел мимо этой трубы — пропускаем тысячи её полигонов!
            }

            // --- МЕШ УРОВЕНЬ: Если луч попал в габариты, запускаем точный Moller-Trumbore ---
            float[] pos = md.pos;
            int[] idx = md.idx;

            for (int i = 0; i < idx.length; i += 3) {
                int i0 = idx[i] * 3;
                int i1 = idx[i + 1] * 3;
                int i2 = idx[i + 2] * 3;

                // ИСПРАВЛЕНО: Если вершины треугольника схлопнуты экструдером, значит это вырез.
                // Пропускаем математику пересечения луча, не позволяя сбросить pickedId всей балки!
                if (i0 == i1 || i1 == i2 || i0 == i2) continue;

                Vec3 v0 = new Vec3(pos[i0], pos[i0 + 1], pos[i0 + 2]);
                Vec3 v1 = new Vec3(pos[i1], pos[i1 + 1], pos[i1 + 2]);
                Vec3 v2 = new Vec3(pos[i2], pos[i2 + 1], pos[i2 + 2]);

                // Направленные ребра треугольника
                Vec3 edge1 = v1.sub(v0);
                Vec3 edge2 = v2.sub(v0);

                // Геометрическая нормаль текущего полигона
                Vec3 normal = edge1.cross(edge2).norm();

                // Backface culling: если полигон отвернут от камеры, игнорируем его
                if (normal.dot(rayDir) >= 0) continue;

                // --- Алгоритм Моллера — Трамбора ---
                Vec3 pvec = rayDir.cross(edge2);
                float det = edge1.dot(pvec);

                // Если детерминант близок к нулю, луч лежит в плоскости треугольника
                if (Math.abs(det) < 1e-7f) continue;
                float invDet = 1f / det;

                Vec3 tvec = camOrigin.sub(v0);
                float uCoord = tvec.dot(pvec) * invDet;
                if (uCoord < 0f || uCoord > 1f) continue;

                Vec3 qvec = tvec.cross(edge1);
                float vCoord = rayDir.dot(qvec) * invDet;
                if (vCoord < 0f || uCoord + vCoord > 1f) continue;

                // Дистанция t от камеры до точки пересечения
                float t = edge2.dot(qvec) * invDet;

                // Проверяем, является ли это пересечение ближайшим к камере
                if (t > 0.1f && t < bestT) {
                    bestT = t;
                    bestElement = e.id;
                    bestNormal = normal;

                    // Вычисляем точный центр треугольника (или точку пересечения) для привязки плоскости
                    bestCenter = v0.add(v1).add(v2).mul(1f / 3f);

                    // Определяем условный индекс грани для системы подсветки (0:-X, 1:+X, 2:-Y, 3:+Y, 4:-Z, 5:+Z)
                    if (Math.abs(normal.x) > Math.abs(normal.y) && Math.abs(normal.x) > Math.abs(normal.z)) {
                        bestFaceIndex = normal.x > 0 ? 1 : 0;
                    } else if (Math.abs(normal.y) > Math.abs(normal.x) && Math.abs(normal.y) > Math.abs(normal.z)) {
                        bestFaceIndex = normal.y > 0 ? 3 : 2;
                    } else {
                        bestFaceIndex = normal.z > 0 ? 5 : 4;
                    }
                }
            }
        }

        // 3. Если пересечение найдено, формируем FaceHit объект
        if (bestElement != null && bestNormal != null && bestCenter != null) {
            FaceHit hit = new FaceHit();
            hit.elementId = bestElement;
            hit.face = bestFaceIndex;
            hit.normal = new float[]{bestNormal.x, bestNormal.y, bestNormal.z};
            hit.center = new float[]{bestCenter.x, bestCenter.y, bestCenter.z};
            lastFace = hit;

            setHighlight(bestElement, bestFaceIndex);
            toastHit(bestElement, bestFaceIndex, hit.center);
            return bestElement;
        }
        return null;
    }
    /**
     * Быстрая проверка пересечения луча с габаритным контейнером AABB (Алгоритм Смита).
     * Позволяет мгновенно отсечь детали, в которые луч гарантированно не попал.
     */
    private boolean rayAABBIntersection(Vec3 origin, Vec3 dir, float[] min, float[] max) {
        float tmin = (min[0] - origin.x) / (Math.abs(dir.x) > 1e-6f ? dir.x : 1e-6f);
        float tmax = (max[0] - origin.x) / (Math.abs(dir.x) > 1e-6f ? dir.x : 1e-6f);
        if (tmin > tmax) { float tmp = tmin; tmin = tmax; tmax = tmp; }

        float tymin = (min[1] - origin.y) / (Math.abs(dir.y) > 1e-6f ? dir.y : 1e-6f);
        float tymax = (max[1] - origin.y) / (Math.abs(dir.y) > 1e-6f ? dir.y : 1e-6f);
        if (tymin > tymax) { float tmp = tymin; tymin = tymax; tymax = tmp; }

        if ((tmin > tymax) || (tymin > tmax)) return false;
        if (tymin > tmin) tmin = tymin;
        if (tymax < tmax) tmax = tymax;

        float tzmin = (min[2] - origin.z) / (Math.abs(dir.z) > 1e-6f ? dir.z : 1e-6f);
        float tzmax = (max[2] - origin.z) / (Math.abs(dir.z) > 1e-6f ? dir.z : 1e-6f);
        if (tzmin > tzmax) { float tmp = tzmin; tzmin = tzmax; tzmax = tmp; }

        if ((tmin > tzmax) || (tzmin > tmax)) return false;

        return tmax > 0; // Пересечение есть, если коробка находится впереди камеры
    }


    /** Диагностика: что реально выбрал тап. Убрать вызов, когда разберёмся. */
    private void toastHit(String elementId, int face, float[] c) {
        String name = elementId;
        for (Element e : scene.elements)
            if (e.id.equals(elementId) && e.name != null) name = e.name;
        final String msg = "Грань: " + name + " [" + face + "] центр ("
                + Math.round(c[0]) + ", " + Math.round(c[1]) + ", " + Math.round(c[2]) + ")";
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                android.widget.Toast.makeText(ctx, msg,
                        android.widget.Toast.LENGTH_SHORT).show());
    }

    private Element findElement(String id) {
        for (Element e : scene.elements) if (e.id.equals(id)) return e;
        return null;
    }

    /** Снимок списка элементов: GL-поток не итерирует живой список. */
    private static List<Element> snapshot(Scene s) {
        try {
            return new ArrayList<>(s.elements);
        } catch (Exception ex) {
            return new ArrayList<>();
        }
    }

    private static int compile(int type, String src) {
        int sh = GLES20.glCreateShader(type);
        GLES20.glShaderSource(sh, src);
        GLES20.glCompileShader(sh);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(sh);
            GLES20.glDeleteShader(sh);
            throw new RuntimeException(log);
        }
        return sh;
    }

    @Override public void onSurfaceCreated(GL10 gl, EGLConfig cfg) {
        program = GLUtil.loadProgram(GLUtil.VERT_SRC, GLUtil.FRAG_SRC);
        hMVP   = GLES20.glGetUniformLocation(program, "uMVP");
        hModel = GLES20.glGetUniformLocation(program, "uModel");
        hColor = GLES20.glGetUniformLocation(program, "uColor");
        hAlpha = GLES20.glGetUniformLocation(program, "uAlpha");
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);

        String pvs =
                "uniform mat4 uMVP;\n" +
                        "uniform float uPointSize;\n" +
                        "attribute vec3 aPos;\n" +
                        "void main() {\n" +
                        "  gl_Position = uMVP * vec4(aPos, 1.0);\n" +
                        "  gl_PointSize = uPointSize;\n" +
                        "}\n";
        String pfs =
                "precision mediump float;\n" +
                        "uniform vec3 uColor;\n" +
                        "uniform float uAlpha;\n" +
                        "uniform float uRound;\n" +
                        "void main() {\n" +
                        "  if (uRound > 0.5) {\n" +
                        "    vec2 c = gl_PointCoord - vec2(0.5);\n" +
                        "    if (dot(c, c) > 0.25) discard;\n" +
                        "  }\n" +
                        "  gl_FragColor = vec4(uColor, uAlpha);\n" +
                        "}\n";

        planeProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(planeProgram, compile(GLES20.GL_VERTEX_SHADER, pvs));
        GLES20.glAttachShader(planeProgram, compile(GLES20.GL_FRAGMENT_SHADER, pfs));
        GLES20.glLinkProgram(planeProgram);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(planeProgram, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(planeProgram);
            GLES20.glDeleteProgram(planeProgram);
            throw new RuntimeException(log);
        }
        phMVP      = GLES20.glGetUniformLocation(planeProgram, "uMVP");
        phPos      = GLES20.glGetAttribLocation(planeProgram, "aPos");
        phColor    = GLES20.glGetUniformLocation(planeProgram, "uColor");
        phAlpha    = GLES20.glGetUniformLocation(planeProgram, "uAlpha");
        phPointSize = GLES20.glGetUniformLocation(planeProgram, "uPointSize");
        phRound    = GLES20.glGetUniformLocation(planeProgram, "uRound");

        grid = buildGrid(3000, 100);
        syncScene();
    }

    @Override public void onSurfaceChanged(GL10 gl, int w, int h) {
        GLES20.glViewport(0, 0, w, h);
        surfaceW = w;
        surfaceH = h;
        Matrix.perspectiveM(proj, 0, 45, (float) w / h, 1, 200000);
    }

    /** Размеры поверхности в пикселях — для пересчёта «экран → плоскость». */
    public int getSurfaceW() { return surfaceW; }
    public int getSurfaceH() { return surfaceH; }

    @Override public void onDrawFrame(GL10 gl) {
        computeCamera();
        Matrix.setIdentityM(model, 0);
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0);
        Matrix.multiplyMM(mvp, 0, mvp, 0, model, 0);

        GLES20.glClearColor(0.13f, 0.14f, 0.16f, 1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        GLES20.glUseProgram(program);
        GLES20.glUniformMatrix4fv(hMVP, 1, false, mvp, 0);
        GLES20.glUniformMatrix4fv(hModel, 1, false, model, 0);
        GLES20.glUniform1f(hAlpha, 1f);

        GLES20.glUniform3f(hColor, 0.30f, 0.31f, 0.34f);
        grid.draw(program);

        // локальная ссылка: за кадр карта не подменится посередине итерации
        Map<String, Mesh> meshNow = meshes;
        if (scene != null)
            for (Element e : snapshot(scene)) {
                Mesh m = meshNow.get(e.id);
                if (m == null) continue;
                float[] c = isSelected(e) ? SELECTED_COLOR : colorFor(e);
                GLES20.glUniform3f(hColor, c[0], c[1], c[2]);
                m.draw(program);
            }

        drawHighlight();
        if (showPlanes) drawPlanes();
        // Отрисовка превью области выреза выдавливанием
        // Отрисовка превью области выреза выдавливанием
        List<float[]> cutLoop = cutoutPreviewLoop;
        Plane3D skPlane = sketchPlane();
        if (cutLoop != null && skPlane != null && !cutLoop.isEmpty()) {
            GLES20.glDisable(GLES20.GL_DEPTH_TEST);
            GLES20.glUseProgram(planeProgram);
            GLES20.glUniformMatrix4fv(phMVP, 1, false, mvp, 0);
            GLES20.glUniform3f(phColor, CUTOUT_PREVIEW_COLOR[0], CUTOUT_PREVIEW_COLOR[1], CUTOUT_PREVIEW_COLOR[2]);
            GLES20.glUniform1f(phAlpha, 1.0f);
            GLES20.glLineWidth(4f);

            float[] verts = new float[cutLoop.size() * 6];
            for (int i = 0; i < cutLoop.size(); i++) {
                float[] p0 = cutLoop.get(i);
                float[] p1 = cutLoop.get((i + 1) % cutLoop.size());
                float[] w0 = worldOf(skPlane, p0[0], p0[1]);
                float[] w1 = worldOf(skPlane, p1[0], p1[1]);
                System.arraycopy(w0, 0, verts, i * 6, 3);
                System.arraycopy(w1, 0, verts, i * 6 + 3, 3);
            }
            drawRaw(verts, GLES20.GL_LINES, 1f);

            // ВАЖНО: Возвращаем настройки OpenGL в исходное состояние, чтобы не ломать отрисовку балок!
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
            GLES20.glUseProgram(program);
        }


        drawSketches();

        if (capturePending) {
            capturePending = false;
            final CaptureCallback cb = captureCb;
            captureCb = null;
            if (cb != null && surfaceW > 0 && surfaceH > 0) {
                java.nio.ByteBuffer buf =
                        java.nio.ByteBuffer.allocateDirect(surfaceW * surfaceH * 4);
                GLES20.glReadPixels(0, 0, surfaceW, surfaceH,
                        GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf);
                android.graphics.Bitmap raw = android.graphics.Bitmap.createBitmap(
                        surfaceW, surfaceH, android.graphics.Bitmap.Config.ARGB_8888);
                buf.rewind();
                raw.copyPixelsFromBuffer(buf);
                android.graphics.Matrix flip = new android.graphics.Matrix();
                flip.setScale(1, -1);
                flip.postTranslate(0, surfaceH);
                android.graphics.Bitmap flipped = android.graphics.Bitmap.createBitmap(
                        raw, 0, 0, surfaceW, surfaceH, flip, true);
                int tw = 320;
                int th = Math.max(1, Math.round(
                        flipped.getHeight() * (tw / (float) flipped.getWidth())));
                final android.graphics.Bitmap thumb =
                        android.graphics.Bitmap.createScaledBitmap(flipped, tw, th, true);
                new android.os.Handler(android.os.Looper.getMainLooper())
                        .post(() -> cb.onCaptured(thumb));
            }
        }
    }

    /** Синий квад поверх выбранной грани без генерации мусора в native-памяти. */
    private void drawHighlight() {
        if (hlElementId == null || hlFace < 0 || scene == null) return;
        Element e = findElement(hlElementId);
        if (e == null) return;
        if (e.anchor != null && e.anchor.dirExpr != null && e.anchor.dirExpr.startsWith("CURVE:")) return;

        float[] box = aabbOf(e);
        if (box == null) return;

        float[] n = FACE_NORMALS[hlFace];
        float off = 1.5f;
        float[] pos = new float[18];
        int axis = hlFace / 2;
        boolean positive = (hlFace % 2) == 1;
        int a2 = (axis + 1) % 3, a3 = (axis + 2) % 3;
        float p0 = positive ? box[axis + 3] : box[axis];
        float c2a = (box[a2] + box[a2 + 3]) / 2;
        float c3a = (box[a3] + box[a3 + 3]) / 2;
        float h2 = (box[a2 + 3] - box[a2]) / 2 + off;
        float h3 = (box[a3 + 3] - box[a3]) / 2 + off;
        float[] c = new float[3];
        int k = 0;
        for (int[] s : new int[][]{{-1, -1}, {1, -1}, {1, 1}, {-1, 1}}) {
            c[axis] = p0;
            c[a2] = c2a + s[0] * h2;
            c[a3] = c3a + s[1] * h3;
            c[0] += n[0] * off; c[1] += n[1] * off; c[2] += n[2] * off;
            pos[k] = c[0]; pos[k + 1] = c[1]; pos[k + 2] = c[2];
            k += 3;
        }

        float[] quad = new float[18];
        int[] tri = {0, 1, 2, 0, 2, 3};
        for (int i = 0; i < 6; i++) {
            int ci = tri[i] * 3;
            quad[i * 3] = pos[ci];
            quad[i * 3 + 1] = pos[ci + 1];
            quad[i * 3 + 2] = pos[ci + 2];
        }

        // Рендерим напрямую через повторно используемый буфер программы плоскостей
        GLES20.glUniform3f(phColor, FACE_COLOR[0], FACE_COLOR[1], FACE_COLOR[2]);
        GLES20.glUniform1f(phAlpha, 0.45f); // Сделали грань полупрозрачной!

        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        drawRaw(quad, GLES20.GL_TRIANGLES, 1f);

        GLES20.glDisable(GLES20.GL_BLEND);
    }

    // ---- визуализация плоскостей ----

    private static float[] planeColor(String id) {
        int i = Math.abs(id.hashCode()) % PLANE_COLORS.length;
        return PLANE_COLORS[i];
    }

    private float[] sceneCenter() {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        boolean any = false;
        for (Element e : snapshot(scene)) {
            float[] b = aabbOf(e);
            if (b == null) continue;
            any = true;
            if (b[0] < minX) minX = b[0]; if (b[3] > maxX) maxX = b[3];
            if (b[1] < minY) minY = b[1]; if (b[4] > maxY) maxY = b[4];
            if (b[2] < minZ) minZ = b[2]; if (b[5] > maxZ) maxZ = b[5];
        }
        if (!any) return new float[]{0, 350, 0};
        return new float[]{(minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2};
    }

    private float[] sceneHalf() {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        boolean any = false;
        for (Element e : snapshot(scene)) {
            float[] b = aabbOf(e);
            if (b == null) continue;
            any = true;
            if (b[0] < minX) minX = b[0]; if (b[3] > maxX) maxX = b[3];
            if (b[1] < minY) minY = b[1]; if (b[4] > maxY) maxY = b[4];
            if (b[2] < minZ) minZ = b[2]; if (b[5] > maxZ) maxZ = b[5];
        }
        if (!any) return new float[]{400, 400, 400};
        return new float[]{(maxX - minX) / 2 + 50,
                (maxY - minY) / 2 + 50,
                (maxZ - minZ) / 2 + 50};
    }

    private static float[] cross(float[] a, float[] b) {
        return new float[]{
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]};
    }

    private static void normalize(float[] v) {
        float l = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (l < 1e-6f) { v[0] = 0; v[1] = 1; v[2] = 0; return; }
        v[0] /= l; v[1] /= l; v[2] /= l;
    }

    private float[] parseU(Scene.PlaneDef d, float[] n) {
        try {
            if (d.u != null && d.u.length() > 0) {
                String[] parts = d.u.split(",");
                if (parts.length == 3) {
                    float[] u = new float[]{
                            Expr.eval(parts[0].trim(), scene.params),
                            Expr.eval(parts[1].trim(), scene.params),
                            Expr.eval(parts[2].trim(), scene.params)};
                    normalize(u);
                    return u;
                }
            }
        } catch (Exception ignored) {}
        float[] ref = Math.abs(n[1]) > 0.9f
                ? new float[]{1, 0, 0} : new float[]{0, 1, 0};
        float[] u = cross(ref, n);
        normalize(u);
        return u;
    }

    private void buildPlanes() {
        Map<String, float[]> newQuads = new LinkedHashMap<>();
        Map<String, float[]> newBorders = new LinkedHashMap<>();
        if (scene == null) {
            planeQuads = newQuads;
            planeBorders = newBorders;
            return;
        }
        float[] bc = sceneCenter();
        float[] bh = sceneHalf();

        for (Map.Entry<String, Scene.PlaneDef> en : scene.planeDefs.entrySet()) {
            try {
                Scene.PlaneDef d = en.getValue();
                float ox = Expr.eval(d.ox, scene.params);
                float oy = Expr.eval(d.oy, scene.params);
                float oz = Expr.eval(d.oz, scene.params);
                float[] n = new float[]{
                        Expr.eval(d.nx, scene.params),
                        Expr.eval(d.ny, scene.params),
                        Expr.eval(d.nz, scene.params)};
                normalize(n);
                float[] u = parseU(d, n);
                float[] v = cross(n, u);
                normalize(v);

                float hu = Math.abs(u[0]) * bh[0] + Math.abs(u[1]) * bh[1]
                        + Math.abs(u[2]) * bh[2];
                float hv = Math.abs(v[0]) * bh[0] + Math.abs(v[1]) * bh[1]
                        + Math.abs(v[2]) * bh[2];

                // центр листа: основание перпендикуляра из центра модели
                // на плоскость: foot = bc - n * ((bc - o) · n)
                float dcx = bc[0] - ox, dcy = bc[1] - oy, dcz = bc[2] - oz;
                float dn = dcx * n[0] + dcy * n[1] + dcz * n[2];
                float px = bc[0] - n[0] * dn, py = bc[1] - n[1] * dn,
                        pz = bc[2] - n[2] * dn;

                float[] su = {-1, -1, 1, 1};
                float[] sv = {-1, 1, 1, -1};
                float[][] c = new float[4][3];
                for (int i = 0; i < 4; i++) {
                    c[i][0] = px + u[0] * hu * su[i] + v[0] * hv * sv[i];
                    c[i][1] = py + u[1] * hu * su[i] + v[1] * hv * sv[i];
                    c[i][2] = pz + u[2] * hu * su[i] + v[2] * hv * sv[i];
                }

                float[] pos = new float[18];
                int[] quad = {0, 1, 2, 0, 2, 3};
                for (int i = 0; i < 6; i++) {
                    int ci = quad[i];
                    pos[i * 3] = c[ci][0];
                    pos[i * 3 + 1] = c[ci][1];
                    pos[i * 3 + 2] = c[ci][2];
                }
                newQuads.put(en.getKey(), pos);

                float[] lp = new float[24];
                int[] ring = {0, 1, 1, 2, 2, 3, 3, 0};
                for (int i = 0; i < 8; i++) {
                    int ci = ring[i];
                    lp[i * 3] = c[ci][0];
                    lp[i * 3 + 1] = c[ci][1];
                    lp[i * 3 + 2] = c[ci][2];
                }
                newBorders.put(en.getKey(), lp);
            } catch (Exception ignored) {
                // плоскость с некорректным выражением просто не рисуется
            }
        }

        planeQuads = newQuads;
        planeBorders = newBorders;
    }

    private static FloatBuffer toFloatBuf(float[] a) {
        FloatBuffer b = ByteBuffer.allocateDirect(a.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(a).position(0);
        return b;
    }

    /** Отрисовка массива вершин программой плоскостей. */
    /** Отрисовка массива вершин программой плоскостей с использованием переиспользуемого буфера. */
    /** Отрисовка массива вершин программой плоскостей с использованием изолированного буфера. */
    private void drawRaw(float[] verts, int mode, float pointSize) {
        if (verts == null || verts.length == 0) return;

        java.nio.ByteBuffer byteBuf = java.nio.ByteBuffer.allocateDirect(verts.length * 4);
        byteBuf.order(java.nio.ByteOrder.nativeOrder());
        java.nio.FloatBuffer tempBuffer = byteBuf.asFloatBuffer();
        tempBuffer.put(verts).position(0);

        GLES20.glEnableVertexAttribArray(phPos);
        GLES20.glVertexAttribPointer(phPos, 3, GLES20.GL_FLOAT, false, 0, tempBuffer);

        GLES20.glUniform1f(phPointSize, pointSize);
        GLES20.glUniform1f(phRound, pointSize > 1.5f ? 1f : 0f);
        GLES20.glDrawArrays(mode, 0, verts.length / 3);

        GLES20.glDisableVertexAttribArray(phPos);

        // ИСПРАВЛЕНО: Принудительно отвязываем буфер от контекста OpenGL,
        // чтобы его куски не улетали в левый нижний угол при следующем кадре
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
    }




    private void drawPlanes() {
        Map<String, float[]> quads = planeQuads;
        Map<String, float[]> borders = planeBorders;
        if (quads.isEmpty()) return;
        GLES20.glUseProgram(planeProgram);
        GLES20.glUniformMatrix4fv(phMVP, 1, false, mvp, 0);
        GLES20.glDepthMask(false);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glLineWidth(2.5f);

        for (Map.Entry<String, float[]> e : quads.entrySet()) {
            if (hiddenPlanes.contains(e.getKey())) continue;
            float[] border = borders.get(e.getKey());
            if (border != null) {
                GLES20.glUniform3f(phColor,
                        BORDER_COLOR[0], BORDER_COLOR[1], BORDER_COLOR[2]);
                GLES20.glUniform1f(phAlpha, 1f);
                drawRaw(border, GLES20.GL_LINES, 1f);
            }
            float[] col = planeColor(e.getKey());
            GLES20.glUniform3f(phColor, col[0], col[1], col[2]);
            GLES20.glUniform1f(phAlpha, PLANE_ALPHA);
            drawRaw(e.getValue(), GLES20.GL_TRIANGLES, 1f);
        }

        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDepthMask(true);
        GLES20.glUseProgram(program);
    }

    // ---- эскизы ----

    /** Включить режим эскиза на плоскости: камера вдоль нормали, цель — центр. */
    public void beginSketch(String planeId) {
        Plane3D pl = scene != null ? scene.planes.get(planeId) : null;
        if (pl == null) return;
        sketchPlaneId = planeId;
        sketchMode = true;
        targetX = pl.origin.x;
        targetY = pl.origin.y;
        targetZ = pl.origin.z;
        viewFromNormal(pl.normal.x, pl.normal.y, pl.normal.z);
        float[] half = sceneHalf();
        float maxHalf = Math.max(half[0], Math.max(half[1], half[2]));
        camDist = Math.max(800, maxHalf * 2.2f);
    }

    /** Выйти из режима эскиза. */
    public void endSketch() {
        sketchMode = false;
        sketchPlaneId = null;
    }

    /** Плоскость активного эскиза. */
    public Plane3D sketchPlane() {
        if (scene == null || sketchPlaneId == null) return null;
        return scene.planes.get(sketchPlaneId);
    }

    /**
     * Панорамирование вида эскиза: сдвиг цели камеры на du, dv вдоль осей
     * u/v плоскости. Дельта — разница локальных координат пальца,
     * тот же путь, что у тапа рисования: если тап ставит точку верно,
     * пан едет верно.
     */
    public void panByUv(float du, float dv) {
        Plane3D pl = sketchPlane();
        if (pl == null) return;
        Vec3 delta = pl.u.mul(du).add(pl.v.mul(dv));
        targetX += delta.x;
        targetY += delta.y;
        targetZ += delta.z;
    }

    /** Тап (x, y) → локальные координаты (u, v) активной плоскости. */
    public float[] screenToPlane(float x, float y, int w, int h) {
        Plane3D pl = sketchPlane();
        if (pl == null) return null;
        computeCamera();
        float[] ray = screenRay(x, y, w, h);
        if (ray == null) return null;
        float denom = pl.normal.x * ray[0] + pl.normal.y * ray[1] + pl.normal.z * ray[2];
        if (Math.abs(denom) < 1e-9f) return null;
        float t = (pl.normal.x * (pl.origin.x - camX)
                + pl.normal.y * (pl.origin.y - camY)
                + pl.normal.z * (pl.origin.z - camZ)) / denom;
        if (t <= 0) return null;
        Vec3 p = new Vec3(camX + ray[0] * t, camY + ray[1] * t, camZ + ray[2] * t);
        Vec2 loc = pl.toLocal(p);
        return new float[]{loc.x, loc.y};
    }

    /** Локальная точка (u, v) → экран (x, y); null, если вне клипа. */
    public float[] planeToScreen(float u, float v, int w, int h) {
        Plane3D pl = sketchPlane();
        if (pl == null) return null;
        Vec3 wp = pl.toWorld(new Vec2(u, v));
        float nx = mvp[0] * wp.x + mvp[4] * wp.y + mvp[8]  * wp.z + mvp[12];
        float ny = mvp[1] * wp.x + mvp[5] * wp.y + mvp[9]  * wp.z + mvp[13];
        float nw = mvp[3] * wp.x + mvp[7] * wp.y + mvp[11] * wp.z + mvp[15];
        if (nw <= 1e-9f) return null;
        return new float[]{(nx / nw * 0.5f + 0.5f) * w,
                (1f - (ny / nw * 0.5f + 0.5f)) * h};
    }

    private static final float SNAP_R = 15f;
    private static final float GRID = 10f;

    /** Допуск «лежит на плоскости», мм — для рёбер, совпадающих с гранью. */
    private static final float SIL_EPS = 0.01f;

    /** Радиус привязки в мм: фиксированные пиксели, пересчитанные
     *  по фактическому масштабу плоскости на экране. */
    /**
     * Адаптивный радиус привязки в мм: зависит от масштаба (удаления камеры).
     * Позволяет выполнять сверхточную привязку при сильном приближении,
     * не позволяя фокусу линий перехватывать сетку.
     */
    private float snapRadiusMm() {
        float[] p0 = planeToScreen(0, 0, surfaceW, surfaceH);
        float[] px = planeToScreen(100, 0, surfaceW, surfaceH);
        if (p0 == null || px == null) return SNAP_R;

        float pixelsPerMm = Math.abs(px[0] - p0[0]) / 100f; // Сколько пикселей экрана в 1 мм модели
        if (pixelsPerMm < 1e-6f) return SNAP_R;

        // Базовый захват на экране — 24 пикселя.
        float rawRadiusMm = 24f / pixelsPerMm;

        // Ограничиваем радиус в мм: на сильном отдалении не даем захватывать пол-экрана,
        // на сильном приближении — не даем падать меньше 1.2 мм для комфортного тапа.
        return Math.max(1.2f, Math.min(80f, rawRadiusMm));
    }


    /**
     * Точки привязки силуэта детали: пересечение плоскости с AABB даёт
     * прямоугольник сечения; для каждой детали — 4 угла, 4 середины сторон
     * и центр, в локальных координатах (u, v) плоскости.
     */
    public List<float[]> silhouettePoints(Plane3D pl) {
        List<float[]> out = new ArrayList<>();
        if (scene == null) return out;
        float[] o = {pl.origin.x, pl.origin.y, pl.origin.z};
        float[] n = {pl.normal.x, pl.normal.y, pl.normal.z};
        for (Element e : snapshot(scene)) {
            float[] b = aabbOf(e);
            if (b == null) continue;
            float[][] cs = {
                    {b[0], b[1], b[2]}, {b[3], b[1], b[2]},
                    {b[3], b[4], b[2]}, {b[0], b[4], b[2]},
                    {b[0], b[1], b[5]}, {b[3], b[1], b[5]},
                    {b[3], b[4], b[5]}, {b[0], b[4], b[5]}};
            int[][] edges = {{0, 1}, {1, 2}, {2, 3}, {3, 0},
                    {4, 5}, {5, 6}, {6, 7}, {7, 4},
                    {0, 4}, {1, 5}, {2, 6}, {3, 7}};
            List<float[]> pts = new ArrayList<>();
            for (int[] ed : edges) {
                float[] p0 = cs[ed[0]], p1 = cs[ed[1]];
                float d0 = n[0] * (p0[0] - o[0]) + n[1] * (p0[1] - o[1]) + n[2] * (p0[2] - o[2]);
                float d1 = n[0] * (p1[0] - o[0]) + n[1] * (p1[1] - o[1]) + n[2] * (p1[2] - o[2]);
                // ребро пересекает плоскость, если концы по разные стороны
                // ИЛИ один конец лежит на плоскости (d ≈ 0). Без второго
                // условия плоскость на грани (смещение 0) теряла все точки.
                boolean s0 = d0 > SIL_EPS, s1 = d1 > SIL_EPS;
                if (s0 == s1 && Math.abs(d0) > SIL_EPS && Math.abs(d1) > SIL_EPS)
                    continue;
                float dd = d0 - d1;
                if (Math.abs(dd) < 1e-9f) continue;
                float t = d0 / dd;
                if (t < 0) t = 0;
                if (t > 1) t = 1;
                pts.add(new float[]{p0[0] + (p1[0] - p0[0]) * t,
                        p0[1] + (p1[1] - p0[1]) * t,
                        p0[2] + (p1[2] - p0[2]) * t});
            }
            if (pts.size() < 3) continue;
            float minU = Float.MAX_VALUE, maxU = -Float.MAX_VALUE;
            float minV = Float.MAX_VALUE, maxV = -Float.MAX_VALUE;
            for (float[] p : pts) {
                Vec2 l = pl.toLocal(new Vec3(p[0], p[1], p[2]));
                if (l.x < minU) minU = l.x;
                if (l.x > maxU) maxU = l.x;
                if (l.y < minV) minV = l.y;
                if (l.y > maxV) maxV = l.y;
            }
            float mu = (minU + maxU) / 2, mv = (minV + maxV) / 2;
            // углы
            out.add(new float[]{minU, minV});
            out.add(new float[]{maxU, minV});
            out.add(new float[]{maxU, maxV});
            out.add(new float[]{minU, maxV});
            // середины сторон
            out.add(new float[]{mu, minV});
            out.add(new float[]{maxU, mv});
            out.add(new float[]{mu, maxV});
            out.add(new float[]{minU, mv});
            // центр детали
            out.add(new float[]{mu, mv});
        }
        return out;
    }

    /** Только углы силуэтов (каждая группа по 9 из silhouettePoints). */
    public List<float[]> silhouetteCorners(Plane3D pl) {
        List<float[]> all = silhouettePoints(pl);
        List<float[]> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i++)
            if (i % 9 < 4) out.add(all.get(i));
        return out;
    }

    /**
     * Все точки привязки (u, v): центр плоскости, вершины примитивов
     * эскиза, углы, середины сторон и центры силуэтов деталей.
     * Эскизы не создаёт — безопасно вызывать из GL-потока.
     */
    public List<float[]> snapCandidates() {
        List<float[]> out = new ArrayList<>();
        Plane3D pl = sketchPlane();
        if (pl == null || scene == null) return out;
        out.add(new float[]{0, 0});
        for (Sketch sk : scene.sketches) {
            if (!sketchPlaneId.equals(sk.planeId)) continue;
            for (Sketch.Entity e : sk.entities) {
                if (e.preview) continue;
                out.addAll(entityVertices(e));
            }
        }
        out.addAll(silhouettePoints(pl));
        return out;
    }

    /** Отрезки для линeйной привязки: рёбра примитивов эскиза
     *  и рёбра силуэтов деталей. */
    private List<float[]> snapSegments() {
        List<float[]> out = new ArrayList<>();
        Plane3D pl = sketchPlane();
        if (pl == null || scene == null) return out;
        for (Sketch sk : scene.sketches) {
            if (!sketchPlaneId.equals(sk.planeId)) continue;
            for (Sketch.Entity e : sk.entities) {
                if (e.preview) continue;
                if ("circle".equals(e.type) || "point".equals(e.type)) continue;
                out.addAll(entitySegments(e));
            }
        }
        List<float[]> corners = silhouetteCorners(pl);
        for (int i = 0; i + 3 < corners.size(); i += 4) {
            float[] a = corners.get(i), b = corners.get(i + 1);
            float[] c2 = corners.get(i + 2), d = corners.get(i + 3);
            out.add(new float[]{a[0], a[1], b[0], b[1]});
            out.add(new float[]{b[0], b[1], c2[0], c2[1]});
            out.add(new float[]{c2[0], c2[1], d[0], d[1]});
            out.add(new float[]{d[0], d[1], a[0], a[1]});
        }
        return out;
    }

    /**
     * Привязка: сначала точки (центр плоскости, вершины, углы, середины,
     * центры силуэтов), затем проекция тапа на ближайший отрезок —
     * «примагничивание к линии», затем сетка 10 мм.
     */
    public float[] sketchSnap(float u, float v) {
        if (sketchPlane() == null) return new float[]{u, v};
        float bestD = snapRadiusMm();
        float[] best = null;

        for (float[] c : snapCandidates()) {
            float du = c[0] - u, dv = c[1] - v;
            float d = (float) Math.sqrt(du * du + dv * dv);
            if (d < bestD) { bestD = d; best = new float[]{c[0], c[1]}; }
        }
        if (best != null) return best;

        for (float[] s : snapSegments()) {
            float rx = s[2] - s[0], ry = s[3] - s[1];
            float len2 = rx * rx + ry * ry;
            if (len2 < 1e-9f) continue;
            float t = ((u - s[0]) * rx + (v - s[1]) * ry) / len2;
            t = Math.max(0, Math.min(1, t));
            float pu = s[0] + rx * t, pv = s[1] + ry * t;
            float du = pu - u, dv = pv - v;
            float d = (float) Math.sqrt(du * du + dv * dv);
            if (d < bestD) { bestD = d; best = new float[]{pu, pv}; }
        }
        if (best != null) return best;

        float gu = Math.round(u / GRID) * GRID;
        float gv = Math.round(v / GRID) * GRID;
        float du = gu - u, dv = gv - v;
        if (du * du + dv * dv < bestD * bestD) return new float[]{gu, gv};
        return new float[]{u, v};
    }

    /** Вершины всех примитивов эскиза как точки (u, v). */
    public static List<float[]> entityVertices(Sketch.Entity e) {
        List<float[]> out = new ArrayList<>();
        float[] p = e.pts;
        switch (e.type) {
            case "point": out.add(new float[]{p[0], p[1]}); break;
            case "line":
                out.add(new float[]{p[0], p[1]});
                out.add(new float[]{p[2], p[3]});
                break;
            case "circle": out.add(new float[]{p[0], p[1]}); break;
            case "rect":
                for (int i = 0; i + 1 < p.length; i += 2)
                    out.add(new float[]{p[i], p[i + 1]});
                break;
            case "polyline":
                for (int i = 0; i + 1 < p.length; i += 2)
                    out.add(new float[]{p[i], p[i + 1]});
                break;
        }
        return out;
    }

    /** Рёбра примитивов эскиза как сегменты (u1, v1, u2, v2). */
    public static List<float[]> entitySegments(Sketch.Entity e) {
        List<float[]> out = new ArrayList<>();
        float[] p = e.pts;
        switch (e.type) {
            case "line":
                out.add(new float[]{p[0], p[1], p[2], p[3]});
                break;
            case "rect":
                out.add(new float[]{p[0], p[1], p[2], p[3]});
                out.add(new float[]{p[2], p[3], p[4], p[5]});
                out.add(new float[]{p[4], p[5], p[6], p[7]});
                out.add(new float[]{p[6], p[7], p[0], p[1]});
                break;
            case "polyline":
                for (int i = 2; i + 1 < p.length; i += 2)
                    out.add(new float[]{p[i - 2], p[i - 1], p[i], p[i + 1]});
                break;
        }
        return out;
    }

    /** Мировые координаты локальной точки плоскости. */
    private static float[] worldOf(Plane3D pl, float u, float v) {
        Vec3 w = pl.toWorld(new Vec2(u, v));
        return new float[]{w.x, w.y, w.z};
    }

    /** Рисование примитива эскиза. */
    private void drawSketchEntity(Plane3D pl, Sketch.Entity e) {
        float[] p = e.pts;
        float[] col = e.preview ? PREVIEW_COLOR : SKETCH_COLOR;
        GLES20.glUniform3f(phColor, col[0], col[1], col[2]);
        GLES20.glUniform1f(phAlpha, 1f);
        GLES20.glLineWidth(2f);
        switch (e.type) {
            case "point": {
                float[] w = worldOf(pl, p[0], p[1]);
                drawRaw(new float[]{w[0], w[1], w[2]}, GLES20.GL_POINTS, 9f);
                break;
            }
            case "line": {
                float[] a = worldOf(pl, p[0], p[1]), b = worldOf(pl, p[2], p[3]);
                drawRaw(new float[]{a[0], a[1], a[2], b[0], b[1], b[2]},
                        GLES20.GL_LINES, 1f);
                drawVertex(pl, p[0], p[1]);
                drawVertex(pl, p[2], p[3]);
                break;
            }
            case "circle": {
                int seg = 48;
                float[] pts = new float[seg * 6];
                for (int i = 0; i < seg; i++) {
                    float a0 = 2f * (float) Math.PI * i / seg;
                    float a1 = 2f * (float) Math.PI * (i + 1) / seg;
                    float[] wa = worldOf(pl, p[0] + p[2] * (float) Math.cos(a0),
                            p[1] + p[2] * (float) Math.sin(a0));
                    float[] wb = worldOf(pl, p[0] + p[2] * (float) Math.cos(a1),
                            p[1] + p[2] * (float) Math.sin(a1));
                    pts[i * 6] = wa[0]; pts[i * 6 + 1] = wa[1]; pts[i * 6 + 2] = wa[2];
                    pts[i * 6 + 3] = wb[0]; pts[i * 6 + 4] = wb[1]; pts[i * 6 + 5] = wb[2];
                }
                drawRaw(pts, GLES20.GL_LINES, 1f);
                drawVertex(pl, p[0], p[1]);
                break;
            }
            case "rect": {
                float[] c0 = worldOf(pl, p[0], p[1]);
                float[] c1 = worldOf(pl, p[2], p[3]);
                float[] c2 = worldOf(pl, p[4], p[5]);
                float[] c3 = worldOf(pl, p[6], p[7]);
                drawRaw(new float[]{
                                c0[0], c0[1], c0[2], c1[0], c1[1], c1[2],
                                c1[0], c1[1], c1[2], c2[0], c2[1], c2[2],
                                c2[0], c2[1], c2[2], c3[0], c3[1], c3[2],
                                c3[0], c3[1], c3[2], c0[0], c0[1], c0[2]},
                        GLES20.GL_LINES, 1f);
                drawVertex(pl, p[0], p[1]);
                drawVertex(pl, p[2], p[3]);
                drawVertex(pl, p[4], p[5]);
                drawVertex(pl, p[6], p[7]);
                break;
            }
            case "polyline": {
                if (p.length >= 6) {
                    int segCount = p.length / 2 - 1;
                    float[] all = new float[segCount * 6];
                    for (int i = 2, k = 0; i + 1 < p.length; i += 2, k += 6) {
                        float[] a = worldOf(pl, p[i - 2], p[i - 1]);
                        float[] b = worldOf(pl, p[i], p[i + 1]);
                        all[k] = a[0]; all[k + 1] = a[1]; all[k + 2] = a[2];
                        all[k + 3] = b[0]; all[k + 4] = b[1]; all[k + 5] = b[2];
                    }
                    drawRaw(all, GLES20.GL_LINES, 1f);
                }
                for (int i = 0; i + 1 < p.length; i += 2)
                    drawVertex(pl, p[i], p[i + 1]);
                break;
            }
        }
    }

    private void drawVertex(Plane3D pl, float u, float v) {
        float[] w = worldOf(pl, u, v);
        GLES20.glUniform3f(phColor, 0.7f, 0.8f, 1f);
        GLES20.glUniform1f(phAlpha, 1f);
        drawRaw(new float[]{w[0], w[1], w[2]}, GLES20.GL_POINTS, 6f);
    }

    /**
     * Эскизы рисуются поверх сцены (тест глубины отключён): линии белым,
     * preview жёлтым; оси активного эскиза — красная U, зелёная V,
     * начало — центр плоскости; жёлтые кружки — все точки привязки.
     */
    private void drawSketches() {
        if (scene == null) return;
        List<Sketch> sketches;
        try {
            sketches = new ArrayList<>(scene.sketches);
        } catch (Exception ex) {
            return;
        }
        if (sketches.isEmpty() && !(sketchMode && sketchPlaneId != null)) return;

        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glUseProgram(planeProgram);
        GLES20.glUniformMatrix4fv(phMVP, 1, false, mvp, 0);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        for (Sketch sk : sketches) {
            Plane3D pl = scene.planes.get(sk.planeId);
            if (pl == null) continue;
            List<Sketch.Entity> ents;
            try {
                ents = new ArrayList<>(sk.entities);
            } catch (Exception ex) {
                continue;
            }
            for (Sketch.Entity e : ents)
                drawSketchEntity(pl, e);
        }

        // оси координат активного эскиза: U красная, V зелёная, начало — центр
        if (sketchMode && sketchPlaneId != null) {
            Plane3D pl = scene.planes.get(sketchPlaneId);
            if (pl != null) {
                float[] half = sceneHalf();
                float len = Math.max(half[0], Math.max(half[1], half[2])) * 1.2f + 100;
                float[] o = worldOf(pl, 0, 0);
                float[] uEnd = worldOf(pl, len, 0);
                float[] vEnd = worldOf(pl, 0, len);
                GLES20.glLineWidth(3f);
                GLES20.glUniform1f(phAlpha, 1f);
                GLES20.glUniform3f(phColor, 1f, 0.25f, 0.25f);
                drawRaw(new float[]{o[0], o[1], o[2], uEnd[0], uEnd[1], uEnd[2]},
                        GLES20.GL_LINES, 1f);
                GLES20.glUniform3f(phColor, 0.3f, 1f, 0.35f);
                drawRaw(new float[]{o[0], o[1], o[2], vEnd[0], vEnd[1], vEnd[2]},
                        GLES20.GL_LINES, 1f);
                GLES20.glUniform3f(phColor, 1f, 1f, 1f);
                drawRaw(o, GLES20.GL_POINTS, 10f);

                // жёлтые маркеры всех точек привязки
                GLES20.glUniform3f(phColor, 1f, 0.85f, 0.2f);
                GLES20.glUniform1f(phAlpha, 1f);
                for (float[] s : snapCandidates()) {
                    float[] wpt = worldOf(pl, s[0], s[1]);
                    drawRaw(new float[]{wpt[0], wpt[1], wpt[2]},
                            GLES20.GL_POINTS, 9f);
                }
            }
        }

        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glUseProgram(program);
    }

    private boolean isSelected(Element e) {
        if (scene.selectedId == null) return false;
        String n = e.name != null ? e.name : e.id;
        return scene.selectedId.equals(e.id) || scene.selectedId.equals(n);
    }

    /** Выбор детали касанием (по телу): тап в проекцию AABB, ближайшая к камере. */
    public String pick(float x, float y, int w, int h) {
        if (scene == null) return null;
        computeCamera();

        String bodyHit = null;
        float bodyDepth = Float.MAX_VALUE;

        for (Element e : snapshot(scene)) {
            float[] box = aabbOf(e);
            if (box == null) continue;
            int corners = 0;
            float minDepth = Float.MAX_VALUE;
            float minSx = Float.MAX_VALUE, maxSx = -Float.MAX_VALUE;
            float minSy = Float.MAX_VALUE, maxSy = -Float.MAX_VALUE;
            for (int i = 0; i < 8; i++) {
                float px = cornerX(box, i), py = cornerY(box, i), pz = cornerZ(box, i);
                float nx = mvp[0] * px + mvp[4] * py + mvp[8]  * pz + mvp[12];
                float ny = mvp[1] * px + mvp[5] * py + mvp[9]  * pz + mvp[13];
                float nw = mvp[3] * px + mvp[7] * py + mvp[11] * pz + mvp[15];
                if (nw <= 0) { corners = -1; break; }
                float ez = view[2] * px + view[6] * py + view[10] * pz + view[14];
                if (-ez < minDepth) minDepth = -ez;
                float sx = (nx / nw * 0.5f + 0.5f) * w;
                float sy = (1f - (ny / nw * 0.5f + 0.5f)) * h;
                if (sx < minSx) minSx = sx;
                if (sx > maxSx) maxSx = sx;
                if (sy < minSy) minSy = sy;
                if (sy > maxSy) maxSy = sy;
                corners++;
            }
            if (corners != 8) continue;
            if (x >= minSx && x <= maxSx && y >= minSy && y <= maxSy
                    && minDepth < bodyDepth) {
                bodyDepth = minDepth;
                bodyHit = e.id;
            }
        }
        return bodyHit;
    }

    /** AABB меша элемента: [minX,minY,minZ,maxX,maxY,maxZ] из массива вершин. */
    private float[] aabbOf(Element e) {
        Extruder.MeshData md = scene.meshes.get(e.id);
        if (md == null || md.pos == null) return null;
        // Просто возвращаем предвычисленный при rebuild() кэш
        return new float[]{md.min[0], md.min[1], md.min[2], md.max[0], md.max[1], md.max[2]};
    }


    /**
     * Переносит геометрию из ядра в GL-буферы. Вызывать после rebuild().
     * Ничего не мутирует: новые карты собираются локально и подменяются
     * ссылкой — безопасно вызывать с UI-потока во время отрисовки.
     */
    /**
     * Переносит геометрию из ядра в GL-буферы. Вызывать после rebuild().
     * ИСПРАВЛЕНО: Создание Mesh буферов принудительно делегируется в GLThread
     * через queueEvent (это решает проблему невидимости отверстий).
     */
    /**
     * Переносит геометрию из ядра в GL-буферы. Вызывать после rebuild().
     * ИСПРАВЛЕНО: Атомарная подмена ссылки предотвращает race condition и черный экран!
     */
    public void syncScene() {
        if (scene == null) return;

        // Строим новую временную карту строго локально, не затрагивая активный meshes во время отрисовки
        Map<String, Mesh> newMeshes = new LinkedHashMap<>();

        // Берем потокобезопасный снимок элементов
        List<Element> elSnapshot = snapshot(scene);

        for (Element e : elSnapshot) {
            Extruder.MeshData md = scene.meshes.get(e.id);
            if (md != null && md.pos != null && md.idx != null) {
                // Создаем GL-меш. Нативная память выделится безопасно
                newMeshes.put(e.id, Mesh.from(md));
            }
        }

        // АТОМАРНЫЙ СВОП: Подменяем ссылку одним действием.
        // Поток OpenGL (onDrawFrame) либо увидит старую карту, либо новую, но никогда — пустую или очищающуюся!
        this.meshes = newMeshes;

        // Перестраиваем плоскости
        buildPlanes();
    }



    private static float[] colorFor(Element e) {
        String m = e.material != null ? e.material.name : "";
        switch (m) {
            case "09Г2С":    return new float[]{0.55f, 0.65f, 0.75f};
            case "AISI 304": return new float[]{0.85f, 0.87f, 0.90f};
            default:         return new float[]{0.72f, 0.72f, 0.76f};
        }
    }

    private static Mesh buildGrid(int extent, int step) {
        int half = extent / 2;
        List<float[]> lines = new ArrayList<>();
        for (int i = -half; i <= half; i += step) {
            lines.add(new float[]{i, 0, -half});
            lines.add(new float[]{i, 0, half});
            lines.add(new float[]{-half, 0, i});
            lines.add(new float[]{half, 0, i});
        }
        float[] pos = new float[lines.size() * 3];
        float[] nrm = new float[lines.size() * 3];
        int k = 0;
        for (float[] p : lines) {
            pos[k * 3] = p[0]; pos[k * 3 + 1] = p[1]; pos[k * 3 + 2] = p[2];
            nrm[k * 3] = 0; nrm[k * 3 + 1] = 1; nrm[k * 3 + 2] = 0;
            k++;
        }
        return new Mesh(pos, nrm, null, GLES20.GL_LINES);
    }
}
