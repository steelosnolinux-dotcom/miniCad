package ru.loftcontur.minicad.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Модуль генерации спецификации, ведомости материалов и деталировки на распил.
 * Группирует элементы чертежа, рассчитывает чистые длины, площади и массу конструкции.
 */
public final class BomExporter {

    private BomExporter() {}

    /**
     * Структура для группировки одинаковых деталей в спецификации.
     */
    public static class BomItem {
        public String typeName;   // Например, "Труба прямоугольная"
        public String label;      // Имя или габариты сечения, например, "40×40×2"
        public String customName; // "Царга", "Ножка" или пустая строка
        public float length;      // Чистая длина (или толщина листа) в мм
        public float width;       // Ширина (только для листов), мм
        public float weightEach;  // Масса одной штуки, кг
        public int count;         // Количество одинаковых деталей
        public String material;   // Марка стали/материала
    }

    /**
     * Генерирует структурированный текстовый отчет по текущей сцене.
     */
    public static String generateReport(Scene scene) {
        Map<String, BomItem> map = new LinkedHashMap<>();
        float totalWeight = 0;

        for (Element e : scene.elements) {
            if (e.anchor == null || e.profile == null) continue;

            // Вычисляем чистую длину/толщину элемента по его выражению
            float length = 0;
            try {
                length = Expr.eval(e.anchor.lengthExpr, scene.params);
            } catch (Exception ignored) {}

            String type = e.typeName != null ? e.typeName : "Профиль";
            String matName = e.material != null ? e.material.name : "Ст3";
            float density = e.material != null ? e.material.density : 7850f; // кг/м³

            // Расчет массы: масса = площадь сечения (м²) * длина (м) * плотность (кг/м³)
            // В ядре area() возвращает площадь сечения в кв. миллиметрах
            float areaM2 = e.profile.area() / 1_000_000f;
            float lengthM = length / 1000f;
            float weightEach = areaM2 * lengthM * density;

            // Для листовых тел извлекаем габариты из выражений dimExprs
            float sheetWidth = 0;
            if ("Лист".equalsIgnoreCase(type) && e.dimExprs != null) {
                try {
                    if (e.dimExprs.containsKey("Ширина W")) {
                        sheetWidth = Expr.eval(e.dimExprs.get("Ширина W"), scene.params);
                    }
                } catch (Exception ignored) {}
            }

            String customName = e.name != null ? e.name : "";

            // Ключ уникальности для группировки деталей на распил
            String key = type + "|" + matName + "|" + length + "|" + sheetWidth + "|" + customName + "|" + e.profile.area();

            if (map.containsKey(key)) {
                map.get(key).count++;
            } else {
                BomItem item = new BomItem();
                item.typeName = type;
                item.customName = customName;
                item.length = length;
                item.width = sheetWidth;
                item.weightEach = weightEach;
                item.count = 1;
                item.material = matName;
                item.label = getProfileDimsLabel(e, scene);
                map.put(key, item);
            }
            totalWeight += weightEach;
        }

        // Собираем текстовый документ
        StringBuilder sb = new StringBuilder();
        sb.append("==================================================\n");
        sb.append("      СПЕЦИФИКАЦИЯ И ДЕТАЛИРОВКА ИЗДЕЛИЯ          \n");
        sb.append("==================================================\n\n");

        sb.append(String.format("Всего деталей в проекте: %d шт.\n", scene.elements.size()));
        sb.append(String.format("Ориентировочная чистая масса изделия: %.2f кг\n\n", totalWeight));

        sb.append("--------------------------------------------------\n");
        sb.append("1. ВЕДОМОСТЬ РАСПИЛА (ПОЗИЦИОННАЯ)\n");
        sb.append("--------------------------------------------------\n");

        int idx = 1;
        for (BomItem item : map.values()) {
            sb.append(String.format("%d. ", idx++));
            if (!item.customName.isEmpty()) {
                sb.append("«").append(item.customName).append("» — ");
            }
            sb.append(item.typeName).append(" [").append(item.label).append("]");

            if ("Лист".equalsIgnoreCase(item.typeName) && item.width > 0) {
                sb.append(String.format(", Размер: %.0fx%.0f мм", item.width, item.length));
            } else {
                sb.append(String.format(", Длина L = %.0f мм", item.length));
            }

            sb.append(String.format(" | Кол-во: %d шт.", item.count));
            sb.append(String.format(" | Материал: %s", item.material));
            sb.append(String.format(" | Вес поз: %.2f кг\n", item.weightEach * item.count));
        }

        sb.append("\n--------------------------------------------------\n");
        sb.append("2. СВОДНЫЙ РАСХОД МАТЕРИАЛОВ (СУММАРНЫЙ ПО ТИПАМ)\n");
        sb.append("--------------------------------------------------\n");

        Map<String, Float> summaryLength = new LinkedHashMap<>();
        Map<String, Integer> summaryCount = new LinkedHashMap<>();

        for (BomItem item : map.values()) {
            String subKey = item.typeName + " [" + item.label + "] (" + item.material + ")";
            float totalLenM = (item.length * item.count) / 1000f;

            summaryLength.put(subKey, summaryLength.getOrDefault(subKey, 0f) + totalLenM);
            summaryCount.put(subKey, summaryCount.getOrDefault(subKey, 0) + item.count);
        }

        for (Map.Entry<String, Float> entry : summaryLength.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("Лист")) {
                sb.append(String.format("* %s — Количество заготовок: %d шт.\n", key, summaryCount.get(key)));
            } else {
                sb.append(String.format("* %s — Суммарный метраж: %.2f пог. м (%d резов)\n",
                        key, entry.getValue(), summaryCount.get(key)));
            }
        }

        sb.append("\nДокумент сгенерирован автоматически движком miniCad.\n");
        return sb.toString();
    }

    private static String getProfileDimsLabel(Element e, Scene scene) {
        if (e.dimExprs == null) return "—";
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : e.dimExprs.entrySet()) {
            if (entry.getKey().contains("Радиус") || entry.getKey().contains("Скругление")) continue;
            try {
                float val = Expr.eval(entry.getValue(), scene.params);
                if (!first) sb.append("×");
                sb.append(val == Math.round(val) ? String.valueOf((int) val) : String.valueOf(val));
                first = false;
            } catch (Exception ignored) {}
        }
        return sb.toString();
    }

    /**
     * Экспорт спецификации в текстовый файл на устройство.
     */
    public static boolean exportToFile(File file, Scene scene) {
        Writer w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(file), "UTF-8");
            w.write(generateReport(scene));
            return true;
        } catch (Exception ex) {
            return false;
        } finally {
            try { if (w != null) w.close(); } catch (Exception ignored) {}
        }
    }
}
