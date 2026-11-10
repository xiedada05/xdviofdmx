package xdviofdmx.special;

import xdviofdmx.Coord;
import xdviofdmx.GraphicsState;
import xdviofdmx.model.ColorSpec;
import xdviofdmx.model.ImagePlacement;

import java.awt.geom.AffineTransform;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * XDV special 解析与执行（对标 xdvipdfmx spc_*.c）。
 *
 * <p>覆盖：dvips 颜色（push/pop/set/background + 命名色）、x:（gsave/grestore/
 * scale/bscale/escale/rotate/renderingmode/backgroundcolor/papersize）、
 * pdf:（image/btrans/etrans/bxgstate/exgstate/literal(clip)/pagesize/bc/sc/ec），
 * pdfcolorstack、PSfile（EPS，经 {@link SpecialSink}）。未识别的 special 记日志跳过。</p>
 */
public final class SpecialParser {

    /** dvips 命名色表（源自 TeX Live dvips/base/color.pro，与 xdvipdfmx color.c 一致） */
    private static final Map<String, ColorSpec> NAMED = new HashMap<>();

    static {
        NAMED.put("GreenYellow", ColorSpec.cmyk(0.15, 0.0, 0.69, 0.0));
        NAMED.put("Yellow", ColorSpec.cmyk(0.0, 0.0, 1.0, 0.0));
        NAMED.put("Goldenrod", ColorSpec.cmyk(0.0, 0.1, 0.84, 0.0));
        NAMED.put("Dandelion", ColorSpec.cmyk(0.0, 0.29, 0.84, 0.0));
        NAMED.put("Apricot", ColorSpec.cmyk(0.0, 0.32, 0.52, 0.0));
        NAMED.put("Peach", ColorSpec.cmyk(0.0, 0.5, 0.7, 0.0));
        NAMED.put("Melon", ColorSpec.cmyk(0.0, 0.46, 0.5, 0.0));
        NAMED.put("YellowOrange", ColorSpec.cmyk(0.0, 0.42, 1.0, 0.0));
        NAMED.put("Orange", ColorSpec.cmyk(0.0, 0.61, 0.87, 0.0));
        NAMED.put("BurntOrange", ColorSpec.cmyk(0.0, 0.51, 1.0, 0.0));
        NAMED.put("Bittersweet", ColorSpec.cmyk(0.0, 0.75, 1.0, 0.24));
        NAMED.put("RedOrange", ColorSpec.cmyk(0.0, 0.77, 0.87, 0.0));
        NAMED.put("Mahogany", ColorSpec.cmyk(0.0, 0.81, 0.85, 0.11));
        NAMED.put("Maroon", ColorSpec.cmyk(0.0, 0.87, 0.68, 0.32));
        NAMED.put("BrickRed", ColorSpec.cmyk(0.0, 0.89, 0.94, 0.28));
        NAMED.put("Red", ColorSpec.cmyk(0.0, 1.0, 1.0, 0.0));
        NAMED.put("OrangeRed", ColorSpec.cmyk(0.0, 1.0, 0.5, 0.0));
        NAMED.put("RubineRed", ColorSpec.cmyk(0.0, 1.0, 0.13, 0.0));
        NAMED.put("WildStrawberry", ColorSpec.cmyk(0.0, 0.96, 0.39, 0.0));
        NAMED.put("Salmon", ColorSpec.cmyk(0.0, 0.53, 0.38, 0.0));
        NAMED.put("CarnationPink", ColorSpec.cmyk(0.0, 0.63, 0.0, 0.0));
        NAMED.put("Magenta", ColorSpec.cmyk(0.0, 1.0, 0.0, 0.0));
        NAMED.put("VioletRed", ColorSpec.cmyk(0.0, 0.81, 0.0, 0.0));
        NAMED.put("Rhodamine", ColorSpec.cmyk(0.0, 0.82, 0.0, 0.0));
        NAMED.put("Mulberry", ColorSpec.cmyk(0.34, 0.907, 0.02, 0.0));
        NAMED.put("RedViolet", ColorSpec.cmyk(0.07, 0.9, 0, 0.348));
        NAMED.put("Fuchsia", ColorSpec.cmyk(0.47, 0.91, 0, 0.08));
        NAMED.put("Lavender", ColorSpec.cmyk(0.0, 0.48, 0.0, 0.0));
        NAMED.put("Thistle", ColorSpec.cmyk(0.12, 0.59, 0, 0.0));
        NAMED.put("Orchid", ColorSpec.cmyk(0.32, 0.64, 0, 0.0));
        NAMED.put("DarkOrchid", ColorSpec.cmyk(0.4, 0.8, 0, 0.0));
        NAMED.put("Purple", ColorSpec.cmyk(0.45, 0.86, 0, 0.0));
        NAMED.put("Plum", ColorSpec.cmyk(0.5, 1.0, 0, 0.0));
        NAMED.put("Violet", ColorSpec.cmyk(0.79, 0.88, 0, 0.0));
        NAMED.put("RoyalPurple", ColorSpec.cmyk(0.75, 0.9, 0, 0.0));
        NAMED.put("BlueViolet", ColorSpec.cmyk(0.86, 0.91, 0, 0.04));
        NAMED.put("Periwinkle", ColorSpec.cmyk(0.57, 0.55, 0, 0.0));
        NAMED.put("CadetBlue", ColorSpec.cmyk(0.62, 0.57, 0.23, 0.0));
        NAMED.put("CornflowerBlue", ColorSpec.cmyk(0.65, 0.13, 0, 0.0));
        NAMED.put("MidnightBlue", ColorSpec.cmyk(0.98, 0.13, 0, 0.0));
        NAMED.put("NavyBlue", ColorSpec.cmyk(0.94, 0.54, 0, 0.0));
        NAMED.put("RoyalBlue", ColorSpec.cmyk(1.0, 0.5, 0, 0.0));
        NAMED.put("Blue", ColorSpec.cmyk(1.0, 1.0, 0.0, 0.0));
        NAMED.put("Cerulean", ColorSpec.cmyk(0.94, 0.11, 0, 0.0));
        NAMED.put("Cyan", ColorSpec.cmyk(1.0, 0.0, 0.0, 0.0));
        NAMED.put("ProcessBlue", ColorSpec.cmyk(0.96, 0.0, 0.0, 0.0));
        NAMED.put("SkyBlue", ColorSpec.cmyk(0.62, 0.0, 0.12, 0.0));
        NAMED.put("Turquoise", ColorSpec.cmyk(0.85, 0.0, 0.2, 0.0));
        NAMED.put("TealBlue", ColorSpec.cmyk(0.86, 0.0, 0.34, 0.02));
        NAMED.put("Aquamarine", ColorSpec.cmyk(0.82, 0.0, 0.3, 0.0));
        NAMED.put("BlueGreen", ColorSpec.cmyk(0.85, 0.0, 0.33, 0.0));
        NAMED.put("Emerald", ColorSpec.cmyk(1.0, 0.0, 0.5, 0.0));
        NAMED.put("JungleGreen", ColorSpec.cmyk(0.99, 0.0, 0.52, 0.0));
        NAMED.put("SeaGreen", ColorSpec.cmyk(0.69, 0.0, 0.5, 0.0));
        NAMED.put("Green", ColorSpec.cmyk(1.0, 0.0, 1.0, 0.0));
        NAMED.put("ForestGreen", ColorSpec.cmyk(0.91, 0.0, 0.88, 0.12));
        NAMED.put("PineGreen", ColorSpec.cmyk(0.92, 0.0, 0.59, 0.25));
        NAMED.put("LimeGreen", ColorSpec.cmyk(0.5, 0.0, 1.0, 0.0));
        NAMED.put("YellowGreen", ColorSpec.cmyk(0.44, 0.0, 0.74, 0.0));
        NAMED.put("SpringGreen", ColorSpec.cmyk(0.26, 0.0, 0.76, 0.0));
        NAMED.put("OliveGreen", ColorSpec.cmyk(0.64, 0.0, 0.56, 0.0));
        NAMED.put("RawSienna", ColorSpec.cmyk(0.0, 0.72, 1.0, 0.45));
        NAMED.put("Sepia", ColorSpec.cmyk(0.0, 0.35, 1.0, 0.45));
        NAMED.put("Brown", ColorSpec.cmyk(0.0, 0.81, 1.0, 0.6));
        NAMED.put("Tan", ColorSpec.cmyk(0.14, 0.42, 0.56, 0.0));
        NAMED.put("Gray", ColorSpec.cmyk(0.0, 0.0, 0.0, 0.5));
        NAMED.put("Black", ColorSpec.cmyk(0.0, 0.0, 0.0, 1.0));
        NAMED.put("White", ColorSpec.cmyk(0.0, 0.0, 0.0, 0.0));
    }

