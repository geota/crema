import java.io.*;
import java.nio.file.*;
import java.util.zip.*;

/**
 * Writes android-zipoutputstream.crema.zip the way Android writes a backup:
 * ZipOutputStream, DEFLATED, so every entry has a data descriptor. The photo
 * comes first and holds all three zip signatures, stored (level 0), so they
 * show in its compressed bytes. Rerun from this directory with
 *   java MakeZip.java android-backup.crema.jsonl android-zipoutputstream.crema.zip
 */
public class MakeZip {
    public static void main(String[] a) throws Exception {
        byte[] jsonl = Files.readAllBytes(Path.of(a[0]));
        byte[] photo = new byte[8192];
        long x = 0x2545F4914F6CDD1DL;
        for (int i = 0; i < photo.length; i++) { x ^= x << 13; x ^= x >>> 7; x ^= x << 17; photo[i] = (byte) x; }
        byte[][] sigs = { {0x50,0x4b,0x03,0x04}, {0x50,0x4b,0x07,0x08}, {0x50,0x4b,0x01,0x02} };
        int[] at = {100, 2000, 5000};
        for (int s = 0; s < 3; s++) System.arraycopy(sigs[s], 0, photo, at[s], 4);
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(a[1]))) {
            zip.setLevel(0); // stored deflate blocks: the photo's signatures appear in the compressed bytes
            zip.putNextEntry(new ZipEntry("images/bean:android-1"));
            zip.write(photo);
            zip.closeEntry();
            zip.setLevel(Deflater.DEFAULT_COMPRESSION);
            zip.putNextEntry(new ZipEntry("backup.jsonl"));
            zip.write(jsonl);
            zip.closeEntry();
        }
    }
}
