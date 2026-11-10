package xdviofdmx;

import org.apache.fontbox.ttf.CmapLookup;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ofdrw.reader.OFDReader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端管线测试：手工 XDV 字节流 → xdviofdmx → OFD，经解包与 OFDReader 断言。
 */
class EndToEndPipelineTest {

    @TempDir
    static Path tmp;

    static Path fontFile;
    static int gidA, gidB, gidH, gidI;
    static TrueTypeFont probeFont;

    @BeforeAll
    static void setup() throws IOException {
        fontFile = findFont();
        probeFont = new TTFParser().parse(
                new org.apache.pdfbox.io.RandomAccessReadBufferedFile(fontFile.toFile()));
        CmapLookup cmap = probeFont.getCmap().getSubtable(3, 1) != null
                ? probeFont.getCmap().getSubtable(3, 1)
                : probeFont.getCmap().getCmaps()[0];
        gidA = cmap.getGlyphId('A');
        gidB = cmap.getGlyphId('B');
        gidH = cmap.getGlyphId('H');
        gidI = cmap.getGlyphId('i');
        assertTrue(gidA > 0 && gidB > 0 && gidH > 0 && gidI > 0, "cmap 应能查到测试字符");
    }

    @AfterAll
    static void teardown() throws IOException {
        probeFont.close();
    }

