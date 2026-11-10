package xdviofdmx.font;

/**
 * XDV native 字体定义（opcode 252，由 xdvjni 解析交付）。
 * name 字段是 XeTeX 字体管理器解析出的字体文件路径。
 *
 * @param colorRgba 仅 XDV_FLAG_COLORED 时非空（RGBA）
 * @param extend    水平拉伸（16.16 定点，1.0=65536）
 * @param slant     斜切（16.16 定点，tan 值）
 * @param embolden  描边加粗量（16.16 定点 pt）
 */
public record NativeFontDef(int fontNum, String name, int sizeRaw, int faceIndex,
                            Integer colorRgba, Integer extend, Integer slant, Integer embolden) {

    public double extendRatio() {
        return extend == null ? 1.0 : Integer.toUnsignedLong(extend) / 65536.0;
    }

    public double slantTan() {
        return slant == null ? 0.0 : slant / 65536.0;
    }

    public double emboldenPt() {
        return embolden == null ? 0.0 : Integer.toUnsignedLong(embolden) / 65536.0;
    }

    /** COLORED 字体的 alpha（0~1），无颜色时 1.0 */
    public double colorAlpha() {
        if (colorRgba == null) {
            return 1.0;
        }
        return (Integer.toUnsignedLong(colorRgba) & 0xFF) / 255.0;
    }

    /** COLORED 字体的 RGB（0~1 数组），无颜色时 null */
    public double[] colorRgb() {
        if (colorRgba == null) {
            return null;
        }
        long v = Integer.toUnsignedLong(colorRgba);
        return new double[]{
                ((v >>> 24) & 0xFF) / 255.0,
                ((v >>> 16) & 0xFF) / 255.0,
                ((v >>> 8) & 0xFF) / 255.0};
    }
}
