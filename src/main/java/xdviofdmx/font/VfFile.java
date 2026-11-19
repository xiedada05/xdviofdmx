package xdviofdmx.font;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * VF（虚拟字体）文件解析与字符 packet 重放。
 *
 * <p>格式（Knuth, vftovp.web §"The character packets"）：</p>
 * <ul>
 *   <li>pre：{@code 247 id[1] k[1] x[k] cs[4] ds[4]}（id = 202）；</li>
 *   <li>基字体定义：DVI 式 {@code fnt_def 243..246}，其中 scale 为相对量
 *       （fix_word，基字体尺寸 = s × ds × 2⁻²⁰ pt），design 为绝对 pt；</li>
 *   <li>字符 packet：长式 {@code 242 pl[4] cc[4] tfm[4] dvi[pl]}；短式为
 *       首字节 {@code pl < 242}，后跟 {@code cc[1] tfm[3] dvi[pl]}；</li>
 *   <li>post：{@code 248 cs[4] ds[4]}，随后 fnt_def 副本与 post_post。</li>
 * </ul>
 *
 * <p>packet 内的 DVI 尺寸以「VF 设计尺寸 × 2⁻²⁰」为单位；外层字体按
 * scale_factor S（DVI 单位）使用时，换算系数为 {@code S / 2²⁰}——该系数同时
 * 把 packet 坐标与基字体 TFM 推进量换算到外层 DVI 单位。</p>
 */
public final class VfFile {

    /** VF 内声明的基字体定义（scale 为 fix_word 相对量，design 为绝对 pt fix_word） */
    public record VfFontDef(int localNum, int checksum, int scale, int design,
                            String area, String name) {
    }

    /** packet 重放的一个基字形摆放（坐标已换算为外层 DVI 单位，相对字符原点） */
    public record Placement(int baseFontNum, int slot, double x, double y) {
    }

    /** packet 重放的一条 rule（尺寸已换算为外层 DVI 单位） */
    public record RulePlacement(double x, double y, double w, double h) {
    }

    /** 一个槽位的展开结果 */
    public record Expanded(List<Placement> placements, List<RulePlacement> rules) {
    }

    public final int checksum;
    public final int designSize;
    private final Map<Integer, VfFontDef> fontDefs = new HashMap<>();
    private final Map<Integer, byte[]> packets = new HashMap<>(); // 槽位 → packet 字节
    private Function<String, TfmFile> baseTfmLookup = n -> null;

    private VfFile(int checksum, int designSize) {
        this.checksum = checksum;
        this.designSize = designSize;
    }

    public VfFontDef baseFont(int localNum) {
        return fontDefs.get(localNum);
    }

    public boolean hasChar(int slot) {
        return packets.containsKey(slot);
    }

    /** 注入基字体名 → TFM 解析回调（packet 内 set_char 推进需要） */
    public void setBaseTfmLookup(Function<String, TfmFile> lookup) {
        this.baseTfmLookup = lookup;
    }

    /**
     * 重放一个槽位的 packet。
     *
     * @param slot 外层槽位号
     * @param dviPerPacketUnit 外层 scale_factor / 2²⁰（packet 单位 → DVI 单位）
     */
    public Expanded expand(int slot, double dviPerPacketUnit) {
        List<Placement> placements = new ArrayList<>();
        List<RulePlacement> rules = new ArrayList<>();
        byte[] pkt = packets.get(slot);
        if (pkt != null) {
            miniDvi(pkt, dviPerPacketUnit, placements, rules, 0);
        }
        return new Expanded(placements, rules);
    }

    // ---- packet 内 mini-DVI 解释器（+x 向右、+y 向下，packet 单位） ----

