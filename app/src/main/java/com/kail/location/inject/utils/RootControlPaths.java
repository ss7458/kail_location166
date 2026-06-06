package com.kail.location.inject.utils;

import android.content.Context;
import android.content.pm.PackageInfo;

public final class RootControlPaths {
    public static final String RUNTIME_DIR = "/data/system/kail-loc";
    public static final String LEGACY_CONTROL_PATH = RUNTIME_DIR + "/location_control.txt";
    public static final String LEGACY_ACK_PATH = RUNTIME_DIR + "/location_control_ack.txt";

    private static final String PACKAGE_NAME = "com.kail.location";

    private RootControlPaths() {
    }

    public static String controlPath(Context context) {
        return controlPathForVersion(versionName(context));
    }

    public static String ackPath(Context context) {
        return ackPathForVersion(versionName(context));
    }

    public static String controlPathForVersion(String versionName) {
        return RUNTIME_DIR + "/location_control_" + channelForVersion(versionName) + ".txt";
    }

    public static String ackPathForVersion(String versionName) {
        return RUNTIME_DIR + "/location_control_ack_" + channelForVersion(versionName) + ".txt";
    }

    public static String channelForVersion(String versionName) {
        String value = versionName == null ? "" : versionName.trim();
        if (value.length() == 0) value = "unknown";
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= 'a' && c <= 'z') ||
                    (c >= 'A' && c <= 'Z') ||
                    (c >= '0' && c <= '9')) {
                out.append(c);
            } else {
                out.append('_');
            }
        }
        return out.toString();
    }

    private static String versionName(Context context) {
        if (context == null) return "unknown";
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(PACKAGE_NAME, 0);
            if (info.versionName != null && info.versionName.trim().length() > 0) {
                return info.versionName;
            }
        } catch (Throwable ignored) {
        }
        return "unknown";
    }
}