    /** pdf:literal 中的裁剪矩形模式（xetex.def：`0 -DEPTH W H re W n`，坐标相对当前点、bp、y 向上） */
    private static final Pattern CLIP_LITERAL = Pattern.compile(
            "^\\s*(-?[\\d.]+)\\s+(-?[\\d.]+)\\s+(-?[\\d.]+)\\s+(-?[\\d.]+)\\s+re\\s+W\\s+n\\s*$");

    private final Coord coord;
    private final GraphicsState state;
    private final SpecialSink sink;
    private final SpecialLog log;

    /** pdfcolorstack 栈 */
    private final Map<Integer, Deque<ColorSpec[]>> colorStacks = new HashMap<>();

    /** 填充/描边双色（pdfcolorstack 需要） */
    private ColorSpec stackFill;
    private ColorSpec stackStroke;

    public SpecialParser(Coord coord, GraphicsState state, SpecialSink sink, SpecialLog log) {
        this.coord = coord;
        this.state = state;
        this.sink = sink;
        this.log = log;
    }

    /** 日志 */
    public interface SpecialLog {
        void warn(String fmt, Object... args);

        void debug(String fmt, Object... args);
    }

    /** 解析结果落点 */
    public interface SpecialSink {
        void pageSize(Double widthMm, Double heightMm);

