package xdviofdmx.font;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * dvips/pdftex 字体映射表（pdftex.map）解析。
 *
 * <p>行格式（本机 updmap 生成树实测）：</p>
 * <pre>
 *   pplr8r URWPalladioL-Roma " TeXBase1Encoding ReEncodeFont " &lt;8r.enc &lt;uplr8a.pfb
 *   ntxsym txsym &lt;txsym.pfb
 *   lmmonolt10-bold LMMonoLt10-Bold " enclmec ReEncodeFont " &lt;lm-ec.enc &lt;lmtk10.pfb
 * </pre>
 * 即：TFM 名、PostScript 名、可选引号变换串、可选 {@code <enc}、
 * {@code <字体文件}（{@code <<} 表示 TrueType/OpenType），以及可选的
 * {@code 0.167 SlantFont} / {@code 1.2 ExtendFont} 合成参数。
 */
public final class DvipsMap {

    /** 一条映射：TFM 名 → 字体程序与变换 */
    public record Entry(String tfmName, String psName, String encFile, String fontFile,
                        boolean fontIsType1, double slant, double extend) {
    }

    private final Map<String, Entry> entries = new HashMap<>();

    /** 按 TFM 名查映射；没有返回 null */
    public Entry get(String tfmName) {
        return entries.get(tfmName);
    }

    public int size() {
        return entries.size();
    }

    public static DvipsMap parse(Path mapFile) throws IOException {
        DvipsMap map = new DvipsMap();
        List<String> lines = Files.readAllLines(mapFile, StandardCharsets.ISO_8859_1);
        for (String line : lines) {
            Entry e = parseLine(line);
            if (e != null) {
                map.entries.putIfAbsent(e.tfmName(), e);
            }
        }
        return map;
    }

    /** 解析一行；注释/空行/不完整行返回 null */
    static Entry parseLine(String line) {
        String t = line.trim();
        if (t.isEmpty() || t.startsWith("%") || t.startsWith("*")
                || t.startsWith("-") || t.startsWith("!")) {
            return null;
        }
        List<String> tokens = tokenize(t);
        if (tokens.size() < 2) {
            return null;
        }
        String tfm = tokens.get(0);
        String psName = tokens.get(1);
        String enc = null;
        String fontFile = null;
        boolean isType1 = true;
        double slant = 0, extend = 0;
        for (int i = 2; i < tokens.size(); i++) {
            String tok = tokens.get(i);
            if (tok.startsWith("<<")) {
                fontFile = tok.substring(2);
                isType1 = false; // << 为 TrueType/OpenType
            } else if (tok.startsWith("<")) {
                String f = tok.substring(1);
                if (f.startsWith("<")) {
                    f = f.substring(1);
                    isType1 = false;
                }
                if (f.endsWith(".enc")) {
                    enc = f;
                } else {
                    fontFile = f; // 最后一个字体文件引用生效
                }
            } else if (tok.startsWith("\"")) {
                // 引号内容已被 tokenizer 合并；忽略（ReEncodeFont 等驱动指令）
            } else if (i + 1 < tokens.size()
                    && (tokens.get(i + 1).equals("SlantFont") || tokens.get(i + 1).equals("ExtendFont"))) {
                try {
                    double v = Double.parseDouble(tok);
                    if (tokens.get(i + 1).equals("SlantFont")) {
                        slant = v;
                    } else {
                        extend = v;
                    }
                    i++;
                } catch (NumberFormatException ignore) {
                    // 非法数字：忽略
                }
            }
        }
        if (fontFile == null) {
            return null; // 无字体程序：无法嵌入（位图/metafont 场景）
        }
        return new Entry(tfm, psName, enc, fontFile, isType1, slant, extend);
    }

    /** 按空白分词，"..." 引号内容合并为一个 token，% 注释截断 */
    private static List<String> tokenize(String line) {
        List<String> out = new java.util.ArrayList<>();
        int i = 0, n = line.length();
        while (i < n) {
            char c = line.charAt(i);
            if (c == ' ' || c == '\t') {
                i++;
            } else if (c == '%') {
                break; // 行内注释
            } else if (c == '"') {
                int j = i + 1;
                while (j < n && line.charAt(j) != '"') {
                    j++;
                }
                out.add(line.substring(i, Math.min(j + 1, n)));
                i = j + 1;
            } else {
                int j = i;
                while (j < n && line.charAt(j) != ' ' && line.charAt(j) != '\t'
                        && line.charAt(j) != '%') {
                    j++;
                }
                out.add(line.substring(i, j));
                i = j;
            }
        }
        return out;
    }
}
