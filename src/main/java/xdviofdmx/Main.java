package xdviofdmx;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.IntPredicate;

/**
 * xdviofdmx 命令行入口（对标 xdvipdfmx 的选项子集）。
 *
 * <pre>
 * 用法: xdviofdmx [选项] [xdvfile[.xdv]|-]
 *   -o file        输出 OFD 文件（缺省: 输入主名 + .ofd）
 *   -s pages       页选择，如 1-3,5（缺省全部）
 *   -p paper       纸张（a4/a3/a5/b5/letter 或 WxH，单位 mm），覆盖 special
 *   -m mag         放大率（百分数，如 1200 表示 1.2 倍）
 *   -v             增加日志（可叠加 -vv）
 *   -q             静默
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

    /** 纸张名或 WxH（mm）→ {w, h} */
    private static double[] paperSize(String spec) {
        return switch (spec.toLowerCase()) {
            case "a4" -> new double[]{210, 297};
            case "a3" -> new double[]{297, 420};
            case "a5" -> new double[]{148, 210};
            case "b5" -> new double[]{176, 250};
            case "letter" -> new double[]{215.9, 279.4};
            case "legal" -> new double[]{215.9, 355.6};
            default -> {
                String[] parts = spec.toLowerCase().split("[x×]");
                if (parts.length == 2) {
                    yield new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1])};
                }
                throw new IllegalArgumentException("未知纸张规格: " + spec);
            }
        };
    }

    private static void usage() {
        System.err.println("""
                用法: xdviofdmx [选项] [xdvfile[.xdv]|-]
                  -o file        输出 OFD 文件（缺省: 输入主名 + .ofd）
                  -s pages       页选择，如 1-3,5（缺省全部）
                  -p paper       纸张（a4/a3/a5/b5/letter/legal 或 WxH，单位 mm），覆盖 special
                  -m mag         放大率（千分数，如 1200 = 1.2 倍）
                  -v             增加日志（-vv 更详细）
                  -q             静默
                  --no-embed-fonts 字体按名引用而不嵌入
                  --version      版本信息""");
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
