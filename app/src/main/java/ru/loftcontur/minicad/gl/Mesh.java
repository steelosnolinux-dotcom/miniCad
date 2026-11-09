package ru.loftcontur.minicad.gl;

import android.opengl.GLES20;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;
import ru.loftcontur.minicad.core.Extruder;

public class Mesh {
    private final FloatBuffer pos, nrm;
    private final IntBuffer idx; // Заменили ShortBuffer на IntBuffer
    private final int count;
    private final int mode;

    private static final Map<Integer, int[]> locCache = new HashMap<>();

    public Mesh(float[] p, float[] n, int[] i, int mode) {
        this.pos = toFloat(p);
        this.nrm = toFloat(n);
        this.idx = i != null ? toInt(i) : null;
        this.count = i != null ? i.length : p.length / 3;
        this.mode = mode;
    }

    public static Mesh from(Extruder.MeshData md) {
        return new Mesh(md.pos, md.nrm, md.idx, GLES20.GL_TRIANGLES);
    }

    private static FloatBuffer toFloat(float[] a) {
        FloatBuffer b = ByteBuffer.allocateDirect(a.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(a).position(0);
        return b;
    }

    private static IntBuffer toInt(int[] a) {
        IntBuffer b = ByteBuffer.allocateDirect(a.length * 4) // 4 байта на int
                .order(ByteOrder.nativeOrder()).asIntBuffer();
        b.put(a).position(0);
        return b;
    }

    public void draw(int program) {
        int[] loc = locCache.get(program);
        if (loc == null) {
            loc = new int[]{
                    GLES20.glGetAttribLocation(program, "aPos"),
                    GLES20.glGetAttribLocation(program, "aNrm")};
            locCache.put(program, loc);
        }
        GLES20.glEnableVertexAttribArray(loc[0]);
        GLES20.glEnableVertexAttribArray(loc[1]);
        GLES20.glVertexAttribPointer(loc[0], 3, GLES20.GL_FLOAT, false, 0, pos);
        GLES20.glVertexAttribPointer(loc[1], 3, GLES20.GL_FLOAT, false, 0, nrm);

        if (idx != null) {
            // ВАЖНО: Заменили GL_UNSIGNED_SHORT на GL_UNSIGNED_INT для поддержки >32к вершин
            GLES20.glDrawElements(mode, count, GLES20.GL_UNSIGNED_INT, idx);
        } else {
            GLES20.glDrawArrays(mode, 0, count);
        }
        GLES20.glDisableVertexAttribArray(loc[0]);
        GLES20.glDisableVertexAttribArray(loc[1]);
    }
}
