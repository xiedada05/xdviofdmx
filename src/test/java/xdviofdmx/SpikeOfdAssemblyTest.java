package xdviofdmx;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ofdrw.core.basicStructure.pageObj.layer.block.ImageObject;
import org.ofdrw.core.basicStructure.pageObj.layer.block.PathObject;
import org.ofdrw.core.basicStructure.pageObj.layer.block.TextObject;
import org.ofdrw.core.basicStructure.res.CT_MultiMedia;
import org.ofdrw.core.basicStructure.res.MediaType;
import org.ofdrw.core.basicType.ST_Box;
import org.ofdrw.core.basicType.ST_ID;
import org.ofdrw.core.basicType.ST_RefID;
import org.ofdrw.core.graph.pathObj.AbbreviatedData;
import org.ofdrw.core.pageDescription.color.color.CT_Color;
import org.ofdrw.core.text.TextCode;
import org.ofdrw.core.text.CT_CGTransform;
import org.ofdrw.core.text.font.CT_Font;
import org.ofdrw.font.Font;
import org.ofdrw.graphics2d.OFDGraphicsDocument;
import org.ofdrw.graphics2d.OFDPageGraphics2D;
import org.ofdrw.layout.engine.ResManager;
import org.ofdrw.reader.OFDReader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spike：验证 ofdrw 2.5.1 低层组装 API（OFDGraphicsDocument + OFDPageGraphics2D.appendPageBlock
 * + layout ResManager 资源注册）能承载 xdviofdmx 需要的全部图元形态。
 * 此测试同时充当技术选型的回归锚点。
 */
class SpikeOfdAssemblyTest {

    @TempDir
    Path tmp;

