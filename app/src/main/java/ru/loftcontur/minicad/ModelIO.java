package ru.loftcontur.minicad;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import ru.loftcontur.minicad.core.Anchor;
import ru.loftcontur.minicad.core.Element;
import ru.loftcontur.minicad.core.Material;
import ru.loftcontur.minicad.core.Scene;

/** Сохранение и загрузка модели в JSON. */
public final class ModelIO {

    private static final String FILE = "model.json";

    private ModelIO() {}

    /** Сериализация сцены в JSONObject. */
    private static JSONObject toJson(Scene scene) throws Exception {
        JSONObject root = new JSONObject();

        JSONObject params = new JSONObject();
        for (Map.Entry<String, Float> e : scene.params.entrySet())
            params.put(e.getKey(), (double) e.getValue());
        root.put("params", params);

        JSONArray planes = new JSONArray();
        for (Map.Entry<String, Scene.PlaneDef> e : scene.planeDefs.entrySet()) {
            Scene.PlaneDef d = e.getValue();
            JSONObject p = new JSONObject();
            p.put("id", e.getKey());
            p.put("ox", d.ox); p.put("oy", d.oy); p.put("oz", d.oz);
            p.put("nx", d.nx); p.put("ny", d.ny); p.put("nz", d.nz);
            p.put("u", d.u == null ? "" : d.u);
            planes.put(p);
        }
        root.put("planes", planes);

        JSONArray els = new JSONArray();
        for (Element el : scene.elements) {
            JSONObject o = new JSONObject();
            o.put("id", el.id);
            if (el.name != null) o.put("name", el.name);
            if (el.typeName != null) o.put("typeName", el.typeName);
            if (el.material != null) o.put("material", el.material.name);
            if (el.dimExprs != null) {
                JSONObject de = new JSONObject();
                for (Map.Entry<String, String> en : el.dimExprs.entrySet())
                    de.put(en.getKey(), en.getValue());
                o.put("dimExprs", de);
            }
            JSONObject an = new JSONObject();
            an.put("planeId", el.anchor.planeId);
            an.put("offsetU", el.anchor.offsetU);
            an.put("offsetV", el.anchor.offsetV);
            an.put("lengthExpr", el.anchor.lengthExpr);
            an.put("dirExpr", el.anchor.dirExpr);
            o.put("anchor", an);
            els.put(o);
        }
        root.put("elements", els);
        return root;
    }

    /** Запись в конкретный файл (для библиотеки моделей). */
    public static boolean saveToFile(File f, Scene scene) {
        Writer w = null;
        try {
            w = new OutputStreamWriter(new java.io.FileOutputStream(f), "UTF-8");
            w.write(toJson(scene).toString());
            return true;
        } catch (Exception ex) {
            return false;
        } finally {
            try { if (w != null) w.close(); } catch (Exception ignored) {}
        }
    }

    /** Чтение из конкретного файла (для библиотеки моделей). */
    public static boolean loadFromFile(File f, Scene scene) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            applyJson(new JSONObject(sb.toString()), scene);
            scene.rebuild();
            return true;
        } catch (Exception ex) {
            return false;
        } finally {
            try { if (r != null) r.close(); } catch (Exception ignored) {}
        }
    }

    /** Разбор JSON в сцену. */
    private static void applyJson(JSONObject root, Scene scene) throws Exception {
        scene.params.clear();
        JSONObject params = root.optJSONObject("params");
        if (params != null)
            for (Iterator<String> it = params.keys(); it.hasNext(); ) {
                String k = it.next();
                scene.params.put(k, (float) params.getDouble(k));
            }

        scene.planeDefs.clear();
        JSONArray planes = root.optJSONArray("planes");
        if (planes != null)
            for (int i = 0; i < planes.length(); i++) {
                JSONObject p = planes.getJSONObject(i);
                Scene.PlaneDef d = new Scene.PlaneDef(
                        p.getString("ox"), p.getString("oy"), p.getString("oz"),
                        p.getString("nx"), p.getString("ny"), p.getString("nz"));
                String u = p.optString("u", "");
                if (u.length() > 0) d.u = u;
                scene.planeDefs.put(p.getString("id"), d);
            }

        scene.elements.clear();
        scene.selectedId = null;
        JSONArray els = root.optJSONArray("elements");
        if (els != null)
            for (int i = 0; i < els.length(); i++) {
                JSONObject o = els.getJSONObject(i);
                Element e = new Element(o.getString("id"));
                e.name = o.optString("name", null);
                e.typeName = o.optString("typeName", null);
                e.material = Material.byName(o.optString("material", "ST3"));

                JSONObject de = o.optJSONObject("dimExprs");
                if (de != null) {
                    Map<String, String> m = new LinkedHashMap<>();
                    for (Iterator<String> it = de.keys(); it.hasNext(); ) {
                        String k = it.next();
                        m.put(k, de.getString(k));
                    }
                    e.dimExprs = m;
                }

                JSONObject an = o.getJSONObject("anchor");
                e.anchor = new Anchor(
                        an.getString("planeId"),
                        an.getString("offsetU"),
                        an.getString("offsetV"),
                        an.getString("lengthExpr"),
                        an.getString("dirExpr"));
                scene.elements.add(e);
            }
    }

    // ---- старые методы для совместимости (одиночный model.json) ----

    public static boolean save(Context ctx, Scene scene) {
        return saveToFile(new File(ctx.getFilesDir(), FILE), scene);
    }

    public static boolean load(Context ctx, Scene scene) {
        return loadFromFile(new File(ctx.getFilesDir(), FILE), scene);
    }
}
