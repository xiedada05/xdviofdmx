package xdviofdmx.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 缓冲中的页面内容（XDV 无 EOP 回调，下一 BOP 或 finish 时落页）。
 */
public final class PageContent {

    public final int pageIndex;        // 0 起
    public final int[] counters;       // TeX 10 页计数器
    public Double widthMm;             // papersize/pdf:pagesize；null → A4 缺省
    public Double heightMm;
    public ColorSpec background;

    public final List<PageItem> items = new ArrayList<>();
    public final List<LinkZone> links = new ArrayList<>();
    public boolean hasContent = false;

    public PageContent(int pageIndex, int[] counters) {
        this.pageIndex = pageIndex;
        this.counters = counters;
    }

    public void add(PageItem item) {
        items.add(item);
        hasContent = true;
    }
}
