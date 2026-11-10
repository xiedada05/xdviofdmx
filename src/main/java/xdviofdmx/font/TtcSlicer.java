package xdviofdmx.font;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * TTC/OTC（ttcf 容器）face 抽取器：把集合中的第 N 个字体抽取为独立 sfnt 文件。
 *
 * <p>实现方式是"重打包"而非区间切片：TTC 中的表数据可以在多个 face 之间共享，
 * 按 [offset_i, offset_{i+1}) 区间切片会截断共享表。正确做法是读取 face 的
 * offset table，把其中引用的每张表从原始文件按全局偏移拷贝到输出文件并重写表项。</p>
 */
public final class TtcSlicer implements LoadedFont.TtcSlicer {

    private final Path tempDir;

    public TtcSlicer(Path tempDir) throws IOException {
        this.tempDir = tempDir;
        Files.createDirectories(tempDir);
    }

    @Override
    public boolean isTtc(Path file) {
        try {
            byte[] head = new byte[4];
            try (var in = Files.newInputStream(file)) {
                if (in.readNBytes(head, 0, 4) < 4) {
                    return false;
                }
            }
            return head[0] == 't' && head[1] == 't' && head[2] == 'c' && head[3] == 'f';
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public Path slice(Path ttcFile, int faceIndex) throws IOException {
        long size = Files.size(ttcFile);
        try (FileChannel ch = FileChannel.open(ttcFile, StandardOpenOption.READ)) {
            ByteBuffer header = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN);
            readFully(ch, header, 0);
            header.flip();
            int tag = header.getInt();            // 'ttcf'
            if (tag != 0x74746366) {
                throw new IOException("不是 ttcf 容器: " + ttcFile);
            }
            header.getInt();                      // version
            int numFonts = header.getInt();
            if (faceIndex < 0 || faceIndex >= numFonts) {
                throw new IOException("faceIndex=%d 超出范围（共 %d 个 face）".formatted(faceIndex, numFonts));
            }
            ByteBuffer offsets = ByteBuffer.allocate(4 * numFonts).order(ByteOrder.BIG_ENDIAN);
            readFully(ch, offsets, 12);
            offsets.flip();
            long faceOffset = Integer.toUnsignedLong(offsets.getInt(4 * faceIndex));
            if (faceOffset <= 0 || faceOffset >= size) {
                throw new IOException("face 偏移非法: %d / %d".formatted(faceOffset, size));
            }

            // 读取该 face 的 sfnt offset table（version u32 + numTables/searchRange/entrySelector/rangeShift u16）
            ByteBuffer sfntHeader = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN);
            readFully(ch, sfntHeader, faceOffset);
            sfntHeader.flip();
            int sfntVersion = sfntHeader.getInt();   // 0x00010000 / 'OTTO' / 'true'
            int numTables = sfntHeader.getShort() & 0xFFFF;
            int searchRange = sfntHeader.getShort() & 0xFFFF;
            int entrySelector = sfntHeader.getShort() & 0xFFFF;
            int rangeShift = sfntHeader.getShort() & 0xFFFF;
            if (numTables <= 0 || numTables > 512) {
                throw new IOException("face 表数量非法: " + numTables);
            }

            record TableEntry(int tag, long checksum, long offset, int length) {}
            TableEntry[] tables = new TableEntry[numTables];
            ByteBuffer dir = ByteBuffer.allocate(16 * numTables).order(ByteOrder.BIG_ENDIAN);
            readFully(ch, dir, faceOffset + 12);
            dir.flip();
            for (int i = 0; i < numTables; i++) {
                int t = dir.getInt();
                long cs = Integer.toUnsignedLong(dir.getInt());
                long off = Integer.toUnsignedLong(dir.getInt());
                int len = dir.getInt();
                if (off + len > size) {
                    throw new IOException("表 %s 越界: [%d, %d) / %d"
                            .formatted(Integer.toHexString(t), off, off + len, size));
                }
                tables[i] = new TableEntry(t, cs, off, len);
            }

            // 重打包：新 header + 表目录（重写偏移）+ 顺序对齐的表数据
            Path out = tempDir.resolve(baseName(ttcFile) + "-f" + faceIndex + ".otf");
            long headerSize = 12 + 16L * numTables;
            try (FileChannel outCh = FileChannel.open(out, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer newHeader = ByteBuffer.allocate((int) headerSize).order(ByteOrder.BIG_ENDIAN);
                newHeader.putInt(sfntVersion).putShort((short) numTables)
                        .putShort((short) searchRange).putShort((short) entrySelector)
                        .putShort((short) rangeShift);
                long cursor = headerSize;
                long[] newOffsets = new long[numTables];
                for (int i = 0; i < numTables; i++) {
                    newOffsets[i] = cursor;
                    cursor += (tables[i].length() + 3L) & ~3L;
                }
                for (int i = 0; i < numTables; i++) {
                    newHeader.putInt(tables[i].tag()).putInt((int) tables[i].checksum())
                            .putInt((int) newOffsets[i]).putInt(tables[i].length());
                }
                newHeader.flip();
                while (newHeader.hasRemaining()) {
                    outCh.write(newHeader);
                }
                // 拷贝每张表（4 字节对齐，填充 0）
                ByteBuffer pad = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
                for (int i = 0; i < numTables; i++) {
                    TableEntry t = tables[i];
                    long pos = 0;
                    ByteBuffer chunk = ByteBuffer.allocate(Math.min(t.length(), 1 << 20));
                    while (pos < t.length()) {
                        int n = Math.min(chunk.capacity(), t.length() - (int) pos);
                        chunk.clear().limit(n);
                        readFully(ch, chunk, t.offset() + pos);
                        chunk.flip();
                        while (chunk.hasRemaining()) {
                            outCh.write(chunk);
                        }
                        pos += n;
                    }
                    int rem = (int) ((4 - (t.length() & 3)) & 3);
                    if (rem > 0) {
                        pad.clear().limit(rem);
                        pad.rewind();
                        while (pad.hasRemaining()) {
                            outCh.write(pad);
                        }
                    }
                }
            }
            return out;
        }
    }

    private static void readFully(FileChannel ch, ByteBuffer buf, long position) throws IOException {
        long p = position;
        while (buf.hasRemaining()) {
            long n = ch.read(buf, p);
            if (n < 0) {
                throw new IOException("文件提前结束");
            }
            p += n;
        }
    }

    private static String baseName(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }
}
