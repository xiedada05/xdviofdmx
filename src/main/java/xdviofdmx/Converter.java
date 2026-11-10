package xdviofdmx;

import xdvjni.XdvParser;
import xdviofdmx.font.FontRegistry;
import xdviofdmx.font.LoadedFont;
import xdviofdmx.ofd.OfdDocBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * 转换编排：读入 XDV → xdvjni 解析 → 解释缓冲 → OFD 打包。
 */
public final class Converter implements AutoCloseable {

    public record Config(
            Path input,            // null = stdin
            Path output,
            IntPredicate pageFilter, // 1 起物理页号
            Double paperWidthMm,
            Double paperHeightMm,
            double mag,
            boolean embedFonts,
            boolean quiet) {}

    private final Config config;
    private final XdvInterpreter.Log log;
    private Path tempDir;

    public Converter(Config config, XdvInterpreter.Log log) {
        this.config = config;
        this.log = log;
    }

    public void run() throws IOException {
        long start = System.currentTimeMillis();
        tempDir = Files.createTempDirectory("xdviofdmx");
        try {
            Coord coord = new Coord(config.mag());
            FontRegistry fonts = new FontRegistry(coord, toFontLog(), tempDir);
            try (OfdDocBuilder builder = new OfdDocBuilder(config.output(), coord, fonts,
                    config.embedFonts(), log)) {
                XdvInterpreter.Options opts = new XdvInterpreter.Options(
                        config.paperWidthMm(), config.paperHeightMm(), config.pageFilter());
                XdvInterpreter interpreter = new XdvInterpreter(coord, fonts, builder, opts, log);

                try (XdvParser parser = new XdvParser(interpreter)) {
                    pump(parser);
                    parser.finish();
                }
                interpreter.finish();
                builder.finishLinks(interpreter.deferredLinkPages());
                builder.finishMetadata(inputTitle(), interpreter.outlines());
                long ms = System.currentTimeMillis() - start;
                log.info("输出: %s（%d ms）", config.output(), ms);
            }
        } finally {
            cleanupTemp();
        }
    }

    private String inputTitle() {
        if (config.input() != null) {
            String n = config.input().getFileName().toString();
            int dot = n.lastIndexOf('.');
            return dot > 0 ? n.substring(0, dot) : n;
        }
        return null;
    }

    private LoadedFont.Logger toFontLog() {
        return new LoadedFont.Logger() {
            @Override
            public void warn(String msg) {
                log.warn("%s", msg);
            }

            @Override
            public void info(String msg) {
                log.info("%s", msg);
            }
        };
    }

    /**
     * 文件：整读一次 parse；stdin：分块 feed（xdvjni 契约：未消费的尾部字节续喂）。
     */
    private void pump(XdvParser parser) throws IOException {
        if (config.input() != null) {
            byte[] data = Files.readAllBytes(config.input());
            long consumed = parser.feed(data);
            if (consumed < data.length) {
                throw new IOException("XDV 解析器未消费全部输入（offset=" + parser.currentOffset() + "）");
            }
        } else {
            InputStream in = System.in;
            byte[] buffer = new byte[64 * 1024];
            byte[] pending = new byte[0];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                byte[] chunk = new byte[pending.length + n];
                System.arraycopy(pending, 0, chunk, 0, pending.length);
                System.arraycopy(buffer, 0, chunk, pending.length, n);
                int consumed = parser.feed(chunk);
                int rest = chunk.length - consumed;
                pending = rest > 0 ? java.util.Arrays.copyOfRange(chunk, consumed, chunk.length) : new byte[0];
            }
            if (pending.length > 0) {
                parser.feed(pending);
            }
        }
    }

    private void cleanupTemp() {
        if (tempDir == null) {
            return;
        }
        try (var walk = Files.walk(tempDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    // 尽力清理
                }
            });
        } catch (IOException | UncheckedIOException e) {
            // 尽力清理
        }
    }

    @Override
    public void close() {
        cleanupTemp();
    }

    /** 页选择：逗号分隔的页号/区间（1 起），如 "1-3,5" */
    public static IntPredicate pageSelector(String spec) {
        if (spec == null || spec.isBlank()) {
            return page -> true;
        }
        List<int[]> ranges = new java.util.ArrayList<>();
        for (String part : spec.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            int dash = p.indexOf('-', 1);
            if (dash > 0) {
                ranges.add(new int[]{Integer.parseInt(p.substring(0, dash).trim()),
                        Integer.parseInt(p.substring(dash + 1).trim())});
            } else {
                int v = Integer.parseInt(p);
                ranges.add(new int[]{v, v});
            }
        }
        return page -> {
            for (int[] r : ranges) {
                if (page >= r[0] && page <= r[1]) {
                    return true;
                }
            }
            return false;
        };
    }
}
