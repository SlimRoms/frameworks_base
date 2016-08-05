/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.systemui.qs.tiles;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.provider.Settings.Global;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.R;
import com.android.systemui.qs.QSTile;

/** Quick settings tile: Sound **/
public class SoundTile extends QSTile<QSTile.State> {

    private static final Intent SOUND_SETTINGS = new Intent("android.settings.SOUND_SETTINGS");

    private final AudioManager mAudioManager;

    private boolean mListening = false;

    private BroadcastReceiver mReceiver;
    private IntentFilter mFilter;

    public SoundTile(Host host) {
        super(host);
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                refreshState();
            }
        };
    }

    @Override
    protected State newTileState() {
        return new State();
    }

    @Override
    public void setListening(boolean listening) {
        if (mListening == listening) return;
        mListening = listening;
        if (listening) {
            final IntentFilter filter = new IntentFilter();
            filter.addAction(AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION);
            mContext.registerReceiver(mReceiver, filter);
        } else {
            mContext.unregisterReceiver(mReceiver);
        }
    }

    @Override
    public void handleClick() {
        updateState();
    }

    @Override
    protected void handleLongClick() {
        mHost.startActivityDismissingKeyguard(SOUND_SETTINGS);
    }

    private void updateState() {
        int oldState = mAudioManager.getRingerModeInternal();
        int newState = oldState;
        switch (oldState) {
            case AudioManager.RINGER_MODE_NORMAL:
                newState = AudioManager.RINGER_MODE_VIBRATE;
                mAudioManager.setRingerModeInternal(newState);
                break;
            case AudioManager.RINGER_MODE_VIBRATE:
                newState = AudioManager.RINGER_MODE_SILENT;
                mAudioManager.setRingerModeInternal(newState);
                break;
            case AudioManager.RINGER_MODE_SILENT:
                newState = AudioManager.RINGER_MODE_NORMAL;
                mAudioManager.setRingerModeInternal(newState);
                break;
            default:
                break;
        }
        MetricsLogger.action(mContext, getMetricsCategory(), newState);
    }

    @Override
    protected void handleUpdateState(State state, Object arg) {
        state.visible = true;
        switch (mAudioManager.getRingerModeInternal()) {
            case AudioManager.RINGER_MODE_NORMAL:
                state.icon = ResourceIcon.get(R.drawable.ic_qs_ringer_audible);
                state.label = mContext.getString(R.string.quick_settings_sound_ring);
                break;
            case AudioManager.RINGER_MODE_VIBRATE:
                state.icon = ResourceIcon.get(R.drawable.ic_qs_ringer_vibrate);
                state.label = mContext.getString(R.string.quick_settings_sound_vibrate);
                break;
            case AudioManager.RINGER_MODE_SILENT:
                state.icon = ResourceIcon.get(R.drawable.ic_qs_ringer_silent);
                state.label = mContext.getString(R.string.quick_settings_sound_silent);
                break;
            default:
                break;
        }
    }

    @Override
    public int getMetricsCategory() {
        return MetricsLogger.NOTIFICATION_APP_NOTIFICATION;
    }
}
