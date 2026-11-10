package xdviofdmx;

/**
 * 坐标与单位换算。
 *
 * <p>XDV（标准前导常量 num=25400000, den=473628672）下 65536 DVI 单位 = 1 TeX pt。
 * 全部换算到 OFD 的毫米（double），输出统一保留 4 位小数（0.0001mm ≈ 0.00028pt，
 * 远高于视觉分辨与 OFD 0.01mm 精度要求，同时避免逐项取整的累积漂移）。</p>
 */
public final class Coord {

    /** 1 TeX pt = 25.4/72.27 mm */
    public static final double MM_PER_TEXPT = 25.4 / 72.27;
    /** 1 bp（PDF 大点，1/72 inch）= 25.4/72 mm */
    public static final double MM_PER_BP = 25.4 / 72.0;
    /** 标准前导常量：每 DVI 单位毫米数（65536 单位 = 1 TeX pt） */
    public static final double MM_PER_DVI = MM_PER_TEXPT / 65536.0;

    private final double scale; // 附加缩放（-m 用户放大率 mag/1000）

    public Coord() {
        this(1.0);
    }

    public Coord(double userMagnification) {
        this.scale = userMagnification;
    }

    /** DVI 单位 → mm（含用户放大率） */
    public double dviToMm(double dvi) {
        return dvi * MM_PER_DVI * scale;
    }

    /** PDF bp → mm（special 中的绝对长度，不受放大率影响，与 xdvipdfmx papersize 行为一致） */
    public static double bpToMm(double bp) {
        return bp * MM_PER_BP;
    }

    /** TeX pt → mm */
    public static double texPtToMm(double pt) {
        return pt * MM_PER_TEXPT;
    }

    /** AWT 字号（点，1/72 inch）取值：使 AWT 输出的字面与 TeX pt 尺寸一致 */
    public static double texPtToAwtPt(double texPt) {
        return texPt * (72.27 / 72.0);
    }

    /** XDV native 字体 size 字段（16.16 定点 pt）→ mm */
    public double fontSizeToMm(int sizeRaw) {
        double pt = Integer.toUnsignedLong(sizeRaw) / 65536.0;
        return texPtToMm(pt) * scale;
    }

    /** 输出归一化：保留 4 位小数，消除 -0.0 与长尾数 */
    public static double r(double v) {
        double r = Math.round(v * 10000.0) / 10000.0;
        return r == 0.0 ? 0.0 : r;
    }

    /** 包围盒向外取整（4 位小数），防止查看器裁切边缘字形 */
    public static double floor4(double v) {
        return Math.floor(v * 10000.0) / 10000.0;
    }

    public static double ceil4(double v) {
        return Math.ceil(v * 10000.0) / 10000.0;
    }
}
