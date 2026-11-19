package xdviofdmx.font;

import org.apache.fontbox.type1.Type1Font;
import xdviofdmx.Coord;

import java.awt.geom.PathIterator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * legacy（TFM/VF/Type1）字体管理器。
 *
 * <p>收到 legacy 字体定义（XDV fnt_def）后：</p>
 * <ol>
 *   <li>{@link TexResolver} 定位并解析 TFM（宽度表，注入解析器供 char run 定位）；</li>
 *   <li>存在 VF 则解析（槽位 → 基字体摆放）；否则该字体本身即基字体；</li>
 *   <li>基字体经 {@code pdftex.map} → enc → PFB 解析，由
 *       {@link Type1OtfBuilder} 转成 OTF，包装为 {@link LoadedFont} 注册进
 *       {@link FontRegistry}（synthetic fontNum）——之后文本 run 完全复用
 *       native 嵌入/渲染管线；</li>
 *   <li>OTF 构建失败但 PFB 可解析时退化为矢量轮廓（{@link OutlineSeg}）；</li>
 *   <li>整链不可解析时按字体粒度告警跳过（保持 v1 行为）。</li>
 * </ol>
 */
public final class LegacyFontManager implements AutoCloseable {

    /** 单个展开结果：基字体字形（unicode 可为 null → 轮廓兜底） */
    public sealed interface Piece permits GlyphPiece, RulePiece, OutlinePiece {
    }

    public record GlyphPiece(BaseFont font, int slot, String unicode,
                             double xMm, double yMm) implements Piece {
    }

    public record RulePiece(double xMm, double yMm, double wMm, double hMm) implements Piece {
    }

    /** 一条 legacy 字体定义的解析结果 */
    public static final class LegacyFont {
        final int fontNum;
        final String name;
        final long scaledSize; // DVI 单位（fnt_def scale_factor）
        final long designSize;
        final TfmFile tfm;     // null = TFM 缺失（整字体不可用）
        final VfFile vf;       // null = 无 VF（字体本身是基字体）
        final boolean usable;

        LegacyFont(int fontNum, String name, long scaledSize, long designSize,
                   TfmFile tfm, VfFile vf, boolean usable) {
            this.fontNum = fontNum;
            this.name = name;
            this.scaledSize = scaledSize;
            this.designSize = designSize;
            this.tfm = tfm;
            this.vf = vf;
            this.usable = usable;
        }
    }

    /** 基字体：TFM 名 → map/enc/PFB/OTF/LoadedFont（同一 TFM 名跨尺寸共享 OTF） */
    public static final class BaseFont implements AutoCloseable {
        public final String tfmName;
        public final DvipsMap.Entry mapEntry;
        public final EncTable enc;
        public final String[] slotGlyphNames; // 256 项
        public final String[] slotUnicode;    // 256 项，null=未知
        public final Path otf;                // null = OTF 构建失败
        public final LoadedFont loaded;       // null = OTF 构建失败
        public final Type1Font type1;         // null = PFB 解析失败
        /** 轮廓兜底：槽位 → 段序列（相对字形原点的 mm，y 向下）；OTF 可用时为 null */
        public final Map<Integer, List<OutlineSeg>> outlineCache = new HashMap<>();
        /** 外层 em（mm）与基字体相对缩放的乘积——synthetic LoadedFont 的 emMm */
        public final double emMm;
        /** 该尺寸下的 synthetic 字体编号（供 TextRun.fontNum / FontRegistry 反查） */
        public final int syntheticFontNum;

        BaseFont(String tfmName, DvipsMap.Entry mapEntry, EncTable enc,
                 String[] slotGlyphNames, String[] slotUnicode,
                 Path otf, LoadedFont loaded, Type1Font type1,
                 double emMm, int syntheticFontNum) {
            this.tfmName = tfmName;
            this.mapEntry = mapEntry;
            this.enc = enc;
            this.slotGlyphNames = slotGlyphNames;
            this.slotUnicode = slotUnicode;
            this.otf = otf;
            this.loaded = loaded;
            this.type1 = type1;
            this.emMm = emMm;
            this.syntheticFontNum = syntheticFontNum;
        }

