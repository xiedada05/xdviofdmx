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

- **xdvjni**（`xdvjni:xdvjni:0.2.0`，MIT）：XDV/SPX 解析（vendor 自 tectonic_xdv 并扩展），
  自含 `libxdvjni.so`（Linux x86_64），推送式事件 API；0.2.0 起转发 legacy 字体定义、
  char run 携带坐标、支持 TFM 宽度注入。
- **ofdrw**（`org.ofdrw:ofdrw-full:2.5.1`，MIT）：OFD 读写库。本项目使用其低层组装通道
  （`OFDGraphicsDocument` + `OFDPageGraphics2D.appendPageBlock`，即官方 PDF 转换管线
  的写法），并以 `org.ofdrw.layout.engine.ResManager` 登记字体/图像资源。
- **legacy 字体链**（`font/` 包，0.2.0）：TFM/VF/pdftex.map/.enc/Type1 解析 +
  Type1→OTF 转换（kpathsea 定位文件；AGL 字形名→Unicode）。
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
| 传统 TFM 字体（char run） | TFM 宽度定位 + VF 展开至基字体 + map/enc 定位 PFB + **Type1→OTF 转换后按 native 管线嵌入**（文字可选可抽取）；OTF 不可用时字形转矢量路径兜底 | TextObject（synthetic OTF）或 PathObject |
| VF 虚拟字体（T1/TS1 重编码、accent 组合） | packet 重放（基字体字形 + rule 摆放，坐标按 scale/2²⁰ 换算） | 同上（多基字体分段） |

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

Debian/Ubuntu 也可直接安装上一节的 deb 包：驱动装到 `/usr/bin/xdviofdmx`，
XeTeX 的裸名 `-output-driver=xdviofdmx` 即可用。

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

# Fat Jar（自包含，一条命令）
mvn -B -DskipTests package
java -jar target/xdviofdmx-0.1.0-dist.jar --version
```

`mvn package` 产物：

- `target/xdviofdmx-<版本>-dist.jar`——**可执行 Fat Jar**（shade 合并全部依赖
  与所用 xdvjni jar 内嵌的本地库，`java -jar` 直接运行）；
- `target/xdviofdmx-<版本>.jar`——普通薄 jar（库依赖）。

另有两个构建变体：

- `mvn package -Pdeb`——Debian 打包用 jar（`-deb.jar`，剔除 Debian 仓库已
  提供的 13 个库，见下节）；
- 测试：`mvn verify`。

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
  <version>0.2.0</version>
</dependency>
```

JitPack 构建的 jar 内含 Linux x86_64 本地库；macOS / Windows 请在对应平台
克隆 xdvjni 仓库执行 `mvn install`（或直接使用其 Release 中的全平台 jar
放入本地仓库）。

## Debian 打包

`scripts/build-deb.sh` 用 `dpkg-deb` 轻量打包（不需要 debhelper），产出
`target/xdviofdmx_<版本>-<修订>_<架构>.deb`：

```bash
scripts/build-deb.sh                 # 自动 mvn -Pdeb 构建并打包
scripts/build-deb.sh --arch all      # jar 含三平台本地库时手动指定
sudo apt install ./target/xdviofdmx_0.1.0-1_amd64.deb
xdviofdmx --version                  # /usr/bin/xdviofdmx，XeTeX 裸名即可用
```

**依赖混合模式**：18 个第三方运行时坐标中，13 个直接使用 Debian 仓库的
`lib*-java` 包（Depends），运行时由 `/usr/share/xdviofdmx/classpath` 挂接
`/usr/share/java` 副本；Debian 未提供的 5 组仍内嵌在 jar 里。构建脚本解析
jar 文件名时优先 `dpkg -L`，包未安装则 `apt-get download` 兜底（无需 root）。

