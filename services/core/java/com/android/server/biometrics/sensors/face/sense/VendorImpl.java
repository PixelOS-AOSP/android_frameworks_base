/*
 * Reverse-engineered from vendorImplPrebuilt.jar.
 * Original package co.aospa.sense.vendor preserved as requested.
 * Source: co/aospa/sense/vendor/VendorImpl.java (CFR 0.152 + javap verification).
 * Placed in-tree under services/core/.../sensors/face/sense/ for system_server build.
 */

package co.aospa.sense.vendor;

import android.content.Context;

import co.aospa.sense.vendor.impl.FacePPImpl;

public class VendorImpl extends Vendor {
    private final Vendor mFaceManager;

    public VendorImpl(Context context) {
        this.mFaceManager = new FacePPImpl(context);
    }

    @Override
    public int compare(byte[] image, int width, int height, int angle,
            boolean needLiveness, boolean bl2, int[] result) {
        return mFaceManager.compare(image, width, height, angle, needLiveness, bl2, result);
    }

    @Override
    public void compareStart() {
        mFaceManager.compareStart();
    }

    @Override
    public void compareStop() {
        mFaceManager.compareStop();
    }

    @Override
    public void deleteFeature(int id) {
        mFaceManager.deleteFeature(id);
    }

    @Override
    public int getFeatureCount() {
        return mFaceManager.getFeatureCount();
    }

    @Override
    public void init() {
        mFaceManager.init();
    }

    @Override
    public void release() {
        mFaceManager.release();
    }

    @Override
    public int saveFeature(byte[] image, int width, int height, int angle,
            boolean needLiveness, byte[] feature, byte[] restoreImage, int[] result) {
        return mFaceManager.saveFeature(image, width, height, angle,
                needLiveness, feature, restoreImage, result);
    }

    @Override
    public void saveFeatureStart() {
        mFaceManager.saveFeatureStart();
    }

    @Override
    public void saveFeatureStop() {
        mFaceManager.saveFeatureStop();
    }

    @Override
    public void setDetectArea(int left, int top, int right, int bottom) {
        mFaceManager.setDetectArea(left, top, right, bottom);
    }
}