        /**
         * 槽位轮廓（mm，相对字形原点，y 向下）；OTF 已可用时返回 null
         * （native 管线经 AWT 走 GID 轮廓）。fontbox Type1 缺该字形时返回 null。
         */
        public List<OutlineSeg> outlineMmOf(int slot, LoadedFont.Logger log) {
            if (type1 == null) {
                return null;
            }
            return outlineCache.computeIfAbsent(slot, s -> {
                String name = slotGlyphNames[s];
                if (name == null || !type1.hasGlyph(name)) {
                    return null;
                }
                java.awt.geom.GeneralPath path;
                try {
                    path = type1.getPath(name);
                } catch (IOException e) {
                    return null;
                }
                if (path == null) {
                    return null;
                }
                // 字形 units → mm：em = emMm（即 UPEM 字形单位 = emMm）
                double toMm = emMm / Type1OtfBuilder.upem();
                List<OutlineSeg> segs = new ArrayList<>();
                double[] c = new double[6];
                for (PathIterator it = path.getPathIterator(null); !it.isDone(); it.next()) {
                    int type = it.currentSegment(c);
                    switch (type) {
                        case PathIterator.SEG_MOVETO -> segs.add(new OutlineSeg(OutlineSeg.Kind.M,
                                new double[] {c[0] * toMm, -c[1] * toMm}));
                        case PathIterator.SEG_LINETO -> segs.add(new OutlineSeg(OutlineSeg.Kind.L,
                                new double[] {c[0] * toMm, -c[1] * toMm}));
                        case PathIterator.SEG_QUADTO -> segs.add(new OutlineSeg(OutlineSeg.Kind.Q,
                                new double[] {c[0] * toMm, -c[1] * toMm,
                                        c[2] * toMm, -c[3] * toMm}));
                        case PathIterator.SEG_CUBICTO -> segs.add(new OutlineSeg(OutlineSeg.Kind.C,
                                new double[] {c[0] * toMm, -c[1] * toMm,
                                        c[2] * toMm, -c[3] * toMm,
                                        c[4] * toMm, -c[5] * toMm}));
                        case PathIterator.SEG_CLOSE -> segs.add(new OutlineSeg(OutlineSeg.Kind.Z,
                                new double[0]));
                        default -> { }
                    }
                }
                return segs;
            });
        }

        @Override
        public void close() {
            if (loaded != null) {
                loaded.close();
            }
            // type1（fontbox Type1Font）无系统资源，无需关闭
        }
    }

    private final TexResolver resolver;
    private final Coord coord;
    private final LoadedFont.Logger log;
    private final FontRegistry registry;
    private final Path tempDir;

    private final Map<String, TfmFile> tfmCache = new HashMap<>();
    private final Map<String, VfFile> vfCache = new HashMap<>();
    private final Map<Integer, LegacyFont> fonts = new HashMap<>();
    /** 基字体缓存：tfmName @ 尺寸（mm 取整到 0.01）→ BaseFont */
    private final Map<String, BaseFont> baseCache = new HashMap<>();
    private DvipsMap fontMap; // 懒加载
    private boolean mapTried;
    private int syntheticSeq = 10_000_000;
    private final List<Path> generatedOtf = new ArrayList<>();

    public LegacyFontManager(TexResolver resolver, Coord coord, LoadedFont.Logger log,
                             FontRegistry registry, Path tempDir) {
        this.resolver = resolver;
        this.coord = coord;
        this.log = log;
        this.registry = registry;
        this.tempDir = tempDir;
    }

    /** 解析器是否可用（不可用时 legacy 文本全部告警跳过） */
    public boolean available() {
        return resolver.available();
    }

