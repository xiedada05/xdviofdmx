package xdviofdmx;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 手工构造 XDV 字节流的测试夹具（字节级布局与 xdvjni sample.rs 一致，
 * 前导 id=7、单位 25400000/473628672）。
 */
public final class XdvFixtureBuilder {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    public byte[] build() {
        return out.toByteArray();
    }

    public XdvFixtureBuilder u8(int v) {
        out.write(v & 0xFF);
        return this;
    }

    public XdvFixtureBuilder u16(int v) {
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
        return this;
    }

    public XdvFixtureBuilder u32(long v) {
        out.write((int) ((v >>> 24) & 0xFF));
        out.write((int) ((v >>> 16) & 0xFF));
        out.write((int) ((v >>> 8) & 0xFF));
        out.write((int) (v & 0xFF));
        return this;
    }

    public XdvFixtureBuilder i32(long v) {
        return u32(v);
    }

    public XdvFixtureBuilder u8str(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length > 255) {
            throw new IllegalArgumentException("string too long");
        }
        return u8(b.length).raw(b);
    }

    public XdvFixtureBuilder raw(byte[] b) {
        out.writeBytes(b);
        return this;
    }

    public int pos() {
        return out.size();
    }

    // ---------------------------------------------------------------- 结构

    public XdvFixtureBuilder preamble(String comment) {
        u8(247).u8(7).u32(25_400_000L).u32(473_628_672L).u32(1000).u8str(comment);
        return this;
    }

    public XdvFixtureBuilder bop(int[] counters, int previousBop) {
        u8(139);
        for (int c : counters) {
            i32(c);
        }
        i32(previousBop);
        return this;
    }

    public XdvFixtureBuilder eop() {
        return u8(140);
    }

    public XdvFixtureBuilder post(int lastBopOffset, int pageCount) {
        u8(248).u32(lastBopOffset).u32(25_400_000L).u32(473_628_672L).u32(1000)
                .u32(100_000).u32(100_000).u16(1).u16(pageCount);
        return this;
    }

    public XdvFixtureBuilder postPost(int postOffset) {
        u8(249).u32(postOffset).u8(7);
        for (int i = 0; i < 5; i++) {
            u8(0xDF);
        }
        return this;
    }

    /** native 字体定义（opcode 252） */
    public XdvFixtureBuilder nativeFontDef(int fontNum, double sizePt, String path,
                                           int faceIndex, Integer rgba, Integer extend,
                                           Integer slant, Integer embolden) {
        u8(252);
        i32(fontNum);
        u32((long) (sizePt * 65536.0));
        int flags = 0;
        if (rgba != null) {
            flags |= 0x0200;
        }
        if (extend != null) {
            flags |= 0x1000;
        }
        if (slant != null) {
            flags |= 0x2000;
        }
        if (embolden != null) {
            flags |= 0x4000;
        }
        u16(flags);
        u8str(path);
        u32(faceIndex);
        if (rgba != null) {
            u32(rgba);
        }
        if (extend != null) {
            i32(extend);
        }
        if (slant != null) {
            i32(slant);
        }
        if (embolden != null) {
            i32(embolden);
        }
        return this;
    }

    /** 选择字体（FNT_NUM_0..63，fontNum ≤ 63） */
    public XdvFixtureBuilder selectFont(int fontNum) {
        if (fontNum > 63) {
            u8(235).i32(fontNum); // fnt4
        } else {
            u8(171 + fontNum);
        }
        return this;
    }

    /** 下移（down4，正值向下） */
    public XdvFixtureBuilder down(int dvi) {
        return u8(160).i32(dvi);
    }

    /** 右移（right4，opcode 146） */
    public XdvFixtureBuilder right(int dvi) {
        return u8(146).i32(dvi);
    }

    /** XDV_GLYPHS（253）：width + 相对偏移 + gid */
    public XdvFixtureBuilder glyphRun(int widthDvi, int[] dx, int[] dy, int[] gids) {
        u8(253);
        i32(widthDvi);
        u16(gids.length);
        for (int i = 0; i < gids.length; i++) {
            i32(dx[i]).i32(dy[i]);
        }
        for (int g : gids) {
            u16(g);
        }
        return this;
    }

    /** XDV_TEXT_AND_GLYPHS（254）：UTF-16 文本 + width + 相对偏移 + gid */
    public XdvFixtureBuilder textAndGlyphs(String text, int widthDvi, int[] dx, int[] dy, int[] gids) {
        u8(254);
        char[] units = text.toCharArray();
        u16(units.length);
        for (char u : units) {
            u16(u);
        }
        i32(widthDvi);
        u16(gids.length);
        for (int i = 0; i < gids.length; i++) {
            i32(dx[i]).i32(dy[i]);
        }
        for (int g : gids) {
            u16(g);
        }
        return this;
    }

    /** set_rule（132，推动当前点） */
    public XdvFixtureBuilder setRule(long heightDvi, long widthDvi) {
        return u8(132).i32(heightDvi).i32(widthDvi);
    }

    /** put_rule（137，不推动当前点） */
    public XdvFixtureBuilder putRule(long heightDvi, long widthDvi) {
        return u8(137).i32(heightDvi).i32(widthDvi);
    }

    /** xxx1 special */
    public XdvFixtureBuilder special(String text) {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        if (b.length > 255) {
            throw new IllegalArgumentException("special too long for xxx1");
        }
        return u8(239).u8(b.length).raw(b);
    }
}
