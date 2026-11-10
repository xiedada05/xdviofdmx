package xdviofdmx.font;

import org.apache.fontbox.ttf.CmapLookup;
import org.apache.fontbox.ttf.CmapTable;
import org.apache.fontbox.ttf.OTFParser;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.awt.geom.PathIterator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 已加载的 native 字体：
 * <ul>
 *   <li>fontbox：反查 cmap（gid→Unicode）、度量、字体名；</li>
 *   <li>AWT：字形轮廓提取（createGlyphVector(glyphIds)），用于无法反查字形的矢量兜底；</li>
 *   <li>TTC：faceIndex 切片为单字体文件（供嵌入与 AWT 加载）。</li>
 * </ul>
 */
public final class LoadedFont implements AutoCloseable {

    private static final FontRenderContext FRC = new FontRenderContext(new AffineTransform(), true, false);

    /** 轮廓/度量共用 FontRenderContext（几何与提示无关的恒等变换） */
    public static FontRenderContext frc() {
        return FRC;
    }

    public final NativeFontDef def;
    /** 供 OFD 嵌入的字体文件（TTC 已切片） */
    public final Path embedFile;
    /** OFD FontName（PostScript 名或文件名） */
    public final String ofdName;
    public final String familyName;
    /** gid → 候选 Unicode 码点列表；null 表示字体不可用 */
    public final CmapLookup cmap;
    /** 上伸部（mm，em 尺寸下，自基线向上为正） */
    public final double ascentMm;
    /** 下伸部（mm，正值） */
    public final double descentMm;
    /** em 尺寸（DVI mm） */
    public final double emMm;
    /** AWT 字体（1 AWT pt = em 的 AWT 值），轮廓提取用；null 表示轮廓提取不可用 */
    public final Font awtFont;
    /** AWT 字号（AWT pt），使轮廓换算到 TeX pt 尺寸 */
    public final double awtSize;
    private final TrueTypeFont fontboxFont;

    private LoadedFont(NativeFontDef def, Path embedFile, String ofdName, String familyName,
                       CmapLookup cmap, double ascentMm, double descentMm, double emMm,
                       Font awtFont, double awtSize, TrueTypeFont fontboxFont) {
        this.def = def;
        this.embedFile = embedFile;
        this.ofdName = ofdName;
        this.familyName = familyName;
        this.cmap = cmap;
        this.ascentMm = ascentMm;
        this.descentMm = descentMm;
        this.emMm = emMm;
        this.awtFont = awtFont;
        this.awtSize = awtSize;
        this.fontboxFont = fontboxFont;
    }

    /**
     * 加载字体定义。文件缺失或解析失败时返回降级字体（cmap=null，无轮廓）。
     *
     * @param sliceTtc TTC face 切片器（调用方提供临时目录）
     */
    public static LoadedFont load(NativeFontDef def, double emMm, TtcSlicer slicer, Logger log) {
        Path src = Path.of(def.name());
        if (!Files.isRegularFile(src)) {
            log.warn("字体文件不存在: %s（fontNum=%d）。将按字体名引用（不嵌入），字形以占位符输出。"
                    .formatted(def.name(), def.fontNum()));
            return degraded(def, emMm, def.name(), def.name());
        }
        try {
            Path embed = slicer.isTtc(src) ? slicer.slice(src, def.faceIndex()) : src;
            // fontbox：cmap + 度量 + 名称
            TrueTypeFont ttf = parseForFontbox(embed);
            CmapLookup lookup = lookupOf(ttf);
            String name = safeName(ttf, src);
            double unitsPerEm = Math.max(1, ttf.getUnitsPerEm());
            double asc = emMm, desc = 0.35 * emMm;
            try {
                var hh = ttf.getHorizontalHeader();
                if (hh != null) {
                    asc = hh.getAscender() / unitsPerEm * emMm;
                    desc = -hh.getDescender() / unitsPerEm * emMm;
                }
            } catch (Exception ignore) {
                // 保留缺省
            }
            // AWT：轮廓提取
            Font awt = null;
            double awtSize = xdviofdmx.Coord.texPtToAwtPt(emMm / xdviofdmx.Coord.MM_PER_TEXPT);
            try {
                awt = Font.createFont(Font.TRUETYPE_FONT, embed.toFile())
                        .deriveFont((float) awtSize);
            } catch (Exception e) {
                log.warn("AWT 无法加载字体 %s（轮廓兜底不可用）: %s".formatted(src, e.getMessage()));
            }
            return new LoadedFont(def, embed, name, name, lookup, asc, desc, emMm,
                    awt, awtSize, ttf);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            log.warn("字体解析失败 %s: %s。将按字体名引用（不嵌入）。".formatted(src, msg));
            if (System.getenv("XDVIOFDMX_DEBUG") != null) {
                e.printStackTrace();
            }
            return degraded(def, emMm, baseName(src), baseName(src));
        }
    }

