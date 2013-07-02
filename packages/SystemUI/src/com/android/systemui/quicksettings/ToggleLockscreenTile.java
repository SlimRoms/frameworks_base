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

import android.app.KeyguardManager;
import android.app.KeyguardManager.KeyguardLock;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;

import com.android.systemui.R;
import com.android.systemui.statusbar.phone.QuickSettingsController;
import com.android.systemui.statusbar.phone.QuickSettingsContainerView;
import com.android.systemui.statusbar.powerwidget.PowerButton;

public class ToggleLockscreenTile extends QuickSettingsTile {

    private KeyguardLock mLock = null;
    private static final String KEY_DISABLED = "lockscreen_disabled";

    private final KeyguardManager mKeyguardManager;
    private boolean mDisabledLockscreen;
    private SharedPreferences mPrefs;
    public static QuickSettingsTile mInstance;

    public static QuickSettingsTile getInstance(Context context, LayoutInflater inflater,
            QuickSettingsContainerView container, final QuickSettingsController qsc, Handler handler, String id) {
        mInstance = null;
        mInstance = new ToggleLockscreenTile(context, inflater, container, qsc);
        return mInstance;
    }

    public ToggleLockscreenTile(Context context,
            LayoutInflater inflater, QuickSettingsContainerView container, QuickSettingsController qsc) {
        super(context, inflater, container, qsc);

        mLabel = mContext.getString(R.string.quick_settings_lockscreen);

        mKeyguardManager = (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);
        mPrefs = mContext.getSharedPreferences("PowerButton-" + PowerButton.BUTTON_LOCKSCREEN, Context.MODE_PRIVATE);
        mDisabledLockscreen = mPrefs.getBoolean(KEY_DISABLED, false);

        mOnClick = new OnClickListener() {

            @Override
            public void onClick(View v) {
                mDisabledLockscreen = !mDisabledLockscreen;

                SharedPreferences.Editor editor = mPrefs.edit();
                editor.putBoolean(KEY_DISABLED, mDisabledLockscreen);
                editor.apply();

                applyLockscreenChanges();
            }
        };

        mOnLongClick = new OnLongClickListener() {

            @Override
            public boolean onLongClick(View v) {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(
                        "com.android.settings",
                        "com.android.settings.Settings$ASSLockscreenActivity"));
                startSettingsActivity(intent);
                return true;
            }
        };
    }

    @Override
    void onPostCreate() {
        applyLockscreenChanges();
        super.onPostCreate();
    }

    @Override
    public void onDestroy() {
        if (mLock != null) {
            mLock.reenableKeyguard();
        }
        super.onDestroy();
    }

    void applyLockscreenChanges() {
        if (mLock == null) {
            KeyguardManager keyguardManager = (KeyguardManager)
                    mContext.getSystemService(Context.KEYGUARD_SERVICE);
            mLock = keyguardManager.newKeyguardLock("LockscreenTile");
        }
        if (mDisabledLockscreen) {
            mDrawable = R.drawable.ic_qs_lock_screen_off;
            mLock.disableKeyguard();
        } else {
            mDrawable = R.drawable.ic_qs_lock_screen_on;
            mLock.reenableKeyguard();
        }
        updateQuickSettings();
    }

}
