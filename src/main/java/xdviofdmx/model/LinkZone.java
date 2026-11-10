package xdviofdmx.model;

import xdviofdmx.GraphicsState;

/**
 * 链接区域（pdf:bann/eann 之间内容的包围盒）。
 * DVI mm 空间矩形；动作二选一：URI 外链 或 内部目标名。
 */
public final class LinkZone extends PageItem {

    public final double x;
    public final double y;
    public final double w;
    public final double h;
    /** 外部 URI；与 destName 互斥 */
    public final String uri;
    /** 内部命名目标 */
    public final String destName;

    public LinkZone(GraphicsState.Snapshot state, double x, double y, double w, double h,
                    String uri, String destName) {
        super(state);
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.uri = uri;
        this.destName = destName;
    }

    public static LinkZone uri(GraphicsState.Snapshot st, double x, double y, double w, double h, String uri) {
        return new LinkZone(st, x, y, w, h, uri, null);
    }

    public static LinkZone dest(GraphicsState.Snapshot st, double x, double y, double w, double h, String name) {
        return new LinkZone(st, x, y, w, h, null, name);
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

    public String uri() {
        return uri;
    }

    public String destName() {
        return destName;
    }
}
