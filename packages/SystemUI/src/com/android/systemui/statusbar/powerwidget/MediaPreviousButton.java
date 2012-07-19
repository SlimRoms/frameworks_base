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

import com.android.systemui.R;

import android.content.Context;
import android.view.KeyEvent;

public class MediaPreviousButton extends MediaKeyEventButton {
    public MediaPreviousButton() { mType = BUTTON_MEDIA_PREVIOUS; }

    @Override
    protected void updateState(Context context) {
        mIcon = R.drawable.stat_media_previous;
        mState = STATE_DISABLED;
    }

    @Override
    protected void toggleState(Context context) {
        sendMediaKeyEvent(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
    }

    @Override
    protected boolean handleLongClick(Context context) {
        return false;
    }
}
