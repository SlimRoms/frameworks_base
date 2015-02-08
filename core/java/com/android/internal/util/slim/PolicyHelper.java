/*
* Copyright (C) 2013 SlimRoms Project
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

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.net.URISyntaxException;
import java.util.ArrayList;

public class PolicyHelper {

    private static final String SYSTEMUI_METADATA_NAME = "com.android.systemui";

    public static ArrayList<ActionConfig> getPowerMenuConfigWithDescription(
            Context context, String values, String entries) {
        String config = Settings.System.getStringForUser(
                    context.getContentResolver(),
                    Settings.System.POWER_MENU_CONFIG,
                    UserHandle.USER_CURRENT);
        if (config == null) {
            config = PolicyConstants.POWER_MENU_CONFIG_DEFAULT;
        }
        return ConfigSplitHelper.getActionConfigValues(context, config, values, entries, true);
    }

    public static void setPowerMenuConfig(Context context,
            ArrayList<ActionConfig> actionConfig, boolean reset) {
        String config;
        if (reset) {
            config = PolicyConstants.POWER_MENU_CONFIG_DEFAULT;
        } else {
            config = ConfigSplitHelper.setActionConfig(actionConfig, true);
        }
        Settings.System.putString(context.getContentResolver(),
                    Settings.System.POWER_MENU_CONFIG,
                    config);
    }

    public static Drawable getPowerMenuIconImage(Context context,
            String clickAction, String customIcon) {
        int resId = -1;
        Drawable d = null;
        PackageManager pm = context.getPackageManager();
        if (pm == null) {
            return null;
        }

        Resources systemUiResources;
        try {
            systemUiResources = pm.getResourcesForApplication(SYSTEMUI_METADATA_NAME);
        } catch (Exception e) {
            Log.e("ButtonsHelper:", "can't access systemui resources",e);
            return null;
        }

        if (!clickAction.startsWith("**")) {
            try {
                String extraIconPath = clickAction.replaceAll(".*?hasExtraIcon=", "");
                if (extraIconPath != null && !extraIconPath.isEmpty()) {
                    File f = new File(Uri.parse(extraIconPath).getPath());
                    if (f.exists()) {
                        d = new BitmapDrawable(context.getResources(),
                                f.getAbsolutePath());
                    }
                }
                if (d == null) {
                    d = pm.getActivityIcon(Intent.parseUri(clickAction, 0));
                }
            } catch (NameNotFoundException e) {
                resId = systemUiResources.getIdentifier(
                    SYSTEMUI_METADATA_NAME + ":drawable/ic_sysbar_null", null, null);
                if (resId > 0) {
                    d = systemUiResources.getDrawable(resId);
                }
            } catch (URISyntaxException e) {
                e.printStackTrace();
            }
        }

        if (customIcon != null && customIcon.startsWith(ActionConstants.SYSTEM_ICON_IDENTIFIER)) {
            resId = systemUiResources.getIdentifier(customIcon.substring(
                        ActionConstants.SYSTEM_ICON_IDENTIFIER.length()), "drawable", "android");
            if (resId > 0) {
                d = systemUiResources.getDrawable(resId);
                if (d != null) {
                    d = ImageHelper.getColoredDrawable(d, 
                            context.getResources().getColor(com.android.internal.R.color.dslv_icon_dark));
                }
            }
        } else if (customIcon != null && !customIcon.equals(ActionConstants.ICON_EMPTY)) {
            File f = new File(Uri.parse(customIcon).getPath());
            if (f.exists()) {
                d = new BitmapDrawable(context.getResources(),
                    ImageHelper.getRoundedCornerBitmap(
                        new BitmapDrawable(context.getResources(),
                        f.getAbsolutePath()).getBitmap()));
            } else {
                Log.e("ActionHelper:", "can't access custom icon image");
                return null;
            }
        } else if (clickAction.startsWith("**")) {
            d = getPowerMenuSystemIcon(context, clickAction);
            if (d != null) {
                d = ImageHelper.getColoredDrawable(d, 
                            context.getResources().getColor(com.android.internal.R.color.dslv_icon_dark));
            }
        }
        return d;
    }

    private static Drawable getPowerMenuSystemIcon(Context context, String clickAction) {
        if (clickAction.equals(PolicyConstants.ACTION_POWER_OFF)) {
            return context.getResources().getDrawable(
                com.android.internal.R.drawable.ic_lock_power_off_alpha);
        } else if (clickAction.equals(PolicyConstants.ACTION_REBOOT)) {
            return context.getResources().getDrawable(
                com.android.internal.R.drawable.ic_lock_reboot_alpha);
        } else if (clickAction.equals(PolicyConstants.ACTION_SCREENSHOT)) {
            return context.getResources().getDrawable(
                com.android.internal.R.drawable.ic_lock_screenshot);
        } else if (clickAction.equals(PolicyConstants.ACTION_AIRPLANE)) {
            return context.getResources().getDrawable(
                com.android.internal.R.drawable.ic_lock_airplane_mode_off_am_alpha);
        } else if (clickAction.equals(PolicyConstants.ACTION_LOCKDOWN)) {
            return context.getResources().getDrawable(
                com.android.internal.R.drawable.ic_lock_lock_alpha);
        }
        return null;
    }

}