| Maven 坐标 | Debian 包 | 版本关系 |
|---|---|---|
| org.slf4j:slf4j-api 1.7.36 | libslf4j-java | 精确同版本 |
| commons-logging:commons-logging 1.4.0 | libcommons-logging-java | 精确同版本 |
| org.apache.commons:commons-compress 1.28.0 | libcommons-compress-java | 精确同版本 |
| jaxen:jaxen 1.2.0 | libjaxen-java | 精确同版本 |
| org.dom4j:dom4j 2.1.4 | libdom4j-java | 同主版本（2.1.1） |
| org.apache.xmlgraphics:xmlgraphics-commons 2.11 | libxmlgraphics-commons-java | 精确同版本 |
| xml-apis:xml-apis / xml-apis-ext | libxml-commons-external-java | 同源 |
| commons-io:commons-io 2.16.1 | libcommons-io-java | 同主版本（2.22） |
| org.bouncycastle:bcprov/bcpkix 1.85 | libbcprov-java / libbcpkix-java | 同主版本（1.86） |
| net.lingala.zip4j:zip4j 2.11.3 | libzip4j-java | 同主版本（2.11.7） |
| org.apache.xmlgraphics:batik-all 1.19 | libbatik-java | 同主版本（1.20） |
| com.twelvemonkeys.imageio/common 3.13 | libtwelvemonkeys-java | 同主版本（3.11） |
| **org.ofdrw 全家 / xdvjni / pdfbox 3.x / jbig2-imageio / ujmp-core** | **（内嵌）** | Debian 未提供 |

内嵌组件许可随包附带（copyright 文件）：ofdrw/xdvjni/tectonic_xdv 为 MIT，
pdfbox 3.x 与 jbig2-imageio 为 Apache-2.0，ujmp-core 为 LGPL-3.0。

**升级核对**：任一 Depends 包在 Debian 中跨主版本（如 commons-io 3.x、
bcprov 2.x）或出现 API 变化时，需重测后调整 `pom.xml` 的 `deb` profile
排除清单与脚本中 `system_pkgs` 列表。PDFBox 3.x（Debian 仅有 1.8/2.0）
与 jbig2-imageio、ujmp 无 Debian 包，长期内嵌。

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

- **传统 TFM 字体**（mathpazo/newtx/psnfss 等，0.2.0 起支持）：
  经 kpathsea 定位 TFM/VF/map/enc/PFB，Type1→OTF 转换后走 native 嵌入管线，
  文字可选中可抽取（实测 DeepSeek-V3 论文 54 页 26181 个文本对象，
  抽取文本与原版一致）。要求系统装有 TeX Live（kpathsea）且字体映射齐备；
  OTF 转换失败的字体退化为矢量轮廓（视觉正确、文本不可抽取）。
  OFM（Omega）/pk 位图字体不支持（告警跳过）。
- **词间空格**：TeX 的空格是间距而非字形，未开启 `\XeTeXgenerateactualtext=1` 时
  抽取文本无词间空格（与 xdvipdfmx 同一表现）；开启后 XDV 254 携带原文，
  等长时逐字符采用。
- **EPS**：依赖系统 Ghostscript（`gs`）栅格化；`pdf:image` 的 `pagebox` 仅支持
  常见取值；PDF 图元按 300 dpi 栅格化嵌入（矢量信息不保留）。
- **子集化**：依赖 `python3` + `fontTools ≥ 4.x`；不可用时整字体嵌入。
- **XDV_FLAG_VERTICAL**（竖排）暂不支持；`x:shadow` 等 xdvipdfmx 亦不支持的
  special 一并跳过。
- 原生库随 xdvjni：dist jar 内嵌所用 xdvjni jar 带的本地库（JitPack 版为
  Linux x86_64；用 xdvjni Release 的全平台 jar 构建则三平台通用，
  见 xdvjni README「跨平台构建」）。

## 许可

MIT（与 xdvjni、tectonic_xdv、ofdrw 一致）。GB/T 33190-2016 标准文本版权归
国家市场监督管理总局/国家标准化管理委员会所有，本项目不包含其文本。
