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

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

import com.android.internal.view.RotationPolicy;
import com.android.systemui.R;

import java.util.ArrayList;
import java.util.List;

public class AutoRotateButton extends PowerButton {

    private static final String TAG = "AutoRotateButton";

    private static final List<Uri> OBSERVED_URIS = new ArrayList<Uri>();
    static {
        OBSERVED_URIS.add(Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION));
    }

    public AutoRotateButton() { mType = BUTTON_AUTOROTATE; }

    @Override
    protected void updateState(Context context) {
        if (getAutoRotation(context)) {
            mIcon = R.drawable.stat_orientation_on;
            mState = STATE_ENABLED;
        } else {
            mIcon = R.drawable.stat_orientation_off;
            mState = STATE_DISABLED;
        }
    }

    @Override
    protected void toggleState(Context context) {
        RotationPolicy.setRotationLock(context, getAutoRotation(context));
    }

    @Override
    protected boolean handleLongClick(Context context) {
        Intent intent = new Intent("android.settings.DISPLAY_SETTINGS");
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        return true;
    }

    @Override
    protected List<Uri> getObservedUris() {
        return OBSERVED_URIS;
    }

    private boolean getAutoRotation(Context context) {
        return !RotationPolicy.isRotationLocked(context);
    }

}
