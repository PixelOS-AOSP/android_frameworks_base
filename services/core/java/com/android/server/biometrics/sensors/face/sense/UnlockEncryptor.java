/*
 * Reverse-engineered from vendorImplPrebuilt.jar.
 * Original package co.aospa.sense.vendor.util preserved as requested.
 * Source: co/aospa/sense/vendor/util/UnlockEncryptor.java.
 * Placed in-tree under services/core/.../sensors/face/sense/ for system_server build.
 */

package co.aospa.sense.vendor.util;

public interface UnlockEncryptor {
    byte[] decrypt(byte[] data);

    byte[] encrypt(byte[] data);
}
