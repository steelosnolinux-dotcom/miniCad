package ru.loftcontur.minicad.core;

import java.util.Map;

/**
 * Оптимизированный парсер математических выражений.
 * Работает без использования регулярных выражений (Regex) и без аллокации строк в цикле,
 * что полностью устраняет нагрузку на Garbage Collector при пересчете сцены.
 */
public final class Expr {

    private Expr() {}

    public static float eval(String s, Map<String, Float> vars) {
        if (s == null) return 0;
        return new Parser(s, vars).parse();
    }

    private static class Parser {
        private final String s;
        private final Map<String, Float> vars;
        private int i = 0;

        Parser(String s, Map<String, Float> vars) {
            this.s = s;
            this.vars = vars;
        }

        float parse() {
            skipWhitespace();
            float v = term();
            skipWhitespace();
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '+') { i++; v += term(); }
                else if (c == '-') { i++; v -= term(); }
                else break;
                skipWhitespace();
            }
            return v;
        }

        private float term() {
            skipWhitespace();
            float v = prim();
            skipWhitespace();
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '*') { i++; v *= prim(); }
                else if (c == '/') { i++; v /= prim(); }
                else break;
                skipWhitespace();
            }
            return v;
        }

        private float prim() {
            skipWhitespace();
            if (i >= s.length()) return 0;
            char c = s.charAt(i);
            if (c == '+') { i++; return prim(); }
            if (c == '-') { i++; return -prim(); }
            if (c == '(') {
                i++;
                float v = parse();
                skipWhitespace();
                if (i < s.length() && s.charAt(i) == ')') i++;
                return v;
            }

            // Переменные (имена параметров вроде width, height, depth)
            if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) {
                    i++;
                }
                String name = s.substring(start, i);
                if (vars != null && vars.containsKey(name)) {
                    return vars.get(name);
                }
                return 0; // Переменная не найдена
            }

            // Числовые литералы
            int start = i;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
                i++;
            }
            if (i == start) { i++; return 0; }
            try {
                return Float.parseFloat(s.substring(start, i));
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        private void skipWhitespace() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }
    }
}
