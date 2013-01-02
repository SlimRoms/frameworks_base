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
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;

import com.android.internal.view.RotationPolicy;
import com.android.systemui.R;
import com.android.systemui.statusbar.phone.QuickSettingsController;
import com.android.systemui.statusbar.phone.QuickSettingsContainerView;

public class AutoRotateTile extends QuickSettingsTile {

    private static final String TAG = "AutoRotateButton";
    public static AutoRotateTile mInstance;

    public static QuickSettingsTile getInstance(Context context, LayoutInflater inflater,
            QuickSettingsContainerView container, final QuickSettingsController qsc, Handler handler) {
        if (mInstance == null) mInstance = new AutoRotateTile(context, inflater, container, qsc, handler);
        else {mInstance.applyAutoRotationChanges(); qsc.registerObservedContent(Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION), mInstance);}
        return mInstance;
    }

    public AutoRotateTile(Context context, LayoutInflater inflater,
            QuickSettingsContainerView container, QuickSettingsController qsc, Handler handler) {
        super(context, inflater, container, qsc);

        mOnClick = new OnClickListener() {
            @Override
            public void onClick(View v) {
                RotationPolicy.setRotationLock(mContext, getAutoRotation());
            }
        };

        mOnLongClick = new OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(
                        "com.android.settings",
                        "com.android.settings.Settings$ASSDisplayActivity"));
                startSettingsActivity(intent);
                return true;
            }
        };
        qsc.registerObservedContent(Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION)
                , this);
    }

    void applyAutoRotationChanges() {
        if(!getAutoRotation()){
            mDrawable = R.drawable.ic_qs_rotation_locked;
            mLabel = mContext.getString(R.string.quick_settings_rotation_locked_label);
        }else{
            mDrawable = R.drawable.ic_qs_auto_rotate;
            mLabel = mContext.getString(R.string.quick_settings_rotation_unlocked_label);
        }
        updateQuickSettings();
    }

    @Override
    void onPostCreate() {
        applyAutoRotationChanges();
        super.onPostCreate();
    }

    private boolean getAutoRotation() {
        return !RotationPolicy.isRotationLocked(mContext);
    }

    @Override
    public void onChangeUri(ContentResolver resolver, Uri uri) {
        applyAutoRotationChanges();
    }
}
