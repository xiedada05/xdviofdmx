package xdviofdmx.font;

import java.nio.file.Path;

/**
 * TeX 文件解析接口：legacy 字体支持按 TFM 名（如 {@code pplr8t}）定位
 * TFM / VF / map / enc / Type1 (PFB) 文件。测试可用内存桩实现注入夹具。
 */
public interface TexResolver {

    /** TFM 度量文件（fonts/tfm/**).tfm）；找不到返回 null */
    Path tfm(String name);

    /** 虚拟字体文件（fonts/vf/**).vf）；找不到返回 null */
    Path vf(String name);

    /** dvips/pdftex 字体映射表（pdftex.map）；找不到返回 null */
    Path fontMap();

    /** dvips 编码文件（fonts/enc/**).enc）；找不到返回 null */
    Path enc(String name);

    /** Type 1 字体程序（PFB/PFA）；找不到返回 null */
    Path type1(String name);

    /** 解析器是否可用（如 kpathsea 是否安装）；不可用时全部返回 null */
    boolean available();
}
