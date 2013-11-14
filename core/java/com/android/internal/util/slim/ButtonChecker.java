package com.android.internal.util.slim;

import android.content.Context;
import android.os.UserHandle;
import android.provider.Settings;

import java.util.ArrayList;

public class ButtonChecker {

    private static final ArrayList<String> mConfigs = new ArrayList<String>();

    static {
        mConfigs.add(Settings.System.NAVIGATION_BAR_CONFIG);
    }

    public static final boolean containsBackButton(Context context) {
        for (int i =0; i < mConfigs.size(); i++) {
            String config = Settings.System.getStringForUser(context.getContentResolver(),
                    mConfigs.get(i), UserHandle.USER_ALL);

            if (config.contains(ActionConstants.ACTION_BACK)) {
                return true;
            }
        }
        return false;
    }

    public static final boolean containsHomeButton(Context context) {
        for (int i =0; i < mConfigs.size(); i++) {
            String config = Settings.System.getStringForUser(context.getContentResolver(),
                    mConfigs.get(i), UserHandle.USER_ALL);

            if (config.contains(ActionConstants.ACTION_HOME)) {
                return true;
            }
        }
        return false;
    }
}
