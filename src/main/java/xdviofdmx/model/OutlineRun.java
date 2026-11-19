package xdviofdmx.model;

import xdviofdmx.GraphicsState;
import xdviofdmx.font.OutlineSeg;

import java.util.List;

/**
 * legacy 字体的矢量轮廓兜底文本（Type1→OTF 不可用时）：
 * 每字符一组轮廓段（mm，相对字形原点，y 向下），经 PathObject 发射。
 */
public final class OutlineRun extends PageItem {

    /** 一个字符的轮廓：段序列 + 绝对放置点（mm，基线原点） */
    public record Glyph(List<OutlineSeg> segments, double xMm, double yMm) {
    }

    public final List<Glyph> glyphs;

    public OutlineRun(GraphicsState.Snapshot state, List<Glyph> glyphs) {
        super(state);
        this.glyphs = List.copyOf(glyphs);
    }
}
