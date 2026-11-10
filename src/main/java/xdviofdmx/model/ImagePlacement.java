package xdviofdmx.model;

import xdviofdmx.GraphicsState;

import java.nio.file.Path;

/**
 * pdf:image / PSfile 图像放置请求。
 * (x, y) 为 special 时的当前点（基线参考点，DVI mm）；srcBboxBp 为源 bbox（bp，y 向上）。
 *
 * @param source        图像源文件（PDF/EPS 为待栅格化文件）
 * @param kind          位图 / PDF / EPS
 * @param pdfPage       PDF 页码（0/1 均按首页处理，仅 PDF）
 * @param srcBboxBp     源 bbox（bp）：{llx, lly, urx, ury}；null 表示由源自身决定
 * @param matrix        pdf:image 的 matrix 选项（6 元，null 表示恒等）
 * @param pageBox       pagebox 选项（cropbox/mediabox/...，仅 PDF）
 * @param widthMm       目标宽度（bp；null 表示由 bbox/natural 决定）
 * @param heightMm      目标高度（bp；null 表示由 bbox/natural 决定）
 * @param depthMm       基线以下深度（DVI mm）
 * @param clipRequested bbox 裁剪请求（clip 1）
 */
public final class ImagePlacement extends PageItem {

    public enum Kind { RASTER, PDF, EPS }

    public final Path source;
    public final Kind kind;
    public final int pdfPage;
    public final double xMm;
    public final double yMm;
    public final double[] srcBboxBp;
    public final double[] matrix;
    public final String pageBox;
    public final Double widthMm;
    public final Double heightMm;
    public final double depthMm;
    public final boolean clipRequested;

    @SuppressWarnings("this-escape")
    public ImagePlacement(GraphicsState.Snapshot state, Path source, Kind kind, int pdfPage,
                          double xMm, double yMm, double[] srcBboxBp, double[] matrix,
                          String pageBox, Double widthMm, Double heightMm,
                          double depthMm, boolean clipRequested) {
        super(state);
        this.source = source;
        this.kind = kind;
        this.pdfPage = pdfPage;
        this.xMm = xMm;
        this.yMm = yMm;
        this.srcBboxBp = srcBboxBp;
        this.matrix = matrix;
        this.pageBox = pageBox;
        this.widthMm = widthMm;
        this.heightMm = heightMm;
        this.depthMm = depthMm;
        this.clipRequested = clipRequested;
    }

    /** 源图自然尺寸（mm，DVI 空间）；bbox 缺失时返回 null */
    public double[] naturalSizeMm() {
        if (srcBboxBp == null) {
            return null;
        }
        double w = xdviofdmx.Coord.bpToMm(srcBboxBp[2] - srcBboxBp[0]);
        double h = xdviofdmx.Coord.bpToMm(srcBboxBp[3] - srcBboxBp[1]);
        return new double[]{w, h};
    }

    public Path source() {
        return source;
    }

    public Kind kind() {
        return kind;
    }

    public int pdfPage() {
        return pdfPage;
    }

    public double xMm() {
        return xMm;
    }

    public double yMm() {
        return yMm;
    }

    public double[] srcBboxBp() {
        return srcBboxBp;
    }

    public double[] matrix() {
        return matrix;
    }

    public String pageBox() {
        return pageBox;
    }

    public Double widthMm() {
        return widthMm;
    }

    public Double heightMm() {
        return heightMm;
    }

    public double depthMm() {
        return depthMm;
    }

    public boolean clipRequested() {
        return clipRequested;
    }
}
