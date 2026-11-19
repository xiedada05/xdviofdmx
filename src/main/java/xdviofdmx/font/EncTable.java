package xdviofdmx.font;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * dvips 编码文件（.enc）解析 + 内置回退编码。
 *
 * <p>槽位 → 字形名的映射来源（优先级）：
 * <ol>
 *   <li>map 行引用的 {@code <xxx.enc} 文件（如 8r.enc）；</li>
 *   <li>PFB 内建 /Encoding（未重编码字体）；</li>
 *   <li>内置 TeXBase1Encoding / StandardEncoding。</li>
 * </ol>
 * 槽位字形名再经 {@link Agal} 推导 Unicode。</p>
 */
public class EncTable {

    private final String name;
    private final String[] glyphs; // 256 项，无 / 前缀

    private EncTable(String name, String[] glyphs) {
        this.name = name;
        this.glyphs = glyphs;
    }

    public String name() {
        return name;
    }

    /** 槽位字形名（无前导斜杠）；未定义返回 null */
    public String glyphAt(int slot) {
        return slot >= 0 && slot < 256 ? glyphs[slot] : null;
    }

    /** 从 .enc 文件解析：{@code /Name [ /g1 /g2 ... ] def}（忽略 %% 行注与 0xNN 槽位标记） */
    public static EncTable parse(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.ISO_8859_1);
        int lt = text.indexOf('[');
        int gt = text.indexOf(']', lt);
        if (lt < 0 || gt < 0) {
            throw new IOException("enc 文件缺少 [ ] 数组: " + file);
        }
        // 逐行剥掉 %% 后的注释（8r.enc 等在数组中用 "% 0xNN" 标记槽位位置）
        StringBuilder cleaned = new StringBuilder();
        for (String line : text.substring(lt + 1, gt).split("\n", -1)) {
            int pct = line.indexOf('%');
            cleaned.append(pct >= 0 ? line.substring(0, pct) : line).append('\n');
        }
        java.util.List<String> toks = new java.util.ArrayList<>();
        for (String t : cleaned.toString().split("\\s+")) {
            t = t.trim();
            if (!t.isEmpty() && !t.startsWith("0x")) {
                toks.add(t);
            }
        }
        String[] glyphs = new String[256];
        int n = Math.min(toks.size(), 256);
        for (int i = 0; i < n; i++) {
            String t = toks.get(i);
            if (t.startsWith("/")) {
                glyphs[i] = t.substring(1).intern();
            }
        }
        String base = file.getFileName().toString();
        return new EncTable(base.endsWith(".enc") ? base.substring(0, base.length() - 4) : base,
                glyphs);
    }

    /** 运行时构造的内置编码（PFB 内建 /Encoding 兜底） */
    public static final class BuiltIn extends EncTable {
        BuiltIn(String[] glyphs) {
            super("BuiltIn", glyphs.clone());
        }
    }

    /** 内置编码（不可用时兜底） */
    public static EncTable standardEncoding() {
        return new EncTable("StandardEncoding", StandardEnc.TABLE);
    }

    public static EncTable texBase1() {
        return new EncTable("TeXBase1Encoding", TeXBase1.TABLE);
    }

    // 内置 StandardEncoding（PS 标准 256 项，缺槽为 null）
    private static final class StandardEnc {
        static final String[] TABLE = build();

        private static String[] build() {
            String[] t = new String[256];
            String[] named = {
                ".notdef", null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                "minus", null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                "space", "exclam", "quotedbl", "numbersign", "dollar", "percent", "ampersand",
                "quoteright",
                "parenleft", "parenright", "asterisk", "plus", "comma", "hyphen", "period",
                "slash",
                "zero", "one", "two", "three", "four", "five", "six", "seven",
                "eight", "nine", "colon", "semicolon", "less", "equal", "greater", "question",
                "at", "A", "B", "C", "D", "E", "F", "G",
                "H", "I", "J", "K", "L", "M", "N", "O",
                "P", "Q", "R", "S", "T", "U", "V", "W",
                "X", "Y", "Z", "bracketleft", "backslash", "bracketright", "asciicircum",
                "underscore",
                "quoteleft", "a", "b", "c", "d", "e", "f", "g",
                "h", "i", "j", "k", "l", "m", "n", "o",
                "p", "q", "r", "s", "t", "u", "v", "w",
                "x", "y", "z", "braceleft", "bar", "braceright", "asciitilde", null,
                null, "exclamdown", "cent", "sterling", "fraction", "yen", "florin", "section",
                "currency", "quotesingle", "quotedblleft", "guillemotleft", "guilsinglleft",
                "guilsinglright", "fi", "fl",
                "endash", "dagger", "daggerdbl", "periodcentered", null, "paragraph", "bullet",
                "quotesinglbase",
                "quotedblbase", "quotedblright", "guillemotright", "ellipsis", "perthousand",
                null, "questiondown", null,
                "grave", "acute", "circumflex", "tilde", "macron", "breve", "dotaccent",
                "dieresis",
                null, "ring", "cedilla", null, "hungarumlaut", "ogonek", "caron", "emdash",
                null, null, null, null, null, null, null, null,
                null, "AE", null, "ordfeminine", null, null, null, null,
                "Lslash", "Oslash", "OE", "ordmasculine", null, null, null, null,
                null, "ae", null, null, null, null, "dotlessi", null,
                null, "lslash", "oslash", "oe", "germandbls", null, null, null,
            };
            System.arraycopy(named, 0, t, 0, Math.min(named.length, 256));
            return t;
        }
    }

    // 内置 TeXBase1Encoding（= 8r.enc 官方内容，供 enc 文件缺失时兜底；
    // 从 TeX Live 8r.enc 生成）
    private static final class TeXBase1 {
        static final String[] TABLE = build();

        private static String[] build() {
            String[] t = new String[256];
            String[] named = {
                ".notdef", "dotaccent", "fi", "fl", "fraction", "hungarumlaut", "Lslash", "lslash",
                "ogonek", "ring", ".notdef", "breve", "minus", ".notdef", "Zcaron", "zcaron",
                "caron", "dotlessi", "dotlessj", "ff", "ffi", "ffl", "notequal", "infinity",
                "lessequal", "greaterequal", "partialdiff", "summation", "product", "pi", "grave", "quotesingle",
                "space", "exclam", "quotedbl", "numbersign", "dollar", "percent", "ampersand", "quoteright",
                "parenleft", "parenright", "asterisk", "plus", "comma", "hyphen", "period", "slash",
                "zero", "one", "two", "three", "four", "five", "six", "seven",
                "eight", "nine", "colon", "semicolon", "less", "equal", "greater", "question",
                "at", "A", "B", "C", "D", "E", "F", "G",
                "H", "I", "J", "K", "L", "M", "N", "O",
                "P", "Q", "R", "S", "T", "U", "V", "W",
                "X", "Y", "Z", "bracketleft", "backslash", "bracketright", "asciicircum", "underscore",
                "quoteleft", "a", "b", "c", "d", "e", "f", "g",
                "h", "i", "j", "k", "l", "m", "n", "o",
                "p", "q", "r", "s", "t", "u", "v", "w",
                "x", "y", "z", "braceleft", "bar", "braceright", "asciitilde", ".notdef",
                "Euro", "integral", "quotesinglbase", "florin", "quotedblbase", "ellipsis", "dagger", "daggerdbl",
                "circumflex", "perthousand", "Scaron", "guilsinglleft", "OE", "Omega", "radical", "approxequal",
                ".notdef", ".notdef", ".notdef", "quotedblleft", "quotedblright", "bullet", "endash", "emdash",
                "tilde", "trademark", "scaron", "guilsinglright", "oe", "Delta", "lozenge", "Ydieresis",
                ".notdef", "exclamdown", "cent", "sterling", "currency", "yen", "brokenbar", "section",
                "dieresis", "copyright", "ordfeminine", "guillemotleft", "logicalnot", "hyphen", "registered", "macron",
                "degree", "plusminus", "twosuperior", "threesuperior", "acute", "mu", "paragraph", "periodcentered",
                "cedilla", "onesuperior", "ordmasculine", "guillemotright", "onequarter", "onehalf", "threequarters", "questiondown",
                "Agrave", "Aacute", "Acircumflex", "Atilde", "Adieresis", "Aring", "AE", "Ccedilla",
                "Egrave", "Eacute", "Ecircumflex", "Edieresis", "Igrave", "Iacute", "Icircumflex", "Idieresis",
                "Eth", "Ntilde", "Ograve", "Oacute", "Ocircumflex", "Otilde", "Odieresis", "multiply",
                "Oslash", "Ugrave", "Uacute", "Ucircumflex", "Udieresis", "Yacute", "Thorn", "germandbls",
                "agrave", "aacute", "acircumflex", "atilde", "adieresis", "aring", "ae", "ccedilla",
                "egrave", "eacute", "ecircumflex", "edieresis", "igrave", "iacute", "icircumflex", "idieresis",
                "eth", "ntilde", "ograve", "oacute", "ocircumflex", "otilde", "odieresis", "divide",
                "oslash", "ugrave", "uacute", "ucircumflex", "udieresis", "yacute", "thorn", "ydieresis",
            };
            System.arraycopy(named, 0, t, 0, Math.min(named.length, 256));
            return t;
        }
    }
}