    /**
     * 处理 legacy 字体定义；宽度表就绪后回调 {@code widthConsumer(fontNum, widths)}
     * （实现方负责注入解析器）。
     */
    public void define(String area, String name, int fontNum, int checksum,
                       int scaledSize, int designSize,
                       BiConsumer<Integer, long[]> widthConsumer) {
        TfmFile tfm = null;
        VfFile vf = null;
        if (resolver.available()) {
            Path tfmPath = resolver.tfm(name);
            if (tfmPath != null) {
                tfm = tfmCache.computeIfAbsent(name, n -> {
                    try {
                        return TfmFile.parse(resolver.tfm(n));
                    } catch (IOException e) {
                        warnOnce("TFM 解析失败 %s: %s", n, e.getMessage());
                        return null;
                    }
                });
            }
            Path vfPath = resolver.vf(name);
            if (vfPath != null) {
                vf = vfCache.computeIfAbsent(name, n -> {
                    try {
                        VfFile parsed = VfFile.parse(resolver.vf(n));
                        parsed.setBaseTfmLookup(this::baseTfm);
                        return parsed;
                    } catch (IOException e) {
                        warnOnce("VF 解析失败 %s: %s", n, e.getMessage());
                        return null;
                    }
                });
            }
        } else {
            warnOnce("kpathsea/TEXMF 不可用，legacy 字体文本将跳过"
                    + "（安装 texlive-binaries 或设置 PATH 后重试）");
        }

        boolean usable = tfm != null;
        fonts.put(fontNum, new LegacyFont(fontNum, name,
                Integer.toUnsignedLong(scaledSize), Integer.toUnsignedLong(designSize),
                tfm, vf, usable));
        if (usable && widthConsumer != null) {
            widthConsumer.accept(fontNum, tfm.widthsDvi(Integer.toUnsignedLong(scaledSize)));
        }
        if (!usable) {
            warnOnce("legacy 字体 %s 无 TFM（fontNum=%d），其文本将被跳过", name, fontNum);
        }
    }

    /** 该 fontNum 是否为可用 legacy 字体 */
    public boolean usable(int fontNum) {
        LegacyFont lf = fonts.get(fontNum);
        return lf != null && lf.usable;
    }

    /** 取 fontNum 的解析结果（未定义/不可用返回 null；expand 内部有 usable 检查） */
    public LegacyFont fontOf(int fontNum) {
        return fonts.get(fontNum);
    }

    private TfmFile baseTfm(String name) {
        if (!resolver.available()) {
            return null;
        }
        return tfmCache.computeIfAbsent(name, n -> {
            try {
                Path p = resolver.tfm(n);
                return p == null ? null : TfmFile.parse(p);
            } catch (IOException e) {
                return null;
            }
        });
    }

    /**
     * 展开一个槽位到字符原点 (xMm, yMm)：VF 重放或直达。
     * 坐标已换算 mm（+DVI 原点由调用方先加好）。
     */
    public List<Piece> expand(LegacyFont lf, int slot, double xMm, double yMm) {
        List<Piece> out = new ArrayList<>();
        if (!lf.usable) {
            return out;
        }
        if (lf.vf == null) {
            BaseFont base = baseFont(lf.name, lf.scaledSize);
            if (base == null) {
                return out;
            }
            String u = slotUnicode(base, slot);
            if (base.loaded != null) {
                out.add(new GlyphPiece(base, slot, u, xMm, yMm));
            } else {
                List<OutlineSeg> segs = base.outlineMmOf(slot, log);
                if (segs != null) {
                    out.add(new OutlinePiece(base, slot, xMm, yMm, segs));
                }
            }
            return out;
        }
        double v = lf.scaledSize / (double) (1 << 20); // packet 单位 → DVI 单位
        VfFile.Expanded exp = lf.vf.expand(slot, v);
        for (VfFile.Placement p : exp.placements()) {
            VfFile.VfFontDef def = lf.vf.baseFont(p.baseFontNum());
            if (def == null) {
                continue;
            }
            BaseFont base = baseFont(def.name(),
                    lf.scaledSize * def.scale() / (double) (1 << 20));
            if (base == null) {
                continue;
            }
            double px = xMm + coord.dviToMm(p.x());
            double py = yMm + coord.dviToMm(p.y());
            String u = slotUnicode(base, p.slot());
            if (base.loaded != null) {
                out.add(new GlyphPiece(base, p.slot(), u, px, py));
            } else {
                List<OutlineSeg> segs = base.outlineMmOf(p.slot(), log);
                if (segs != null) {
                    out.add(new OutlinePiece(base, p.slot(), px, py, segs));
                }
            }
        }
        for (VfFile.RulePlacement r : exp.rules()) {
            out.add(new RulePiece(xMm + coord.dviToMm(r.x()), yMm + coord.dviToMm(r.y()),
                    coord.dviToMm(r.w()), coord.dviToMm(r.h())));
        }
        return out;
    }

