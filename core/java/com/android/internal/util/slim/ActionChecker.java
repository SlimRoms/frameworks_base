package com.android.internal.util.slim;

import android.content.Context;
import android.os.UserHandle;
import android.provider.Settings;

import java.util.ArrayList;

public class ActionChecker {

    private static final ArrayList<String> mConfigs = new ArrayList<String>();

    static {
        mConfigs.add(Settings.System.NAVIGATION_BAR_CONFIG);
    }

    public static boolean actionConfigContainsAction(ActionConfig config, String action) {
        return action.equals(config.getClickAction())
                || action.equals(config.getLongpressAction());
    }

    public static boolean containsAction(Context context,
            ActionConfig config, String action) {

        if (!actionConfigContainsAction(config, action)) return true;

        for (int i = 0; i < mConfigs.size(); i++) {
            String configsString = Settings.System.getStringForUser(context.getContentResolver(),
                    mConfigs.get(i), UserHandle.USER_CURRENT);

            if (configsString.contains(ActionConstants.ACTION_BACK)) {
                String input = configsString;
                int index = input.indexOf(ActionConstants.ACTION_BACK);
                int count = 0;
                while (index != -1) {
                    count++;
                    input = input.substring(index + 1);
                    index = input.indexOf(ActionConstants.ACTION_BACK);
                }
                if (count <= 1) {
                    return true;
                } else {
                    return false;
                }
            }
        }
        return false;
    }
}
