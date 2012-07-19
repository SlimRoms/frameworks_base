/*
 * Copyright (C) 2012 The CyanogenMod Project
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

package com.android.systemui.statusbar.powerwidget;

import android.app.KeyguardManager;
import android.app.KeyguardManager.KeyguardLock;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;

import com.android.systemui.R;

public class LockScreenButton extends PowerButton {
    private static final String KEY_DISABLED = "lockscreen_disabled";

    private KeyguardLock mLock = null;
    private boolean mDisabledLockscreen = false;

    public LockScreenButton() { mType = BUTTON_LOCKSCREEN; }

    @Override
    protected void updateState(Context context) {
        if (!mDisabledLockscreen) {
            mIcon = R.drawable.stat_lock_screen_on;
            mState = STATE_ENABLED;
        } else {
            mIcon = R.drawable.stat_lock_screen_off;
            mState = STATE_DISABLED;
        }
    }

    @Override
    protected void setupButton(View view) {
        super.setupButton(view);

        if (view == null && mDisabledLockscreen) {
            mLock.reenableKeyguard();
            mLock = null;
        } else if (view != null) {
            Context context = view.getContext();
            mDisabledLockscreen = getPreferences(context).getBoolean(KEY_DISABLED, false);
            applyState(context);
        }
    }

    @Override
    protected void toggleState(Context context) {
        mDisabledLockscreen = !mDisabledLockscreen;

        SharedPreferences.Editor editor = getPreferences(context).edit();
        editor.putBoolean(KEY_DISABLED, mDisabledLockscreen);
        editor.apply();

        applyState(context);
    }

    @Override
    protected boolean handleLongClick(Context context) {
        Intent intent = new Intent("android.settings.SECURITY_SETTINGS");
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        return true;
    }

    private void applyState(Context context) {
        if (mLock == null) {
            KeyguardManager keyguardManager = (KeyguardManager)
                    context.getSystemService(Context.KEYGUARD_SERVICE);
            mLock = keyguardManager.newKeyguardLock("PowerWidget");
        }
        if (mDisabledLockscreen) {
            mLock.disableKeyguard();
        } else {
            mLock.reenableKeyguard();
        }
    }
}

