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
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;

import com.android.systemui.R;
import com.android.systemui.statusbar.phone.QuickSettingsContainerView;
import com.android.systemui.statusbar.phone.QuickSettingsController;

public class ExpandedDesktopTile extends QuickSettingsTile {
    public static ExpandedDesktopTile mInstance;
    private boolean enabled = false;

    public static QuickSettingsTile getInstance(Context context, LayoutInflater inflater,
            QuickSettingsContainerView container, final QuickSettingsController qsc,
            Handler handler, String id) {
        mInstance = null;
        mInstance = new ExpandedDesktopTile(context, inflater, container, qsc, handler);
        return mInstance;
    }

    private Context mContext;

    public ExpandedDesktopTile(Context context, LayoutInflater inflater,
            QuickSettingsContainerView container, QuickSettingsController qsc,
            Handler handler) {
        super(context, inflater, container, qsc);

        mContext = context;

        mOnClick = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Change the system setting
                Settings.System.putInt(mContext.getContentResolver(), Settings.System.EXPANDED_DESKTOP_STATE,
                                        !enabled ? 1 : 0);
                }
        };

        mOnLongClick = new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.setClassName("com.android.settings", "com.android.settings.Settings$PowerMenuSettingsActivity");
                startSettingsActivity(intent);
                return true;
            }
        };
        qsc.registerObservedContent(Settings.System.getUriFor(Settings.System.EXPANDED_DESKTOP_STATE), this);
    }

    void onPostCreate() {
        updateTile();
        super.onPostCreate();
    }

    private synchronized void updateTile() {
        enabled = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.EXPANDED_DESKTOP_STATE, 0) == 1;
        if (enabled) {
            mDrawable = R.drawable.ic_qs_expanded_desktop_on;
            mLabel = mContext.getString(R.string.quick_settings_expanded_desktop);
        } else {
            mDrawable = R.drawable.ic_qs_expanded_desktop_off;
            mLabel = mContext.getString(R.string.quick_settings_expanded_desktop_off);
        }
    }

    @Override
    public void onChangeUri(ContentResolver resolver, Uri uri) {
        updateTile();
    }
}
