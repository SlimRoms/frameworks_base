/*
* Copyright (C) 2013-2015 SlimRoms Project
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

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
                    return false;
                } else {
                    return true;
                }
            }
        }
        return false;
    }
}
