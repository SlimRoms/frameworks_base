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
import android.media.AudioManager;
import android.view.KeyEvent;

public class MediaPlayPauseButton extends MediaKeyEventButton {
    public MediaPlayPauseButton() { mType = BUTTON_MEDIA_PLAY_PAUSE; }

    private static final int MEDIA_STATE_UNKNOWN  = -1;
    private static final int MEDIA_STATE_INACTIVE =  0;
    private static final int MEDIA_STATE_ACTIVE   =  1;

    private int mCurrentState = MEDIA_STATE_UNKNOWN;

    @Override
    protected void updateState(Context context) {
        mState = STATE_DISABLED;
        if (isMusicActive(context)) {
            mIcon = R.drawable.stat_media_pause;
        } else {
            mIcon = R.drawable.stat_media_play;
        }
    }

    @Override
    protected void toggleState(Context context) {
        sendMediaKeyEvent(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);

        mCurrentState = (isMusicActive(context) ? MEDIA_STATE_INACTIVE : MEDIA_STATE_ACTIVE);

        update(context);
    }

    @Override
    protected boolean handleLongClick(Context context) {
        return false;
    }

    private boolean isMusicActive(Context context) {
        if (mCurrentState == MEDIA_STATE_UNKNOWN) {
            mCurrentState = MEDIA_STATE_INACTIVE;
            AudioManager am = getAudioManager(context);
            if (am != null) {
                mCurrentState = (am.isMusicActive() ? MEDIA_STATE_ACTIVE : MEDIA_STATE_INACTIVE);
            }

            return (mCurrentState == MEDIA_STATE_ACTIVE);
        } else {
            boolean active = (mCurrentState == MEDIA_STATE_ACTIVE);
            mCurrentState = MEDIA_STATE_UNKNOWN;
            return active;
        }
    }
}
