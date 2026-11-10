package xdviofdmx.model;

import xdviofdmx.GraphicsState;

/**
 * 页面图元基类：携带事件时刻的图形状态快照。
 * 坐标均为 DVI 毫米空间（65536 单位 = 1 TeX pt，y 向下）；
 * 页面位置 = snapshot.ctm ∘ 坐标。
 */
public abstract class PageItem {

    private final GraphicsState.Snapshot state;

    protected PageItem(GraphicsState.Snapshot state) {
        this.state = state;
    }

    public GraphicsState.Snapshot state() {
        return state;
    }
}
