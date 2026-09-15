/*
 * Reverse-engineered from vendorImplPrebuilt.jar.
 * Original package co.aospa.sense.vendor.impl preserved as requested.
 * Source: co/aospa/sense/vendor/impl/MegviiFaceUnlockImpl.java.
 * Placed in-tree under services/core/.../sensors/face/sense/ for system_server build.
 */

package co.aospa.sense.vendor.impl;

import com.megvii.facepp.sdk.Lite;

public class MegviiFaceUnlockImpl extends Lite {
    private static MegviiFaceUnlockImpl sInstance;

    private MegviiFaceUnlockImpl() {
    }

    public static MegviiFaceUnlockImpl getInstance() {
        if (sInstance == null) {
            sInstance = new MegviiFaceUnlockImpl();
        }
        return sInstance;
    }
}
