/*
 * Copyright (C) 2014 The TeamEos Project
 * Author: Randall Rushing aka Bigrushdog
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
 *
 * Control class for NX media fuctions. Basic logic flow inspired by
 * Roman Burg aka romanbb in his Equalizer tile produced for Cyanogenmod
 *
 */

package com.android.systemui.nx.eyecandy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import android.annotation.NonNull;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.PowerManager;

public class NxMediaController implements NxRenderer,
        MediaSessionManager.OnActiveSessionsChangedListener {
    private static final int MSG_SET_DISABLED_FLAGS = 101;
    private static final int MSG_INVALIDATE = 102;

    private Context mContext;
    private Map<MediaSession.Token, CallbackInfo> mCallbacks = new HashMap<>();
    private MediaSessionManager mMediaSessionManager;
    private NxPulse mPulse;
    private boolean mKeyguardShowing;
    private boolean mLinked;
    private boolean mIsAnythingPlaying;
    private boolean mPowerSaveModeEnabled;
    private boolean mPulseEnabled;
    private Handler mUiHandler;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (PowerManager.ACTION_POWER_SAVE_MODE_CHANGING.equals(intent.getAction())) {
                mPowerSaveModeEnabled = intent.getBooleanExtra(PowerManager.EXTRA_POWER_SAVE_MODE,
                        false);
                checkIfPlaying();
            }
        }
    };

    public NxMediaController(Context context, Handler handler) {
        mContext = context;
        mUiHandler = handler;
        mPulse = new NxPulse(context, handler);
    }

    public void setKeyguardShowing(boolean showing) {
        mKeyguardShowing = showing;
        doLinkage();
    }

    /*
     * we don't need to pass new size as args here we'll capture fresh dimens on
     * callback
     */
    public void onSizeChanged() {
        if (mLinked) {
            mPulse.setSizeChanged();
        }
    }

    public void setLeftInLandscape(boolean leftInLandscape) {
        mPulse.setLeftInLandscape(leftInLandscape);
    }

    public void setPulseEnabled(boolean enabled) {
        if (enabled == mPulseEnabled) {
            return;
        }
        mPulseEnabled = enabled;
        if (mPulseEnabled) {
            mMediaSessionManager = (MediaSessionManager)
                    mContext.getSystemService(Context.MEDIA_SESSION_SERVICE);
            mContext.registerReceiver(mReceiver,
                    new IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGING));
            PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
            mPowerSaveModeEnabled = pm.isPowerSaveMode();
            if (!mPowerSaveModeEnabled) {
                mIsAnythingPlaying = isAnythingPlayingColdCheck();
            }
            mMediaSessionManager.addOnActiveSessionsChangedListener(this, null);
        } else {
            mIsAnythingPlaying = false;
            mMediaSessionManager.removeOnActiveSessionsChangedListener(this);
            for (Map.Entry<MediaSession.Token, CallbackInfo> entry : mCallbacks.entrySet()) {
                entry.getValue().unregister();
            }
            mCallbacks.clear();
            mContext.unregisterReceiver(mReceiver);
        }
        doLinkage();
    }

    public boolean isPulseEnabled() {
        return mPulseEnabled;
    }

    public boolean shouldDrawPulse() {
        return mLinked;
    }

    @Override
    public Canvas onDrawNx(Canvas canvas) {
        return mPulse.onDrawNx(canvas);
    }

    @Override
    public void onSetNxSurface(NxSurface surface) {
        mPulse.onSetNxSurface(surface);
    }

    private void doLinkage() {
        if (mKeyguardShowing || !mPulseEnabled) {
            if (mLinked) {
                // explicitly unlink
                AsyncTask.execute(mUnlinkVisualizer);
            }
        } else {
            // no keyguard, relink if there's something playing
            if (mIsAnythingPlaying && !mLinked && mPulseEnabled) {
                AsyncTask.execute(mLinkVisualizer);
            } else if (mLinked) {
                AsyncTask.execute(mUnlinkVisualizer);
            }
        }
    }

    private final Runnable mLinkVisualizer = new Runnable() {
        @Override
        public void run() {
            if (mPulse != null) {
                if (!mLinked && !mKeyguardShowing) {
                    mPulse.start();
                    mLinked = true;
                    mUiHandler.obtainMessage(MSG_SET_DISABLED_FLAGS).sendToTarget();
                }
            }
        }
    };

    private final Runnable mUnlinkVisualizer = new Runnable() {
        @Override
        public void run() {
            if (mPulse != null) {
                if (mLinked) {
                    mPulse.stop();
                    mLinked = false;
                    mUiHandler.obtainMessage(MSG_SET_DISABLED_FLAGS).sendToTarget();
                    mUiHandler.obtainMessage(MSG_INVALIDATE).sendToTarget();
                }
            }
        }
    };

    @Override
    public void onActiveSessionsChanged(List<MediaController> controllers) {
        if (controllers != null) {
            for (MediaController controller : controllers) {
                if (!mCallbacks.containsKey(controller.getSessionToken())) {
                    mCallbacks.put(controller.getSessionToken(), new CallbackInfo(controller));
                }
            }
        }
    }

    private boolean isAnythingPlayingColdCheck() {
        List<MediaController> activeSessions = mMediaSessionManager.getActiveSessions(null);
        for (MediaController activeSession : activeSessions) {
            PlaybackState playbackState = activeSession.getPlaybackState();
            if (playbackState != null && playbackState.getState()
                        == PlaybackState.STATE_PLAYING) {
                return true;
            }
        }
        return false;
    }

    private void checkIfPlaying() {
        boolean anythingPlaying = false;
        if (!mPowerSaveModeEnabled) {
            for (Map.Entry<MediaSession.Token, CallbackInfo> entry : mCallbacks.entrySet()) {
                if (entry.getValue().isPlaying()) {
                    anythingPlaying = true;
                    break;
                }
            }
        }
        if (anythingPlaying != mIsAnythingPlaying) {
            mIsAnythingPlaying = anythingPlaying;
            doLinkage();
        }
    }

    private class CallbackInfo {
        MediaController.Callback mCallback;
        MediaController mController;
        boolean mIsPlaying;

        public CallbackInfo(final MediaController controller) {
            this.mController = controller;
            mCallback = new MediaController.Callback() {
                @Override
                public void onSessionDestroyed() {
                    destroy();
                    checkIfPlaying();
                }

                @Override
                public void onPlaybackStateChanged(@NonNull
                PlaybackState state) {
                    mIsPlaying = state.getState() == PlaybackState.STATE_PLAYING;
                    checkIfPlaying();
                }
            };
            controller.registerCallback(mCallback);
        }

        public boolean isPlaying() {
            return mIsPlaying;
        }

        public void unregister() {
            mController.unregisterCallback(mCallback);
            mIsPlaying = false;
        }

        public void destroy() {
            unregister();
            mCallbacks.remove(mController.getSessionToken());
            mController = null;
            mCallback = null;
        }
    }
}
