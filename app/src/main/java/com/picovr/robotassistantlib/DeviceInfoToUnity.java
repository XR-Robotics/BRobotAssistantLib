package com.picovr.robotassistantlib;

import android.app.Activity;

public class DeviceInfoToUnity {

    private Activity mUnityActivity;
    private String tag=DeviceInfoToUnity.class.getName();


    /**
     * Must call in unity.In order to init Activity.
     *
     * @param unityActivity
     */
    public void setUnityActivity(Activity unityActivity) {

        this.mUnityActivity = unityActivity;
    }

    public boolean isBDevices() {
        return DeviceUtils.isBDevices(mUnityActivity);
    }

}
