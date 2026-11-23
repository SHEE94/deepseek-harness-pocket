// scripts/dev/TarRepro.java — pure-JVM repro of TarExtractor head-reading on the
// same gz artifact. If the Kotlin loop fabricates a phantom first header, this
// shows it without a device round trip.
import java.io.*;
import java.util.zip.GZIPInputStream;

public class TarRepro {
    static byte[] buf = new byte[512];
    static InputStream in;

    static boolean readBlock() throws IOException {
        int off = 0;
        while (off < 512) {
            int n = in.read(buf, off, 512 - off);
            if (n < 0) return off == 0;
            off += n;
        }
        return true;
    }

    public static void main(String[] a) throws Exception {
        in = new GZIPInputStream(new BufferedInputStream(new FileInputStream(a[0])), 1 << 16);
        int blocks = 0, zeroBlocks = 0, nonZero = 0;
        StringBuilder firstNonZero = new StringBuilder();
        // head-scan until first phantom candidate: non-zero block with EMPTY name
        // field (bytes 0..100 all zero) — the exact Kotlin guard condition.
        while (blocks < 2000) {
            if (!readBlock()) { System.out.println("clean EOF at block " + blocks); break; }
            boolean allZero = true;
            for (int i = 0; i < 512; i++) if (buf[i] != 0) { allZero = false; break; }
            blocks++;
            if (allZero) { zeroBlocks++; continue; }
            nonZero++;
            boolean nameEmpty = true;
            for (int i = 0; i < 100; i++) if (buf[i] != 0) { nameEmpty = false; break; }
            if (nameEmpty) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 200; i++) sb.append(String.format("%02x", buf[i]));
                System.out.println("PHANTOM-CANDIDATE at block " + blocks + " type=" + buf[156] + " hdr200=" + sb);
                break;
            }
            if (firstNonZero.length() == 0) {
                for (int i = 0; i < 64; i++) firstNonZero.append(String.format("%02x", buf[i]));
            }
        }
        System.out.println("blocks=" + blocks + " zero=" + zeroBlocks + " nonZero=" + nonZero);
        System.out.println("first-nonzero64=" + firstNonZero);
    }
}
