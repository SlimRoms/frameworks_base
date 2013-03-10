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

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;

import com.android.systemui.R;
import com.android.systemui.statusbar.phone.QuickSettingsContainerView;
import com.android.systemui.statusbar.phone.QuickSettingsController;

public class VolumeTile extends QuickSettingsTile {
    public static VolumeTile mInstance;

    public static QuickSettingsTile getInstance(Context context, LayoutInflater inflater,
            QuickSettingsContainerView container, final QuickSettingsController qsc,
            Handler handler, String id) {
        mInstance = null;
        mInstance = new VolumeTile(context, inflater, container, qsc, handler);
        return mInstance;
    }

    private Context mContext;

    public VolumeTile(Context context, LayoutInflater inflater,
            QuickSettingsContainerView container, final QuickSettingsController qsc,
            Handler handler) {
        super(context, inflater, container, qsc);

        mContext = context;

        mOnClick = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                qsc.mBar.collapseAllPanels(true);
                AudioManager am = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
                am.adjustVolume(AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI);
            }
        };

        mOnLongClick = new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                startSettingsActivity(android.provider.Settings.ACTION_SOUND_SETTINGS);
                return true;
            }
        };
    }

    @Override
    void onPostCreate() {
        updateTile();
        super.onPostCreate();
    }

    private synchronized void updateTile() {
        mDrawable = R.drawable.ic_qs_volume;
        mLabel = mContext.getString(R.string.quick_settings_volume);
    }
}
