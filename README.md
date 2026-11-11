# xdviofdmx

把 XeTeX 的 XDV 输出**原生转换为 OFD**（GB/T 33190-2016 版式文档）的 Java 转换器——
即 `xdvipdfmx` 的 OFD 对应物。

```text
xelatex -no-pdf demo.tex   →  demo.xdv
java -jar xdviofdmx.jar demo.xdv  →  demo.ofd
```

与"先转 PDF 再由其它工具转 OFD"的间接路线不同，xdviofdmx 直接解析 XDV 事件流并
生成 OFD 原生对象（文本对象 / 路径 / 图像），文字可选中、可复制、可搜索，
字形位置由 TeX 排版结果精确决定。

## 架构

```
.xdv ──xdvjni(tectonic_xdv, Rust JNI)──► 事件流
        XdvInterpreter        解释器：specials 解析、图形状态栈、字体注册表、页缓冲
        OfdDocBuilder         发射器：ofdrw 低层组装（TextObject/PathObject/ImageObject）
                              + fontTools 子集化 + Outlines 书签 + 资源登记
        → demo.ofd
```

- **xdvjni**（`xdvjni:xdvjni:0.1.0`，MIT）：Rust `tectonic_xdv` 的 JNI 绑定，自含
  `libxdvjni.so`（Linux x86_64），推送式事件 API。
- **ofdrw**（`org.ofdrw:ofdrw-full:2.5.1`，MIT）：OFD 读写库。本项目使用其低层组装通道
  （`OFDGraphicsDocument` + `OFDPageGraphics2D.appendPageBlock`，即官方 PDF 转换管线
  的写法），并以 `org.ofdrw.layout.engine.ResManager` 登记字体/图像资源。
- **fontTools**（python3-fontTools，可选）：字体子集化（`retain_gids` 保持字形编号，
  CGTransform 无需重写）。缺失时回退为整字体嵌入（体积较大），不中断转换。
- **Ghostscript**（可选）：EPS 图元栅格化。缺失时跳过 EPS 并告警。
- **Apache PDFBox**（ofdrw 传递依赖）：PDF 图元栅格化。

## 特性矩阵

| XDV 内容 | 处理 | OFD 映射 |
|---|---|---|
| 252 native 字体定义 | 路径/faceIndex 解析（TTC/OTC 重打包抽取），fontbox 反查 cmap、AWT 字形轮廓 | Font 资源（嵌入子集或按名引用） |
| 253/254 字形 run | 每字形绝对坐标 + 反查 Unicode（254 原文优先） | TextObject + TextCode(X/Y/DeltaX/DeltaY) + CGTransform |
| 无法反查的字形 | AWT GlyphVector 轮廓兜底 | PathObject 矢量轮廓 |
| 132/137 rules | 底边对齐基线 | PathObject 矩形填充 |
| dvips 颜色（rgb/cmyk/gray/hsb/命名色/push/pop/background） | 颜色栈 | FillColor/StrokeColor（CMYK 原生） |
| papersize / pdf:pagesize | 页面尺寸 | CT_PageArea |
| x:gsave/grestore/scale/rotate/bscale/escale、pdf:btrans/etrans | 绕当前点合成 | 对象 CTM |
| pdf:bxgstate(/ca)、252 COLORED | Alpha | 对象 Alpha(0–255) |
| x:renderingmode | 填充/描边/隐藏 | Fill/Stroke/Visible |
| pdf:literal `re W n` | 裁剪 | Clips |
| pdf:image（PNG/JPEG 直存、PDF 经 PDFBox、EPS 经 Ghostscript 栅格化） | bbox/width/height/depth/clip | ImageObject + MultiMedia |
| pdf:bann/eann（URI 外链、GoTo 内链）、pdf:dest | 链接区域 | Actions/URI、Actions/GotoA |
| pdf:outline | 层级栈建树 | Outlines |
| pdfcolorstack(init) | 颜色栈 g/G/rg/RG/k/K | FillColor/StrokeColor |
| 传统 TFM 字体（char run） | **警告并跳过**（v1 不支持） | — |

## 命令行

```text
用法: xdviofdmx [选项] [xdvfile[.xdv]|-]
  -o file        输出 OFD 文件（缺省: 输入主名 + .ofd；*.pdf 自动改为 *.ofd）
  -s pages       页选择，如 1-3,5（缺省全部）
  -p paper       纸张（a0–a10/b0–b10/c0–c10、letter/legal/ledger 等，或 WxH，mm），覆盖 special
  -m mag         放大率（千分数，如 1200 = 1.2 倍）
  -v             增加日志（-vv 更详细）
  -q             静默
  -E             兼容 xdvipdfmx：恒嵌入字体，接受并忽略
  --no-embed-fonts 字体按名引用而不嵌入
  --version      版本信息
```

输入为 `-` 时从 stdin 流式读取。
图像等相对路径按当前工作目录解析（与 xdvipdfmx/kpathsea 一致）。

### 作为 XeLaTeX 输出驱动（一条命令直接出 OFD）

