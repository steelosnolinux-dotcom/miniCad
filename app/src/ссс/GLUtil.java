package ru.loftcontur.minicad.gl;

import android.opengl.GLES20;

/**
 * Шейдеры и загрузка программы. Имена атрибутов согласованы с Mesh: aPos, aNrm.
 * Освещение: ключевой источник сверху-спереди + заполняющий снизу-сбоку,
 * плюс базовый уровень 0.32 — грани и радиусы читаются, тени не чернеют.
 */
public final class GLUtil {

    public static final String VERT_SRC =
            "uniform mat4 uMVP;\n" +
                    "uniform mat4 uModel;\n" +
                    "attribute vec3 aPos;\n" +
                    "attribute vec3 aNrm;\n" +
                    "varying vec3 vNrm;\n" +
                    "void main() {\n" +
                    "  vNrm = mat3(uModel) * aNrm;\n" +
                    "  gl_Position = uMVP * vec4(aPos, 1.0);\n" +
                    "}\n";

    public static final String FRAG_SRC =
            "precision mediump float;\n" +
                    "uniform vec3 uColor;\n" +
                    "uniform float uAlpha;\n" +
                    "varying vec3 vNrm;\n" +
                    "void main() {\n" +
                    "  vec3 n = normalize(vNrm);\n" +
                    "  float key  = max(dot(n, normalize(vec3(0.45, 0.85, 0.3))), 0.0);\n" +
                    "  float fill = max(dot(n, normalize(vec3(-0.6, 0.25, -0.5))), 0.0);\n" +
                    "  float light = 0.32 + 0.55 * key + 0.28 * fill;\n" +
                    "  gl_FragColor = vec4(uColor * light, uAlpha);\n" +
                    "}\n";

    private GLUtil() {}

    /** Компиляция шейдера; при ошибке — RuntimeException с логом. */
    public static int compile(int type, String src) {
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

    /** Сборка программы из вершинного и фрагментного шейдеров. */
    public static int loadProgram(String vs, String fs) {
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs));
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs));
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(p);
            GLES20.glDeleteProgram(p);
            throw new RuntimeException(log);
        }
        return p;
    }
}