        void pageBackground(ColorSpec c);

        void image(ImagePlacement placement);

        /** pdf:bann（dict 为原始 PDF 字典文本，可为 null） */
        default void linkBegin(String dict, double xDviMm, double yDviMm) {}

        /** pdf:eann */
        default void linkEnd() {}

        /** pdf:dest */
        default void dest(String name, double xDviMm, double yDviMm) {}

        /** pdf:outline 书签条目（level 自 1 起，open 为 null 表示未声明开合状态） */
        default void outline(int level, Boolean open, String title, String destName,
                             double xDviMm, double yDviMm) {}
    }

    // ---------------------------------------------------------------- 入口

    public void execute(String text, double xDviMm, double yDviMm) {
        String s = text.trim();
        if (s.isEmpty()) {
            return;
        }
        try {
            if (s.startsWith("x:")) {
                executeXtx(s.substring(2), xDviMm, yDviMm);
            } else if (s.startsWith("pdf:")) {
                executePdf(s.substring(4), xDviMm, yDviMm);
            } else if (s.equals("color") || s.startsWith("color ")) {
                executeColor(rest(s, "color"), false);
            } else if (s.equals("background") || s.startsWith("background ")) {
                executeColor(rest(s, "background"), true);
            } else if (s.startsWith("pdfcolorstackinit ")) {
                colorStackInit(rest(s, "pdfcolorstackinit "));
            } else if (s.startsWith("pdfcolorstack ")) {
                colorStack(rest(s, "pdfcolorstack "));
            } else if (s.startsWith("papersize")) {
                executePapersize(s);
            } else if (s.startsWith("PSfile=") || s.startsWith("psfile=")) {
                executePsfile(s, xDviMm, yDviMm);
            } else if (s.equals("landscape") || s.startsWith("landscape ")
                    || s.startsWith("src:") || s.startsWith("pos:") || s.startsWith("om:")) {
                log.debug("忽略 special: %s", s);
            } else {
                log.warn("不支持的 special（跳过）: %s", abbreviate(s));
            }
        } catch (Exception e) {
            log.warn("special 解析失败（跳过）: %s — %s", abbreviate(s), e.getMessage());
        }
    }

    private static String rest(String s, String prefix) {
        return s.substring(prefix.length()).trim();
    }

    private static String abbreviate(String s) {
        return s.length() > 80 ? s.substring(0, 77) + "..." : s;
    }

    // ---------------------------------------------------------------- dvips 颜色

    private void executeColor(String arg, boolean background) {
        if (arg.isEmpty()) {
            return;
        }
        List<String> tokens = tokens(arg);
        String head = tokens.get(0);
        ColorSpec c;
        if (head.equals("push")) {
            c = parseColorSpec(tokens.subList(1, tokens.size()));
            if (c != null) {
                state.pushColor(c);
            }
            return;
        }
        if (head.equals("pop")) {
            state.popColor();
            return;
        }
        c = parseColorSpec(tokens);
        if (c == null) {
            log.warn("无法解析颜色规格: %s", arg);
            return;
        }
        if (background) {
            sink.pageBackground(c);
        } else {
            state.setColorSpec(c);
        }
    }

    /** 解析颜色规格（rgb/cmyk/gray/hsb/命名色）；colors 也接受前缀词 */
    private ColorSpec parseColorSpec(List<String> tokens) {
        if (tokens.isEmpty()) {
            return null;
        }
        String first = tokens.get(0).toLowerCase(Locale.ROOT);
        Iterator<String> it = tokens.listIterator(1); // 数值从第一个词之后开始
        ColorSpec c = ColorSpec.parseSpec(first, it);
        if (c != null) {
            return c;
        }
        return namedColor(tokens.get(0));
    }

