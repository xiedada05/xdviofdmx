package xdviofdmx.font;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * legacy 解析层单测：TFM/VF 用合成字节（不依赖系统 TeX Live），
 * map/enc/AGL 用真实行格式夹具。
 */
class LegacyParsersTest {

    // ---------------------------------------------------------------- TFM

    /** 合成 TFM：bc=65 ec=67，宽度表 [0.5em, 0.25em, -0.1em]（fixword） */
    private static byte[] syntheticTfm() {
        int bc = 65, ec = 67, nw = 3;
        int lh = 2; // checksum + design_size
        int nChars = ec - bc + 1;
        int lf = 6 + lh + nChars + nw;
        ByteBuffer b = ByteBuffer.allocate(lf * 4);
        b.putShort((short) lf).putShort((short) lh)
                .putShort((short) bc).putShort((short) ec).putShort((short) nw)
                .putShort((short) 1).putShort((short) 1).putShort((short) 0)
                .putShort((short) 0).putShort((short) 0).putShort((short) 0)
                .putShort((short) 0);
        b.putInt(0x12345678); // checksum
        b.putInt(1 << 20);    // design_size = 1pt（fixword）
        // char_info：宽度索引 0,1,2（字节 0 = width index）
        for (int i = 0; i < nChars; i++) {
            b.putInt(i << 24);
        }
        // widths：0.5em=524288, 0.25em=262144, -0.1em=-104858
        b.putInt(524288).putInt(262144).putInt(-104858);
        return b.array();
    }

    @Test
    void tfmWidthsScaleByFactor() throws Exception {
        Path f = FilesWrite.writeTemp(syntheticTfm(), "t.tfm");
        TfmFile tfm = TfmFile.parse(f);
        assertEquals(0x12345678, tfm.checksum);
        // fix 524288 × scale 655360 / 2^20 = 327680 DVI units
        assertEquals(327680, tfm.widthDvi(65, 655360));
        assertEquals(163840, tfm.widthDvi(66, 655360));
        // 未定义槽位（64/68）为 0
        assertEquals(0, tfm.widthDvi(64, 655360));
        assertEquals(0, tfm.widthDvi(68, 655360));
        // 负宽度（斜体修正等）保留符号
        assertTrue(tfm.widthDvi(67, 655360) < 0);
        // 全表注入：长度 = ec+1
        long[] widths = tfm.widthsDvi(655360);
        assertEquals(68, widths.length);
        assertEquals(327680, widths[65]);
    }

    // ---------------------------------------------------------------- VF

    /** 合成 VF：基字体 0 = base8r（scale 0x100000），槽 65 packet = set_char 65 + right 100 */
    private static byte[] syntheticVf() {
        ByteBuffer b = ByteBuffer.allocate(256);
        b.put((byte) 0xF7).put((byte) 202); // pre, id=202
        b.put((byte) 0);                    // comment len 0
        b.putInt(0x00ABCDEF);               // checksum
        b.putInt(1 << 20);                  // design 1pt（fixword pt，绝对）
        // fnt_def1: num=0 checksum scale(1.0 fix_word) design area name
        b.put((byte) 243);
        b.put((byte) 0);
        b.putInt(0).putInt(0x100000).putInt(1 << 20);
        b.put((byte) 0).put((byte) 6).put("base8r".getBytes());
        // 短 char packet：pl=6 cc=65 tfm[3] dvi{set_char 65 (0x41), right4 100}
        b.put((byte) 6).put((byte) 65).put((byte) 0).put((byte) 0x0A).put((byte) 0x00);
        b.put((byte) 0x41); // set_char_65
        b.put((byte) 146).putInt(100); // right4 100
        // post
        b.put((byte) 248);
        b.putInt(0x00ABCDEF).putInt(1 << 20);
        return b.array();
    }

