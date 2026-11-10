package xdviofdmx;

import xdvjni.FileType;
import xdvjni.XdvEvents;
import xdviofdmx.font.FontRegistry;
import xdviofdmx.font.LoadedFont;
import xdviofdmx.font.NativeFontDef;
import xdviofdmx.model.ColorSpec;
import xdviofdmx.model.DestPoint;
import xdviofdmx.model.ImagePlacement;
import xdviofdmx.model.LinkZone;
import xdviofdmx.model.PageContent;
import xdviofdmx.model.Rule;
import xdviofdmx.model.TextRun;
import xdviofdmx.special.SpecialParser;

import java.awt.geom.Point2D;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * XDV 事件解释器：xdvjni 事件流 → 页缓冲模型（{@link PageContent}）。
 * 落页时机：下一个 BOP 或 {@link #finish()}（XDV 无 EOP 回调）。
 */
public final class XdvInterpreter implements XdvEvents, SpecialParser.SpecialSink {

    /**
     * DVI 原点约定：DVI 坐标 (0,0) 位于纸张左上角内 1 英寸处（xdvipdfmx dev_origin=(72bp,72bp)）。
     * 所有位置量（special 点、字形坐标、rule 原点）需要加该偏移；尺寸量不加。
     */
    static final double ORIGIN_MM = 25.4;

    private final Coord coord;
    private final GraphicsState state = new GraphicsState();
    private final FontRegistry fonts;
    private final SpecialParser specials;
    private final Emitter emitter;
    private final Options options;
    private final Log log;

    private PageContent page;
    private int pageIndex = -1;
    private boolean skipping;
    private final List<DestPoint> dests = new ArrayList<>();

    /** 链接（bann/eann）累积窗口 */
    private boolean linkActive;
    private double linkMinX, linkMinY, linkMaxX, linkMaxY;
    private String linkUri, linkDest;
    private int linksCreated;

    /** 书签条目（pdf:outline，按文档顺序） */
    public record OutlineEntry(int level, Boolean open, String title, String destName) {}

    private final List<OutlineEntry> outlines = new ArrayList<>();

    private long legacyCharRuns;
    private long unsupportedSpecials;

    public XdvInterpreter(Coord coord, FontRegistry fonts, Emitter emitter, Options options, Log log) {
        this.coord = coord;
        this.fonts = fonts;
        this.emitter = emitter;
        this.options = options;
        this.log = log;
        this.specials = new SpecialParser(coord, state, this, new SpecialLog());
    }

    /** 页面发射器（由 OfdDocBuilder 实现） */
    public interface Emitter {
        void emitPage(PageContent page, List<DestPoint> pageDests);
    }

    public record Options(Double paperWidthMm, Double paperHeightMm,
                          java.util.function.IntPredicate pageFilter) {

        public Options(Double paperWidthMm, Double paperHeightMm) {
            this(paperWidthMm, paperHeightMm, page -> true);
        }
    }

    public interface Log {
        void info(String fmt, Object... args);

        void warn(String fmt, Object... args);

        void debug(String fmt, Object... args);
    }

    private final class SpecialLog implements SpecialParser.SpecialLog {
        @Override
        public void warn(String fmt, Object... args) {
            XdvInterpreter.this.warn("special: " + fmt, args);
        }

        @Override
        public void debug(String fmt, Object... args) {
            XdvInterpreter.this.log.debug(fmt, args);
        }
    }

    private void info(String fmt, Object... args) {
        if (log != null) {
            log.info(fmt, args);
        }
    }

    private void warn(String fmt, Object... args) {
        if (log != null) {
            log.warn(fmt, args);
        }
    }

    // ---------------------------------------------------------------- XdvEvents

    @Override
    public void handleHeader(FileType filetype, byte[] comment) {
        info("输入格式: %s, 注释: %s", filetype,
                comment == null ? "" : new String(comment, StandardCharsets.UTF_8).trim());
    }

    @Override
    public void handleBeginPage(int[] counters, int previousBop) {
        flushPage();
        pageIndex++;
        if (!options.pageFilter().test(pageIndex + 1)) {
            page = null; // 页选择过滤：跳过该页全部内容（skipping 状态）
            skipping = true;
            return;
        }
        skipping = false;
        page = new PageContent(pageIndex, counters.clone());
        if (options.paperWidthMm() != null) {
            page.widthMm = options.paperWidthMm();
            page.heightMm = options.paperHeightMm();
        }
    }

    @Override
    public void handleSpecial(int x, int y, byte[] contents) {
        ensurePage();
        if (page == null) {
            return;
        }
        double xMm = coord.dviToMm(x) + ORIGIN_MM;
        double yMm = coord.dviToMm(y) + ORIGIN_MM;
        specials.execute(new String(contents, StandardCharsets.UTF_8), xMm, yMm);
    }

    @Override
    public void handleDefineNativeFont(String name, int fontNum, int size,
                                       int faceIndex, Integer colorRgba, Integer extend,
                                       Integer slant, Integer embolden) {
        fonts.define(new NativeFontDef(fontNum, name, size, faceIndex,
                colorRgba, extend, slant, embolden));
        info("字体定义 #%d: %s (face=%d, %s pt)", fontNum, name, faceIndex,
                String.format("%.2f", Integer.toUnsignedLong(size) / 65536.0));
    }

    @Override
    public void handleGlyphRun(int fontNum, char[] glyphs, int[] x, int[] y) {
        addTextRun(fontNum, glyphs, x, y, null);
    }

    @Override
    public void handleTextAndGlyphs(int fontNum, String text, int width,
                                    char[] glyphs, int[] x, int[] y) {
        addTextRun(fontNum, glyphs, x, y, text);
    }

    @Override
    public void handleRule(int x, int y, int height, int width) {
        ensurePage();
        if (page == null) {
            return;
        }
        page.add(new Rule(state.snapshot(),
                coord.r(coord.dviToMm(x) + ORIGIN_MM), coord.r(coord.dviToMm(y) + ORIGIN_MM),
                coord.r(coord.dviToMm(width)), coord.r(coord.dviToMm(height))));
        if (linkActive) {
            accumulateLinkBox(coord.dviToMm(x) + ORIGIN_MM,
                    coord.dviToMm(y) - coord.dviToMm(height) + ORIGIN_MM,
                    coord.dviToMm(x) + coord.dviToMm(width) + ORIGIN_MM,
                    coord.dviToMm(y) + ORIGIN_MM);
        }
    }

    @Override
    public void handleCharRun(int fontNum, int[] chars) {
        legacyCharRuns++;
        if (legacyCharRuns == 1) {
            warn("遇到传统（TFM）字体文本（fontNum=%d）——xdviofdmx v1 仅支持 native 字体，"
                    + "此类文本被跳过（典型场景：未启用 unicode-math 的数学公式）。", fontNum);
        }
    }

    // ---------------------------------------------------------------- 文本 run

    private void addTextRun(int fontNum, char[] glyphs, int[] x, int[] y, String actualText) {
        ensurePage();
        if (page == null) {
            return;
        }
        int n = glyphs.length;
        int[] gids = new int[n];
        double[] xs = new double[n];
        double[] ys = new double[n];
        for (int i = 0; i < n; i++) {
            gids[i] = glyphs[i] & 0xFFFF;
            xs[i] = coord.dviToMm(x[i]) + ORIGIN_MM;
            ys[i] = coord.dviToMm(y[i]) + ORIGIN_MM;
        }
        // 优先采用 254 原文（等长时逐字符权威），否则反查 cmap
        String[] unicodes;
        if (actualText != null && actualText.length() == n) {
            unicodes = new String[n];
            for (int i = 0; i < n; i++) {
                unicodes[i] = String.valueOf(actualText.charAt(i));
            }
        } else {
            unicodes = fonts.mapGlyphs(fontNum, gids);
        }
        page.add(new TextRun(state.snapshot(), fontNum, gids, xs, ys, unicodes, actualText));
        if (linkActive) {
            LoadedFont f = fonts.get(fontNum);
            double asc = f != null ? f.ascentMm : 0;
            double desc = f != null ? f.descentMm : 0.35 * coord.dviToMm(655360.0 / 10.0);
            double minX = xs[0], maxX = xs[n - 1];
            double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                minX = Math.min(minX, xs[i]);
                maxX = Math.max(maxX, xs[i]);
                minY = Math.min(minY, ys[i]);
                maxY = Math.max(maxY, ys[i]);
            }
            accumulateLinkBox(minX, minY - asc, maxX, maxY + desc);
        }
    }

    private void accumulateLinkBox(double x0, double y0, double x1, double y1) {
        if (!linkActive) {
            linkMinX = x0;
            linkMinY = y0;
            linkMaxX = x1;
            linkMaxY = y1;
            return;
        }
        linkMinX = Math.min(linkMinX, x0);
        linkMinY = Math.min(linkMinY, y0);
        linkMaxX = Math.max(linkMaxX, x1);
        linkMaxY = Math.max(linkMaxY, y1);
    }

    // ---------------------------------------------------------------- SpecialSink

    @Override
    public void pageSize(Double widthMm, Double heightMm) {
        ensurePage();
        if (page == null) {
            return;
        }
        if (options.paperWidthMm() != null) {
            return; // -p 命令行优先
        }
        if (widthMm != null) {
            page.widthMm = widthMm;
        }
        if (heightMm != null) {
            page.heightMm = heightMm;
        }
    }

    @Override
    public void pageBackground(ColorSpec c) {
        ensurePage();
        if (page == null) {
            return;
        }
        if (isWhite(c)) {
            return; // xetex.def: "background gray 1" 表示无背景色
        }
        page.background = c;
    }

    private static boolean isWhite(ColorSpec c) {
        ColorSpec rgb = c.toRgb();
        for (double v : rgb.components()) {
            if (v < 0.999) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void image(ImagePlacement placement) {
        ensurePage();
        if (page == null) {
            return;
        }
        page.add(placement);
        if (linkActive && placement.naturalSizeMm() != null) {
            double[] nat = placement.naturalSizeMm();
            accumulateLinkBox(placement.xMm, placement.yMm - nat[1],
                    placement.xMm + nat[0], placement.yMm);
        }
    }

    @Override
    public void dest(String name, double xDviMm, double yDviMm) {
        ensurePage();
        if (page == null) {
            return;
        }
        Point2D p = state.toPage(xDviMm, yDviMm);
        dests.add(new DestPoint(name, pageIndex, p.getX(), p.getY()));
        info("目标: %s @ (%.2f, %.2f) mm", name, p.getX(), p.getY());
    }

    @Override
    public void linkBegin(String dict, double xDviMm, double yDviMm) {
        linkActive = true;
        linkMinX = linkMaxX = xDviMm;
        linkMinY = linkMaxY = yDviMm;
        linkUri = null;
        linkDest = null;
        if (dict != null) {
            // /A<</S/URI/URI(url)>> 与 /A<</S/GoTo/D(name)>>，以及直接 /Dest(name)
            java.util.regex.Matcher uri = java.util.regex.Pattern
                    .compile("/URI\\s*\\(([^)]*)\\)").matcher(dict);
            if (uri.find()) {
                linkUri = uri.group(1);
            }
            java.util.regex.Matcher dst = java.util.regex.Pattern
                    .compile("/D\\s*\\(([^)]*)\\)").matcher(dict);
            if (dst.find()) {
                linkDest = dst.group(1);
            } else {
                java.util.regex.Matcher dst2 = java.util.regex.Pattern
                        .compile("/Dest\\s*\\(?([A-Za-z0-9._@-]+)\\)?").matcher(dict);
                if (dst2.find()) {
                    linkDest = dst2.group(1);
                }
            }
        }
    }

    @Override
    public void linkEnd() {
        if (!linkActive || page == null) {
            return;
        }
        linkActive = false;
        double pad = 0.2;
        page.links.add(LinkZone.uri(state.snapshot(),
                coord.r(linkMinX - pad), coord.r(linkMinY - pad),
                coord.r(Math.max(0.1, linkMaxX - linkMinX + 2 * pad)),
                coord.r(Math.max(0.1, linkMaxY - linkMinY + 2 * pad)),
                linkUri != null ? linkUri : linkDest == null ? "" : "#" + linkDest));
        if (linkUri == null && linkDest == null) {
            warn("pdf:bann 无 /URI 或 /Dest，链接区域被忽略");
        }
        linksCreated++;
    }

    @Override
    public void outline(int level, Boolean open, String title, String destName,
                        double xDviMm, double yDviMm) {
        if (title == null || destName == null) {
            warn("pdf:outline 缺少 Title 或目标（跳过）");
            return;
        }
        outlines.add(new OutlineEntry(level, open, title, destName));
    }

    // ---------------------------------------------------------------- 落页

    private void ensurePage() {
        if (page == null && !skipping) {
            pageIndex++;
            page = new PageContent(pageIndex, null);
        }
    }

    /** 结束当前页并发射（下一 BOP 或输入结束时调用） */
    public void flushPage() {
        if (page != null && (page.hasContent || page.background != null)) {
            List<DestPoint> pageDests = dests.stream()
                    .filter(d -> d.pageIndex() == page.pageIndex)
                    .toList();
            emitter.emitPage(page, pageDests);
            if (!page.links.isEmpty()) {
                deferredLinkPages.add(page);
            }
        }
        page = null;
    }

    /** 含链接区的已发射页（供发射器在 dest 集齐后统一补发链接） */
    public List<PageContent> deferredLinkPages() {
        return deferredLinkPages;
    }

    private final List<PageContent> deferredLinkPages = new ArrayList<>();

    public void finish() {
        flushPage();
        if (legacyCharRuns > 0) {
            warn("共 %d 处传统字体文本被跳过。", legacyCharRuns);
        }
        if (linksCreated > 0) {
            info("共 %d 个链接区域。", linksCreated);
        }
    }

    public List<DestPoint> dests() {
        return dests;
    }

    public List<OutlineEntry> outlines() {
        return outlines;
    }
}
