/*
 * Reverse-engineered from vendorImplPrebuilt.jar.
 * Original package co.aospa.sense.vendor.impl preserved as requested.
 * Source: co/aospa/sense/vendor/impl/FacePPImpl.java (CFR 0.152 + javap verification).
 * Placed in-tree under services/core/.../sensors/face/sense/ for system_server build.
 *
 * Notes:
 * - TAG = FacePPImpl.class.getSimpleName(), DEBUG = true, SDK_VERSION = "1".
 * - Depends on co.aospa.sense.util.PreferenceHelper (stubbed in-tree, was external
 *   to the prebuilt jar) and co.aospa.sense.vendor.util.ConUtil /
 *   VendorUnlockEncryptor (also reverse-engineered here).
 * - Reference to co/aospa/sense/util/Constants exists in the class constant pool
 *   but is never used by any method body, so no stub is required for it.
 */

package co.aospa.sense.vendor.impl;

import android.content.Context;
import android.util.Log;

import co.aospa.sense.util.PreferenceHelper;
import co.aospa.sense.vendor.Vendor;
import co.aospa.sense.vendor.util.ConUtil;
import co.aospa.sense.vendor.util.VendorUnlockEncryptor;

import java.io.File;

public class FacePPImpl extends Vendor {
    private static final String TAG = FacePPImpl.class.getSimpleName();
    private static final boolean DEBUG = true;
    private static final String SDK_VERSION = "1";

    private final Context mContext;
    private SERVICE_STATE mCurrentState = SERVICE_STATE.INITING;
    private final PreferenceHelper mPreferenceHelper;

    public FacePPImpl(Context context) {
        this.mContext = context;
        this.mPreferenceHelper = new PreferenceHelper(context);
    }

    @Override
    public synchronized void init() {
        if (mCurrentState != SERVICE_STATE.INITING) {
            Log.d(TAG, " Has been init, ignore");
            return;
        }
        String tag = TAG;
        Log.i(tag, "init start");
        boolean needsUpgrade =
                !SDK_VERSION.equals(mPreferenceHelper.getStringValueByKey("sdk_version"));
        File dir = mContext.getDir("faceunlock_data", 0);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        String modelPath = ConUtil.getRaw(mContext, "model_file", "model", "model_file",
                needsUpgrade);
        if (modelPath == null) {
            Log.e(tag, "Unavalibale memory, init failed, stop self");
            return;
        }
        String livePath = ConUtil.getRaw(mContext, "panorama_mgb", "model", "panorama_mgb",
                needsUpgrade);
        MegviiFaceUnlockImpl.getInstance().initHandle(dir.getAbsolutePath(),
                new VendorUnlockEncryptor());
        Log.i(tag, "init stop");
        if (MegviiFaceUnlockImpl.getInstance().initAllWithPath(livePath, "", modelPath) != 0) {
            Log.e(tag, "init failed, stop self");
            return;
        }
        if (needsUpgrade) {
            restoreFeature();
            mPreferenceHelper.saveStringValue("sdk_version", SDK_VERSION);
        }
        mCurrentState = SERVICE_STATE.IDLE;
    }

    private synchronized void restoreFeature() {
        Log.i(TAG, "RestoreFeature");
        MegviiFaceUnlockImpl.getInstance().prepare();
        MegviiFaceUnlockImpl.getInstance().restoreFeature();
        MegviiFaceUnlockImpl.getInstance().reset();
    }

    @Override
    public synchronized void compareStart() {
        if (mCurrentState == SERVICE_STATE.INITING) {
            init();
        }
        if (mCurrentState == SERVICE_STATE.UNLOCKING) {
            return;
        }
        if (mCurrentState != SERVICE_STATE.IDLE) {
            Log.e(TAG, "unlock start failed: current state: " + mCurrentState);
            return;
        }
        Log.i(TAG, "compareStart");
        MegviiFaceUnlockImpl.getInstance().prepare();
        mCurrentState = SERVICE_STATE.UNLOCKING;
    }

    @Override
    public synchronized int compare(byte[] image, int width, int height, int angle,
            boolean needLiveness, boolean bl2, int[] result) {
        if (mCurrentState != SERVICE_STATE.UNLOCKING) {
            Log.e(TAG, "compare failed: current state: " + mCurrentState);
            return -1;
        }
        int ret = MegviiFaceUnlockImpl.getInstance().compare(
                image, width, height, angle, needLiveness, bl2, result);
        Log.i(TAG, "compare finish: " + ret);
        if (ret == 0) {
            compareStop();
        }
        return ret;
    }

    @Override
    public synchronized void compareStop() {
        if (mCurrentState != SERVICE_STATE.UNLOCKING) {
            Log.e(TAG, "compareStop failed: current state: " + mCurrentState);
            return;
        }
        Log.i(TAG, "compareStop");
        MegviiFaceUnlockImpl.getInstance().reset();
        mCurrentState = SERVICE_STATE.IDLE;
    }

    @Override
    public synchronized void saveFeatureStart() {
        if (mCurrentState == SERVICE_STATE.INITING) {
            init();
        } else if (mCurrentState == SERVICE_STATE.UNLOCKING) {
            Log.e(TAG, "save feature, stop unlock");
            compareStop();
        }
        if (mCurrentState != SERVICE_STATE.IDLE) {
            Log.e(TAG, "saveFeatureStart failed: current state: " + mCurrentState);
        }
        Log.i(TAG, "saveFeatureStart");
        MegviiFaceUnlockImpl.getInstance().prepare();
        mCurrentState = SERVICE_STATE.ENROLLING;
    }

    @Override
    public synchronized int saveFeature(byte[] image, int width, int height, int angle,
            boolean needLiveness, byte[] feature, byte[] restoreImage, int[] result) {
        if (mCurrentState != SERVICE_STATE.ENROLLING) {
            Log.e(TAG, "save feature failed , current state : " + mCurrentState);
            return -1;
        }
        Log.i(TAG, "saveFeature");
        return MegviiFaceUnlockImpl.getInstance().saveFeature(
                image, width, height, angle, needLiveness, feature, restoreImage, result);
    }

    @Override
    public synchronized void saveFeatureStop() {
        if (mCurrentState != SERVICE_STATE.ENROLLING) {
            Log.d(TAG, "saveFeatureStop failed: current state: " + mCurrentState);
        }
        Log.i(TAG, "saveFeatureStop");
        MegviiFaceUnlockImpl.getInstance().reset();
        mCurrentState = SERVICE_STATE.IDLE;
    }

    @Override
    public synchronized void setDetectArea(int left, int top, int right, int bottom) {
        Log.i(TAG, "setDetectArea start");
        MegviiFaceUnlockImpl.getInstance().setDetectArea(left, top, right, bottom);
    }

    @Override
    public synchronized void deleteFeature(int id) {
        Log.i(TAG, "deleteFeature start");
        MegviiFaceUnlockImpl.getInstance().deleteFeature(id);
        Log.i(TAG, "deleteFeature stop");
        release();
    }

    @Override
    public int getFeatureCount() {
        return 0;
    }

    @Override
    public synchronized void release() {
        if (mCurrentState == SERVICE_STATE.INITING) {
            Log.i(TAG, "has been released, ignore");
            return;
        }
        Log.i(TAG, "release start");
        MegviiFaceUnlockImpl.getInstance().release();
        mCurrentState = SERVICE_STATE.INITING;
        Log.i(TAG, "release stop");
    }

    public static enum SERVICE_STATE {
        INITING,
        IDLE,
        ENROLLING,
        UNLOCKING,
        ERROR
    }
}
