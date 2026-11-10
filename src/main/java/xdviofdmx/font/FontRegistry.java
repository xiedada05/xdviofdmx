package xdviofdmx.font;

import xdviofdmx.Coord;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * native 字体注册表：fontNum → 定义/加载缓存。
 * 加载是惰性的：首次用到该字体的字形 run 时才解析文件。
 */
public final class FontRegistry implements AutoCloseable {

    private final Map<Integer, NativeFontDef> defs = new HashMap<>();
    private final Map<Integer, LoadedFont> loaded = new HashMap<>();
    private final Coord coord;
    private final LoadedFont.Logger log;
    private final LoadedFont.TtcSlicer slicer;

    public FontRegistry(Coord coord, LoadedFont.Logger log, Path tempDir) throws java.io.IOException {
        this.coord = coord;
        this.log = log;
        this.slicer = new TtcSlicer(tempDir);
    }

    public void define(NativeFontDef def) {
        defs.put(def.fontNum(), def);
        loaded.remove(def.fontNum()); // 重新定义时废弃旧缓存
    }

    public NativeFontDef defOf(int fontNum) {
        return defs.get(fontNum);
    }

    public LoadedFont get(int fontNum) {
        LoadedFont f = loaded.get(fontNum);
        if (f == null) {
            NativeFontDef def = defs.get(fontNum);
            if (def == null) {
                return null;
            }
            double emMm = coord.fontSizeToMm(def.sizeRaw());
            f = LoadedFont.load(def, emMm, slicer, log);
            loaded.put(fontNum, f);
        }
        return f;
    }

    /**
     * 为字形 run 计算每字形 Unicode 文本；无法反查的位置为 null（轮廓兜底）。
     */
    public String[] mapGlyphs(int fontNum, int[] gids) {
        LoadedFont f = get(fontNum);
        String[] result = new String[gids.length];
        if (f == null) {
            return result;
        }
        for (int i = 0; i < gids.length; i++) {
            result[i] = f.unicodeOf(gids[i] & 0xFFFF);
        }
        return result;
    }

    @Override
    public void close() {
        for (LoadedFont f : loaded.values()) {
            f.close();
        }
        loaded.clear();
    }
}
