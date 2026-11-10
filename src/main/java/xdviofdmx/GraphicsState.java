package xdviofdmx;

import xdviofdmx.model.ColorSpec;

import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * XDV 图形状态：颜色栈、CTM（DVI 毫米空间，y 向下）、Alpha、裁剪、渲染模式。
 *
 * <p>对应 xdvipdfmx 的 graphics state 模型：
 * {@code x:gsave/x:grestore} 与 {@code pdf:btrans/etrans} 保存/恢复全部状态；
 * dvips 颜色 special 使用独立的 push/pop 栈；{@code x:scale/x:rotate} 直接
 * 在当前状态上绕当前点合成变换（与 PDF {@code cm} 语义一致）。</p>
 */
public final class GraphicsState {

    /** 单个裁剪矩形（DVI 毫米空间，y 向下）{x, y, w, h} */
    public record ClipRect(double x, double y, double w, double h) {}

    /** 发射对象时捕获的状态快照 */
    public record Snapshot(
            ColorSpec fill,
            ColorSpec stroke,
            int alpha,
            AffineTransform ctm,
            List<ClipRect> clips,
            int renderingMode) {

        public Snapshot {
            fill = fill == null ? ColorSpec.gray(0) : fill;
            stroke = stroke == null ? ColorSpec.gray(0) : stroke;
            clips = List.copyOf(clips);
            ctm = (AffineTransform) ctm.clone();
        }

        public boolean isIdentityCtm() {
            return ctm.isIdentity();
        }

        public boolean hasClip() {
            return !clips.isEmpty();
        }
    }

    private ColorSpec fill = ColorSpec.gray(0);
    private ColorSpec stroke = ColorSpec.gray(0);
    private int alpha = 255;
    private final AffineTransform ctm = new AffineTransform();
    private final List<ClipRect> clips = new ArrayList<>();
    private int renderingMode = 0;

    private final Deque<Object[]> gsaveStack = new ArrayDeque<>();
    private final Deque<ColorSpec> colorStack = new ArrayDeque<>();

    /** x:gsave / pdf:btrans（btrans 额外带变换，由调用方在保存后合成） */
    public void save() {
        gsaveStack.push(new Object[]{
                fill, stroke, alpha, (AffineTransform) ctm.clone(), List.copyOf(clips), renderingMode});
    }

    /** x:grestore / pdf:etrans */
    public void restore() {
        if (gsaveStack.isEmpty()) {
            return; // 不平衡的 grestore：忽略（与 xdvipdfmx 行为一致，仅告警由调用方处理）
        }
        Object[] s = gsaveStack.pop();
        fill = (ColorSpec) s[0];
        stroke = (ColorSpec) s[1];
        alpha = (Integer) s[2];
        ctm.setTransform((AffineTransform) s[3]);
        clips.clear();
        @SuppressWarnings("unchecked")
        List<ClipRect> saved = (List<ClipRect>) s[4];
        clips.addAll(saved);
        renderingMode = (Integer) s[5];
    }

    /** dvips 颜色 special：push（fill/stroke 同时设置） */
    public void pushColor(ColorSpec c) {
        colorStack.push(fill);
        setColor(c);
    }

    /** dvips 颜色 special：pop */
    public void popColor() {
        ColorSpec c = colorStack.isEmpty() ? null : colorStack.pop();
        if (c != null) {
            setColor(c);
        }
    }

    /** color special 直接设置（同时清空颜色栈，与 xdvipdfmx spc_handler_color_default 一致） */
    public void setColorSpec(ColorSpec c) {
        colorStack.clear();
        setColor(c);
    }

    public void setColor(ColorSpec c) {
        this.fill = c;
        this.stroke = c;
    }

    /** 仅设置填充色（pdfcolorstack g/rg/k） */
    public void setFillColorDirect(ColorSpec c) {
        if (c != null) {
            this.fill = c;
        }
    }

    /** 仅设置描边色（pdfcolorstack G/RG/K） */
    public void setStrokeColorDirect(ColorSpec c) {
        if (c != null) {
            this.stroke = c;
        }
    }

    /** pdf:bxgstate /ca（0~1） */
    public void setAlpha01(double a) {
        this.alpha = (int) Math.round(Math.min(1.0, Math.max(0.0, a)) * 255.0);
    }

    /**
     * 绕当前点 (x, y)（DVI 毫米）合成变换 M：{@code ctm ← ctm ∘ T(x,y)·M·T(−x,−y)}
     * 与 xdvipdfmx spc_handler_xtx_do_transform / PDF cm 语义一致。
     */
    public void transformAbout(double x, double y, AffineTransform m) {
        AffineTransform t = new AffineTransform();
        t.translate(x, y);
        t.concatenate(m);
        t.translate(-x, -y);
        ctm.concatenate(t);
    }

    /** 纯缩放（x:scale，sy 缺省时取 sx） */
    public void scaleAbout(double x, double y, double sx, double sy) {
        transformAbout(x, y, AffineTransform.getScaleInstance(sx, sy));
    }

    /** 旋转（x:rotate / pdf:btrans rotate，角度制，逆时针为正——与 PDF 一致） */
    public void rotateAbout(double x, double y, double degrees) {
        transformAbout(x, y, AffineTransform.getRotateInstance(Math.toRadians(degrees)));
    }

    /** x:bscale / x:escale：与 gsave/grestore 相同语义的成对缩放栈 */
    public void beginScale(double sx, double sy) {
        save();
        ctm.concatenate(AffineTransform.getScaleInstance(sx, sy));
    }

    public void endScale() {
        restore();
    }

    /** 裁剪：追加一个矩形（re W n），语义为与现有裁剪区求交 */
    public void addClip(ClipRect r) {
        clips.add(r);
    }

    /** 裁剪：完整替换（x:initoverlay 等） */
    public void resetClip() {
        clips.clear();
    }

    public void setRenderingMode(int m) {
        this.renderingMode = m;
    }

    public int getRenderingMode() {
        return renderingMode;
    }

    /** 捕获快照 */
    public Snapshot snapshot() {
        return new Snapshot(fill, stroke, alpha, ctm, clips, renderingMode);
    }

    /**
     * 将 DVI 空间点经当前 CTM 变换到页面空间（mm）。
     */
    public Point2D toPage(double x, double y) {
        Point2D p = new Point2D.Double(x, y);
        return ctm.transform(p, null);
    }

    /**
     * 求逆变换（用于把页面空间点拉回 DVI 空间）。
     */
    public Point2D fromPage(double x, double y) throws NoninvertibleTransformException {
        Point2D p = new Point2D.Double(x, y);
        return ctm.inverseTransform(p, null);
    }
}
