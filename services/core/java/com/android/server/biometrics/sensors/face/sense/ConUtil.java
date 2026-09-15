/*
 * Reverse-engineered from vendorImplPrebuilt.jar.
 * Original package co.aospa.sense.vendor.util preserved as requested.
 * Source: co/aospa/sense/vendor/util/ConUtil.java (CFR 0.152 + javap verification).
 * Placed in-tree under services/core/.../sensors/face/sense/ for system_server build.
 *
 * Note: the raw resource package "co.aospa.sense" is intentionally preserved.
 * It is a runtime resource package name (Resources.getIdentifier(..., "co.aospa.sense")),
 * not a Java package reference, so it must not be renamed with the Java packages.
 */

package co.aospa.sense.vendor.util;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class ConUtil {
    public static String getRaw(Context context, String rawName, String subDir, String fileName,
            boolean forceCopy) {
        File dir = new File(context.getDir("faceunlock_data", 0), subDir);
        if (dir.exists() || dir.mkdirs()) {
            File out = new File(dir, fileName);
            if (!forceCopy && out.exists()) {
                return out.getAbsolutePath();
            }
            byte[] buf = new byte[1024];
            try {
                FileOutputStream fos = new FileOutputStream(out);
                InputStream is = context.getResources().openRawResource(
                        context.getResources().getIdentifier(rawName, "raw", "co.aospa.sense"));
                int n;
                while ((n = is.read(buf)) != -1) {
                    fos.write(buf, 0, n);
                }
                String path = out.getAbsolutePath();
                if (is != null) {
                    is.close();
                }
                fos.close();
                return path;
            } catch (IOException e) {
                e.printStackTrace();
                return null;
            }
        }
        return null;
    }
}
