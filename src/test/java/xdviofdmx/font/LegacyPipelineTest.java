package xdviofdmx.font;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * legacy 全链集成测试：真实 TeX Live 文件（pplr8t：TFM→VF→map→8r.enc→PFB→OTF）。
 * 无 kpathsea/TeX Live 的环境自动跳过。
 */
class LegacyPipelineTest {

    private static final class Rec implements LoadedFont.Logger {
        final List<String> warns = new ArrayList<>();

        @Override
        public void warn(String msg) {
            warns.add(msg);
        }

        @Override
        public void info(String msg) {
        }
    }

    private static TexResolver resolver() {
        KpathseaTexResolver r = new KpathseaTexResolver();
        Assumptions.assumeTrue(r.available(), "kpathsea/TeX Live 不可用，跳过 legacy 集成测试");
        return r;
    }

    @Test
    void pplr8tFullChainProducesWidthsAndOtf() throws Exception {
        TexResolver res = resolver();
        Path temp = Files.createTempDirectory("xdviofdmx-legacy-test");
        temp.toFile().deleteOnExit();
        Rec log = new Rec();
        FontRegistry registry = new FontRegistry(new xdviofdmx.Coord(), log, temp);
        LegacyFontManager mgr = new LegacyFontManager(res, new xdviofdmx.Coord(), log,
                registry, temp);
        try {
            // 定义（XDVI 语义：11pt → scale_factor = 720896 DVI units）
            List<long[]> widths = new ArrayList<>();
            mgr.define("", "pplr8t", 49, 0, 720896, 655360, (num, w) -> widths.add(w));
            assertTrue(mgr.usable(49), "pplr8t 全链应可用；告警: " + log.warns);
            assertEquals(1, widths.size());
            assertEquals(256, widths.get(0).length);
            assertTrue(widths.get(0)[72] > 0, "'H' 的宽度应为正（DVI 单位）");

            // 展开一个字符：VF → pplr8r 摆放
            LegacyFontManager.LegacyFont lf = mgr.fontOf(49);
            assertNotNull(lf);
            List<LegacyFontManager.Piece> pieces = mgr.expand(lf, 72, 10, 20);
            assertEquals(1, pieces.size());
            assertInstanceOf(LegacyFontManager.GlyphPiece.class, pieces.get(0));
            LegacyFontManager.GlyphPiece gp = (LegacyFontManager.GlyphPiece) pieces.get(0);
            assertEquals(72, gp.slot());
            assertEquals("H", gp.unicode(), "8r.enc slot 72 → 'H'");
            assertNotNull(gp.font().loaded, "OTF 应已生成并注册 synthetic LoadedFont");
            assertTrue(gp.font().syntheticFontNum > 0);
            assertTrue(gp.font().syntheticFontNum >= 10_000_000);

            // synthetic 注册后：FontRegistry 反查（native 管线复用入口）
            LoadedFont loaded = registry.get(gp.font().syntheticFontNum);
            assertNotNull(loaded);
            assertNotNull(loaded.embedFile);
            assertTrue(Files.exists(loaded.embedFile));
            // cmap 正向/反向一致（GID=槽位）
            assertEquals(72, loaded.gidOf("H"));
            assertEquals("H", loaded.unicodeOf(72));

            // OTF 是 fontbox 可解析的 OpenType
            try (var raf = new org.apache.pdfbox.io.RandomAccessReadBufferedFile(
                    loaded.embedFile.toFile())) {
                var ttf = new org.apache.fontbox.ttf.OTFParser().parse(raf);
                assertEquals(1000, ttf.getUnitsPerEm());
                ttf.close();
            }

            // 数学符号字体（无 VF，PFB 内建编码）也应可用
            mgr.define("", "ntxsym", 81, 0, 717619, 655360, null);
            assertTrue(mgr.usable(81), "ntxsym 应可用；告警: " + log.warns);
        } finally {
            mgr.close();
        }
    }
}