    /**
     * 在系统中找一个可用的 TTF/OTF 字体文件（不依赖特定发行版）。
     */
    private static Path findSystemFont() {
        String[] candidates = {
                "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
                "/usr/share/fonts/truetype/liberation/LiberationSerif-Regular.ttf",
                "/usr/local/share/fonts/HarmonyOS Sans/HarmonyOS_Sans_TC/HarmonyOS_Sans_TC_Regular.ttf",
        };
        for (String c : candidates) {
            Path p = Path.of(c);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }

    private static Path makeTestPng(Path dir) throws IOException {
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 8; y++) {
                img.setRGB(x, y, ((x * 31 + y * 7) % 255) << 16 | 0x0000AA);
            }
        }
        Path png = dir.resolve("spike.png");
        ImageIO.write(img, "png", png.toFile());
        return png;
    }

    private static String readZipEntry(ZipFile zip, String name) throws IOException {
        ZipEntry e = zip.getEntry(name);
        if (e == null) {
            return null;
        }
        return new String(zip.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void fullAssemblySpike() throws Exception {
        Path fontFile = findSystemFont();
        assertNotNull(fontFile, "系统中未找到候选字体文件");
        Path png = makeTestPng(tmp);
        Path out = tmp.resolve("spike.ofd");

        ST_ID fontId;
        ST_ID imgId;
        try (OFDGraphicsDocument doc = new OFDGraphicsDocument(out)) {
            // 资源管理器：layout ResManager 叠加在 graphics2d 文档的容器与 ID 空间上
            ResManager res = new ResManager(doc.ofdDir, doc.docDir, doc.MaxUnitID);

            // 元数据
            doc.ofdDir.getOfd().getDocBody().getDocInfo()
                    .setCreator("xdviofdmx-spike").setTile("Spike");

            // 资源注册：嵌入字体 + 原格式图片
            fontId = res.addFont(new Font("SpikeFont", "SpikeFont", fontFile));
            assertNotNull(fontId);
            imgId = res.addImage(png);
            assertNotNull(imgId);

            // 页面：A4 竖版，单位 mm
            OFDPageGraphics2D page = doc.newPage(210.0, 297.0);

            // 1. 文本对象：TextCode + DeltaX + CGTransform
            TextObject text = new TextObject(page.newObjectId());
            text.setFont(new ST_RefID(fontId));
            text.setSize(10.0); // mm
            text.setBoundary(20.0, 30.0, 80.0, 15.0);
            text.setFillColor(CT_Color.rgb(0, 0, 0));
            TextCode tc = new TextCode();
            tc.setContent("你好 XDV");
            tc.setCoordinate(0.5, 10.0); // 对象坐标系，基线位置
            tc.setDeltaX(0.0, 10.0, 10.0, 10.0, 10.0, 10.0);
            text.addTextCode(tc);
            CT_CGTransform cgt = new CT_CGTransform();
            cgt.setCodePosition(0).setCodeCount(6).setGlyphCount(6)
               .setGlyphs(org.ofdrw.core.basicType.ST_Array.getInstance("25 26 27 28 29 30"));
            text.addCGTransform(cgt);
            page.appendPageBlock(text);

            // 2. 路径对象（模拟 rule）：带 CTM 与 Alpha
            PathObject rule = new PathObject(page.newObjectId());
            rule.setBoundary(20.0, 60.0, 50.0, 3.0);
            AbbreviatedData ad = new AbbreviatedData();
            ad.M(0, 0).L(50, 0).L(50, 3).L(0, 3).C();
            rule.setAbbreviatedData(ad);
            rule.setFill(true);
            rule.setStroke(false);
            rule.setFillColor(CT_Color.rgb(255, 0, 0));
            rule.setAlpha(200);
            rule.setCTM(new org.ofdrw.core.basicType.ST_Array(1, 0, 0, 1, 0, 0));
            page.appendPageBlock(rule);

            // 3. 图像对象
            ImageObject img = new ImageObject(page.newObjectId());
            img.setResourceID(imgId.ref());
            img.setBoundary(20.0, 80.0, 40.0, 40.0);
            page.appendPageBlock(img);

            doc.close();
        }

        assertTrue(Files.size(out) > 0, "OFD 文件应已生成");

        // 结构断言：解包检查
        try (ZipFile zip = new ZipFile(out.toFile())) {
            assertNotNull(zip.getEntry("OFD.xml"));
            String docXml = readZipEntry(zip, "Doc_0/Document.xml");
            assertNotNull(docXml, "Document.xml 应存在");
            assertTrue(docXml.contains("Pages/Page_0/Content.xml"), "Pages 应含页面引用");
            assertTrue(docXml.contains("PublicRes.xml"), "字体应登记在公共资源");
            assertTrue(docXml.contains("DocumentRes.xml"), "图片应登记在文档资源");

            String content = null;
            for (var entries = zip.entries(); entries.hasMoreElements(); ) {
                ZipEntry e = entries.nextElement();
                if (e.getName().endsWith("Page_0/Content.xml")) {
                    content = new String(zip.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
                    break;
                }
            }
            assertNotNull(content, "Content.xml 应存在");
            assertTrue(content.contains("TextObject"), "应含文本对象");
            assertTrue(content.contains("你好 XDV"), "TextCode 内容应写入");
            assertTrue(content.contains("DeltaX"), "应含 DeltaX 定位");
            assertTrue(content.contains("CGTransform"), "应含字形索引映射");
            assertTrue(content.contains("Glyphs"), "CGTransform 应含 Glyphs 列表");
            assertTrue(content.contains("PathObject"), "应含路径对象");
            assertTrue(content.contains("AbbreviatedData"), "路径应有简写数据");
            assertTrue(content.contains("ImageObject"), "应含图像对象");

            // 字体文件应被拷入容器
            boolean hasFontFile = false;
            boolean hasImageFile = false;
            for (var entries = zip.entries(); entries.hasMoreElements(); ) {
                String n = entries.nextElement().getName();
                if (n.startsWith("Doc_0/Res/") && (n.endsWith(".ttf") || n.endsWith(".otf") || n.endsWith(".ttc"))) {
                    hasFontFile = true;
                }
                if (n.startsWith("Doc_0/Res/") && n.endsWith(".png")) {
                    hasImageFile = true;
                }
            }
            assertTrue(hasFontFile, "字体文件应嵌入 Doc_0/Res/");
            assertTrue(hasImageFile, "图片文件应拷入 Doc_0/Res/");
        }

        // OFDReader 回读
        try (OFDReader reader = new OFDReader(out)) {
            List<?> pages = reader.getPageList();
            assertEquals(1, pages.size(), "应有 1 页");
        }
    }
}
