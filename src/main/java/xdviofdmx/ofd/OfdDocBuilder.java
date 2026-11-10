package xdviofdmx.ofd;

import org.ofdrw.core.action.Actions;
import org.ofdrw.core.action.CT_Action;
import org.ofdrw.core.action.EventType;
import org.ofdrw.core.action.actionType.GotoA;
import org.ofdrw.core.action.actionType.URI;
import org.ofdrw.core.action.actionType.actionGoto.CT_Dest;
import org.ofdrw.core.action.actionType.actionGoto.DestType;
import org.ofdrw.core.basicStructure.pageObj.layer.block.ImageObject;
import org.ofdrw.core.basicStructure.pageObj.layer.block.PathObject;
import org.ofdrw.core.basicStructure.pageObj.layer.block.TextObject;
import org.ofdrw.core.basicType.ST_Array;
import org.ofdrw.core.basicType.ST_ID;
import org.ofdrw.core.basicType.ST_RefID;
import org.ofdrw.core.graph.pathObj.AbbreviatedData;
import org.ofdrw.core.pageDescription.color.color.CT_Color;
import org.ofdrw.core.text.CT_CGTransform;
import org.ofdrw.core.text.TextCode;
import org.ofdrw.font.Font;
import org.ofdrw.graphics2d.OFDGraphicsDocument;
import org.ofdrw.graphics2d.OFDPageGraphics2D;
import org.ofdrw.layout.engine.ResManager;
import xdviofdmx.Coord;
import xdviofdmx.GraphicsState;
import xdviofdmx.XdvInterpreter;
import xdviofdmx.font.FontRegistry;
import xdviofdmx.font.LoadedFont;
import xdviofdmx.font.OutlineSeg;
import xdviofdmx.model.ColorSpec;
import xdviofdmx.model.DestPoint;
import xdviofdmx.model.ImagePlacement;
import xdviofdmx.model.LinkZone;
import xdviofdmx.model.PageContent;
import xdviofdmx.model.PageItem;
import xdviofdmx.model.Rule;
import xdviofdmx.model.TextRun;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * OFD 文档构建器：把缓冲的 {@link PageContent} 逐图元发射为 ofdrw 对象。
 *
 * <p>对象坐标系模型（GB/T 33190：对象坐标原点 = Boundary 左上角，CTM 映射对象坐标到父空间）：
 * 对象空间 = DVI 毫米空间平移至包围盒左上角；Boundary 取变换后包围盒（父空间）；
 * CTM 只写纯线性部分（与 ofdrw-converter TextRunBuilder 同一约定）；
 * TextCode.X/Y = 首字形原点相对 Boundary 左上角的偏移（对象空间）；
 * DeltaX/DeltaY = 相邻字形原点之差（对象空间）。</p>
 */
public final class OfdDocBuilder implements XdvInterpreter.Emitter, AutoCloseable {

    /** 单个文字对象的最大字形数（与 ofdrw-converter 一致） */
    private static final int MAX_GLYPHS_PER_RUN = 2000;
    /** 包围盒外扩比例（1%）+ 绝对下限，吸收浮点误差与查看器取整差异 */
    private static final double BOUNDARY_PADDING_RATIO = 0.01;
    private static final double BOUNDARY_PADDING_MIN = 0.01;
    private static final String PLACEHOLDER = "\u00A4";
    private static final double DEFAULT_WIDTH_MM = 210.0;
    private static final double DEFAULT_HEIGHT_MM = 297.0;

    private final Coord coord;
    private final OFDGraphicsDocument doc;
    private final ResManager res;
    private final FontRegistry fonts;
    private final boolean embedFonts;
    private final XdvInterpreter.Log log;

    private final Map<Integer, ST_ID> fontIds = new HashMap<>();
    /** 每字体的资源名 → 使用的 GID 集合（子集化输入） */
    private final Map<String, java.util.TreeSet<Integer>> fontUsedGids = new LinkedHashMap<>();
    /** 每字体的资源名 → 使用的 Unicode 码位集合（子集 cmap 重建） */
    private final Map<String, java.util.TreeSet<Integer>> fontUsedUnicodes = new LinkedHashMap<>();
    /** 资源名 → 字体文件在 Res 容器中的实际路径（子集化覆写目标） */
    private final Map<String, Path> fontResPaths = new LinkedHashMap<>();
    private final Map<String, ST_ID> imageIds = new LinkedHashMap<>();
    private final List<ST_ID> pageIds = new ArrayList<>();
    private final Map<String, DestPoint> destByName = new HashMap<>();
    private final Map<String, Path> rasterCache = new HashMap<>();
    /** 每页已构建的页面对象（链接在全部 dest 收集后统一发射，支持前向引用） */
    private final List<OFDPageGraphics2D> pageGraphicsList = new ArrayList<>();
    private boolean gsAvailable = true;

    public OfdDocBuilder(Path outPath, Coord coord, FontRegistry fonts,
                         boolean embedFonts, XdvInterpreter.Log log) throws IOException {
        this.coord = coord;
        this.fonts = fonts;
        this.embedFonts = embedFonts;
        this.log = log;
        this.doc = new OFDGraphicsDocument(outPath);
        this.res = new ResManager(doc.ofdDir, doc.docDir, doc.MaxUnitID);
    }

    // ---------------------------------------------------------------- 页面发射

