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

import android.app.ActivityManagerNative;
import android.content.Context;
import android.media.AudioManager;
import android.media.IAudioService;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

public abstract class MediaKeyEventButton extends PowerButton {
    private static final String TAG = "MediaKeyEventButton";

    private AudioManager mAM = null;
    private IAudioService mAS = null;

    protected void sendMediaKeyEvent(Context context, int code) {
        long eventtime = SystemClock.uptimeMillis();
        KeyEvent key = new KeyEvent(eventtime, eventtime, KeyEvent.ACTION_DOWN, code, 0);
        dispatchMediaKeyWithWakeLockToAudioService(key);
        dispatchMediaKeyWithWakeLockToAudioService(KeyEvent.changeAction(key, KeyEvent.ACTION_UP));
    }

    void dispatchMediaKeyWithWakeLockToAudioService(KeyEvent event) {
        if (ActivityManagerNative.isSystemReady()) {
            IAudioService audioService = getAudioService();
            if (audioService != null) {
                try {
                    audioService.dispatchMediaKeyEventUnderWakelock(event);
                } catch (RemoteException e) {
                    Log.e(TAG, "dispatchMediaKeyEvent threw exception " + e);
                }
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

    protected AudioManager getAudioManager(Context context) {
        if (mAM == null) {
            mAM = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        }

        return mAM;
    }
}
