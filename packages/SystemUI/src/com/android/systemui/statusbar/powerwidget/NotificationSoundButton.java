/*
 * Copyright (C) 2012 Slimroms Project
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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.provider.Settings;
import android.os.PowerManager;
import android.util.Log;

import com.android.systemui.R;

import java.util.ArrayList;
import java.util.List;

public class NotificationSoundButton extends PowerButton {

    public NotificationSoundButton() { mType = BUTTON_NOTIFICATION_SOUND; }

    private AudioManager mAudioManager;
    private int savedVol;

    @Override
    protected void updateState(Context context) {
        ensureAudioManager(context);
        findCurrentState(context);
    }

    @Override
    protected void toggleState(Context context) {
        ensureAudioManager(context);
        boolean mVolumeLinkNotification = (Settings.System.getInt(context.getContentResolver(),
                Settings.System.VOLUME_LINK_NOTIFICATION, 1) == 1);

        if (mVolumeLinkNotification) {
            Settings.System.putInt(context.getContentResolver(),
                    Settings.System.VOLUME_LINK_NOTIFICATION, 0);
        }

        int curVol = mAudioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION);

        if (mAudioManager.isStreamMute(AudioManager.STREAM_NOTIFICATION)) {
            int mutedStreams = Settings.System.getInt(context.getContentResolver(),
                    Settings.System.MODE_RINGER_STREAMS_AFFECTED, 0);
            // Remove Notifications from shared streams
            mutedStreams &= ~(1 << AudioManager.STREAM_NOTIFICATION);

            Settings.System.putInt(context.getContentResolver(),
                    Settings.System.MODE_RINGER_STREAMS_AFFECTED, mutedStreams);
            mAudioManager.setStreamMute(AudioManager.STREAM_NOTIFICATION, false);
        }

        if (curVol == 0) {
            if (savedVol == 0) {
                // There is no previous savedVol, use 1 as a default for on
                savedVol = 1;
            }
            mAudioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, savedVol, 0);
        } else {
            savedVol = curVol;
            mAudioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0);
        }
    }

    @Override
    protected boolean handleLongClick(Context context) {
        Intent intent = new Intent("android.settings.SOUND_SETTINGS");
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        return true;
    }

    private void findCurrentState(Context context) {
        int curVol = mAudioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION);

        if (curVol == 0) {
            mIcon = R.drawable.stat_notify_sound_off;
            mState = STATE_DISABLED;
        } else {
            mIcon = R.drawable.stat_notify_sound_on;
            mState = STATE_ENABLED;
        }
    }

    private void ensureAudioManager(Context context) {
        if (mAudioManager == null) {
            mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        int streamType = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1);
        if (streamType == AudioManager.STREAM_NOTIFICATION) {
                ensureAudioManager(context);
                findCurrentState(context);
        }
    }

    @Override
    protected IntentFilter getBroadcastIntentFilter() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(AudioManager.VOLUME_CHANGED_ACTION);
        return filter;
    }

}