    @Override
    public void emitPage(PageContent page, List<DestPoint> pageDests) {
        double w = page.widthMm != null ? page.widthMm : DEFAULT_WIDTH_MM;
        double h = page.heightMm != null ? page.heightMm : DEFAULT_HEIGHT_MM;
        OFDPageGraphics2D pg = doc.newPage(w, h);
        while (pageIds.size() <= page.pageIndex) {
            pageIds.add(null);
        }
        pageIds.set(page.pageIndex, pg.pageID);
        while (pageGraphicsList.size() <= page.pageIndex) {
            pageGraphicsList.add(null);
        }
        pageGraphicsList.set(page.pageIndex, pg);
        for (DestPoint d : pageDests) {
            destByName.putIfAbsent(d.name(), d);
        }

        // 背景（首个对象 = z 序最底）
        if (page.background != null) {
            PathObject bg = newPath(pg);
            bg.setBoundary(0.0, 0.0, w, h);
            AbbreviatedData ad = new AbbreviatedData();
            ad.M(0, 0).L(w, 0).L(w, h).L(0, h).C();
            bg.setAbbreviatedData(ad);
            bg.setFill(true);
            bg.setStroke(false);
            bg.setFillColor(colorOf(page.background));
            append(pg, bg);
        }

        for (PageItem item : page.items) {
            if (item instanceof TextRun run) {
                emitTextRun(pg, run);
            } else if (item instanceof Rule rule) {
                emitRule(pg, rule);
            } else if (item instanceof ImagePlacement img) {
                emitImage(pg, img);
            }
        }
        // 链接区域在 finishLinks() 统一发射（dest 可能出现在后续页）
    }

    /** 全部页面发射完毕后调用：此时命名目标已收集完整，可解析前向引用 */
    public void finishLinks(List<PageContent> pagesWithLinks) {
        for (PageContent page : pagesWithLinks) {
            if (page.pageIndex >= pageGraphicsList.size() || pageGraphicsList.get(page.pageIndex) == null) {
                continue;
            }
            OFDPageGraphics2D pg = pageGraphicsList.get(page.pageIndex);
            for (LinkZone link : page.links) {
                emitLink(pg, link);
            }
        }
    }

    // ---------------------------------------------------------------- 文本

    private void emitTextRun(OFDPageGraphics2D pg, TextRun run) {
        LoadedFont font = fonts.get(run.fontNum);
        if (font == null) {
            warnOnce("fontNum=%d 无字体定义，跳过文本 run", run.fontNum);
            return;
        }
        int n = run.gids.length;
        int start = 0;
        while (start < n) {
            // 连续可反查字形 → 一个 TextObject；不可反查字形 → 轮廓兜底
            if (run.unicodes[start] == null) {
                emitGlyphOutline(pg, run, start, font);
                start++;
                continue;
            }
            int end = start + 1;
            while (end < n && run.unicodes[end] != null && end - start < MAX_GLYPHS_PER_RUN) {
                end++;
            }
            emitTextSegment(pg, run, start, end, font);
            start = end;
        }
    }

    private void emitTextSegment(OFDPageGraphics2D pg, TextRun run, int from, int to, LoadedFont font) {
        int count = to - from;
        double em = Math.max(font.emMm, 1e-6);
        double ascent = font.ascentMm;
        double descent = font.descentMm;
        double[] xs = new double[count];
        double[] ys = new double[count];
        StringBuilder content = new StringBuilder();
        int[] gids = new int[count];
        for (int i = 0; i < count; i++) {
            int k = from + i;
            xs[i] = run.xs[k];
            ys[i] = run.ys[k];
            gids[i] = run.gids[k];
            String u = run.unicodes[k];
            content.append(u == null || u.isEmpty() ? PLACEHOLDER : u);
            noteGlyphUse(font, gids[i], u);
        }

        // 墨迹包围盒（AWT 字形轮廓，AWT pt → mm），无 AWT 时退化为基线/字号估计
        double inkMinX = Double.MAX_VALUE, inkMaxX = -Double.MAX_VALUE;
        double inkMinY = Double.MAX_VALUE, inkMaxY = -Double.MAX_VALUE;
        if (font.awtFont != null) {
            try {
                var gv = font.awtFont.createGlyphVector(LoadedFont.frc(), gids);
                for (int i = 0; i < count; i++) {
                    Rectangle2D b = gv.getGlyphVisualBounds(i).getBounds2D();
                    Point2D layout = gv.getGlyphPosition(i);
                    if (!b.isEmpty() && !Double.isNaN(b.getMinX())) {
                        double bx0 = xs[i] + LoadedFont.awtPtToMm(b.getMinX() - layout.getX());
                        double by0 = ys[i] + LoadedFont.awtPtToMm(b.getMinY() - layout.getY());
                        double bx1 = xs[i] + LoadedFont.awtPtToMm(b.getMaxX() - layout.getX());
                        double by1 = ys[i] + LoadedFont.awtPtToMm(b.getMaxY() - layout.getY());
                        inkMinX = Math.min(inkMinX, bx0);
                        inkMinY = Math.min(inkMinY, by0);
                        inkMaxX = Math.max(inkMaxX, bx1);
                        inkMaxY = Math.max(inkMaxY, by1);
                    }
                }
            } catch (Exception e) {
                // 度量失败退化为基线估计
            }
        }

        double minOriginX = Double.MAX_VALUE, maxOriginX = -Double.MAX_VALUE;
        double minOriginY = Double.MAX_VALUE, maxOriginY = -Double.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            minOriginX = Math.min(minOriginX, xs[i]);
            maxOriginX = Math.max(maxOriginX, Math.max(xs[i], xs[i] + em));
            minOriginY = Math.min(minOriginY, ys[i]);
            maxOriginY = Math.max(maxOriginY, ys[i]);
        }
        double topY = ys[0] - ascent;
        double bottomY = Math.max(maxOriginY + descent,
                inkMaxY == -Double.MAX_VALUE ? maxOriginY + descent : inkMaxY);
        double boxMinX = Math.min(minOriginX, inkMinX == Double.MAX_VALUE ? minOriginX : inkMinX);
        double boxMaxX = Math.max(maxOriginX, inkMaxX == -Double.MAX_VALUE ? maxOriginX : inkMaxX);
        double boxMinY = Math.min(topY, bottomY);
        double boxMaxY = Math.max(bottomY, topY);
        double w = boxMaxX - boxMinX;
        double h = boxMaxY - boxMinY;
        double padX = Math.abs(w) * BOUNDARY_PADDING_RATIO + BOUNDARY_PADDING_MIN;
        double padY = Math.abs(h) * BOUNDARY_PADDING_RATIO + BOUNDARY_PADDING_MIN;