    @Test
    void vfParseAndExpand() throws Exception {
        Path f = FilesWrite.writeTemp(syntheticVf(), "t.vf");
        VfFile vf = VfFile.parse(f);
        assertTrue(vf.hasChar(65));
        assertFalse(vf.hasChar(66));
        VfFile.VfFontDef def = vf.baseFont(0);
        assertEquals("base8r", def.name());
        assertEquals(0x100000, def.scale());
        // packet 单位 → DVI：v = scaleFactor / 2^20
        double v = 655360 / (double) (1 << 20);
        VfFile.Expanded exp = vf.expand(65, v);
        assertEquals(1, exp.placements().size());
        VfFile.Placement p = exp.placements().get(0);
        assertEquals(0, p.baseFontNum()); // 隐式初始字体 0
        assertEquals(65, p.slot());
        assertEquals(0.0, p.x(), 1e-9);
        assertEquals(0.0, p.y(), 1e-9);
    }

    // ---------------------------------------------------------------- map

    @Test
    void mapLineVariants() {
        // 本机 updmap 真实行格式
        DvipsMap.Entry e = DvipsMap.parseLine(
                "pplr8r URWPalladioL-Roma \" TeXBase1Encoding ReEncodeFont \" <8r.enc <uplr8a.pfb");
        assertNotNull(e);
        assertEquals("pplr8r", e.tfmName());
        assertEquals("URWPalladioL-Roma", e.psName());
        assertEquals("8r.enc", e.encFile());
        assertEquals("uplr8a.pfb", e.fontFile());
        assertTrue(e.fontIsType1());
        // 无 enc 直达
        DvipsMap.Entry e2 = DvipsMap.parseLine("ntxsym txsym <txsym.pfb");
        assertEquals("txsym.pfb", e2.fontFile());
        assertNull(e2.encFile());
        // SlantFont 合成参数
        DvipsMap.Entry e3 = DvipsMap.parseLine(
                "fxlr8r URWPalladioL-Ital \" ReEncodeFont \" <8r.enc <uplri8a.pfb 0.167 SlantFont");
        assertEquals(0.167, e3.slant(), 1e-9);
        // TrueType（<<）
        DvipsMap.Entry e4 = DvipsMap.parseLine("fnt TTF-Name <<fnt.ttf");
        assertFalse(e4.fontIsType1());
        // 注释/残行
        assertNull(DvipsMap.parseLine("% comment"));
        assertNull(DvipsMap.parseLine(""));
        assertNull(DvipsMap.parseLine("onlykey"));
    }

    // ---------------------------------------------------------------- enc / Agal

    @Test
    void encParsesIgnoringPositionComments() throws Exception {
        String enc = """
                /TeXTestEncoding [
                /grave /acute /circumflex /tilde % 0x03
                 0x04
                /space /exclam /quotedbl /A /B % 0x08
                ] def
                """;
        Path f = FilesWrite.writeTemp(enc.getBytes(), "t.enc");
        EncTable et = EncTable.parse(f);
        assertEquals("grave", et.glyphAt(0));
        assertEquals("tilde", et.glyphAt(3));
        assertEquals("space", et.glyphAt(4)); // 0x04 标记已剥除，不错位
        assertEquals("B", et.glyphAt(8));
    }

    @Test
    void agalCoreNamesAndFallbacks() {
        assertEquals('A', Agal.codePointOf("A"));
        assertEquals('u', Agal.codePointOf("u")); // 单字母 u 不得被 uXXXX 规则吞掉
        assertEquals('H', Agal.codePointOf("H"));
        assertEquals(0x2013, Agal.codePointOf("endash"));
        assertEquals(0x00C9, Agal.codePointOf("Eacute"));
        assertEquals(0xFB01, Agal.codePointOf("fi"));
        // uniXXXX / uXXXX 复合规则
        assertEquals(0x2014, Agal.codePointOf("uni2014"));
        assertEquals(0x2014, Agal.codePointOf("u2014"));
        assertEquals(-1, Agal.codePointOf("nonexistentglyph"));
    }

    /** 测试辅助：临时文件 */
    static final class FilesWrite {
        static Path writeTemp(byte[] data, String name) throws Exception {
            Path p = Path.of(System.getProperty("java.io.tmpdir"), "xdviofdmx-test-" + name);
            java.nio.file.Files.write(p, data);
            p.toFile().deleteOnExit();
            return p;
        }
    }
}
