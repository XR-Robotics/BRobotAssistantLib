package com.picovr.robotassistantlib;

import static com.picovr.robotassistantlib.SystemUtils.getSystemProperties;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;

public class DeviceUtils {
    public static final String VALUE_DEVICE_ID_PRODUCT_NEO2 = "Pico Neo 2";
    public static final String VALUE_DEVICE_ID_PRODUCT_G24K = "Pico G2 4K";

    public static boolean isBDevices(Context context) {
        if (isFinch2_4K() || isNeo2()) {
            return isBDevicesBySettings(context) || isBDevicesByRoProp();
        } else {
            return isBDevicesByRoProp();
        }
    }

    public static boolean isFinch2_4K() {
        return VALUE_DEVICE_ID_PRODUCT_G24K.equals(Build.MODEL);
    }

    public static boolean isNeo2() {
        return VALUE_DEVICE_ID_PRODUCT_NEO2.equals(Build.MODEL);
    }

    private static boolean isBDevicesBySettings(Context context) {
        String _str = Settings.System.getString(context.getContentResolver(), "externalFunc");
        return _str == null || _str.equals("1") || _str.equals("");
    }

    private static boolean isBDevicesByRoProp() {
        int value = getSystemProperties("ro.pxr.externalfunc", 0);
        return value == 1;
    }

}