    private static ColorSpec namedColor(String name) {
        ColorSpec c = NAMED.get(name);
        if (c == null) {
            c = NAMED.get(name.toLowerCase(Locale.ROOT));
        }
        if (c == null) {
            c = NAMED.get(name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1).toLowerCase(Locale.ROOT));
        }
        return c;
    }

    private static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        StringTokenizer st = new StringTokenizer(s, " \t");
        while (st.hasMoreTokens()) {
            out.add(st.nextToken());
        }
        return out;
    }

    // ---------------------------------------------------------------- x: specials

    private void executeXtx(String arg, double x, double y) {
        String s = arg.trim();
        if (s.equals("gsave")) {
            state.save();
        } else if (s.equals("grestore")) {
            state.restore();
        } else if (s.startsWith("scale")) {
            double[] v = numbers(rest(s, "scale"), 2, 1.0);
            state.scaleAbout(x, y, v[0], v.length > 1 ? v[1] : v[0]);
        } else if (s.startsWith("bscale")) {
            double[] v = numbers(rest(s, "bscale"), 2, 1.0);
            state.beginScale(v[0], v.length > 1 ? v[1] : v[0]);
        } else if (s.equals("escale")) {
            state.endScale();
        } else if (s.startsWith("rotate")) {
            double[] v = numbers(rest(s, "rotate"), 1, 0.0);
            state.rotateAbout(x, y, v[0]);
        } else if (s.startsWith("papersize")) {
            // 在 bop 预扫描期处理；此处忽略（xdvipdfmx 同行为）
        } else if (s.startsWith("backgroundcolor")) {
            ColorSpec c = parseColorSpec(tokens(rest(s, "backgroundcolor")));
            if (c != null) {
                sink.pageBackground(c);
            }
        } else if (s.startsWith("renderingmode")) {
            int m = (int) numbers(rest(s, "renderingmode"), 1, 0.0)[0];
            state.setRenderingMode(Math.max(0, Math.min(7, m)));
        } else if (s.startsWith("fontmapline") || s.startsWith("fontmapfile")
                || s.startsWith("initoverlay") || s.startsWith("clipoverlay")) {
            log.debug("忽略 x: special: x:%s", abbreviate(s));
        } else {
            log.warn("不支持的 x: special（跳过）: x:%s", abbreviate(s));
        }
    }

    // ---------------------------------------------------------------- pdf: specials

    private void executePdf(String arg, double x, double y) {
        String s = arg.trim();
        if (s.startsWith("image") && (s.length() == 5 || s.charAt(5) == ' ')) {
            executePdfImage(rest(s, "image"), x, y);
        } else if (s.startsWith("btrans")) {
            executePdfBtrans(rest(s, "btrans"), x, y);
        } else if (s.equals("etrans")) {
            state.restore();
        } else if (s.startsWith("bxgstate")) {
            executePdfBxgstate(rest(s, "bxgstate"));
        } else if (s.equals("exgstate")) {
            state.restore();
        } else if (s.startsWith("literal")) {
            executePdfLiteral(rest(s, "literal"), x, y);
        } else if (s.startsWith("pagesize")) {
            executePdfPagesize(rest(s, "pagesize"));
        } else if (s.startsWith("bann")) {
            String dict = rest(s, "bann").trim();
            sink.linkBegin(dict.isEmpty() ? null : dict, x, y);
        } else if (s.equals("eann")) {
            sink.linkEnd();
        } else if (s.startsWith("dest")) {
            String[] parts = rest(s, "dest").split("\\s+", 2);
            if (parts.length > 0 && !parts[0].isEmpty()) {
                sink.dest(stripNamePrefix(parts[0]), x, y);
            }
        } else if (s.startsWith("outline") && (s.length() == 7 || s.charAt(7) == ' ' || s.charAt(7) == '[')) {
            executePdfOutline(rest(s, "outline"), x, y);
        } else if (s.startsWith("bc") || s.startsWith("bcolor") || s.startsWith("begincolor")) {
            ColorSpec c = parseColorSpec(tokens(colorSpecArg(s)));
            if (c != null) {
                state.pushColor(c);
            }
        } else if (s.startsWith("sc") || s.startsWith("setcolor")) {
            ColorSpec c = parseColorSpec(tokens(colorSpecArg(s)));
            if (c != null) {
                state.setColorSpec(c);
            }
        } else if (s.startsWith("ec") || s.startsWith("endcolor")) {
            state.popColor();
        } else if (s.startsWith("bgcolor")) {
            ColorSpec c = parseColorSpec(tokens(rest(s, s.startsWith("bgcolor ") ? "bgcolor " : "bgcolor")));
            if (c != null) {
                sink.pageBackground(c);
            }
        } else {
            log.debug("忽略 pdf: special: pdf:%s", abbreviate(s));
        }
    }

    /** pdf:bc/sc 的颜色参数：跳过选项词（如 gray/rgb 已由 parseColorSpec 消费） */
    private static String colorSpecArg(String s) {
        int i = s.indexOf(' ');
        return i < 0 ? "" : s.substring(i + 1);
    }

    private static String stripNamePrefix(String name) {
        if (name.startsWith("@")) {
            return name.substring(1);
        }
        if (name.startsWith("(") && name.endsWith(")")) {
            return name.substring(1, name.length() - 1);
        }
        return name;
    }

    /** 书签：[-|+] level <<dict>>（Title 为 UTF-16BE 的 PDF 字符串，/A GoTo /D(name)） */
    private void executePdfOutline(String arg, double x, double y) {
        String s = arg.trim();
        Boolean open = null;
        if (s.startsWith("[")) {
            int close = s.indexOf(']');
            if (close > 0) {
                open = !s.substring(1, close).contains("-");
                s = s.substring(close + 1).trim();
            }
        }
        int brace = s.indexOf('<');
        if (brace < 0) {
            log.warn("pdf:outline 缺少字典（跳过）");
            return;
        }
        int level;
        try {
            level = (int) Double.parseDouble(s.substring(0, brace).trim());
        } catch (NumberFormatException e) {
            log.warn("pdf:outline 层级非法（跳过）: %s", abbreviate(arg));
            return;
        }
        String dict = s.substring(brace);
        String title = pdfStringOf(dict, "/Title");
        String dest = pdfStringOf(dict, "/D(");
        if (dest == null) {
            dest = pdfStringOf(dict, "/D ");
        }
        sink.outline(Math.max(1, level), open, title, dest, x, y);
    }

    /**
     * 提取 PDF 字典中 key 后的字符串（(...) 形式，处理八进制/字符转义与嵌套括号）；
     * 以 \376\377 开头时按 UTF-16BE 解码。
     */
    static String pdfStringOf(String dict, String key) {
        int k = dict.indexOf(key);
        if (k < 0) {
            return null;
        }
        int open = dict.indexOf('(', k);
        if (open < 0) {
            return null;
        }
        StringBuilder raw = new StringBuilder();
        int depth = 1;
        for (int i = open + 1; i < dict.length(); i++) {
            char c = dict.charAt(i);
            if (c == '\\' && i + 1 < dict.length()) {
                char n = dict.charAt(++i);
                switch (n) {
                    case 'n' -> raw.append('\n');
                    case 'r' -> raw.append('\r');
                    case 't' -> raw.append('\t');
                    case 'b' -> raw.append('\b');
                    case 'f' -> raw.append('\f');
                    case '(', ')' -> raw.append(n);
                    case '\\' -> raw.append('\\');
                    default -> {
                        if (n >= '0' && n <= '7') {
                            int val = n - '0';
                            int digits = 1;
                            while (digits < 3 && i + 1 < dict.length()
                                    && dict.charAt(i + 1) >= '0' && dict.charAt(i + 1) <= '7') {
                                val = val * 8 + (dict.charAt(++i) - '0');
                                digits++;
                            }
                            raw.append((char) val);
                        } else {
                            raw.append(n); // 行续接 \newline 等按字面处理
                        }
                    }
                }
            } else if (c == '(') {
                depth++;
                raw.append(c);
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    break;
                }
                raw.append(c);
            } else {
                raw.append(c);
            }
        }
        String s = raw.toString();
        if (s.length() >= 2 && (s.charAt(0) == 0xFE) && (s.charAt(1) == 0xFF)) {
            // UTF-16BE（含 BOM）
            byte[] bytes = new byte[s.length() - 2];
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) s.charAt(2 + i);
            }
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_16BE);
        }
        return s;
    }

    private void executePdfImage(String arg, double x, double y) {
        // 语法: [page N] [pagebox X] [bbox x1 y1 x2 y2] [width W] [height H] [depth D]
        //       [scale s|xscale x|yscale y|rotate deg|matrix a b c d e f] [clip 0|1|hide] (file) [<<..>>]
        List<String> tokens = tokens(arg);
        int page = 0;
        String pageBox = null;
        double[] bboxBp = null;
        Double width = null, height = null, depth = null;
        double[] matrix = null;
        double xscale = 1.0, yscale = 1.0;
        boolean clip = false;
        String file = null;
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            switch (t) {
                case "page" -> page = (int) Double.parseDouble(tokens.get(++i));
                case "pagebox" -> pageBox = tokens.get(++i);
                case "bbox" -> bboxBp = new double[]{
                        Double.parseDouble(tokens.get(++i)), Double.parseDouble(tokens.get(++i)),
                        Double.parseDouble(tokens.get(++i)), Double.parseDouble(tokens.get(++i))};
                case "width" -> width = lenBp(tokens.get(++i));
                case "height" -> height = lenBp(tokens.get(++i));
                case "depth" -> depth = lenBp(tokens.get(++i));
                case "scale" -> xscale = yscale = Double.parseDouble(tokens.get(++i));
                case "xscale" -> xscale = Double.parseDouble(tokens.get(++i));
                case "yscale" -> yscale = Double.parseDouble(tokens.get(++i));
                case "rotate" -> {
                    double deg = Double.parseDouble(tokens.get(++i));
                    double rad = Math.toRadians(deg);
                    matrix = new double[]{Math.cos(rad), Math.sin(rad),
                            -Math.sin(rad), Math.cos(rad), 0, 0};
                }
                case "matrix" -> matrix = new double[]{
                        Double.parseDouble(tokens.get(++i)), Double.parseDouble(tokens.get(++i)),
                        Double.parseDouble(tokens.get(++i)), Double.parseDouble(tokens.get(++i)),
                        Double.parseDouble(tokens.get(++i)), Double.parseDouble(tokens.get(++i))};
                case "clip" -> clip = tokens.get(++i).equals("1");
                case "<<" -> { /* 尾部字典，忽略余下 */ i = tokens.size(); }
                default -> {
                    if (t.startsWith("(") && file == null) {
                        file = unparen(tokens, i);
                    }
                }
            }
        }
        if (file == null) {
            log.warn("pdf:image 缺少文件名（跳过）");
            return;
        }
        var path = java.nio.file.Path.of(file);
        ImagePlacement.Kind kind = kindOf(file);
        Double wMm = width == null ? null : Coord.bpToMm(width) * xscale;
        Double hMm = height == null ? null : Coord.bpToMm(height) * yscale;
        double depthMm = depth == null ? 0.0 : Coord.bpToMm(depth) * yscale;
        sink.image(new ImagePlacement(state.snapshot(), path, kind, page,
                x, y, bboxBp, matrix, pageBox, wMm, hMm, depthMm, clip));
    }

    private static ImagePlacement.Kind kindOf(String file) {
        String f = file.toLowerCase(Locale.ROOT);
        if (f.endsWith(".pdf")) {
            return ImagePlacement.Kind.PDF;
        }
        if (f.endsWith(".eps") || f.endsWith(".ai")) {
            return ImagePlacement.Kind.EPS;
        }
        return ImagePlacement.Kind.RASTER;
    }

    /** 提取 (filename)，支持括号内的空格与 \) 转义 */
    private static String unparen(List<String> tokens, int start) {
        StringBuilder sb = new StringBuilder(tokens.get(start).substring(1));
        // 括号可能被空白分断（(my file.png)）——从原串重组不可靠，dvipdfmx 亦允许带空格路径
        for (int i = start + 1; i < tokens.size(); i++) {
            String t = tokens.get(i);
            sb.append(' ').append(t);
        }
        String s = sb.toString();
        int close = s.lastIndexOf(')');
        if (close >= 0) {
            s = s.substring(0, close);
        }
        // 多余 token（字典等）已在 file 之后；这里仅裁到第一个不含转义的 ')'——上面的 lastIndexOf 已处理
        return s.replace("\\)", ")");
    }

    private void executePdfBtrans(String arg, double x, double y) {
        state.save();
        String s = arg.trim();
        if (s.isEmpty()) {
            return;
        }
        List<String> tokens = tokens(s);
        for (int i = 0; i < tokens.size(); i++) {
            switch (tokens.get(i)) {
                case "rotate" -> state.rotateAbout(x, y, Double.parseDouble(tokens.get(++i)));
                case "scale" -> {
                    double sx = Double.parseDouble(tokens.get(++i));
                    double sy = i + 1 < tokens.size() && isNumber(tokens.get(i + 1))
                            ? Double.parseDouble(tokens.get(++i)) : sx;
                    state.scaleAbout(x, y, sx, sy);
                }
                case "matrix" -> {
                    double[] m = new double[6];
                    for (int k = 0; k < 6; k++) {
                        m[k] = Double.parseDouble(tokens.get(++i));
                    }
                    state.transformAbout(x, y, new AffineTransform(m));
                }
                default -> { }
            }
        }
    }

    private void executePdfBxgstate(String dict) {
        state.save();
        if (dict != null && !dict.isBlank()) {
            Matcher m = Pattern.compile("/ca\\s+([\\d.]+)").matcher(dict);
            if (m.find()) {
                state.setAlpha01(Double.parseDouble(m.group(1)));
            } else {
                Matcher ca = Pattern.compile("/CA\\s+([\\d.]+)").matcher(dict);
                if (ca.find()) {
                    state.setAlpha01(Double.parseDouble(ca.group(1)));
                }
            }
        }
    }

    private void executePdfLiteral(String arg, double x, double y) {
        String s = arg.trim();
        boolean direct = s.startsWith("direct");
        if (direct) {
            s = s.substring(6).trim();
        }
        Matcher m = CLIP_LITERAL.matcher(s);
        if (m.matches()) {
            // bp、y 向上、相对当前点 → DVI mm、y 向下
            double x0 = Coord.bpToMm(Double.parseDouble(m.group(1)));
            double y0 = Coord.bpToMm(Double.parseDouble(m.group(2)));
            double w = Coord.bpToMm(Double.parseDouble(m.group(3)));
            double h = Coord.bpToMm(Double.parseDouble(m.group(4)));
            state.addClip(new GraphicsState.ClipRect(x + x0, y - y0 - h, w, h));
        } else {
            log.warn("不支持的 pdf:literal（跳过）: %s", abbreviate(s));
        }
    }

    private void executePdfPagesize(String arg) {
        List<String> tokens = tokens(arg);
        Double w = null, h = null;
        for (int i = 0; i < tokens.size(); i++) {
            switch (tokens.get(i)) {
                case "width" -> w = Coord.bpToMm(lenBp(tokens.get(++i)));
                case "height" -> h = Coord.bpToMm(lenBp(tokens.get(++i)));
                case "default" -> { w = null; h = null; }
                default -> { }
            }
        }
        if (w != null || h != null) {
            sink.pageSize(w, h);
        }
    }

    // ---------------------------------------------------------------- pdfcolorstack

    private void colorStackInit(String arg) {
        // <n> page direct (ops)
        List<String> tokens = tokens(arg);
        if (tokens.isEmpty()) {
            return;
        }
        int n = (int) Double.parseDouble(tokens.get(0));
        String ops = parenTail(arg);
        colorStacks.put(n, new ArrayDeque<>());
        applyColorOps(ops);
    }

    private void colorStack(String arg) {
        // <n> push|pop|set|current [page direct] (ops)
        List<String> tokens = tokens(arg);
        if (tokens.size() < 2) {
            return;
        }
        int n = (int) Double.parseDouble(tokens.get(0));
        String op = tokens.get(1);
        String ops = parenTail(arg);
        Deque<ColorSpec[]> stack = colorStacks.computeIfAbsent(n, k -> new ArrayDeque<>());
        switch (op) {
            case "push" -> {
                stack.push(new ColorSpec[]{state.snapshot().fill(), state.snapshot().stroke()});
                applyColorOps(ops);
            }
            case "pop" -> {
                ColorSpec[] prev = stack.isEmpty() ? null : stack.pop();
                if (prev != null) {
                    state.setFillColorDirect(prev[0]);
                    state.setStrokeColorDirect(prev[1]);
                }
            }
            case "set", "current" -> applyColorOps(ops);
            default -> log.warn("未知 pdfcolorstack 操作: %s", op);
        }
    }

    /** 解析颜色操作串（g/G/rg/RG/k/K），仅识别这些算子 */
    private void applyColorOps(String ops) {
        if (ops == null || ops.isBlank()) {
            return;
        }
        List<String> tokens = tokens(ops);
        for (int i = 0; i < tokens.size(); i++) {
            switch (tokens.get(i)) {
                case "g" -> {
                    stackFill = ColorSpec.gray(Double.parseDouble(tokens.get(++i)));
                    state.setFillColorDirect(stackFill);
                }
                case "G" -> {
                    stackStroke = ColorSpec.gray(Double.parseDouble(tokens.get(++i)));
                    state.setStrokeColorDirect(stackStroke);
                }
                case "rg" -> {
                    stackFill = ColorSpec.rgb(Double.parseDouble(tokens.get(++i)),
                            Double.parseDouble(tokens.get(++i)), Double.parseDouble(tokens.get(++i)));
                    state.setFillColorDirect(stackFill);
                }
                case "RG" -> {
                    stackStroke = ColorSpec.rgb(Double.parseDouble(tokens.get(++i)),
                            Double.parseDouble(tokens.get(++i)), Double.parseDouble(tokens.get(++i)));
                    state.setStrokeColorDirect(stackStroke);
                }
                case "k" -> {
                    stackFill = ColorSpec.cmyk(Double.parseDouble(tokens.get(++i)), Double.parseDouble(tokens.get(++i)),
                            Double.parseDouble(tokens.get(++i)), Double.parseDouble(tokens.get(++i)));
                    state.setFillColorDirect(stackFill);
                }
                case "K" -> {
                    stackStroke = ColorSpec.cmyk(Double.parseDouble(tokens.get(++i)), Double.parseDouble(tokens.get(++i)),
                            Double.parseDouble(tokens.get(++i)), Double.parseDouble(tokens.get(++i)));
                    state.setStrokeColorDirect(stackStroke);
                }
                default -> { }
            }
        }
    }

    /** 取最后一个 (...) 内容（颜色操作串） */
    private static String parenTail(String s) {
        int open = s.indexOf('(');
        if (open < 0) {
            return null;
        }
        int close = s.lastIndexOf(')');
        if (close < open) {
            return null;
        }
        return s.substring(open + 1, close);
    }

    // ---------------------------------------------------------------- papersize / PSfile

    private void executePapersize(String s) {
        // papersize=W,H —— 长度可带单位、允许引号
        String body = s.substring("papersize".length()).trim();
        if (body.startsWith("=")) {
            body = body.substring(1).trim();
        }
        if (body.startsWith("'") || body.startsWith("\"")) {
            body = body.substring(1);
        }
        String[] parts = body.split(",");
        if (parts.length < 2) {
            log.warn("papersize 参数不完整: %s", abbreviate(s));
            return;
        }
        double wBp = lenBp(parts[0].trim().replace("\"", "").replace("'", ""));
        double hBp = lenBp(parts[1].trim().replace("\"", "").replace("'", ""));
        sink.pageSize(Coord.bpToMm(wBp), Coord.bpToMm(hBp));
    }

    private void executePsfile(String s, double x, double y) {
        // PSfile="file.eps" [llx=.. lly=.. urx=.. ury=..] [rwi=N] [rhi=N] [clip]
        String file = null;
        double llx = 0, lly = 0, urx = Double.NaN, ury = Double.NaN;
        double rwi = Double.NaN, rhi = Double.NaN;
        boolean clip = false;
        Matcher m = Pattern.compile("(\\w+)\\s*=\\s*(\"[^\"]*\"|\\S+)").matcher(s);
        while (m.find()) {
            String k = m.group(1);
            String v = m.group(2);
            if (k.equalsIgnoreCase("psfile")) {
                file = v.replace("\"", "");
            } else if (k.equals("llx")) {
                llx = Double.parseDouble(v);
            } else if (k.equals("lly")) {
                lly = Double.parseDouble(v);
            } else if (k.equals("urx")) {
                urx = Double.parseDouble(v);
            } else if (k.equals("ury")) {
                ury = Double.parseDouble(v);
            } else if (k.equals("rwi")) {
                rwi = Double.parseDouble(v);
            } else if (k.equals("rhi")) {
                rhi = Double.parseDouble(v);
            } else if (k.equals("clip")) {
                clip = true;
            }
        }
        if (file == null) {
            log.warn("PSfile 缺少文件名（跳过）");
            return;
        }
        double[] bbox = Double.isNaN(urx) || Double.isNaN(ury)
                ? null : new double[]{llx, lly, urx, ury};
        Double wMm = Double.isNaN(rwi) ? null : Coord.bpToMm(rwi / 10.0);
        Double hMm = Double.isNaN(rhi) ? null : Coord.bpToMm(rhi / 10.0);
        sink.image(new ImagePlacement(state.snapshot(), java.nio.file.Path.of(file),
                ImagePlacement.Kind.EPS, 0, x, y, bbox, null, null, wMm, hMm, 0.0, clip));
    }

    // ---------------------------------------------------------------- 工具

    /** 带单位长度 → bp；无单位按 bp */
    private static double lenBp(String token) {
        token = token.trim();
        String num = token;
        String unit = "";
        int i = token.length();
        while (i > 0 && !Character.isDigit(token.charAt(i - 1)) && token.charAt(i - 1) != '.'
                && token.charAt(i - 1) != '-') {
            i--;
        }
        num = token.substring(0, i);
        unit = token.substring(i).toLowerCase(Locale.ROOT);
        double v = Double.parseDouble(num.isEmpty() ? token : num);
        return switch (unit) {
            case "pt", "texpt" -> v * 72.0 / 72.27;
            case "bp", "bigpt", "" -> v;
            case "in", "inch" -> v * 72.0;
            case "cm" -> v * 72.0 / 2.54;
            case "mm" -> v * 72.0 / 25.4;
            case "pc", "pica" -> v * 12.0;
            case "sp" -> v / 65536.0 * 72.0 / 72.27;
            case "dd" -> v * 1238.0 / 1157.0 * 72.0 / 72.27;
            case "cc" -> v * 12.0 * 1238.0 / 1157.0 * 72.0 / 72.27;
            default -> v;
        };
    }

    /** 读取至多 max 个数字，不足补 fill */
    private static double[] numbers(String s, int max, double fill) {
        double[] out = new double[max];
        java.util.Arrays.fill(out, fill);
        List<String> tokens = tokens(s);
        for (int i = 0; i < Math.min(max, tokens.size()); i++) {
            out[i] = Double.parseDouble(tokens.get(i));
        }
        return out;
    }

    private static boolean isNumber(String s) {
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
