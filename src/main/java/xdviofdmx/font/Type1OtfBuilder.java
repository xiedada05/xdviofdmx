package xdviofdmx.font;

import org.apache.fontbox.type1.Type1Font;

import java.awt.geom.AffineTransform;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

/**
 * Type 1 (PFB) → OpenType/CFF 转换器。
 *
 * <p>OFD 仅接受 TrueType/OpenType 嵌入，因此 legacy 基字体经本类转成自包含
 * OTF 后接入既有 native 嵌入管线（fontbox 解析、AWT 轮廓兜底、fontTools
 * 子集化）。</p>
 *
 * <p>关键设计——<b>GID = TFM 槽位</b>（0..255 恒密度，.notdef 固定 GID 0，
 * 未定义槽位为空字形）：外层 char run 携带的就是槽位号，无需映射层；cmap 直接
 * 由「槽位 → 编码字形名 → AGL → Unicode」构造，文本可反查、无需 CGTransform。
 * charset 各字形统一复用 SID 0（.notdef）——字形名仅内部使用，cmap 承载全部
 * 语义，对渲染与子集化无影响。</p>
 *
 * <p>charstring 由 fontbox 的字形轮廓（GeneralPath，seac 组合已完成）重新编码
 * 为 Type 2 算子；advance 取 {@link Type1Font#getWidth(String)}（hsbw 的 wx）。
 * 全部坐标按 PFB FontMatrix 预缩放到 unitsPerEm=1000，与 CFF 默认矩阵一致
 * （无需写出 FontMatrix）。</p>
 */
public final class Type1OtfBuilder {

    private static final int UPEM = 1000;
    private static final int NGlyphs = 256;

    /** 生成的 OTF unitsPerEm（恒 1000） */
    public static int upem() {
        return UPEM;
    }

    private Type1OtfBuilder() {
    }

    /** 转换输入：槽位字形名（256 项，null=未定义）与槽位 Unicode（null=未知） */
    public record Slots(String[] glyphNames, String[] unicodes) {
    }