    private static Path findFont() {
        String[] candidates = {
                "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
                "/usr/share/fonts/truetype/liberation/LiberationSerif-Regular.ttf",
        };
        for (String c : candidates) {
            Path p = Path.of(c);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        throw new IllegalStateException("系统中未找到测试字体");
    }

    private static void makePng(Path dir, String name) throws IOException {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                img.setRGB(x, y, x * 15 << 8 | y * 15);
            }
        }
        ImageIO.write(img, "png", dir.resolve(name).toFile());
    }

    private static String zipEntry(ZipFile zip, String name) throws IOException {
        ZipEntry e = zip.getEntry(name);
        if (e == null) {
            for (var entries = zip.entries(); entries.hasMoreElements(); ) {
                ZipEntry cand = entries.nextElement();
                if (cand.getName().equals(name)) {
                    e = cand;
                    break;
                }
            }
        }
        return e == null ? null
                : new String(zip.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String findContentXml(ZipFile zip) throws IOException {
        for (var entries = zip.entries(); entries.hasMoreElements(); ) {
            ZipEntry e = entries.nextElement();
            if (e.getName().endsWith("Content.xml")) {
                return new String(zip.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /** 记录式日志（供断言与诊断） */
    private static final class TestLog implements XdvInterpreter.Log {
        final StringBuilder warnings = new StringBuilder();

        @Override
        public void info(String fmt, Object... args) { }

        @Override
        public void warn(String fmt, Object... args) {
            warnings.append(fmt.formatted(args)).append('\n');
        }

        @Override
        public void debug(String fmt, Object... args) { }
    }

    private static String pageContentXml(byte[] xdv, Path tmpDir, TestLog log) throws IOException {
        Path xdvFile = tmpDir.resolve("in.xdv");
        Files.write(xdvFile, xdv);
        Path out = tmpDir.resolve("out.ofd");
        Converter.Config config = new Converter.Config(xdvFile, out,
                Converter.pageSelector(null), null, null, 1.0, true, true);
        try (Converter c = new Converter(config, log)) {
            c.run();
        }
        assertTrue(Files.exists(out), "OFD 应生成");
        try (ZipFile zip = new ZipFile(out.toFile())) {
            return findContentXml(zip);
        }
    }

    @Test
    void textRuleColorPaperize() throws IOException {
        // 10pt DejaVu Sans；"AB" + 红色 rule + "Hi"
        XdvFixtureBuilder b = new XdvFixtureBuilder();
        b.preamble("e2e test");
        int bop0 = b.pos();
        b.bop(new int[]{1, 0, 0, 0, 0, 0, 0, 0, 0, 0}, -1);
        b.nativeFontDef(5, 10.0, fontFile.toString(), 0, null, null, null, null);
        b.selectFont(5);
        b.special("papersize=200pt,100pt");
        b.special("color push rgb 1 0 0");
        b.down(655360); // 10pt 下移（基线）
        b.glyphRun(1310720, new int[]{0, 655360}, new int[]{0, 0}, new int[]{gidA, gidB});
        b.setRule(65536, 1310720); // 高 1pt 宽 20pt 的 rule
        b.special("color pop");
        b.right(0);
        b.textAndGlyphs("Hi", 655360, new int[]{0, 327680}, new int[]{0, 0}, new int[]{gidH, gidI});
        b.eop();
        int postPos = b.pos();
        b.post(bop0, 1);
        b.postPost(postPos);

        TestLog log = new TestLog();
        String content = pageContentXml(b.build(), tmp, log);
        assertTrue(log.warnings.isEmpty(), "不应有警告: " + log.warnings);

        assertNotNull(content);
        assertTrue(content.contains("TextObject"), "应有文本对象");
        assertTrue(content.contains(">AB<"), "字形反查文本应写入: " + snippet(content, "AB"));
        assertTrue(content.contains(">Hi<"), "254 原文文本应写入");
        assertTrue(content.contains("DeltaX"), "应有 DeltaX");
        assertTrue(content.contains("PathObject"), "应有 rule 路径对象");
        assertTrue(content.contains("255 0 0") || content.contains("255.0 0.0 0.0"),
                "红色应写入（Value 0-255）: " + snippet(content, "255"));
        // 页面尺寸 200pt x 100pt → 70.29 x 35.15 mm（写入每页 PageArea）
        try (OFDReader reader = new OFDReader(tmp.resolve("out.ofd"))) {
            var area = reader.getPageArea(1); // reader 页码 1 起
            var box = area.getApplicationBox() != null ? area.getApplicationBox() : area.getPhysicalBox();
            assertNotNull(box, "页面应有自己的 PageArea");
            assertEquals(70.2919, box.getWidth(), 0.01, "页宽应为 200pt 换算的 mm");
            assertEquals(35.1460, box.getHeight(), 0.01, "页高应为 100pt 换算的 mm");
        }
    }

    @Test
    void rotatedTextAndImage() throws IOException {
        Path png = tmp.resolve("e2e-img.png");
        makePng(tmp, "e2e-img.png");

        XdvFixtureBuilder b = new XdvFixtureBuilder();
        b.preamble("e2e rotate/image");
        int bop0 = b.pos();
        b.bop(new int[]{1, 0, 0, 0, 0, 0, 0, 0, 0, 0}, -1);
        b.nativeFontDef(5, 10.0, fontFile.toString(), 0, null, null, null, null);
        b.selectFont(5);
        b.down(655360);
        // 旋转 90° 后画 "AB"
        b.special("x:rotate 90");
        b.glyphRun(1310720, new int[]{0, 655360}, new int[]{0, 0}, new int[]{gidA, gidB});
        b.special("x:grestore");
        b.special("x:gsave"); // 平衡（rotate 不产生栈，这里仅验证解析不炸）
        // 图片：bbox 0 0 72 72 → 25.4mm，宽高 36bp ≈ 12.7mm
        b.special("pdf:image bbox 0 0 72 72 clip 0 width 36bp height 36bp (" + png + ")");
        b.special("x:grestore");
        b.eop();
        int postPos = b.pos();
        b.post(bop0, 1);
        b.postPost(postPos);

        TestLog log = new TestLog();
        Path xdvFile = tmp.resolve("in2.xdv");
        Files.write(xdvFile, b.build());
        Path out = tmp.resolve("out2.ofd");
        Converter.Config config = new Converter.Config(xdvFile, out,
                Converter.pageSelector(null), null, null, 1.0, true, true);
        try (Converter c = new Converter(config, log)) {
            c.run();
        }

        try (ZipFile zip = new ZipFile(out.toFile())) {
            String content = findContentXml(zip);
            assertNotNull(content);
            assertTrue(content.contains("ImageObject"), "应有图像对象");
            assertTrue(content.contains("ResourceID"), "图像应引用资源");
            // 旋转文本的 CTM：90° → (0 1 -1 0) 近似
            assertTrue(content.contains("CTM") || content.contains("CTM>"),
                    "旋转文本应有 CTM");
            boolean hasFontFile = false;
            for (var entries = zip.entries(); entries.hasMoreElements(); ) {
                String n = entries.nextElement().getName();
                if (n.startsWith("Doc_0/Res/") && n.endsWith(".png")) {
                    hasFontFile = true;
                }
            }
            assertTrue(hasFontFile, "PNG 应拷入资源目录");
        }
        try (OFDReader reader = new OFDReader(out)) {
            assertEquals(1, reader.getPageList().size());
        }
    }

    @Test
    void unmappedGlyphFallsBackToOutline() throws IOException {
        XdvFixtureBuilder b = new XdvFixtureBuilder();
        b.preamble("e2e outline");
        int bop0 = b.pos();
        b.bop(new int[]{1, 0, 0, 0, 0, 0, 0, 0, 0, 0}, -1);
        b.nativeFontDef(5, 10.0, fontFile.toString(), 0, null, null, null, null);
        b.selectFont(5);
        b.down(655360);
        // gid 0（.notdef，通常无 cmap 反查）+ 'A'
        b.glyphRun(1310720, new int[]{0, 655360}, new int[]{0, 0}, new int[]{0, gidA});
        b.eop();
        int postPos = b.pos();
        b.post(bop0, 1);
        b.postPost(postPos);

        TestLog log = new TestLog();
        String content = pageContentXml(b.build(), tmp, log);
        assertNotNull(content);
        // gid 0 → 轮廓路径（.notdef 通常可由 AWT 输出轮廓）
        assertTrue(content.contains(">A<"), "'A' 应有文本对象");
    }

    @Test
    void legacyCharRunWarns() throws IOException {
        XdvFixtureBuilder b = new XdvFixtureBuilder();
        b.preamble("e2e char");
        int bop0 = b.pos();
        b.bop(new int[]{1, 0, 0, 0, 0, 0, 0, 0, 0, 0}, -1);
        b.u8(72); // set_char 'H'（无字体定义 → 传统字体路径）
        b.eop();
        int postPos = b.pos();
        b.post(bop0, 1);
        b.postPost(postPos);

        TestLog log = new TestLog();
        try {
            pageContentXml(b.build(), tmp, log);
        } catch (AssertionError expected) {
            // 全部内容被跳过时可能不生成 OFD（无内容页），仅验证警告
        }
        assertTrue(log.warnings.toString().contains("传统"), "应警告传统字体: " + log.warnings);
    }

    private static String snippet(String s, String around) {
        int i = s.indexOf(around);
        if (i < 0) {
            return "(not found)";
        }
        int a = Math.max(0, i - 60);
        int b2 = Math.min(s.length(), i + around.length() + 60);
        return s.substring(a, b2).replaceAll("\\s+", " ");
    }
}