    /** 轮廓兜底 piece（OTF 不可用时的矢量路径） */
    public record OutlinePiece(BaseFont font, int slot, double xMm, double yMm,
                               List<OutlineSeg> segments) implements Piece {
    }

    private String slotUnicode(BaseFont base, int slot) {
        return slot >= 0 && slot < base.slotUnicode.length ? base.slotUnicode[slot] : null;
    }

    /**
     * 获取/构建基字体（含 OTF 转换与 synthetic 注册）。
     *
     * @param scaledSize 基字体实际尺寸（DVI 单位；VF 基字体 = 外层 × s/2²⁰）
     */
    private BaseFont baseFont(String tfmName, double scaledSize) {
        double emMm = coord.dviToMm(scaledSize);
        String key = tfmName + "@" + String.format("%.2f", emMm);
        BaseFont cached = baseCache.get(key);
        if (cached != null) {
            return cached;
        }
        DvipsMap.Entry entry = mapEntryOf(tfmName);
        if (entry == null) {
            warnOnce("pdftex.map 无 %s 的映射，字形不可渲染", tfmName);
            baseCache.put(key, null);
            return null;
        }
        if (!entry.fontIsType1()) {
            warnOnce("%s 映射到 TrueType/OpenType（%s），legacy v1 仅支持 Type 1", tfmName,
                    entry.fontFile());
            baseCache.put(key, null);
            return null;
        }

        // PFB 解析（fontbox）
        Type1Font type1 = null;
        Path pfb = resolver.type1(entry.fontFile());
        if (pfb != null) {
            try {
                type1 = Type1Font.createWithPFB(Files.readAllBytes(pfb));
            } catch (IOException e) {
                warnOnce("PFB 解析失败 %s: %s", entry.fontFile(), e.getMessage());
            }
        } else {
            warnOnce("PFB 文件不存在: %s", entry.fontFile());
        }

        // 编码：map 引用的 .enc 优先；缺失时用 PFB 内建 /Encoding
        EncTable enc = resolveEnc(entry, type1);
        BaseFont partial = new BaseFont(tfmName, entry, enc,
                new String[256], new String[256], null, null, type1, emMm, -1);
        // 槽位字形名与 Unicode
        for (int slot = 0; slot < 256; slot++) {
            String glyph = enc != null ? enc.glyphAt(slot) : null;
            if (glyph != null && glyph.equals(".notdef")) {
                glyph = null;
            }
            partial.slotGlyphNames[slot] = glyph;
            partial.slotUnicode[slot] = glyph == null ? null : Agal.unicodeOf(glyph);
        }

        LoadedFont loaded = null;
        Path otf = null;
        if (type1 != null) {
            try {
                otf = tempDir.resolve("legacy-" + Integer.toHexString(
                        (tfmName + "@" + Math.round(emMm * 100)).hashCode()) + ".otf");
                Type1OtfBuilder.build(type1, new Type1OtfBuilder.Slots(
                        partial.slotGlyphNames, partial.slotUnicode), otf);
                generatedOtf.add(otf);
                // synthetic native def：指向生成的 OTF，尺寸=基字体实际 em
                int sizeRaw = (int) Math.round(emMm / Coord.MM_PER_TEXPT * 65536.0);
                int syntheticNum = ++syntheticSeq;
                // map 的 slant/extend 合成参数作为字体变换传递
                Integer slantRaw = entry.slant() == 0 ? null
                        : (int) Math.round(entry.slant() * 65536.0);
                Integer extendRaw = entry.extend() == 0 ? null
                        : (int) Math.round(entry.extend() * 65536.0);
                NativeFontDef sdef = new NativeFontDef(syntheticNum, otf.toString(),
                        (int) sizeRaw, 0, null, extendRaw, slantRaw, null);
                loaded = LoadedFont.load(sdef, emMm, nonSlicer(), log);
                registry.registerSynthetic(sdef, loaded);
                partial = new BaseFont(tfmName, entry, enc, partial.slotGlyphNames,
                        partial.slotUnicode, otf, loaded, type1, emMm, syntheticNum);
            } catch (IOException e) {
                warnOnce("OTF 转换失败 %s（轮廓兜底）: %s", tfmName, e.getMessage());
                partial = new BaseFont(tfmName, entry, enc, partial.slotGlyphNames,
                        partial.slotUnicode, null, null, type1, emMm, -1);
            }
        } else {
            partial = new BaseFont(tfmName, entry, enc, partial.slotGlyphNames,
                    partial.slotUnicode, null, null, type1, emMm, -1);
        }
        baseCache.put(key, partial);
        return partial;
    }

