package xdviofdmx.model;

import java.util.Locale;

/**
 * 颜色模型（dvips 颜色空间），分量 0~1。
 * OFD 原生支持 Gray/RGB/CMYK，转换发生在 PageEmitter。
 */
public record ColorSpec(Kind kind, double[] components) {

    public enum Kind { GRAY, RGB, CMYK }

    public ColorSpec {
        components = components.clone();
    }

    public static ColorSpec gray(double g) {
        return new ColorSpec(Kind.GRAY, new double[]{clamp01(g)});
    }

    public static ColorSpec rgb(double r, double g, double b) {
        return new ColorSpec(Kind.RGB, new double[]{clamp01(r), clamp01(g), clamp01(b)});
    }

    public static ColorSpec cmyk(double c, double m, double y, double k) {
        return new ColorSpec(Kind.CMYK, new double[]{clamp01(c), clamp01(m), clamp01(y), clamp01(k)});
    }

    /** hsb → rgb（dvips hsb 颜色规格） */
    public static ColorSpec hsb(double h, double s, double b) {
        double r, g, bl;
        h = ((h % 1.0) + 1.0) % 1.0;
        if (s == 0) {
            r = g = bl = b;
        } else {
            int i = (int) (h * 6.0);
            double f = h * 6.0 - i;
            double p = b * (1 - s);
            double q = b * (1 - s * f);
            double t = b * (1 - s * (1 - f));
            switch (i % 6) {
                case 0 -> { r = b; g = t; bl = p; }
                case 1 -> { r = q; g = b; bl = p; }
                case 2 -> { r = p; g = b; bl = t; }
                case 3 -> { r = p; g = q; bl = b; }
                case 4 -> { r = t; g = p; bl = b; }
                default -> { r = b; g = p; bl = q; }
            }
        }
        return rgb(r, g, bl);
    }

    /** CMYK 近似转 RGB（与 ofdrw-converter 相同公式） */
    public ColorSpec toRgb() {
        return switch (kind) {
            case RGB -> this;
            case GRAY -> gray(components[0]);
            case CMYK -> {
                double k = components[3];
                yield rgb((1 - components[0]) * (1 - k),
                          (1 - components[1]) * (1 - k),
                          (1 - components[2]) * (1 - k));
            }
        };
    }

    private static double clamp01(double v) {
        return Math.min(1.0, Math.max(0.0, v));
    }

    /** 解析 "rgb 1 0 0" / "cmyk 0 0 1 0" / "gray .5" / "hsb ..." 之后的规格词 */
    public static ColorSpec parseSpec(String word, java.util.Iterator<String> tokens) {
        return switch (word.toLowerCase(Locale.ROOT)) {
            case "rgb", "rgbcolor" -> rgb(next(tokens), next(tokens), next(tokens));
            case "cmyk", "cmykcolor" -> cmyk(next(tokens), next(tokens), next(tokens), next(tokens));
            case "gray", "grey", "graycolor", "greycolor" -> gray(next(tokens));
            case "hsb", "hsbcolor" -> hsb(next(tokens), next(tokens), next(tokens));
            default -> null; // 交由命名色表处理
        };
    }

    private static double next(java.util.Iterator<String> it) {
        return Double.parseDouble(it.next());
    }
}
