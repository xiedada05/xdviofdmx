package xdviofdmx.model;

import xdviofdmx.GraphicsState;

/**
 * DVI rule（set_rule/put_rule）：底边在基线 y 上，向上延伸 h，向右延伸 w。
 */
public final class Rule extends PageItem {

    public final double x;
    public final double y;    // 基线（底边）
    public final double w;
    public final double h;    // 全部 DVI mm

    public Rule(GraphicsState.Snapshot state, double x, double y, double w, double h) {
        super(state);
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    /** y 向下空间中的矩形（顶边） */
    public double topY() {
        return y - h;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double w() {
        return w;
    }

    public double h() {
        return h;
    }
}