    private DvipsMap.Entry mapEntryOf(String tfmName) {
        if (!mapTried) {
            mapTried = true;
            Path mapPath = resolver.fontMap();
            if (mapPath != null) {
                try {
                    fontMap = DvipsMap.parse(mapPath);
                } catch (IOException e) {
                    warnOnce("pdftex.map 解析失败: %s", e.getMessage());
                }
            } else {
                warnOnce("找不到 pdftex.map");
            }
        }
        return fontMap == null ? null : fontMap.get(tfmName);
    }

    private EncTable resolveEnc(DvipsMap.Entry entry, Type1Font type1) {
        if (entry.encFile() != null && resolver.available()) {
            Path encPath = resolver.enc(entry.encFile());
            if (encPath != null) {
                try {
                    return EncTable.parse(encPath);
                } catch (IOException e) {
                    warnOnce("enc 解析失败 %s: %s", entry.encFile(), e.getMessage());
                }
            } else if (type1 == null) {
                warnOnce("enc 文件不存在: %s 且 PFB 不可解析，编码不可知", entry.encFile());
                return null;
            } else {
                warnOnce("enc 文件不存在: %s（用 PFB 内建 /Encoding 兜底）", entry.encFile());
            }
        }
        // PFB 内建 /Encoding（fontbox Encoding.getName(slot)）
        if (type1 != null) {
            try {
                var fbEnc = type1.getEncoding();
                if (fbEnc != null) {
                    String[] names = new String[256];
                    for (int slot = 0; slot < 256; slot++) {
                        String n = fbEnc.getName(slot);
                        if (n != null && !n.equals(".notdef")) {
                            names[slot] = n;
                        }
                    }
                    return new EncTable.BuiltIn(names);
                }
            } catch (Exception ignore) {
                // Encoding.getName 不声明 IO 异常；任何意外按无编码处理
            }
        }
        return null;
    }

    /** TtcSlicer 适配：OTF 不是 TTC，直接原样返回 */
    private LoadedFont.TtcSlicer nonSlicer() {
        return new LoadedFont.TtcSlicer() {
            @Override
            public boolean isTtc(Path file) {
                return false;
            }

            @Override
            public Path slice(Path ttcFile, int faceIndex) {
                return ttcFile;
            }
        };
    }

    private long warned = 0;

    private void warnOnce(String fmt, Object... args) {
        warned++;
        if (log != null) {
            log.warn(String.format(fmt, args));
        }
    }

    /** legacy 处理统计（finish 汇总用） */
    public long warningCount() {
        return warned;
    }

    public void close() {
        for (BaseFont b : baseCache.values()) {
            if (b != null) {
                b.close();
            }
        }
        baseCache.clear();
        fonts.clear();
        // 临时 OTF 文件清理
        for (Path p : generatedOtf) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignore) {
                // 忽略
            }
        }
        generatedOtf.clear();
    }
}