        // 对象空间 = DVI 空间平移至包围盒左上角
        double btlX = Coord.floor4(boxMinX - padX);
        double btlY = Coord.floor4(boxMinY - padY);
        double bw = Coord.ceil4(boxMaxX + padX) - btlX;
        double bh = Coord.ceil4(boxMaxY + padY) - btlY;

        GraphicsState.Snapshot st = run.state();
        AffineTransform m = new AffineTransform(st.ctm());
        if (font.def.slantTan() != 0.0) {
            m.concatenate(slantAbout(ys[0], font.def.slantTan()));
        }

        TextObject text = new TextObject(pg.newObjectId());
        // 1. 字号与字体（必选）
        ST_ID fontId = registerFont(font);
        if (fontId == null) {
            return;
        }
        text.setFont(new ST_RefID(fontId));
        text.setSize(Coord.r(em));
        // 2. 变换与边界
        applyCtmAndBoundary(text, m, btlX, btlY, bw, bh);
        // 3. 绘制属性（GB/T 33190 表 16 顺序：颜色先于文字内容）
        ColorSpec fill = font.def.colorRgb() != null
                ? ColorSpec.rgb(font.def.colorRgb()[0], font.def.colorRgb()[1], font.def.colorRgb()[2])
                : st.fill();
        text.setFill(true);
        applyRenderingMode(text, st, fill);
        if (fill != null) {
            text.setFillColor(colorOf(fill));
        }
        int alpha = Math.min(st.alpha(), (int) Math.round(font.def.colorAlpha() * 255.0));
        if (alpha < 255) {
            text.setAlpha(alpha);
        }
        if (font.def.extendRatio() != 1.0) {
            text.setHScale(Coord.r(font.def.extendRatio()));
        }
        if (font.def.emboldenPt() > 0) {
            double lw = Math.max(0.05, Coord.texPtToMm(font.def.emboldenPt()));
            text.setStroke(true);
            text.setLineWidth(Coord.r(lw));
            text.setStrokeColor(colorOf(fill));
        }
        // 4. CGTransform（先于 TextCode）
        addCgTransforms(text, font, gids, content.toString());
        // 5. TextCode（必须最后加入）
        TextCode code = new TextCode();
        code.setContent(content.toString());
        code.setCoordinate(Coord.r(xs[0] - btlX), Coord.r(ys[0] - btlY));
        if (count > 1) {
            Double[] dx = new Double[count - 1];
            Double[] dy = new Double[count - 1];
            for (int i = 1; i < count; i++) {
                dx[i - 1] = Coord.r(xs[i] - xs[i - 1]);
                dy[i - 1] = Coord.r(ys[i] - ys[i - 1]);
            }
            code.setDeltaX(dx);
            code.setDeltaY(dy);
        }
        text.addTextCode(code);

