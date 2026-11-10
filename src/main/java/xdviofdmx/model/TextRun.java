package xdviofdmx.model;

import xdviofdmx.GraphicsState;

/**
 * 一个字形 run（XDV 253/254）。
 *
 * @param fontNum   native 字体编号
 * @param gids      字形索引
 * @param xs        每个字形原点 X（DVI mm，页面绝对）
 * @param ys        每个字形原点 Y（DVI mm，页面绝对）
 * @param unicodes  每个字形对应的 Unicode 文本（null 表示无法反查，需轮廓兜底）
 * @param actualText opcode 254 携带的原始文本（null 表示 253）
 */
public final class TextRun extends PageItem {

    public final int fontNum;
    public final int[] gids;
    public final double[] xs;
    public final double[] ys;
    public final String[] unicodes;
    public final String actualText;

    public TextRun(GraphicsState.Snapshot state, int fontNum, int[] gids,
                   double[] xs, double[] ys, String[] unicodes, String actualText) {
        super(state);
        this.fontNum = fontNum;
        this.gids = gids;
        this.xs = xs;
        this.ys = ys;
        this.unicodes = unicodes;
        this.actualText = actualText;
    }
}