    /**
     * 构建 OTF 文件。
     *
     * @param font  fontbox 已解析的 PFB
     * @param slots 槽位字形名/Unicode 表
     * @param out   输出 .otf 路径
     */
    public static void build(Type1Font font, Slots slots, Path out) throws IOException {
        // FontMatrix[0] = 1/upem；轮廓统一预缩放到 glyph units = UPEM
        double scale = 1.0;
        try {
            List<java.lang.Number> fm = font.getFontMatrix();
            if (fm != null && !fm.isEmpty() && fm.get(0).doubleValue() > 0) {
                scale = UPEM * fm.get(0).doubleValue();
            }
        } catch (Exception ignore) {
            // 缺省（FontMatrix 已是 0.001）
        }

        String psName = sanitize(font.getFontName());
        if (psName == null || psName.isEmpty()) {
            psName = "LegacyFont";
        }
        String family = psName;
        try {
            String f = font.getFamilyName();
            if (f != null && !f.isBlank()) {
                family = sanitize(f);
            }
        } catch (Exception ignore) {
            // 用 PS 名
        }
        boolean bold = psName.contains("Bold");
        boolean italic = psName.contains("Italic") || psName.contains("Oblique");
        float italicAngle = 0;
        try {
            italicAngle = font.getItalicAngle();
        } catch (Exception ignore) {
            // 缺省 0
        }

        String[] names = slots.glyphNames();
        String[] unicodes = slots.unicodes();

        // ---- 逐槽位：轮廓 → Type2 charstring ----
        byte[][] charstrings = new byte[NGlyphs][];
        int[] advances = new int[NGlyphs];
        int[] lsbs = new int[NGlyphs];
        Rectangle2D bbox = null;
        for (int slot = 0; slot < NGlyphs; slot++) {
            String name = slot < names.length ? names[slot] : null;
            if (slot == 0 || name == null || !font.hasGlyph(name)) {
                charstrings[slot] = new byte[] {14}; // .notdef / 未定义：空字形
                continue;
            }
            java.awt.geom.GeneralPath path;
            try {
                path = font.getPath(name);
            } catch (IOException e) {
                path = null;
            }
            float adv = 0;
            try {
                adv = font.getWidth(name);
            } catch (IOException ignore) {
                // advance 0
            }
            advances[slot] = (int) Math.max(0, Math.round(adv * scale));
            if (path == null || isDone(path)) {
                charstrings[slot] = new byte[] {14};
                continue;
            }
            Rectangle2D b = path.getBounds2D();
            if (!b.isEmpty()) {
                Rectangle2D scaled = new Rectangle2D.Double(
                        b.getMinX() * scale, b.getMinY() * scale,
                        b.getWidth() * scale, b.getHeight() * scale);
                bbox = bbox == null ? scaled : bbox.createUnion(scaled);
            }
            lsbs[slot] = (int) Math.round(b.getMinX() * scale);
            charstrings[slot] = encodeCharstring(path, scale, advances[slot]);
        }
        if (bbox == null) {
            bbox = new Rectangle2D.Double(0, -UPEM / 4.0, UPEM / 2.0, UPEM / 4.0);
        }
        int advanceMax = 0;
        for (int a : advances) {
            advanceMax = Math.max(advanceMax, a);
        }

        // ---- SFNT 表 ----
        int asc = (int) Math.round(bbox.getMaxY());
        int desc = (int) Math.round(bbox.getMinY());
        List<Table> ordered = new ArrayList<>();
        ordered.add(new Table("CFF ", buildCff(psName, charstrings)));
        ordered.add(new Table("OS/2", buildOs2(bold, italic, asc, desc, unicodes)));
        ordered.add(new Table("cmap", buildCmap(unicodes)));
        ordered.add(new Table("head", buildHead(bbox, bold, italic)));
        ordered.add(new Table("hhea", buildHhea(asc, desc, advanceMax, advances, lsbs)));
        ordered.add(new Table("hmtx", buildHmtx(advances, lsbs)));
        ordered.add(new Table("maxp", buildMaxp()));
        ordered.add(new Table("name", buildName(psName, family)));
        ordered.add(new Table("post", buildPost(italicAngle)));
        Files.write(out, assembleSfnt(ordered));
    }

    private static String sanitize(String n) {
        return n == null ? null : n.replaceAll("[\\p{Cntrl}()\\[\\]/\\\\]", "");
    }

    // ---------------------------------------------------------------- CFF

    private static byte[] buildCff(String psName, byte[][] charstrings) {
        byte[] header = {1, 0, 4, 4};
        byte[] nameIndex = index(new byte[][] {ascii(psName)}, 1);
        byte[] stringIndex = index(new byte[0][], 1);
        byte[] gsubrIndex = index(new byte[0][], 1);
        byte[] privDict = {(byte) 139, 20, (byte) 139, 21}; // defaultWidthX 0, nominalWidthX 0

        // TopDICT body：FontMatrix 省略（默认 0.001 = UPEM）；3 个 5 字节占位 int
        ByteArrayOutputStream dict = new ByteArrayOutputStream();
        int phCharset = dict.size();
        dictOp5(dict, 15);            // charset
        int phCharstrings = dict.size();
        dictOp5(dict, 17);            // CharStrings
        int phPrivSize = dict.size(); // int28 占位（3 字节）
        dict.write(28);
        dict.write(0);
        dict.write(0);
        int phPrivOff = dict.size();
        dictOp5(dict, 18);            // Private [size offset]
        byte[] body = dict.toByteArray();

        int topDictIndexLen = index(new byte[][] {body}, 1).length;
        int charsetOffset = header.length + nameIndex.length + topDictIndexLen
                + stringIndex.length + gsubrIndex.length;
        int charsetLen = 1 + 2 * (NGlyphs - 1);
        int charstringsOffset = charsetOffset + charsetLen;
        byte[] csIndex = index(charstrings, 2);
        int privateOffset = charstringsOffset + csIndex.length;

        patchI32(body, phCharset + 1, charsetOffset);
        patchI32(body, phCharstrings + 1, charstringsOffset);
        body[phPrivSize + 1] = (byte) ((privDict.length >> 8) & 0xFF);
        body[phPrivSize + 2] = (byte) (privDict.length & 0xFF);
        patchI32(body, phPrivOff + 1, privateOffset);
        byte[] topDictIndex = index(new byte[][] {body}, 1);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(header);
        out.writeBytes(nameIndex);
        out.writeBytes(topDictIndex);
        out.writeBytes(stringIndex);
        out.writeBytes(gsubrIndex);
        out.write(0); // charset format 0：GID 1..255 的 SID 统一 0（.notdef）
        for (int g = 1; g < NGlyphs; g++) {
            out.write(0);
            out.write(0);
        }
        out.writeBytes(csIndex);
        out.writeBytes(privDict);
        return out.toByteArray();
    }

