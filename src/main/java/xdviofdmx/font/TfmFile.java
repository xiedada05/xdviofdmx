package xdviofdmx.font;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * TFM（TeX Font Metric）文件解析。
 *
 * <p>legacy 字符 run 的水平推进全部来自外层 TFM 宽度（DVI 语义：
 * {@code set_char} 按当前字体的 TFM 宽度推进当前点），因此这是 legacy
 * 定位的数据源：{@code widthDvi(slot) = round(fixword × scaleFactor / 2²⁰)}。</p>
 *
 * <p>仅解析宽度表；高度/深度/斜率等度量暂不需要。</p>
 */
public final class TfmFile {

    private final int firstChar; // bc
    private final int lastChar;  // ec
    private final int[] charInfo; // 槽位 → 原始 32 位 char_info word（未定义槽位为 -1）
    private final int[] widths;   // fixword 表
    public final int checksum;
    public final int designSize; // fixword pt

    private TfmFile(int firstChar, int lastChar, int[] charInfo, int[] widths,
                    int checksum, int designSize) {
        this.firstChar = firstChar;
        this.lastChar = lastChar;
        this.charInfo = charInfo;
        this.widths = widths;
        this.checksum = checksum;
        this.designSize = designSize;
    }

    public boolean hasChar(int slot) {
        return slot >= firstChar && slot <= lastChar
                && charInfo[slot - firstChar] >= 0;
    }

    /** 槽位原始 TFM 宽度（fixword，相对设计尺寸）；未定义槽位返回 0 */
    public int fixWidth(int slot) {
        if (!hasChar(slot)) {
            return 0;
        }
        int wi = (charInfo[slot - firstChar] >>> 24) & 0xFF;
        return wi < widths.length ? widths[wi] : 0;
    }

    /**
     * 槽位宽度换算到 DVI 单位：{@code round(fixword × scaleFactor / 2²⁰)}。
     * DVI 语义：fnt_def 的 scale_factor 即当前字体的 s（DVI 单位）。
     */
    public long widthDvi(int slot, long scaleFactor) {
        long prod = (long) fixWidth(slot) * scaleFactor;
        return Math.round(prod / (double) (1 << 20));
    }

    /**
     * 全表宽度（槽位索引，DVI 单位），供解析器宽度注入。
     * 表长 = ec+1；未定义槽位为 0。
     */
    public long[] widthsDvi(long scaleFactor) {
        int len = lastChar + 1;
        long[] out = new long[Math.max(1, len)];
        for (int c = firstChar; c <= lastChar; c++) {
            out[c] = widthDvi(c, scaleFactor);
        }
        return out;
    }

    public static TfmFile parse(Path file) throws IOException {
        byte[] raw = Files.readAllBytes(file);
        ByteBuffer buf = ByteBuffer.wrap(raw);
        int lf = buf.getShort() & 0xFFFF; // 全文件长度（words）
        int lh = buf.getShort() & 0xFFFF;
        int bc = buf.getShort() & 0xFFFF;
        int ec = buf.getShort() & 0xFFFF;
        int nw = buf.getShort() & 0xFFFF;
        buf.getShort(); // nh
        buf.getShort(); // nd
        buf.getShort(); // ni
        buf.getShort(); // nl
        buf.getShort(); // nk
        buf.getShort(); // ne
        buf.getShort(); // np
        if (bc > ec || ec > 65535 || nw == 0) {
            throw new IOException("TFM 参数异常 bc=" + bc + " ec=" + ec + " nw=" + nw);
        }

        // header: checksum, design_size, （编码 scheme/family 等按需跳过）
        int checksum = buf.getInt();
        int designSize = buf.getInt();
        // 跳过其余 header 与所有中间表，直接到 char_info 与 width 表
        int pos = 6 + lh; // words 前 6 个长度字 + header

        int nChars = ec - bc + 1;
        int charInfoPos = pos;
        int widthPos = charInfoPos + nChars;
        // width 表在 char_info 之后（TFM 布局: header, char_info, width, height, ...）
        int[] info = new int[nChars];
        buf.position(charInfoPos * 4);
        for (int i = 0; i < nChars; i++) {
            info[i] = buf.getInt();
        }
        int[] w = new int[nw];
        buf.position(widthPos * 4);
        for (int i = 0; i < nw; i++) {
            w[i] = buf.getInt();
        }
        return new TfmFile(bc, ec, info, w, checksum, designSize);
    }
}