仓库根目录提供启动脚本 `xdviofdmx`（bash，另有 Windows 版 `xdviofdmx.cmd`），
负责定位 jar 与 Java 并把参数透传给转换器。安装 jar 与脚本后即可：

```bash
# 安装（jar 放到脚本可找到的位置之一）
sudo install -m644 target/xdviofdmx-*-dist.jar /usr/local/share/xdviofdmx/xdviofdmx.jar
sudo install -m755 xdviofdmx /usr/local/bin/xdviofdmx

# 一条命令产出 OFD
xelatex -output-driver=/usr/local/bin/xdviofdmx doc.tex    # → doc.ofd
```

XeTeX 以 `xdviofdmx -q -E -o "doc.pdf" [-p paper]` 调用驱动并把 XDV 写入其
stdin；`-E` 按 xdvipdfmx 语义接受（恒嵌入），`-o` 的 `.pdf` 扩展名自动改写为
`.ofd`——所以 XeTeX 日志照常显示 "Output written on doc.pdf"，实际生成的是
`doc.ofd`。

**驱动名必须可被 popen 的 shell 找到**：XeTeX 对裸名字（不含 `/`）会拼接其
编译期 bindir（Debian/Ubuntu 即 `/usr/bin`），不吃调用方的 `PATH`。因此要么
写绝对路径（如上），要么把脚本装到 `/usr/bin` 后直接用 `-output-driver=xdviofdmx`。
环境变量 `XDVIOFDMX_JAR` 可指定 jar 位置，`JAVA_OPTS` 传 JVM 参数（如 `-Xmx2g`）。

不想动系统的场合，两步法等价：

```bash
xelatex -no-pdf doc.tex && xdviofdmx doc.xdv
```

## 构建

```bash
# 前置：xdvjni 安装到本地仓库
cd ../xdvjni && mvn install

# 本项目（普通 jar + 可执行 dist jar）
mvn package
java -jar target/xdviofdmx-0.1.0-dist.jar --version
```

依赖：`org.ofdrw:ofdrw-full:2.5.1`、`org.ofdrw:ofdrw-graphics2d:2.5.1`
（Maven Central 自动解析）与 `xdvjni`（本项目的解析前端，独立仓库
[xiedada05/xdvjni](https://github.com/xiedada05/xdvjni)，MIT）：

```xml
<repositories>
  <repository>
    <id>jitpack</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>

<dependency>
  <groupId>com.github.xiedada05</groupId>
  <artifactId>xdvjni</artifactId>
  <version>0.1.0</version>
</dependency>
```

JitPack 构建的 jar 内含 Linux x86_64 本地库；macOS / Windows 请在对应平台
克隆 xdvjni 仓库执行 `mvn install`（或直接使用其 Release 中的全平台 jar
放入本地仓库）。

## 验收

以 `xelatex -no-pdf` 产出的 XDV 为输入，与 `xdvipdfmx` 的 PDF 输出渲染对比：

- 中文/西文混排（含连字 ligature，字形经 CGTransform 精确对应）——逐像素级对齐；
- 15 行文字基线与 xdvipdfmx 偏差 ≤ 0.17 mm（60–150 dpi 渲染测量）；
- 彩色（rgb/cmyk/gray/命名色）、规则线、表格线、`colorbox`/`fbox` 裁剪、
  `x:scale`/`x:rotate` 变换、超链接（外链 URI / 内部跳转）、书签层级树、
  PNG/JPEG/PDF/EPS 图元——全部一致；
- 字体子集化：A4 中文文档 42.5 MB → 238 KB（`NotoSerifCJK` 23.4 MB → 742 KB）；
- 文字抽取：27 页 XeTeX 参考手册抽取 34779 字符，关键词命中正确。

## 已知限制

- **传统 TFM 字体**（未启用 `unicode-math` 的数学公式、`\font` 传统字体）：
  文本跳过并告警。使用 `unicode-math` + OpenType 数学字体可全程 native 化（已验证）。
- **词间空格**：TeX 的空格是间距而非字形，未开启 `\XeTeXgenerateactualtext=1` 时
  抽取文本无词间空格（与 xdvipdfmx 同一表现）；开启后 XDV 254 携带原文，
  等长时逐字符采用。
- **EPS**：依赖系统 Ghostscript（`gs`）栅格化；`pdf:image` 的 `pagebox` 仅支持
  常见取值；PDF 图元按 300 dpi 栅格化嵌入（矢量信息不保留）。
- **子集化**：依赖 `python3` + `fontTools ≥ 4.x`；不可用时整字体嵌入。
- **XDV_FLAG_VERTICAL**（竖排）暂不支持；`x:shadow` 等 xdvipdfmx 亦不支持的
  special 一并跳过。
- 原生库仅 Linux x86_64（随 xdvjni）。

## 许可

MIT（与 xdvjni、tectonic_xdv、ofdrw 一致）。GB/T 33190-2016 标准文本版权归
国家市场监督管理总局/国家标准化管理委员会所有，本项目不包含其文本。
