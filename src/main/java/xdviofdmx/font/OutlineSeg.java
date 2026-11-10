package xdviofdmx.font;

/**
 * 字形轮廓段（AWT pt 坐标，基线原点，y 向下）。
 */
public record OutlineSeg(Kind kind, double[] coords) {

    public enum Kind { M, L, Q, C, Z }

    public OutlineSeg {
        coords = coords.clone();
    }
}