        if (st.hasClip()) {
            text.setClips(clipsOf(st, btlX, btlY));
        }
        append(pg, text);
    }

    /** 渲染模式（x:renderingmode）→ Fill/Stroke/Visible */
    private void applyRenderingMode(TextObject text, GraphicsState.Snapshot st, ColorSpec fill) {
        switch (st.renderingMode()) {
            case 0 -> text.setFill(true);
            case 1 -> {
                text.setFill(false);
                text.setStroke(true);
                text.setLineWidth(Math.max(0.05, Coord.texPtToMm(0.2)));
                text.setStrokeColor(colorOf(st.stroke()));
            }
            case 2 -> {
                text.setFill(true);
                text.setStroke(true);
                text.setLineWidth(Math.max(0.05, Coord.texPtToMm(0.2)));
                text.setStrokeColor(colorOf(st.stroke()));
                if (fill != null) {
                    text.setFillColor(colorOf(fill));
                }
            }
            case 3 -> text.setVisible(false);
            default -> {
                warnOnce("renderingmode %d（裁剪类）近似为填充模式", st.renderingMode());
                text.setFill(true);
            }
        }
    }

    /**
     * CGTransform：当字形与反查文本不能由内嵌字体 cmap 直接对应时，写精确 GID。
     * 本实现中每个字形对应 TextCode 中的 1 个字符（无法反查的字形走轮廓兜底）；
     * 全部 cmap(gid)==gid 时省略 CGTransform（查看器按内嵌字体 cmap 渲染即可）。
     */
    private void addCgTransforms(TextObject text, LoadedFont font, int[] gids, String content) {
        boolean needed = false;
        for (int i = 0; i < gids.length && i < content.length(); i++) {
            if (font.gidOf(String.valueOf(content.charAt(i))) != gids[i]) {
                needed = true;
                break;
            }
        }
        if (!needed) {
            return;
        }
        text.addCGTransform(new CT_CGTransform()
                .setCodePosition(0)
                .setCodeCount(content.length())
                .setGlyphCount(gids.length)
                .setGlyphs(intArrayToSTArray(gids)));
    }

    private static ST_Array intArrayToSTArray(int[] gids) {
        Integer[] arr = new Integer[gids.length];
        for (int i = 0; i < gids.length; i++) {
            arr[i] = gids[i];
        }
        return new ST_Array(arr);
    }

    // ---------------------------------------------------------------- 轮廓兜底

    private void emitGlyphOutline(OFDPageGraphics2D pg, TextRun run, int index, LoadedFont font) {
        List<OutlineSeg> segs = font.outlineOf(run.gids[index] & 0xFFFF);
        if (segs == null || segs.isEmpty()) {
            return; // 轮廓不可用：放弃该字形（已警告过）
        }
        noteGlyphUse(font, run.gids[index] & 0xFFFF, null);
        double px = run.xs[index];
        double py = run.ys[index];
        // AWT pt → DVI mm（基线原点，y 向下）
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        List<double[]> pts = new ArrayList<>();
        for (OutlineSeg seg : segs) {
            double[] c = new double[seg.coords().length];
            for (int i = 0; i < c.length; i++) {
                c[i] = (i % 2 == 0) ? px + LoadedFont.awtPtToMm(seg.coords()[i])
                        : py + LoadedFont.awtPtToMm(seg.coords()[i]);
                if (i % 2 == 0) {
                    minX = Math.min(minX, c[i]);
                    maxX = Math.max(maxX, c[i]);
                } else {
                    minY = Math.min(minY, c[i]);
                    maxY = Math.max(maxY, c[i]);
                }
            }
            pts.add(c);
        }
        if (minX > maxX) {
            return;
        }
        double pad = BOUNDARY_PADDING_MIN;
        double btlX = Coord.floor4(minX - pad);
        double btlY = Coord.floor4(minY - pad);
        double bw = Coord.ceil4(maxX + pad) - btlX;
        double bh = Coord.ceil4(maxY + pad) - btlY;

        GraphicsState.Snapshot st = run.state();
        AffineTransform m = new AffineTransform(st.ctm());
        PathObject path = newPath(pg);
        applyCtmAndBoundary(path, m, btlX, btlY, bw, bh);
        AbbreviatedData ad = new AbbreviatedData();
        for (double[] c : pts) {
            switch (c.length) {
                case 2 -> ad.M(Coord.r(c[0] - btlX), Coord.r(c[1] - btlY));
                case 4 -> ad.Q(Coord.r(c[0] - btlX), Coord.r(c[1] - btlY),
                        Coord.r(c[2] - btlX), Coord.r(c[3] - btlY));
                case 6 -> ad.B(Coord.r(c[0] - btlX), Coord.r(c[1] - btlY),
                        Coord.r(c[2] - btlX), Coord.r(c[3] - btlY),
                        Coord.r(c[4] - btlX), Coord.r(c[5] - btlY));
                default -> ad.C();
            }
        }
        path.setAbbreviatedData(ad);
        path.setFill(true);
        path.setStroke(false);
        path.setRule(org.ofdrw.core.graph.pathObj.Rule.NonZero);
        path.setFillColor(colorOf(st.fill()));
        if (st.alpha() < 255) {
            path.setAlpha(st.alpha());
        }
        if (st.hasClip()) {
            path.setClips(clipsOf(st, btlX, btlY));
        }
        append(pg, path);
    }

    // ---------------------------------------------------------------- 规则

    private void emitRule(OFDPageGraphics2D pg, Rule rule) {
        GraphicsState.Snapshot st = rule.state();
        double rectX = rule.x();
        double rectY = rule.topY();
        AffineTransform m = new AffineTransform(st.ctm());
        PathObject path = newPath(pg);
        applyCtmAndBoundary(path, m, rectX, rectY, rule.w(), rule.h());
        AbbreviatedData ad = new AbbreviatedData();
        ad.M(0, 0).L(Coord.r(rule.w()), 0).L(Coord.r(rule.w()), Coord.r(rule.h()))
                .L(0, Coord.r(rule.h())).C();
        path.setAbbreviatedData(ad);
        path.setFill(true);
        path.setStroke(false);
        path.setRule(org.ofdrw.core.graph.pathObj.Rule.NonZero);
        path.setFillColor(colorOf(st.fill()));
        if (st.alpha() < 255) {
            path.setAlpha(st.alpha());
        }
        if (st.hasClip()) {
            path.setClips(clipsOf(st, rectX, rectY));
        }
        append(pg, path);
    }

    // ---------------------------------------------------------------- 图像

    private void emitImage(OFDPageGraphics2D pg, ImagePlacement img) {
        Path resolved = resolveImage(img);
        if (resolved == null) {
            return;
        }
        double[] natural = naturalSizeMm(img, resolved);
        if (natural == null) {
            warnOnce("无法确定图像尺寸: %s", img.source());
            return;
        }
        double natW = Math.max(natural[0], 1e-6);
        double natH = Math.max(natural[1], 1e-6);
        double w = img.widthMm() != null ? img.widthMm() : natW;
        double h = img.heightMm() != null ? img.heightMm() : natH;
        // 只给一边时按源图纵横比补全
        if (img.widthMm() != null && img.heightMm() == null) {
            h = natH * (w / natW);
        } else if (img.heightMm() != null && img.widthMm() == null) {
            w = natW * (h / natH);
        }
        // pdf:image 放置（xdvipdfmx 语义）：源 bbox 左下角对齐当前点（y 向上），
        // 深度部分位于基线下方；DVI y 向下：顶边 = y + depth − height
        double llxMm = img.srcBboxBp() != null ? Coord.bpToMm(img.srcBboxBp()[0]) : 0.0;
        double scaleX = w / natW;
        double rectX = img.xMm() - llxMm * scaleX;
        double rectY = img.yMm() + img.depthMm() - h;

        ST_ID resId = registerImage(resolved);
        if (resId == null) {
            return;
        }
        GraphicsState.Snapshot st = img.state();
        AffineTransform m = new AffineTransform(st.ctm());
        if (img.matrix() != null) {
            m.concatenate(new AffineTransform(img.matrix()));
        }
        /*
         * 图像发射约定（与 ofdrw 生态一致，经 CTM 探针实证）：
         * 图像内容定义在单位方阵 [0,1]² 上，CTM 携带尺寸（线性部分 ∘ diag(w,h)），
         * Boundary = 放置矩形的父空间 AABB（锚定内容左上角并兼作裁剪框）。
         * 文本对象则不同：对象坐标 = DVI 空间 − 包围盒左上角，CTM 只写纯线性部分。
         */
        double[] corners = {
                rectX, rectY, rectX + w, rectY, rectX + w, rectY + h, rectX, rectY + h};
        double[] dst = new double[8];
        m.transform(corners, 0, dst, 0, 4);
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            minX = Math.min(minX, dst[2 * i]);
            maxX = Math.max(maxX, dst[2 * i]);
            minY = Math.min(minY, dst[2 * i + 1]);
            maxY = Math.max(maxY, dst[2 * i + 1]);
        }
        ImageObject obj = new ImageObject(pg.newObjectId());
        obj.setBoundary(Coord.floor4(minX), Coord.floor4(minY),
                Coord.ceil4(maxX - minX), Coord.ceil4(maxY - minY));
        AffineTransform lin = new AffineTransform(
                m.getScaleX(), m.getShearY(), m.getShearX(), m.getScaleY(), 0, 0);
        lin.scale(w, h);
        obj.setCTM(new ST_Array(
                Coord.r(lin.getScaleX()), Coord.r(lin.getShearY()),
                Coord.r(lin.getShearX()), Coord.r(lin.getScaleY()),
                0.0, 0.0));
        obj.setResourceID(resId.ref());
        if (st.alpha() < 255) {
            obj.setAlpha(st.alpha());
        }
        if (st.hasClip()) {
            obj.setClips(clipsOf(st, rectX, rectY));
        }
        append(pg, obj);
    }

    /** PDF/EPS 栅格化；RASTER 原样返回 */
    private Path resolveImage(ImagePlacement img) {
        if (img.kind() == ImagePlacement.Kind.RASTER || !Files.isRegularFile(img.source())) {
            if (!Files.isRegularFile(img.source())) {
                warnOnce("图像文件不存在: %s", img.source());
                return null;
            }
            return img.source();
        }
        String cacheKey = img.source().toAbsolutePath() + "#" + img.pdfPage();
        Path cached = rasterCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        try {
            Path out = rasterCacheDir().resolve(rasterName(img.source(), img.pdfPage()));
            if (img.kind() == ImagePlacement.Kind.PDF) {
                rasterizePdf(img.source(), img.pdfPage(), out);
            } else {
                rasterizeEps(img.source(), out);
            }
            rasterCache.put(cacheKey, out);
            return out;
        } catch (Exception e) {
            warnOnce("图像栅格化失败 %s: %s", img.source(), e.getMessage());
            return null;
        }
    }

    private Path rasterCacheDir;

    private Path rasterCacheDir() throws IOException {
        if (rasterCacheDir == null) {
            rasterCacheDir = Files.createTempDirectory("xdviofdmx-img");
        }
        return rasterCacheDir;
    }

    private static String rasterName(Path src, int page) {
        String name = src.getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_");
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return base + (page > 1 ? "-p" + page : "") + ".png";
    }

    private void rasterizePdf(Path pdf, int page, Path out) throws IOException {
        try (var document = org.apache.pdfbox.Loader.loadPDF(pdf.toFile())) {
            int idx = Math.max(0, Math.min(document.getNumberOfPages() - 1, page - 1));
            var renderer = new org.apache.pdfbox.rendering.PDFRenderer(document);
            var image = renderer.renderImageWithDPI(idx, 300);
            javax.imageio.ImageIO.write(image, "png", out.toFile());
        }
    }

    private void rasterizeEps(Path eps, Path out) throws IOException, InterruptedException {
        if (!gsAvailable) {
            throw new IOException("系统缺少 Ghostscript，无法栅格化 EPS");
        }
        Process p = new ProcessBuilder("gs", "-dSAFER", "-dEPSCrop", "-dBATCH", "-dNOPAUSE",
                "-sDEVICE=pngalpha", "-r300", "-dTextAlphaBits=4", "-dGraphicsAlphaBits=4",
                "-sOutputFile=" + out, eps.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .start();
        String outText = new String(p.getInputStream().readAllBytes());
        int code = p.waitFor();
        if (code != 0 || !Files.isRegularFile(out)) {
            gsAvailable = gsCheck(outText);
            throw new IOException("Ghostscript 退出码 " + code);
        }
    }

    private static boolean gsCheck(String gsOutput) {
        return !gsOutput.contains("not found") && !gsOutput.contains("No such file");
    }

    /** 自然尺寸（DVI mm）：bbox 优先；PDF 取页面框；位图按像素（1px=1bp） */
    private double[] naturalSizeMm(ImagePlacement img, Path resolved) {
        double[] nat = img.naturalSizeMm();
        if (nat != null && nat[0] > 0 && nat[1] > 0) {
            return nat;
        }
        if (img.kind() == ImagePlacement.Kind.PDF) {
            // PDF 矢量图：自然尺寸 = 页面框（pagebox 选项），与栅格化分辨率无关
            try (var document = org.apache.pdfbox.Loader.loadPDF(img.source().toFile())) {
                int idx = Math.max(0, Math.min(document.getNumberOfPages() - 1, img.pdfPage() - 1));
                var page = document.getPage(idx);
                var box = switch (img.pageBox() == null ? "cropbox" : img.pageBox().toLowerCase()) {
                    case "mediabox" -> page.getMediaBox();
                    case "artbox" -> page.getArtBox() != null ? page.getArtBox() : page.getCropBox();
                    case "trimbox" -> page.getTrimBox() != null ? page.getTrimBox() : page.getCropBox();
                    case "bleedbox" -> page.getBleedBox() != null ? page.getBleedBox() : page.getCropBox();
                    default -> page.getCropBox() != null ? page.getCropBox() : page.getMediaBox();
                };
                return new double[]{Coord.bpToMm(box.getWidth()), Coord.bpToMm(box.getHeight())};
            } catch (Exception e) {
                return null;
            }
        }
        try {
            var readers = javax.imageio.ImageIO.getImageReadersBySuffix(ext(resolved));
            if (!readers.hasNext()) {
                return null;
            }
            var reader = readers.next();
            try (var in = javax.imageio.ImageIO.createImageInputStream(resolved.toFile())) {
                reader.setInput(in);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                return new double[]{Coord.bpToMm(w), Coord.bpToMm(h)};
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String ext(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    // ---------------------------------------------------------------- 链接

    private void emitLink(OFDPageGraphics2D pg, LinkZone link) {
        if (link.uri() == null || link.uri().isBlank()) {
            return;
        }
        GraphicsState.Snapshot st = link.state();
        AffineTransform m = new AffineTransform(st.ctm());
        PathObject zone = newPath(pg);
        applyCtmAndBoundary(zone, m, link.x(), link.y(), link.w(), link.h());
        AbbreviatedData ad = new AbbreviatedData();
        ad.M(0, 0).L(Coord.r(link.w()), 0).L(Coord.r(link.w()), Coord.r(link.h()))
                .L(0, Coord.r(link.h())).C();
        zone.setAbbreviatedData(ad);
        zone.setFill(false);
        zone.setStroke(false);
        Actions actions = new Actions();
        if (link.uri().startsWith("#")) {
            CT_Action action = gotoActionFor(link.uri().substring(1));
            if (action == null) {
                warnOnce("内部链接目标不存在: %s", link.uri());
                return;
            }
            actions.addAction(action);
        } else {
            actions.addAction(new CT_Action(EventType.CLICK, new URI(link.uri())));
        }
        zone.setActions(actions);
        append(pg, zone);
    }

    /** 按命名目标构造 GotoA 动作；目标未知时返回 null */
    private CT_Action gotoActionFor(String destName) {
        DestPoint target = destByName.get(destName);
        if (target == null) {
            return null;
        }
        ST_ID targetPageId = target.pageIndex() < pageIds.size() ? pageIds.get(target.pageIndex()) : null;
        if (targetPageId == null) {
            return null;
        }
        GotoA gotoA = new GotoA();
        CT_Dest dest = new CT_Dest()
                .setType(DestType.XYZ)
                .setPageID(new ST_RefID(targetPageId))
                .setLeft(Coord.r(target.xMm()))
                .setTop(Coord.r(target.yMm()));
        gotoA.add(dest);
        return new CT_Action(EventType.CLICK, gotoA);
    }

    // ---------------------------------------------------------------- 通用

    /**
     * 对象坐标模型：Boundary = M∘包围盒（父空间 AABB）；CTM = M 的线性部分（无平移）；
     * 对象内部坐标 = DVI 坐标 − BTL(DVI)。M 为恒等时不写 CTM。
     */
    private static void applyCtmAndBoundary(org.ofdrw.core.pageDescription.CT_GraphicUnit<?> obj,
                                            AffineTransform m,
                                            double btlX, double btlY, double w, double h) {
        if (m.isIdentity()) {
            obj.setBoundary(Coord.r(btlX), Coord.r(btlY), Coord.r(w), Coord.r(h));
            return;
        }
        // 变换包围盒四角 → 父空间 AABB
        double[] corners = {
                btlX, btlY, btlX + w, btlY, btlX + w, btlY + h, btlX, btlY + h};
        double[] dst = new double[8];
        m.transform(corners, 0, dst, 0, 4);
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            minX = Math.min(minX, dst[2 * i]);
            maxX = Math.max(maxX, dst[2 * i]);
            minY = Math.min(minY, dst[2 * i + 1]);
            maxY = Math.max(maxY, dst[2 * i + 1]);
        }
        double pad = BOUNDARY_PADDING_RATIO * Math.max(maxX - minX, maxY - minY) + BOUNDARY_PADDING_MIN;
        obj.setBoundary(Coord.floor4(minX - pad), Coord.floor4(minY - pad),
                Coord.ceil4(maxX - minX + 2 * pad), Coord.ceil4(maxY - minY + 2 * pad));
        // 纯线性 CTM（对象坐标系与页面同尺度、无平移）
        obj.setCTM(new ST_Array(
                Coord.r(m.getScaleX()), Coord.r(m.getShearY()),
                Coord.r(m.getShearX()), Coord.r(m.getScaleY()),
                0.0, 0.0));
    }

    /** 快照裁剪 → OFD Clips（对象空间坐标） */
    private org.ofdrw.core.pageDescription.clips.Clips clipsOf(GraphicsState.Snapshot st,
                                                              double btlX, double btlY) {
        org.ofdrw.core.pageDescription.clips.Clips clips =
                new org.ofdrw.core.pageDescription.clips.Clips();
        for (GraphicsState.ClipRect r : st.clips()) {
            org.ofdrw.core.graph.pathObj.CT_Path clipObj = new org.ofdrw.core.graph.pathObj.CT_Path();
            clipObj.setBoundary(Coord.r(r.x() - btlX), Coord.r(r.y() - btlY), Coord.r(r.w()), Coord.r(r.h()));
            AbbreviatedData ad = new AbbreviatedData();
            ad.M(0, 0)
                    .L(Coord.r(r.w()), 0)
                    .L(Coord.r(r.w()), Coord.r(r.h()))
                    .L(0, Coord.r(r.h()))
                    .C();
            clipObj.setAbbreviatedData(ad);
            clipObj.setFill(true);
            clips.addClip(new org.ofdrw.core.pageDescription.clips.CT_Clip()
                    .addArea(new org.ofdrw.core.pageDescription.clips.Area().setClipObj(clipObj)));
        }
        return clips;
    }

    private static AffineTransform slantAbout(double baselineY, double s) {
        AffineTransform at = new AffineTransform();
        at.translate(0, baselineY);
        at.shear(-s, 0);
        at.translate(0, -baselineY);
        return at;
    }

    private static CT_Color colorOf(ColorSpec c) {
        return switch (c.kind()) {
            case GRAY -> {
                int v = comp(c.components()[0]);
                yield CT_Color.rgb(v, v, v);
            }
            case RGB -> CT_Color.rgb(comp(c.components()[0]), comp(c.components()[1]), comp(c.components()[2]));
            case CMYK -> {
                var color = new CT_Color();
                color.setValue(new ST_Array(
                        comp(c.components()[0]), comp(c.components()[1]),
                        comp(c.components()[2]), comp(c.components()[3])));
                yield color;
            }
        };
    }

    private static int comp(double v01) {
        return Math.max(0, Math.min(255, (int) Math.round(v01 * 255.0)));
    }

    private ST_ID registerFont(LoadedFont font) {
        return fontIds.computeIfAbsent(font.def.fontNum(), num -> {
            try {
                Font f = (embedFonts && font.embedFile != null)
                        ? new Font(font.ofdName, font.familyName, font.embedFile)
                        : new Font(font.ofdName, font.familyName);
                ST_ID id = res.addFont(f);
                if (embedFonts && font.embedFile != null) {
                    fontResPaths.put(font.ofdName,
                            doc.docDir.obtainRes().getContainerPath()
                                    .resolve(font.embedFile.getFileName().toString()));
                    fontUsedGids.computeIfAbsent(font.ofdName, k -> new java.util.TreeSet<>()).add(0);
                    fontUsedUnicodes.computeIfAbsent(font.ofdName, k -> new java.util.TreeSet<>());
                }
                return id;
            } catch (IOException e) {
                log.warn("字体注册失败 %s: %s", font.ofdName, e.getMessage());
                return null;
            }
        });
    }

    /** 登记字体实际使用的 GID 与 Unicode（子集化输入）；集合按需创建，不依赖注册顺序 */
    private void noteGlyphUse(LoadedFont font, int gid, String unicode) {
        if (font.embedFile == null) {
            return;
        }
        fontUsedGids.computeIfAbsent(font.ofdName, k -> new java.util.TreeSet<>())
                .add(gid & 0xFFFF);
        if (unicode != null && !unicode.isEmpty()) {
            fontUsedUnicodes.computeIfAbsent(font.ofdName, k -> new java.util.TreeSet<>())
                    .addAll(unicode.codePoints().boxed().toList());
        }
    }

    private ST_ID registerImage(Path resolved) {
        return imageIds.computeIfAbsent(resolved.toAbsolutePath().toString(), p -> {
            try {
                return res.addImage(resolved);
            } catch (IOException e) {
                log.warn("图像注册失败 %s: %s", resolved, e.getMessage());
                return null;
            }
        });
    }

    private static PathObject newPath(OFDPageGraphics2D pg) {
        return new PathObject(pg.newObjectId());
    }

    private static void append(OFDPageGraphics2D pg, org.ofdrw.core.basicStructure.pageObj.layer.PageBlockType block) {
        pg.appendPageBlock(block);
    }

    private void warnOnce(String fmt, Object... args) {
        log.warn(fmt, args);
    }

    /** 文档元数据、书签树、字体子集化与打包 */
    public void finishMetadata(String title, List<xdviofdmx.XdvInterpreter.OutlineEntry> outlines) {
        try {
            var info = doc.ofdDir.getOfd().getDocBody().getDocInfo();
            info.setCreator("xdviofdmx 0.1.0");
            if (title != null && !title.isBlank()) {
                info.setTile(title);
            }
        } catch (Exception e) {
            log.warn("无法写入文档元数据: %s", e.getMessage());
        }
        if (outlines != null && !outlines.isEmpty()) {
            buildOutlines(outlines);
        }
        subsetFonts();
    }

    // ---------------------------------------------------------------- 字体子集化

    /**
     * 用 python3 fontTools.subset 对每个嵌入字体做子集化（retain_gids 保持字形编号，
     * CGTransform 无需重写），成功后覆写 Res 容器中的字体文件。
     * python3/fontTools 不可用时保留整字体（体积较大），不中断转换。
     */
    private void subsetFonts() {
        if (fontResPaths.isEmpty()) {
            return;
        }
        if (!fontToolsAvailable()) {
            log.warn("python3/fontTools 不可用，字体将整字体嵌入（文件体积较大）。"
                    + "安装 python3-fontTools 后可自动子集化。");
            return;
        }
        for (var entry : fontResPaths.entrySet()) {
            String name = entry.getKey();
            Path resFile = entry.getValue();
            var gids = fontUsedGids.get(name);
            var unicodes = fontUsedUnicodes.get(name);
            if (resFile == null || !Files.isRegularFile(resFile) || gids == null) {
                continue;
            }
            long before = sizeOf(resFile);
            try {
                Path spec = writeSubsetSpec(name, gids, unicodes);
                Path out = spec.resolveSibling(spec.getFileName() + ".out");
                Process p = new ProcessBuilder("python3", subsetScript.toString(), spec.toString())
                        .redirectErrorStream(true)
                        .start();
                String outputText = new String(p.getInputStream().readAllBytes());
                int code = p.waitFor();
                if (code != 0 || !Files.isRegularFile(out)) {
                    throw new IOException("fontTools 退出码 " + code + (outputText.isBlank() ? "" : " — " + tail(outputText)));
                }
                long after = Files.size(out);
                if (after >= before || after == 0) {
                    throw new IOException("子集未减小体积（%d → %d）".formatted(before, after));
                }
                try (var in = Files.newInputStream(out)) {
                    Files.copy(in, resFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                log.info("字体子集: %s（%s → %s）", name, humanSize(before), humanSize(after));
            } catch (Exception e) {
                log.warn("字体子集化失败 %s（保留整字体）: %s", name,
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        }
    }

    private Path subsetScript;
    private Boolean fontToolsOk;

    private boolean fontToolsAvailable() {
        if (fontToolsOk == null) {
            try {
                Process p = new ProcessBuilder("python3", "-c", "import fontTools")
                        .redirectErrorStream(true)
                        .start();
                fontToolsOk = p.waitFor() == 0;
            } catch (Exception e) {
                fontToolsOk = false;
            }
        }
        return fontToolsOk;
    }

    private Path writeSubsetSpec(String name, java.util.TreeSet<Integer> gids,
                                 java.util.TreeSet<Integer> unicodes) throws IOException {
        if (subsetScript == null) {
            Path dir = rasterCacheDir();
            subsetScript = dir.resolve("subset.py");
            Files.writeString(subsetScript, """
                    import json, sys
                    from fontTools import subset
                    spec = json.load(open(sys.argv[1]))
                    options = subset.Options()
                    options.retain_gids = True      # 保留字形编号：CGTransform 的 GID 无需重写
                    options.notdef_outline = True
                    options.hinting = False
                    options.layout_features = []    # 定位已由 DeltaX/CGTransform 钉死，shaping 表不需要
                    options.name_IDs = [0, 1, 2, 3, 4, 6]
                    font = subset.load_font(spec["input"], options)
                    sub = subset.Subsetter(options)
                    sub.populate(unicodes=spec.get("unicodes", []), gids=spec.get("gids", []))
                    sub.subset(font)
                    subset.save_font(font, spec["output"], options)
                    """);
        }
        Path in = rasterCacheDir().resolve("subset-" + sanitize(name) + ".in");
        Files.copy(resFileOf(name), in, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Path spec = rasterCacheDir().resolve("subset-" + sanitize(name) + ".json");
        Path out = spec.resolveSibling(spec.getFileName() + ".out");
        // 手工构造 JSON：gids/unicodes 为 TreeSet，其 toString 形如 [1, 2, 3]（合法 JSON 数组）
        String json = "{\"input\":\"" + escapeJson(in.toString())
                + "\",\"output\":\"" + escapeJson(out.toString())
                + "\",\"gids\":" + gids
                + ",\"unicodes\":" + (unicodes == null ? "[]" : unicodes.toString())
                + "}";
        Files.writeString(spec, json);
        return spec;
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private Path resFileOf(String name) {
        return fontResPaths.get(name);
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return -1;
        }
    }

    private static String humanSize(long bytes) {
        if (bytes < 0) {
            return "?";
        }
        if (bytes < 1024 * 1024) {
            return "%.1f KB".formatted(bytes / 1024.0);
        }
        return "%.2f MB".formatted(bytes / 1024.0 / 1024.0);
    }

    private static String tail(String s) {
        s = s.trim();
        return s.length() > 200 ? "..." + s.substring(s.length() - 200) : s;
    }

    /**
     * 按 hyperref 层级（level 1 = 当前父节点的直接子级）构建 OFD Outlines 树。
     */
    private void buildOutlines(List<xdviofdmx.XdvInterpreter.OutlineEntry> entries) {
        org.ofdrw.core.basicStructure.outlines.Outlines root =
                new org.ofdrw.core.basicStructure.outlines.Outlines();
        List<org.ofdrw.core.basicStructure.outlines.CT_OutlineElem> stack = new ArrayList<>();
        int built = 0;
        for (var e : entries) {
            int level = Math.min(e.level(), stack.size() + 1);
            CT_Action action = gotoActionFor(e.destName());
            if (action == null) {
                log.warn("书签目标不存在（跳过）: %s", e.destName());
                // 仍需维护层级栈，保证后续兄弟关系正确
                trimStack(stack, level - 1);
                continue;
            }
            var node = new org.ofdrw.core.basicStructure.outlines.CT_OutlineElem(e.title());
            node.setActions(new Actions().addAction(action));
            if (level == 1) {
                root.addOutlineElem(node);
            } else {
                stack.get(level - 2).addOutlineElem(node);
            }
            trimStack(stack, level);
            stack.add(node);
            built++;
        }
        if (built > 0) {
            doc.document.setOutlines(root);
            log.info("书签: %d 条。", built);
        }
    }

    private static void trimStack(List<?> stack, int depth) {
        while (stack.size() >= depth) {
            stack.remove(stack.size() - 1);
        }
    }

    @Override
    public void close() throws IOException {
        doc.close();
    }
}
