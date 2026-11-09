package ru.loftcontur.minicad.core;

import java.util.Map;

/** Сборка профиля по типу и уже вычисленным размерам. Ключи — как в «Сортаменте». */
public final class ProfileFactory {

    private ProfileFactory() {}

    public static Profile build(String type, Map<String, Float> f) {
        switch (type) {
            case "Труба прямоугольная":
                return Profile.rectTube(get(f, "Ширина W"), get(f, "Высота H"),
                        get(f, "Стенка T"), get(f, "Радиус R"));
            case "Труба круглая":
                return Profile.roundTube(get(f, "Диаметр D"), get(f, "Стенка T"));
            case "Труба овальная":
                return Profile.ovalTube(get(f, "Ось A"), get(f, "Ось B"), get(f, "Стенка T"));
            case "Уголок":
                return Profile.angle(get(f, "Полка B"), get(f, "Высота H"), get(f, "Стенка T"));
            case "Швеллер":
                return Profile.channel(get(f, "Высота H"), get(f, "Полка B"), get(f, "Стенка T"));
            case "Тавр":
                return Profile.tee(get(f, "Высота H"), get(f, "Полка B"),
                        get(f, "Стенка TW"), get(f, "Полка TF"));
            case "Лист":
                return Profile.sheet(get(f, "Ширина W"), get(f, "Глубина H"), get(f, "Скругление R"));
            default:
                return null;
        }
    }

    private static float get(Map<String, Float> f, String k) {
        Float v = f.get(k);
        return v == null ? 0 : v;
    }
}
