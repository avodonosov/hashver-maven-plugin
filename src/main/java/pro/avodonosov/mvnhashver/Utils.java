package pro.avodonosov.mvnhashver;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import static java.nio.charset.StandardCharsets.UTF_8;

public class Utils {

    static void saveToFile(File f, byte[] value) throws IOException {
        f.delete();
        try (FileOutputStream stream = new FileOutputStream(f)) {
            stream.write(value);
        }
    }

    static void saveToFile(File f, String value) throws IOException {
        saveToFile(f, value.getBytes(UTF_8));
    }

    static void cleanDir(File dir) throws IOException {
        File[] children = dir.listFiles();
        if(children != null) {
            for(File child: children) {
                if(child.isDirectory()) {
                    cleanDir(child);
                }
                if (!child.delete()) {
                    throw new IOException(
                            "Error deleting " + child.getAbsolutePath());
                }
            }
        }
    }
}