    private void miniDvi(byte[] p, double u, List<Placement> out, List<RulePlacement> rules,
                         int depth) {
        if (depth > 8) {
            return; // 防御：嵌套展开深度上限（实际 VF 基字体不再是 VF 时 depth=1）
        }
        double x = 0, y = 0;
        int w = 0, xx = 0, yy = 0, z = 0;
        int fnt = 0; // VF 规范：packet 隐式初始字体为 preamble 定义的 font 0
        List<double[]> stack = new ArrayList<>();
        int i = 0;
        while (i < p.length) {
            int op = p[i++] & 0xFF;
            if (op <= 127) { // set_char_i
                out.add(new Placement(fnt, op, x * u, y * u));
                x += charWidth(fnt, op);
            } else if (op <= 131) { // set1..4
                if (!need(p, i, op - 127)) {
                    break;
                }
                int c = (int) readUnsigned(p, i, op - 127);
                i += op - 127;
                out.add(new Placement(fnt, c, x * u, y * u));
                x += charWidth(fnt, c);
            } else if (op == 132 || op == 137) { // set/put_rule：h[4] w[4]
                if (!need(p, i, 8)) {
                    break;
                }
                int rh = readU32(p, i);
                int rw = readU32(p, i + 4);
                i += 8;
                rules.add(new RulePlacement(x * u, y * u, rw * u, rh * u));
                if (op == 132) {
                    x += rw * u; // set_rule 推进 h；put_rule 不推进
                }
            } else if (op <= 136) { // put1..4：不推进
                if (!need(p, i, op - 132)) {
                    break;
                }
                int c = (int) readUnsigned(p, i, op - 132);
                i += op - 132;
                out.add(new Placement(fnt, c, x * u, y * u));
            } else if (op == 138) { // nop
            } else if (op == 141) { // push
                stack.add(new double[] {x, y, w, xx, yy, z});
            } else if (op == 142) { // pop
                if (!stack.isEmpty()) {
                    double[] s = stack.remove(stack.size() - 1);
                    x = s[0];
                    y = s[1];
                    w = (int) s[2];
                    xx = (int) s[3];
                    yy = (int) s[4];
                    z = (int) s[5];
                }
            } else if (op <= 146) { // right1..4
                if (!need(p, i, op - 142)) {
                    break;
                }
                x += readSigned(p, i, op - 142) * u;
                i += op - 142;
            } else if (op == 147) { // w0
                x += w * u;
            } else if (op <= 151) { // w1..4：设 w 并移动
                if (!need(p, i, op - 147)) {
                    break;
                }
                w = readSigned(p, i, op - 147);
                x += w * u;
                i += op - 147;
            } else if (op == 152) { // x0
                x += xx * u;
            } else if (op <= 156) { // x1..4
                if (!need(p, i, op - 152)) {
                    break;
                }
                xx = readSigned(p, i, op - 152);
                x += xx * u;
                i += op - 152;
            } else if (op == 157) { // down0：无操作
            } else if (op <= 161) { // down1..4
                if (!need(p, i, op - 157)) {
                    break;
                }
                y += readSigned(p, i, op - 157) * u;
                i += op - 157;
            } else if (op == 162) { // y0
                y += yy * u;
            } else if (op <= 166) { // y1..4
                if (!need(p, i, op - 162)) {
                    break;
                }
                yy = readSigned(p, i, op - 162);
                y += yy * u;
                i += op - 162;
            } else if (op == 167) { // z0
                y += z * u;
            } else if (op <= 171) { // z1..4
                if (!need(p, i, op - 167)) {
                    break;
                }
                z = readSigned(p, i, op - 167);
                y += z * u;
                i += op - 167;
            } else if (op <= 234) { // fnt_num_i
                fnt = op - 172;
            } else if (op <= 238) { // fnt1..4
                if (!need(p, i, op - 234)) {
                    break;
                }
                fnt = (int) readUnsigned(p, i, op - 234);
                i += op - 234;
            } else if (op <= 242) { // xxx1..4（242 在 packet 内是 xxx4，非 packet 头）
                if (!need(p, i, op - 238)) {
                    break;
                }
                int len = (int) readUnsigned(p, i, op - 238);
                i += op - 238 + len;
                if (i > p.length) {
                    break;
                }
            } else {
                break; // bop/eop/fnt_def 等 packet 内非法：停止
            }
        }
    }

    /** packet 内变长读取边界检查 */
    private static boolean need(byte[] p, int i, int n) {
        return i >= 0 && i + n <= p.length;
    }

    /** packet 内 set_char 的推进量（packet 单位）：base_fix × s_base / 2²⁰ */
    private double charWidth(int localFontNum, int slot) {
        VfFontDef def = fontDefs.get(localFontNum);
        if (def == null) {
            return 0;
        }
        TfmFile base = baseTfmLookup.apply(def.name());
        if (base == null) {
            return 0;
        }
        long fix = base.fixWidth(slot);
        return Math.round(fix * Integer.toUnsignedLong(def.scale) / (double) (1 << 20));
    }

