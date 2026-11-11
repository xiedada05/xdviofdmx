package xdviofdmx;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntPredicate;

/**
 * xdviofdmx 命令行入口（对标 xdvipdfmx 的选项子集）。
 *
 * <p>同时兼容作为 XeTeX 输出驱动被调用（{@code xelatex -output-driver=xdviofdmx}）：
 * XeTeX 以 {@code xdviofdmx -q -E -o "<jobname>.pdf" [-p paper]} 的形式启动本程序
 * 并把 XDV 写入 stdin，因此 {@code -E} 被接受并忽略（字体恒嵌入），{@code -o} 的
 * {@code .pdf} 扩展名自动改写为 {@code .ofd}。</p>
 *
 * <pre>
 * 用法: xdviofdmx [选项] [xdvfile[.xdv]|-]
 *   -o file        输出 OFD 文件（缺省: 输入主名 + .ofd；*.pdf 自动改为 *.ofd）
 *   -s pages       页选择，如 1-3,5（缺省全部）
 *   -p paper       纸张（a0–a10/b0–b10/c0–c10、letter/legal 等，或 WxH，单位 mm）
 *   -m mag         放大率（千分数，如 1200 = 1.2 倍）
 *   -v             增加日志（-vv 更详细）
 *   -q             静默
 *   -E             兼容 xdvipdfmx：恒嵌入字体，接受并忽略
 *   --no-embed-fonts 字体按名引用而不嵌入
 *   --version      版本信息
 * </pre>
 */
public final class Main {

    public static final String VERSION = "xdviofdmx 0.1.0";

