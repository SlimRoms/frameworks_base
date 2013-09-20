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
import android.net.Uri;
import android.os.Handler;
import android.provider.AlarmClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;

import com.android.systemui.R;
import com.android.systemui.statusbar.phone.QuickSettingsController;
import com.android.systemui.statusbar.phone.QuickSettingsContainerView;

public class AlarmTile extends QuickSettingsTile{

    public static QuickSettingsTile mInstance;

    public static QuickSettingsTile getInstance(Context context, LayoutInflater inflater,
            QuickSettingsContainerView container, final QuickSettingsController qsc, Handler handler, String id) {
        mInstance = null;
        mInstance = new AlarmTile(context, inflater, container, qsc, handler);
        return mInstance;
    }

    public AlarmTile(Context context, LayoutInflater inflater,
            QuickSettingsContainerView container,
            QuickSettingsController qsc, Handler handler) {
        super(context, inflater, container, qsc);

        mDrawable = R.drawable.ic_qs_alarm_on;

        mOnClick = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startSettingsActivity(new Intent(AlarmClock.ACTION_SET_ALARM));
            }
        };
        qsc.registerObservedContent(Settings.System.getUriFor(
                Settings.System.NEXT_ALARM_FORMATTED), this);
        updateStatus();
    }

    @Override
    public void onChangeUri(ContentResolver resolver, Uri uri) {
        updateStatus();
        updateQuickSettings();
    }

    @Override
    public void updateQuickSettings() {
        mTile.setVisibility(!TextUtils.isEmpty(mLabel) ? View.VISIBLE : View.GONE);
        super.updateQuickSettings();
    }

    /**
     * Updates the alarm status shown on the tile.
     */
    private void updateStatus() {
        mLabel = Settings.System.getString(mContext.getContentResolver(),
            Settings.System.NEXT_ALARM_FORMATTED);
    }

}