    private static void dictOp5(ByteArrayOutputStream o, int op) {
        o.write(29); // 32 位 int 占位（后续 patchI32）
        o.write(0);
        o.write(0);
        o.write(0);
        o.write(0);
        o.write(op);
    }

    private static void patchI32(byte[] body, int at, int value) {
        body[at] = (byte) (value >>> 24);
        body[at + 1] = (byte) (value >>> 16);
        body[at + 2] = (byte) (value >>> 8);
        body[at + 3] = (byte) value;
    }

    /** CFF INDEX：count(2) offSize(1) offsets[count+1] data */
    private static byte[] index(byte[][] items, int offSize) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        writeU16(o, items.length);
        if (items.length == 0) {
            return o.toByteArray();
        }
        o.write(offSize);
        int acc = 1;
        int[] offs = new int[items.length + 1];
        offs[0] = 1;
        for (int i = 0; i < items.length; i++) {
            acc += items[i].length;
            offs[i + 1] = acc;
        }
        for (int v : offs) {
            writeIntN(o, v, offSize);
        }
        for (byte[] it : items) {
            o.writeBytes(it);
        }
        return o.toByteArray();
    }

    // ---------------------------------------------------------------- Type2

    private static boolean isDone(java.awt.geom.GeneralPath p) {
        if (p == null) {
            return true;
        }
        return p.getPathIterator(null).isDone();
    }

    /** GeneralPath → Type2 charstring；advance≠0 作为首 rmoveto 的 width 操作数 */
    private static byte[] encodeCharstring(java.awt.geom.GeneralPath path, double scale,
                                           int advance) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        double cx = 0, cy = 0; // 未缩放当前点（原始坐标）
        int curX = 0, curY = 0; // 已缩放当前点
        int startX = 0, startY = 0;
        boolean firstMove = true;
        double[] c = new double[6];
        for (PathIterator it = path.getPathIterator(new AffineTransform()); !it.isDone();
                it.next()) {
            int t = it.currentSegment(c);
            switch (t) {
                case PathIterator.SEG_MOVETO -> {
                    int x = rint(c[0] * scale), y = rint(c[1] * scale);
                    List<Integer> args = new ArrayList<>();
                    if (firstMove && advance != 0) {
                        args.add(advance); // Type2：rmoveto 首操作数 = width
                        args.add(x - curX);
                        args.add(y - curY);
                        firstMove = false;
                    } else {
                        args.add(x - curX);
                        args.add(y - curY);
                    }
                    emitOp(o, args, 21); // rmoveto
                    startX = x;
                    startY = y;
                    curX = x;
                    curY = y;
                    cx = c[0];
                    cy = c[1];
                }
                case PathIterator.SEG_LINETO -> {
                    int nx = rint(c[0] * scale), ny = rint(c[1] * scale);
                    emitOp(o, List.of(nx - curX, ny - curY), 5); // rlineto
                    curX = nx;
                    curY = ny;
                    cx = c[0];
                    cy = c[1];
                }
                case PathIterator.SEG_QUADTO -> {
                    // 二次 → 三次（1/3、2/3 控制点）
                    double x1 = c[2], y1 = c[3];
                    double cx1 = cx + 2.0 / 3 * (c[0] - cx), cy1 = cy + 2.0 / 3 * (c[1] - cy);
                    double cx2 = x1 + 2.0 / 3 * (c[0] - x1), cy2 = y1 + 2.0 / 3 * (c[1] - y1);
                    int a1 = rint(cx1 * scale) - curX, a2 = rint(cy1 * scale) - curY;
                    int a3 = rint(cx2 * scale) - rint(cx1 * scale), a4 = rint(cy2 * scale)
                            - rint(cy1 * scale);
                    int a5 = rint(x1 * scale) - rint(cx2 * scale), a6 = rint(y1 * scale)
                            - rint(cy2 * scale);
                    emitOp(o, List.of(a1, a2, a3, a4, a5, a6), 8); // rrcurveto
                    curX = rint(x1 * scale);
                    curY = rint(y1 * scale);
                    cx = x1;
                    cy = y1;
                }
                case PathIterator.SEG_CUBICTO -> {
                    int a1 = rint(c[0] * scale) - curX, a2 = rint(c[1] * scale) - curY;
                    int a3 = rint(c[2] * scale) - rint(c[0] * scale);
                    int a4 = rint(c[3] * scale) - rint(c[1] * scale);
                    int a5 = rint(c[4] * scale) - rint(c[2] * scale);
                    int a6 = rint(c[5] * scale) - rint(c[3] * scale);
                    emitOp(o, List.of(a1, a2, a3, a4, a5, a6), 8); // rrcurveto
                    curX = rint(c[4] * scale);
                    curY = rint(c[5] * scale);
                    cx = c[4];
                    cy = c[5];
                }
                case PathIterator.SEG_CLOSE -> {
                    curX = startX;
                    curY = startY;
                    cx = startX / scale;
                    cy = startY / scale;
                }
                default -> { }
            }
        }
        if (o.size() == 0) {
            if (advance == 0) {
                return new byte[] {14}; // endchar
            }
            ByteArrayOutputStream e = new ByteArrayOutputStream();
            pushNum(e, advance);
            e.write(14); // endchar（单操作数 = width）
            return e.toByteArray();
        }
        o.write(14); // endchar
        return o.toByteArray();
    }

    private static void emitOp(ByteArrayOutputStream o, List<Integer> args, int op) {
        for (int v : args) {
            pushNum(o, v);
        }
        o.write(op);
    }

    private static int rint(double v) {
        return (int) Math.round(v);
    }

    /** Type2 数字编码 */
    private static void pushNum(ByteArrayOutputStream o, int v) {
        if (v >= -107 && v <= 107) {
            o.write(v + 139);
        } else if (v >= 108 && v <= 1131) {
            int d = v - 108;
            o.write((d >> 8) + 247);
            o.write(d & 0xFF);
        } else if (v <= -108 && v >= -1131) {
            int d = -v - 108;
            o.write((d >> 8) + 251);
            o.write(d & 0xFF);
        } else {
            o.write(255);
            o.write(v >>> 24);
            o.write(v >>> 16);
            o.write(v >>> 8);
            o.write(v);
        }
    }

    // ---------------------------------------------------------------- SFNT 表

    private static byte[] buildHead(Rectangle2D bbox, boolean bold, boolean italic) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        writeU32(o, 0x00010000);
        writeU32(o, 0x00010000); // fontRevision
        writeU32(o, 0); // checkSumAdjustment（不校验；fontTools/AWT 均容忍 0）
        writeU32(o, 0x5F0F3CF5); // magic
        writeU16(o, 0x000B); // flags
        writeU16(o, UPEM);
        writeU32(o, 0); // created LONGDATETIME 低 32 位（8 字节，0 即 1904-01-01）
        writeU32(o, 0); // created 高 32 位
        writeU32(o, 0); // modified 低 32 位
        writeU32(o, 0); // modified 高 32 位
        writeI16(o, (int) Math.round(bbox.getMinX()));
        writeI16(o, (int) Math.round(bbox.getMinY()));
        writeI16(o, (int) Math.round(bbox.getMaxX()));
        writeI16(o, (int) Math.round(bbox.getMaxY()));
        writeU16(o, (bold ? 1 : 0) | (italic ? 2 : 0)); // macStyle
        writeU16(o, 8); // lowestRecPPEM
        writeI16(o, 2); // fontDirectionHint
        writeU16(o, 0); // indexToLocFormat（CFF 无 loca）
        writeI16(o, 0); // glyphDataFormat
        return o.toByteArray();
    }

    private static byte[] buildHhea(int asc, int desc, int advanceMax, int[] advances,
                                    int[] lsbs) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        writeU32(o, 0x00010000);
        writeI16(o, asc);
        writeI16(o, desc);
        writeI16(o, 0); // lineGap
        writeU16(o, Math.max(1, advanceMax));
        int minLsb = Integer.MAX_VALUE, minRsb = Integer.MAX_VALUE, xExtent = 0;
        for (int g = 0; g < NGlyphs; g++) {
            if (advances[g] > 0 || lsbs[g] != 0) {
                minLsb = Math.min(minLsb, lsbs[g]);
                minRsb = Math.min(minRsb, advances[g] - lsbs[g]);
                xExtent = Math.max(xExtent, advances[g]);
            }
        }
        if (minLsb == Integer.MAX_VALUE) {
            minLsb = 0;
            minRsb = 0;
        }
        writeI16(o, minLsb);
        writeI16(o, minRsb);
        writeI16(o, xExtent);
        writeI16(o, 1); // caretSlopeRise
        writeI16(o, 0); // caretSlopeRun
        writeI16(o, 0); // caretOffset
        for (int i = 0; i < 4; i++) {
            writeI16(o, 0); // reserved（恒 4 个）
        }
        writeI16(o, 0); // metricDataFormat
        writeU16(o, NGlyphs); // numberOfHMetrics
        return o.toByteArray();
    }

    private static byte[] buildMaxp() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        writeU32(o, 0x00005000); // CFF 版本 0.5
        writeU16(o, NGlyphs);
        return o.toByteArray();
    }

    private static byte[] buildHmtx(int[] advances, int[] lsbs) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (int g = 0; g < NGlyphs; g++) {
            writeU16(o, Math.min(0xFFFF, Math.max(0, advances[g])));
            writeI16(o, lsbs[g]);
        }
        return o.toByteArray();
    }

    /** cmap format 4（unicode → 槽位=gid），BMP */
    private static byte[] buildCmap(String[] unicodes) {
        TreeMap<Integer, Integer> map = new TreeMap<>();
        for (int slot = 0; slot < unicodes.length; slot++) {
            String u = unicodes[slot];
            if (u == null) {
                continue;
            }
            int cp = u.codePointAt(0);
            if (cp <= 0xFFFF && !map.containsKey(cp)) {
                map.put(cp, slot);
            }
        }
        // 分段：连续 codepoint 且 delta 恒定的段合并
        List<int[]> segs = new ArrayList<>(); // {start, end, delta}
        int segStart = -1, segEnd = -1, segDelta = 0;
        for (var e : map.entrySet()) {
            int cp = e.getKey(), gid = e.getValue();
            int delta = (gid - cp) & 0xFFFF;
            if (segStart >= 0 && cp == segEnd + 1 && delta == segDelta) {
                segEnd = cp;
            } else {
                if (segStart >= 0) {
                    segs.add(new int[] {segStart, segEnd, segDelta});
                }
                segStart = cp;
                segEnd = cp;
                segDelta = delta;
            }
        }
        if (segStart >= 0) {
            segs.add(new int[] {segStart, segEnd, segDelta});
        }
        segs.add(new int[] {0xFFFF, 0xFFFF, 1}); // 终止段（映射到 GID 0）

        int segCount = segs.size();
        // 头 14 字节（format/length/language/segCountX2/searchRange/entrySelector/rangeShift）
        // + endCode[seg] + reservedPad(2) + startCode[seg] + idDelta[seg] + idRangeOffset[seg]
        int len = 16 + 8 * segCount;
        ByteArrayOutputStream sub = new ByteArrayOutputStream();
        writeU16(sub, 4);
        writeU16(sub, len);
        writeU16(sub, 0);
        writeU16(sub, segCount * 2);
        int entrySelector = Integer.numberOfTrailingZeros(Integer.highestOneBit(segCount));
        int searchRange = (1 << entrySelector) * 2;
        writeU16(sub, searchRange);
        writeU16(sub, entrySelector);
        writeU16(sub, segCount * 2 - searchRange);
        for (int[] s : segs) {
            writeU16(sub, s[1]); // endCode
        }
        writeU16(sub, 0); // reservedPad
        for (int[] s : segs) {
            writeU16(sub, s[0]); // startCode
        }
        for (int[] s : segs) {
            writeU16(sub, s[2]); // idDelta
        }
        for (int i = 0; i < segCount; i++) {
            writeU16(sub, 0); // idRangeOffset
        }

        ByteArrayOutputStream o = new ByteArrayOutputStream();
        writeU16(o, 0); // version
        writeU16(o, 1); // numTables
        writeU16(o, 3); // platformID Windows
        writeU16(o, 1); // encodingID BMP
        writeU32(o, 12); // subtable offset
        o.writeBytes(sub.toByteArray());
        return o.toByteArray();
    }

    private static byte[] buildName(String psName, String family) {
        List<byte[]> records = new ArrayList<>();
        List<int[]> ids = new ArrayList<>();
        // nameID 1..6：family, subfamily, unique, full, version, postscript
        addName(records, ids, 1, family);
        addName(records, ids, 2, "Regular");
        addName(records, ids, 3, psName + ";xdviofdmx-legacy");
        addName(records, ids, 4, family);
        addName(records, ids, 5, "Version 1.0 (legacy conversion)");
        addName(records, ids, 6, psName);

        ByteArrayOutputStream o = new ByteArrayOutputStream();
        writeU16(o, 0);
        writeU16(o, records.size());
        writeU16(o, 6 + 12 * records.size());
        int strPos = 0;
        for (int i = 0; i < records.size(); i++) {
            writeU16(o, 3); // platform Windows
            writeU16(o, 1); // UTF-16BE
            writeU16(o, 0x409); // en-US
            writeU16(o, ids.get(i)[0]);
            writeU16(o, records.get(i).length);
            writeU16(o, strPos);
            strPos += records.get(i).length;
        }
        for (byte[] r : records) {
            o.writeBytes(r);
        }
        return o.toByteArray();
    }

    private static void addName(List<byte[]> recs, List<int[]> ids, int nameId, String value) {
        recs.add(value.getBytes(StandardCharsets.UTF_16BE));
        ids.add(new int[] {nameId});
    }

    private static byte[] buildOs2(boolean bold, boolean italic, int asc, int desc,
                                   String[] unicodes) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        writeU16(o, 1); // version 1
        writeI16(o, 500); // xAvgCharWidth（近似）
        writeU16(o, bold ? 700 : 400); // usWeightClass
        writeU16(o, 5); // usWidthClass Medium
        writeU16(o, 0x0008); // fsType：可嵌入可转换
        for (int i = 0; i < 10; i++) {
            writeI16(o, 500); // subscript 等（占位值）
        }
        o.write(0);
        o.write(0); // sFamilyClass
        for (int i = 0; i < 10; i++) {
            o.write(0); // panose
        }
        writeU32(o, 0);
        writeU32(o, 0);
        writeU32(o, 0);
        writeU32(o, 0); // ulUnicodeRange1-4
        ascii4(o, "xdvf"); // achVendID
        writeU16(o, (italic ? 0x01 : 0) | (bold ? 0x20 : 0)); // fsSelection
        int first = 0xFFFF, last = 0;
        for (String u : unicodes) {
            if (u != null) {
                int cp = u.codePointAt(0);
                first = Math.min(first, cp);
                last = Math.max(last, cp);
            }
        }
        if (first > last) {
            first = 0x20;
            last = 0xFFFD;
        }
        writeU16(o, first);
        writeU16(o, Math.min(0xFFFF, last));
        writeI16(o, asc);
        writeI16(o, desc);
        writeI16(o, 0); // typoLineGap
        writeU16(o, Math.max(0, asc));
        writeU16(o, Math.max(0, -desc));
        writeU32(o, 1); // ulCodePageRange1：Latin 1
        writeU32(o, 0); // ulCodePageRange2
        return o.toByteArray();
    }

    private static byte[] buildPost(float italicAngle) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        writeU32(o, 0x00030000); // version 3（无字形名）
        writeU32(o, floatBits(italicAngle));
        writeI16(o, -100); // underlinePosition
        writeI16(o, 50); // underlineThickness
        writeU32(o, 0); // isFixedPitch
        writeU32(o, 0);
        writeU32(o, 0);
        writeU32(o, 0);
        writeU32(o, 0);
        return o.toByteArray();
    }

    // ---- SFNT 容器 ----

    /** SFNT 表（tag + 数据） */
    private record Table(String tag, byte[] data) {
    }

    private static byte[] assembleSfnt(List<Table> tables) {
        tables.sort(Comparator.comparing(Table::tag));
        int numTables = tables.size();
        int entrySelector = 31 - Integer.numberOfLeadingZeros(Integer.highestOneBit(numTables));
        int searchRange = (1 << entrySelector) * 16;
        int rangeShift = numTables * 16 - searchRange;

        int[] offsets = new int[numTables];
        int[] lens = new int[numTables];
        long[] sums = new long[numTables];
        int offset = 12 + 16 * numTables;
        for (int i = 0; i < numTables; i++) {
            byte[] data = tables.get(i).data();
            lens[i] = data.length;
            sums[i] = checksum(data);
            offsets[i] = offset;
            offset += (data.length + 3) & ~3;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeU32(out, 0x4F54544F); // 'OTTO'（CFF 轮廓）
        writeU16(out, numTables);
        writeU16(out, searchRange);
        writeU16(out, entrySelector);
        writeU16(out, rangeShift);
        for (int i = 0; i < numTables; i++) {
            out.writeBytes(ascii(tables.get(i).tag()));
            writeU32(out, (int) sums[i]);
            writeU32(out, offsets[i]);
            writeU32(out, lens[i]);
        }
        for (Table t : tables) {
            out.writeBytes(t.data());
            int pad = (4 - (t.data().length & 3)) & 3;
            for (int k = 0; k < pad; k++) {
                out.write(0);
            }
        }
        return out.toByteArray();
    }

    /** SFNT 表校验和：4 字节对齐（尾部 0 补齐）的 u32 累加 */
    static long checksum(byte[] data) {
        long sum = 0;
        int n = (data.length + 3) & ~3;
        for (int i = 0; i < n; i += 4) {
            long b0 = i < data.length ? data[i] & 0xFF : 0;
            long b1 = i + 1 < data.length ? data[i + 1] & 0xFF : 0;
            long b2 = i + 2 < data.length ? data[i + 2] & 0xFF : 0;
            long b3 = i + 3 < data.length ? data[i + 3] & 0xFF : 0;
            sum = (sum + ((b0 << 24) | (b1 << 16) | (b2 << 8) | b3)) & 0xFFFFFFFFL;
        }
        return sum;
    }

    // ---- 基础写出 ----

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static void ascii4(ByteArrayOutputStream o, String s) {
        byte[] b = ascii(s);
        o.write(b.length >= 4 ? b[0] : 0);
        o.write(b.length >= 4 ? b[1] : 0);
        o.write(b.length >= 4 ? b[2] : 0);
        o.write(b.length >= 4 ? b[3] : 0);
    }

    private static void writeU16(ByteArrayOutputStream o, int v) {
        o.write((v >>> 8) & 0xFF);
        o.write(v & 0xFF);
    }

    private static void writeI16(ByteArrayOutputStream o, int v) {
        writeU16(o, v & 0xFFFF);
    }

    private static void writeU32(ByteArrayOutputStream o, long v) {
        o.write((int) ((v >>> 24) & 0xFF));
        o.write((int) ((v >>> 16) & 0xFF));
        o.write((int) ((v >>> 8) & 0xFF));
        o.write((int) (v & 0xFF));
    }

    private static void writeIntN(ByteArrayOutputStream o, int v, int n) {
        for (int i = n - 1; i >= 0; i--) {
            o.write((v >>> (8 * i)) & 0xFF);
        }
    }

    private static long floatBits(float f) {
        return Float.floatToIntBits(f) & 0xFFFFFFFFL;
    }
}