    public static void main(String[] args) {
        Path input = null;
        Path output = null;
        String pageSpec = null;
        Double paperW = null, paperH = null;
        double mag = 1.0;
        boolean embed = true;
        int verbose = 0;
        boolean quiet = false;

        try {
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "-o" -> output = Path.of(require(args, ++i, "-o"));
                    case "-s" -> pageSpec = require(args, ++i, "-s");
                    case "-p" -> {
                        double[] wh = paperSize(require(args, ++i, "-p"));
                        paperW = wh[0];
                        paperH = wh[1];
                    }
                    case "-m" -> mag = Double.parseDouble(require(args, ++i, "-m")) / 1000.0;
                    case "-v" -> verbose++;
                    case "-vv" -> verbose += 2;
                    case "-q" -> quiet = true;
                    case "-E" -> {
                        // xdvipdfmx 的“恒嵌入字体”开关；xdviofdmx 恒嵌入，接受并忽略
                    }
                    case "--no-embed-fonts" -> embed = false;
                    case "--version" -> {
                        System.out.println(VERSION);
                        return;
                    }
                    case "--help", "-h" -> {
                        usage();
                        return;
                    }
                    default -> {
                        if (a.equals("-")) {
                            input = null; // stdin
                        } else if (a.startsWith("-")) {
                            throw new IllegalArgumentException("未知选项: " + a);
                        } else if (input != null) {
                            throw new IllegalArgumentException("多余的输入文件: " + a);
                        } else {
                            input = Path.of(a);
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println(VERSION);
            System.err.println("错误: " + e.getMessage());
            usage();
            System.exit(2);
        }

        if (input == null && System.in == null) {
            System.err.println("错误: 缺少输入文件");
            usage();
            System.exit(2);
        }
        if (input != null && !Files.isRegularFile(input)) {
            // 补扩展名
            Path withExt = Path.of(input + ".xdv");
            if (Files.isRegularFile(withExt)) {
                input = withExt;
            } else {
                System.err.println("错误: 输入文件不存在: " + input);
                System.exit(2);
            }
        }
        if (output == null) {
            String base = input != null ? stripExts(input.getFileName().toString()) : "stdin";
            output = Path.of(base + ".ofd");
        }
        // XeTeX 输出驱动约定：XeTeX 以 -o "<jobname>.pdf" 调用输出驱动；
        // 本程序生成 OFD，把 .pdf 扩展名改写为 .ofd
        String outName = output.getFileName().toString();
        if (outName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            Path renamed = output.resolveSibling(
                    outName.substring(0, outName.length() - 4) + ".ofd");
            if (!quiet) {
                System.err.println("[INFO] -o " + output + " → " + renamed + "（xdviofdmx 生成 OFD 格式）");
            }
            output = renamed;
        }

        IntPredicate pageFilter = Converter.pageSelector(pageSpec);
        Converter.Config config = new Converter.Config(input, output, pageFilter,
                paperW, paperH, mag, embed, quiet);
        XdvInterpreter.Log log = new LogPrinter(verbose, quiet);
        try (Converter converter = new Converter(config, log)) {
            converter.run();
        } catch (xdvjni.XdvException e) {
            System.err.println("XDV 解析错误: " + e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.err.println("IO 错误: " + e.getMessage());
            System.exit(1);
        } catch (RuntimeException e) {
            System.err.println("转换失败: " + e);
            if (verbose >= 2) {
                e.printStackTrace();
            }
            System.exit(1);
        }
    }

    private static String require(String[] args, int i, String opt) {
        if (i >= args.length) {
            throw new IllegalArgumentException("选项 " + opt + " 缺少参数");
        }
        return args[i];
    }

    private static String stripExts(String name) {
        int dot = name.indexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** 常见纸张名 → {宽, 高} mm（覆盖 xdvipdfmx paper.c 的常用子集；b/c 系列为 ISO 216/269） */
    private static final Map<String, double[]> PAPER_SIZES = Map.ofEntries(
            Map.entry("a0", new double[]{841, 1189}),
            Map.entry("a1", new double[]{594, 841}),
            Map.entry("a2", new double[]{420, 594}),
            Map.entry("a3", new double[]{297, 420}),
            Map.entry("a4", new double[]{210, 297}),
            Map.entry("a5", new double[]{148, 210}),
            Map.entry("a6", new double[]{105, 148}),
            Map.entry("a7", new double[]{74, 105}),
            Map.entry("a8", new double[]{52, 74}),
            Map.entry("a9", new double[]{37, 52}),
            Map.entry("a10", new double[]{26, 37}),
            Map.entry("b0", new double[]{1000, 1414}),
            Map.entry("b1", new double[]{707, 1000}),
            Map.entry("b2", new double[]{500, 707}),
            Map.entry("b3", new double[]{353, 500}),
            Map.entry("b4", new double[]{250, 353}),
            Map.entry("b5", new double[]{176, 250}),
            Map.entry("b6", new double[]{125, 176}),
            Map.entry("b7", new double[]{88, 125}),
            Map.entry("b8", new double[]{62, 88}),
            Map.entry("b9", new double[]{44, 62}),
            Map.entry("b10", new double[]{31, 44}),
            Map.entry("c0", new double[]{917, 1297}),
            Map.entry("c1", new double[]{648, 917}),
            Map.entry("c2", new double[]{458, 648}),
            Map.entry("c3", new double[]{324, 458}),
            Map.entry("c4", new double[]{229, 324}),
            Map.entry("c5", new double[]{162, 229}),
            Map.entry("c6", new double[]{114, 162}),
            Map.entry("c7", new double[]{81, 114}),
            Map.entry("c8", new double[]{57, 81}),
            Map.entry("c9", new double[]{40, 57}),
            Map.entry("c10", new double[]{28, 40}),
            Map.entry("letter", new double[]{215.9, 279.4}),
            Map.entry("legal", new double[]{215.9, 355.6}),
            Map.entry("ledger", new double[]{431.8, 279.4}),
            Map.entry("tabloid", new double[]{279.4, 431.8}),
            Map.entry("executive", new double[]{184.1, 266.7}),
            Map.entry("statement", new double[]{139.7, 215.9}),
            Map.entry("folio", new double[]{215.9, 330.2}));

    /** 纸张名（含 xdvipdfmx 常用名与 "xxpaper" 变体）或 WxH（mm）→ {w, h} */
    private static double[] paperSize(String spec) {
        String s = spec.toLowerCase(Locale.ROOT);
        if (s.endsWith("paper")) {
            s = s.substring(0, s.length() - "paper".length());
        }
        double[] size = PAPER_SIZES.get(s);
        if (size != null) {
            return size.clone();
        }
        String[] parts = s.split("[x×]");
        if (parts.length == 2) {
            try {
                return new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1])};
            } catch (NumberFormatException e) {
                // 落到未知规格报错
            }
        }
        throw new IllegalArgumentException("未知纸张规格: " + spec);
    }

    private static void usage() {
        System.err.println("""
                用法: xdviofdmx [选项] [xdvfile[.xdv]|-]
                  -o file        输出 OFD 文件（缺省: 输入主名 + .ofd；*.pdf 自动改为 *.ofd）
                  -s pages       页选择，如 1-3,5（缺省全部）
                  -p paper       纸张（a0–a10/b0–b10/c0–c10、letter/legal/ledger 等或 WxH，mm），覆盖 special
                  -m mag         放大率（千分数，如 1200 = 1.2 倍）
                  -v             增加日志（-vv 更详细）
                  -q             静默
                  -E             兼容 xdvipdfmx：恒嵌入字体，接受并忽略
                  --no-embed-fonts 字体按名引用而不嵌入
                  --version      版本信息

                作为 XeTeX 输出驱动:  xelatex -output-driver=xdviofdmx doc.tex""");
    }

    /** stderr 日志 */
    private record LogPrinter(int verbose, boolean quiet) implements XdvInterpreter.Log {
        @Override
        public void info(String fmt, Object... args) {
            if (!quiet && verbose >= 1) {
                System.err.println("[INFO] " + fmt.formatted(args));
            }
        }

        @Override
        public void warn(String fmt, Object... args) {
            if (!quiet) {
                System.err.println("[WARN] " + fmt.formatted(args));
            }
        }

        @Override
        public void debug(String fmt, Object... args) {
            if (!quiet && verbose >= 2) {
                System.err.println("[DEBUG] " + fmt.formatted(args));
            }
        }
    }
}