    // ---- 解析 ----

    public static VfFile parse(Path file) throws IOException {
        VfFile vf = parseBytes(Files.readAllBytes(file));
        if (vf == null) {
            throw new IOException("不是合法的 VF 文件: " + file);
        }
        return vf;
    }

    static VfFile parseBytes(byte[] raw) throws IOException {
        if (raw.length < 14 || (raw[0] & 0xFF) != 0xF7 || (raw[1] & 0xFF) != 202) {
            return null;
        }
        int i = 2;
        int commentLen = raw[i++] & 0xFF;
        i += commentLen;
        VfFile vf = new VfFile(readU32(raw, i), readU32(raw, i + 4));
        i += 8;

        // 前导基字体定义
        i = parseFontDefs(vf, raw, i, false);

        // 字符 packet：首字节 < 242 → 短式（pl 即该字节）；242 → 长式
        while (i < raw.length) {
            int b = raw[i++] & 0xFF;
            if (b == 242) { // long: pl[4] cc[4] tfm[4] dvi[pl]
                if (!need(raw, i, 12)) {
                    return null;
                }
                int pl = readU32(raw, i);
                int cc = readU32(raw, i + 4);
                i += 12;
                if (pl < 0 || !need(raw, i, pl)) {
                    return null;
                }
                byte[] pkt = new byte[pl];
                System.arraycopy(raw, i, pkt, 0, pl);
                i += pl;
                vf.packets.put(cc, pkt);
            } else if (b < 242) { // short: pl(b) cc[1] tfm[3] dvi[pl]
                int pl = b;
                if (!need(raw, i, 4 + pl)) {
                    return null;
                }
                int cc = raw[i++] & 0xFF;
                i += 3; // tfm
                byte[] pkt = new byte[pl];
                System.arraycopy(raw, i, pkt, 0, pl);
                i += pl;
                vf.packets.put(cc, pkt);
            } else if (b == 248) { // post：cs[4] ds[4]，随后 fnt_def 副本；不再解析
                return vf;
            } else {
                return null; // 非法结构
            }
        }
        return null; // 缺 post
    }

    /** 解析连续的 fnt_def 序列，返回结束位置 */
    private static int parseFontDefs(VfFile vf, byte[] raw, int i, boolean skip) {
        while (i < raw.length) {
            int op = raw[i] & 0xFF;
            if (op < 243 || op > 246) {
                return i;
            }
            i++;
            int nb = op - 242;
            int num = (int) readUnsigned(raw, i, nb);
            i += nb;
            int checksum = readU32(raw, i);
            i += 4;
            int scale = readU32(raw, i);
            i += 4;
            int design = readU32(raw, i);
            i += 4;
            int areaLen = raw[i++] & 0xFF;
            int nameLen = raw[i++] & 0xFF;
            String area = new String(raw, i, areaLen, java.nio.charset.StandardCharsets.ISO_8859_1);
            i += areaLen;
            String name = new String(raw, i, nameLen, java.nio.charset.StandardCharsets.ISO_8859_1);
            i += nameLen;
            if (!skip) {
                vf.fontDefs.put(num, new VfFontDef(num, checksum, scale, design, area, name));
            }
        }
        return i;
    }

    private static int readU32(byte[] b, int i) {
        return ((b[i] & 0xFF) << 24) | ((b[i + 1] & 0xFF) << 16) | ((b[i + 2] & 0xFF) << 8)
                | (b[i + 3] & 0xFF);
    }

    private static long readUnsigned(byte[] b, int i, int n) {
        long v = 0;
        for (int k = 0; k < n; k++) {
            v = (v << 8) | (b[i + k] & 0xFF);
        }
        return v;
    }

    private static int readI32(byte[] b, int i) {
        return readU32(b, i);
    }

    /** n 字节大端有符号整数（packet 内 right/w/x/down/y/z 参数） */
    private static int readSigned(byte[] b, int i, int n) {
        int v = (b[i] & 0x80) != 0 ? -1 : 0; // 符号扩展
        for (int k = 0; k < n; k++) {
            v = (v << 8) | (b[i + k] & 0xFF);
        }
        return v;
    }
}
