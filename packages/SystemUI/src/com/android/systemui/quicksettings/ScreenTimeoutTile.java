/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.quicksettings;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;
import com.android.systemui.R;
import com.android.systemui.statusbar.phone.QuickSettingsController;
import com.android.systemui.statusbar.phone.QuickSettingsContainerView;

public class ScreenTimeoutTile extends QuickSettingsTile {

    // timeout values
    private static final int SCREEN_TIMEOUT_MIN    =  15000;
    private static final int SCREEN_TIMEOUT_LOW    =  30000;
    private static final int SCREEN_TIMEOUT_NORMAL =  60000;
    private static final int SCREEN_TIMEOUT_HIGH   = 120000;
    private static final int SCREEN_TIMEOUT_MAX    = 300000;
    private static final int SCREEN_TIMEOUT_NEVER  = Integer.MAX_VALUE;

    // cm modes
    private static final int CM_MODE_15_60_300_NEVER = 0;
    private static final int CM_MODE_30_120_300_NEVER = 1;
    public static QuickSettingsTile mInstance;

    public static QuickSettingsTile getInstance(Context context, LayoutInflater inflater,
            QuickSettingsContainerView container, final QuickSettingsController qsc, Handler handler, String id) {
        mInstance = null;
        mInstance = new ScreenTimeoutTile(context, inflater, container, qsc);
        return mInstance;
    }

    public ScreenTimeoutTile(Context context,
            LayoutInflater inflater, QuickSettingsContainerView container, QuickSettingsController qsc) {
        super(context, inflater, container, qsc);

        updateTileState();

        mOnClick = new OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleState();
                applyTimeoutChanges();
            }
        };

        mOnLongClick = new OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                Intent intent = new Intent("android.settings.DISPLAY_SETTINGS");
                startSettingsActivity(intent);
                return true;
            }
        };

        qsc.registerObservedContent(Settings.System.getUriFor(Settings.System.SCREEN_OFF_TIMEOUT)
                , this);
    }

    @Override
    public void onChangeUri(ContentResolver resolver, Uri uri) {
        applyTimeoutChanges();
    }

    void applyTimeoutChanges() {
        updateTileState();
        updateQuickSettings();
    }

    protected void updateTileState() {
        int timeout = getScreenTimeout();
        mLabel = makeTimeoutSummaryString(mContext, timeout);
        mDrawable = R.drawable.ic_qs_screen_timeout_off;
    }

    protected void toggleState() {
        int screenTimeout = getScreenTimeout();
        int currentMode = getCurrentCMMode();

        if (screenTimeout < SCREEN_TIMEOUT_MIN) {
            if (currentMode == CM_MODE_15_60_300_NEVER) {
                screenTimeout = SCREEN_TIMEOUT_MIN;
            } else {
                screenTimeout = SCREEN_TIMEOUT_LOW;
            }
        } else if (screenTimeout < SCREEN_TIMEOUT_LOW) {
            if (currentMode == CM_MODE_15_60_300_NEVER) {
                screenTimeout = SCREEN_TIMEOUT_NORMAL;
            } else {
                screenTimeout = SCREEN_TIMEOUT_LOW;
            }
        } else if (screenTimeout < SCREEN_TIMEOUT_NORMAL) {
            if (currentMode == CM_MODE_15_60_300_NEVER) {
                screenTimeout = SCREEN_TIMEOUT_NORMAL;
            } else {
                screenTimeout = SCREEN_TIMEOUT_HIGH;
            }
        } else if (screenTimeout < SCREEN_TIMEOUT_HIGH) {
            if (currentMode == CM_MODE_15_60_300_NEVER) {
                screenTimeout = SCREEN_TIMEOUT_MAX;
            } else {
                screenTimeout = SCREEN_TIMEOUT_HIGH;
            }
        } else if (screenTimeout < SCREEN_TIMEOUT_MAX) {
            screenTimeout = SCREEN_TIMEOUT_MAX;
        } else if (screenTimeout < SCREEN_TIMEOUT_NEVER) {
            screenTimeout = SCREEN_TIMEOUT_NEVER;
        } else if (currentMode == CM_MODE_30_120_300_NEVER) {
            screenTimeout = SCREEN_TIMEOUT_LOW;
        } else {
            screenTimeout = SCREEN_TIMEOUT_MIN;
        }

        Settings.System.putInt(
                mContext.getContentResolver(),
                Settings.System.SCREEN_OFF_TIMEOUT, screenTimeout);
    }

    private String makeTimeoutSummaryString(Context context, int timeout) {
        Resources res = context.getResources();
        int resId;
        String timeoutSummary = null;

        if (timeout == SCREEN_TIMEOUT_NEVER) {
            timeoutSummary = res.getString(R.string.quick_settings_screen_timeout_summary_never);
        } else {
            /* ms -> seconds */
            timeout /= 1000;

            if (timeout >= 60 && timeout % 60 == 0) {
                /* seconds -> minutes */
                timeout /= 60;
                if (timeout >= 60 && timeout % 60 == 0) {
                    /* minutes -> hours */
                    timeout /= 60;
                    resId = timeout == 1
                            ? com.android.internal.R.string.hour
                            : com.android.internal.R.string.hours;
                } else {
                    resId = timeout == 1
                            ? com.android.internal.R.string.minute
                            : com.android.internal.R.string.minutes;
                }
            } else {
                resId = timeout == 1
                        ? com.android.internal.R.string.second
                        : com.android.internal.R.string.seconds;
            }

            timeoutSummary = res.getString(R.string.quick_settings_screen_timeout_summary,
                    timeout, res.getString(resId));
        }
        return timeoutSummary;
    }

    private int getScreenTimeout() {
        return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.SCREEN_OFF_TIMEOUT, 0);
    }

    private int getCurrentCMMode() {
        return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.EXPANDED_SCREENTIMEOUT_MODE, CM_MODE_15_60_300_NEVER);
    }
}