    private static TrueTypeFont parseForFontbox(Path file) throws IOException {
        byte[] head = new byte[4];
        try (var in = Files.newInputStream(file)) {
            int n = in.readNBytes(head, 0, 4);
            if (n < 4) {
                throw new IOException("字体文件过小: " + file);
            }
        }
        String tag = new String(head, java.nio.charset.StandardCharsets.ISO_8859_1);
        var raf = new org.apache.pdfbox.io.RandomAccessReadBufferedFile(file.toFile());
        return switch (tag) {
            case "OTTO" -> new OTFParser().parse(raf);
            default -> new TTFParser().parse(raf);
        };
    }

    private static CmapLookup lookupOf(TrueTypeFont ttf) {
        try {
            CmapTable cmapTable = ttf.getCmap();
            if (cmapTable != null) {
                org.apache.fontbox.ttf.CmapSubtable[] subs = cmapTable.getCmaps();
                if (subs != null && subs.length > 0) {
                    // 优先 (3,10) UCS-4 > (3,1) Windows BMP > (0,*) Unicode > 其它
                    org.apache.fontbox.ttf.CmapSubtable best = null;
                    int bestScore = -1;
                    for (org.apache.fontbox.ttf.CmapSubtable s : subs) {
                        int score = switch (s.getPlatformId()) {
                            case 3 -> s.getPlatformEncodingId() == CmapTable.ENCODING_WIN_UNICODE_FULL ? 40
                                    : s.getPlatformEncodingId() == CmapTable.ENCODING_WIN_UNICODE_BMP ? 30
                                    : s.getPlatformEncodingId() == CmapTable.ENCODING_WIN_SYMBOL ? 5 : 10;
                            case 0 -> 20;
                            default -> 0;
                        };
                        if (score > bestScore) {
                            bestScore = score;
                            best = s;
                        }
                    }
                    return best;
                }
            }
        } catch (IOException ignore) {
            // 无 cmap
        }
        return null;
    }

    private static String safeName(TrueTypeFont ttf, Path src) {
        try {
            String n = ttf.getName();
            if (n != null && !n.isBlank()) {
                return n;
            }
        } catch (IOException ignore) {
            // 落到文件名
        }
        return baseName(src);
    }

