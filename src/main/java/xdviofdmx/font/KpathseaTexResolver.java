package xdviofdmx.font;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 kpathsea（{@code kpsewhich}）的 TeX 文件解析器。
 *
 * <p>查询带进程级缓存；kpsewhich 不在 PATH、或查询超时/失败时返回 null
 * （调用方按字体粒度降级）。另设常见 TEXMF 目录兜底直查，覆盖 kpathsea
 * 缺失但文件在标准位置的场景。</p>
 */
public final class KpathseaTexResolver implements TexResolver {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** 常见 TEXMF 根（kpathsea 缺失时的兜底搜索根） */
    private static final Path[] TEXMF_ROOTS = {
        Path.of("/usr/share/texlive/texmf-dist"),
        Path.of("/usr/share/texmf"),
        Path.of("/usr/local/texlive"),
        Path.of("/var/lib/texmf"),
    };

    private final String kpsewhich;
    private final Map<String, Path> cache = new ConcurrentHashMap<>();
    private final Boolean kpseAvailable; // null = 未探测

    public KpathseaTexResolver() {
        this("kpsewhich");
    }

    public KpathseaTexResolver(String kpsewhichCommand) {
        this.kpsewhich = kpsewhichCommand;
        this.kpseAvailable = probe();
    }

    private boolean probe() {
        try {
            Process p = new ProcessBuilder(kpsewhich, "--version").start();
            boolean ok = p.waitFor(TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                    && p.exitValue() == 0;
            p.destroyForcibly();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean available() {
        return kpseAvailable || standardRootsPresent();
    }

    private static boolean standardRootsPresent() {
        for (Path root : TEXMF_ROOTS) {
            if (Files.isDirectory(root)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Path tfm(String name) {
        return resolve(name + ".tfm", "tfm");
    }

    @Override
    public Path vf(String name) {
        return resolve(name + ".vf", "vf");
    }

    @Override
    public Path fontMap() {
        return resolve("pdftex.map", "map");
    }

    @Override
    public Path enc(String name) {
        return resolve(name.endsWith(".enc") ? name : name + ".enc", "enc");
    }

    @Override
    public Path type1(String name) {
        // map 行引用通常已带扩展名（uplr8a.pfb）；裸名按 .pfb 兜底
        String file = name.indexOf('.') >= 0 ? name : name + ".pfb";
        return resolve(file, "type1");
    }

    private Path resolve(String file, String format) {
        return cache.computeIfAbsent(format + '\0' + file, k -> {
            Path r = viaKpathsea(file, format);
            if (r == null) {
                r = viaStandardRoots(file, format);
            }
            return r;
        });
    }

    private Path viaKpathsea(String file, String format) {
        if (!kpseAvailable) {
            return null;
        }
        try {
            Process p = new ProcessBuilder(kpsewhich, "-format=" + format, file)
                    .redirectErrorStream(true)
                    .start();
            byte[] out;
            try (var in = p.getInputStream()) {
                out = in.readAllBytes();
            }
            if (!p.waitFor(TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0) {
                return null;
            }
            String line = new String(out, java.nio.charset.StandardCharsets.UTF_8)
                    .lines().findFirst().orElse("").trim();
            if (line.isEmpty()) {
                return null;
            }
            Path path = Path.of(line);
            return Files.isReadable(path) ? path : null;
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    /** 无 kpathsea 时按 TDS 目录约定直查 */
    private static Path viaStandardRoots(String file, String format) {
        String rel = switch (format) {
            case "tfm" -> "fonts/tfm";
            case "vf" -> "fonts/vf";
            case "enc" -> "fonts/enc";
            case "type1" -> "fonts/type1";
            case "map" -> "fonts/map";
            default -> null;
        };
        if (rel == null) {
            return null;
        }
        for (Path root : TEXMF_ROOTS) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            // pdftex.map 在 updmap 生成树与源树两个位置
            if ("map".equals(format)) {
                for (String sub : new String[] {
                        "pdftex/updmap/" + file,
                        "pdftex/plain/" + file,
                        "dvips/base/" + file}) {
                    Path c = root.resolve(rel).resolve(sub);
                    if (Files.isReadable(c)) {
                        return c;
                    }
                }
                continue;
            }
            try (var stream = Files.walk(root.resolve(rel), 6)) {
                Path hit = stream
                        .filter(f -> f.getFileName().toString().equals(file))
                        .filter(Files::isReadable)
                        .findFirst().orElse(null);
                if (hit != null) {
                    return hit;
                }
            } catch (IOException ignore) {
                // 继续下一根
            }
        }
        return null;
    }
}
