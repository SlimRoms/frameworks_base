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

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.UserHandle;
import android.provider.Settings;

import com.android.internal.util.slim.TorchConstants;
import com.android.systemui.R;

import java.util.ArrayList;
import java.util.List;

public class FlashlightButton extends PowerButton {
    private static final IntentFilter STATE_FILTER =
            new IntentFilter(TorchConstants.ACTION_STATE_CHANGED);
    private boolean mActive = false;

    public FlashlightButton() { mType = BUTTON_FLASHLIGHT; }

    @Override
    protected void updateState(Context context) {
        if (mActive) {
            mIcon = R.drawable.stat_flashlight_on;
            mState = STATE_ENABLED;
        } else {
            mIcon = R.drawable.stat_flashlight_off;
            mState = STATE_DISABLED;
        }
    }

    @Override
    protected void toggleState(Context context) {
        boolean bright = Settings.System.getIntForUser(context.getContentResolver(),
                Settings.System.EXPANDED_FLASH_MODE, 0, UserHandle.USER_CURRENT) == 1;
        Intent i = new Intent(TorchConstants.ACTION_TOGGLE_STATE);
        i.putExtra(TorchConstants.EXTRA_BRIGHT_MODE, bright);
        context.sendBroadcast(i);
    }

    @Override
    protected boolean handleLongClick(Context context) {
        Intent intent = new Intent(TorchConstants.INTENT_LAUNCH_APP);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        return true;
    }

    @Override
    protected IntentFilter getBroadcastIntentFilter() {
        return STATE_FILTER;
    }

    @Override
    protected void onReceive(Context context, Intent intent) {
        mActive = intent.getIntExtra(TorchConstants.EXTRA_CURRENT_STATE, 0) != 0;
    }
}