    private static String baseName(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private static LoadedFont degraded(NativeFontDef def, double emMm, String name, String family) {
        return new LoadedFont(def, null, name, family, null, emMm, 0.35 * emMm, emMm, null, 0, null);
    }

    /**
     * gid → Unicode 文本；无法反查返回 null（轮廓兜底）。
     *
     * <p>反查可能命中多个码位（如 Kangxi 部首与正字共享字形）。选择规则：
     * 优先常用文字区（CJK 统一表意、ASCII/Latin、日文假名等），部首/笔画/符号区最后，
     * 避免把「文」反查成「⽂」。</p>
     */
    public String unicodeOf(int gid) {
        if (cmap == null) {
            return null;
        }
        try {
            List<Integer> codes = cmap.getCharCodes(gid);
            if (codes == null || codes.isEmpty()) {
                return null;
            }
            Integer best = null;
            int bestScore = Integer.MIN_VALUE;
            for (Integer c : codes) {
                if (c == null || c < 0 || !Character.isValidCodePoint(c)) {
                    continue;
                }
                int score = codepointPreference(c);
                if (score > bestScore || (score == bestScore && best != null && c < best)) {
                    bestScore = score;
                    best = c;
                }
            }
            return best == null ? null : new String(Character.toChars(best));
        } catch (Exception e) {
            return null;
        }
    }

    /** 码位用途偏好分（越高越优先用于文本反查） */
    private static int codepointPreference(int c) {
        if (c < 0x2500) {
            return 60; // ASCII / Latin / 拉丁扩展
        }
        if ((c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF)) {
            return 80; // CJK 统一表意文字（首选）
        }
        if (c >= 0x3040 && c <= 0x30FF) {
            return 70; // 假名
        }
        if (c >= 0xAC00 && c <= 0xD7AF) {
            return 70; // 谚文
        }
        if (c >= 0x3000 && c <= 0x303F) {
            return 65; // CJK 标点
        }
        if (c >= 0xF900 && c <= 0xFAFF) {
            return 55; // 兼容表意
        }
        if ((c >= 0x2E80 && c <= 0x2FDF) || (c >= 0x31C0 && c <= 0x31EF)) {
            return 10; // 部首 / 笔画（最后）
        }
        if (c >= 0xFF00 && c <= 0xFFEF) {
            return 62; // 全角形式
        }
        return 30;
    }

    /**
     * char → gid（判断 CGTransform 必要性）。
     */
    public int gidOf(String s) {
        if (cmap == null || s == null || s.isEmpty()) {
            return -1;
        }
        return cmap.getGlyphId(s.codePointAt(0));
    }

    /**
     * 提取单个字形的矢量轮廓（对象：mm，DVI 空间，基线原点，y 向下）。
     * 返回折线/贝塞尔段列表；不可用时返回 null。
     */
    public List<OutlineSeg> outlineOf(int gid) {
        if (awtFont == null) {
            return null;
        }
        try {
            var gv = awtFont.createGlyphVector(FRC, new int[]{gid});
            var shape = gv.getGlyphOutline(0);
            return segments(shape);
        } catch (Exception e) {
            return null;
        }
    }

    /** AWT Shape → 段序列（AWT pt 坐标，基线原点） */
    private static List<OutlineSeg> segments(java.awt.Shape shape) {
        List<OutlineSeg> segs = new ArrayList<>();
        double[] c = new double[6];
        for (PathIterator it = shape.getPathIterator(null); !it.isDone(); it.next()) {
            int type = it.currentSegment(c);
            switch (type) {
                case PathIterator.SEG_MOVETO -> segs.add(new OutlineSeg(OutlineSeg.Kind.M, new double[]{c[0], c[1]}));
                case PathIterator.SEG_LINETO -> segs.add(new OutlineSeg(OutlineSeg.Kind.L, new double[]{c[0], c[1]}));
                case PathIterator.SEG_QUADTO -> segs.add(new OutlineSeg(OutlineSeg.Kind.Q, new double[]{c[0], c[1], c[2], c[3]}));
                case PathIterator.SEG_CUBICTO -> segs.add(new OutlineSeg(OutlineSeg.Kind.C, new double[]{c[0], c[1], c[2], c[3], c[4], c[5]}));
                case PathIterator.SEG_CLOSE -> segs.add(new OutlineSeg(OutlineSeg.Kind.Z, new double[0]));
                default -> { }
            }
        }
        return segs;
    }

    /** AWT pt → mm（1 AWT pt = 1/72 inch） */
    public static double awtPtToMm(double v) {
        return v * 25.4 / 72.0;
    }

    @Override
    public void close() {
        if (fontboxFont != null) {
            try {
                fontboxFont.close();
            } catch (IOException ignore) {
                // 忽略关闭失败
            }
        }
    }

    /** 日志回调 */
    public interface Logger {
        void warn(String msg);

        void info(String msg);
    }

    /** TTC face 切片器 */
    public interface TtcSlicer {
        boolean isTtc(Path file);

        Path slice(Path ttcFile, int faceIndex) throws IOException;
    }
}
