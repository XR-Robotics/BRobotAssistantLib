package com.picovr.robotassistantlib;

import android.text.TextUtils;
import android.util.Log;

import java.lang.reflect.Method;

public class SystemUtils {
    private static final String TAG = "SystemUtils";

    public static String getSystemProperties(String key, String defaultValue) {
        try {
            final Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            final Method get = systemProperties.getMethod("get", String.class, String.class);
            String result = (String) get.invoke(null, key, defaultValue);
            Log.i(TAG, "getSystemProperties--->key=" + key + " value=" + result);
            return TextUtils.isEmpty(result) ? defaultValue : result;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    public static int getSystemProperties(String key, int defaultValue) {
        try {
            final Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            final Method get = systemProperties.getMethod("getInt", String.class, int.class);
            return (int) get.invoke(null, key, defaultValue);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    public static void setSystemProperties(String key, String value) {
        try {
            final Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            final Method set = systemProperties.getMethod("set", String.class, String.class);
            set.invoke(null, key, value);
            Log.i(TAG, "setSystemProperties--->key=" + key + " value=" + value);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
