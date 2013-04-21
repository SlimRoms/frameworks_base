/*
 * Copyright (C) 2012 The Android Open Source Project
 * Copyright (C) 2013 The SlimRoms Project
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
import android.media.IAudioService;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;

import com.android.systemui.R;
import com.android.systemui.statusbar.phone.QuickSettingsContainerView;
import com.android.systemui.statusbar.phone.QuickSettingsController;

public class MusicTile extends QuickSettingsTile {
    private final String TAG = "MusicTile";

    public static MusicTile mInstance;

    private AudioManager mAM = null;
    private IAudioService mAS = null;

    private static final int MEDIA_STATE_UNKNOWN  = -1;
    private static final int MEDIA_STATE_INACTIVE =  0;
    private static final int MEDIA_STATE_ACTIVE   =  1;

    private int mCurrentState = MEDIA_STATE_UNKNOWN;

    public static QuickSettingsTile getInstance(Context context, LayoutInflater inflater,
            QuickSettingsContainerView container, final QuickSettingsController qsc,
            Handler handler, String id) {
        mInstance = null;
        mInstance = new MusicTile(context, inflater, container, qsc, handler);
        return mInstance;
    }

    private Context mContext;

    public MusicTile(Context context, LayoutInflater inflater,
            QuickSettingsContainerView container, final QuickSettingsController qsc,
            Handler handler) {
        super(context, inflater, container, qsc);

        mContext = context;

        mOnClick = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
            }
        };

        mOnLongClick = new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_NEXT);
                return true;
            }
        };
    }

    @Override
    void onPostCreate() {
        updateTile();
        super.onPostCreate();
    }

    private void updateTile() {
        if (isMusicActive()) {
            mDrawable = com.android.internal.R.drawable.ic_media_pause;
            mLabel = mContext.getString(R.string.quick_settings_music_pause);
        } else {
            mDrawable = com.android.internal.R.drawable.ic_media_play;
            mLabel = mContext.getString(R.string.quick_settings_music_play);
        }
        updateQuickSettings();
    }

    private boolean isMusicActive() {
        if (mCurrentState == MEDIA_STATE_UNKNOWN) {
            mCurrentState = MEDIA_STATE_INACTIVE;
            AudioManager am = getAudioManager();
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

    protected void sendMediaKeyEvent(int code) {
        long eventTime = SystemClock.uptimeMillis();
        KeyEvent key = new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, code, 0);
        dispatchMediaKeyWithWakeLockToAudioService(key);
        dispatchMediaKeyWithWakeLockToAudioService(KeyEvent.changeAction(key, KeyEvent.ACTION_UP));
        mCurrentState = (isMusicActive() ? MEDIA_STATE_INACTIVE : MEDIA_STATE_ACTIVE);
        updateTile();
    }

    void dispatchMediaKeyWithWakeLockToAudioService(KeyEvent event) {
        IAudioService audioService = getAudioService();
        if (audioService != null) {
            try {
                audioService.dispatchMediaKeyEventUnderWakelock(event);
            } catch (RemoteException e) {
                Log.e(TAG, "dispatchMediaKeyEvent threw exception " + e);
            }
        }
    }

    IAudioService getAudioService() {
        if (mAS == null) {
            mAS = IAudioService.Stub.asInterface(
                    ServiceManager.checkService(Context.AUDIO_SERVICE));
            if (mAS == null) {
                Log.w(TAG, "Unable to find IAudioService interface.");
            }
        }
        return mAS;
    }

    protected AudioManager getAudioManager() {
        if (mAM == null) {
            mAM = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        }
        return mAM;
    }
}
