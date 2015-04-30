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

import android.content.Context;
import android.graphics.Canvas;
import android.media.AudioManager;
import android.media.RemoteControlClient;
import android.media.RemoteController;

public class NxMediaController implements NxRenderer {
    private Context mContext;
    private NxPulse mPulse;
    private NxSurface mSurface;
    private int mCurrentPlayState;
    private boolean mKeyguardShowing;
    private AudioManager mAudioManager;
    private RemoteController mRemoteController;
    private RemoteController.OnClientUpdateListener mRCClientUpdateListener =
            new RemoteController.OnClientUpdateListener() {
                @Override
                public void onClientChange(boolean clearing) {
                    if (clearing) {
                        updateState(RemoteControlClient.PLAYSTATE_STOPPED);
                    }
                }

                @Override
                public void onClientPlaybackStateUpdate(int state) {
                    updateState(state);
                }

                @Override
                public void onClientPlaybackStateUpdate(
                        int state, long stateChangeTimeMs, long currentPosMs, float speed) {
                    updateState(state);
                }

                @Override
                public void onClientTransportControlUpdate(int transportControlFlags) {
                    // Do nothing here
                }

                @Override
                public void onClientMetadataUpdate(RemoteController.MetadataEditor metadataEditor) {
                    // Do nothing here
                }

                @Override
                public void onClientFolderInfoBrowsedPlayer(String stringUri) {
                    // TODO Auto-generated method stub
                }

                @Override
                public void onClientUpdateNowPlayingEntries(long[] playList) {
                    // TODO Auto-generated method stub
                }

                @Override
                public void onClientNowPlayingContentChange() {
                    // TODO Auto-generated method stub
                }

                @Override
                public void onClientPlayItemResponse(boolean success) {
                    // TODO Auto-generated method stub
                }
            };

    private boolean mPulseEnabled;

    public NxMediaController(Context context) {
        mContext = context;
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        mRemoteController = new RemoteController(mContext, mRCClientUpdateListener);
        mCurrentPlayState = RemoteControlClient.PLAYSTATE_NONE;
        mPulse = new NxPulse(context);
    }

    private void startListening() {
        mAudioManager.registerRemoteController(mRemoteController);
    }

    private void stopListening() {
        mAudioManager.unregisterRemoteController(mRemoteController);
    }

    private void updateState(int newPlaybackState) {
        if (mCurrentPlayState != newPlaybackState) {
            mCurrentPlayState = newPlaybackState;
            if (mCurrentPlayState == RemoteControlClient.PLAYSTATE_PLAYING) {
                mPulse.start();
            } else {
                mPulse.stop();
            }
        }
        mSurface.updateBar();
    }

    public void setKeyguardShowing(boolean showing) {
        mKeyguardShowing = showing;
    }

    /*
     * we don't need to pass new size as args here
     * we'll capture fresh dimens on callback
     */
    public void onSizeChanged() {
        if (shouldDrawPulse()) {
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
        if (enabled) {
            startListening();
            if (mCurrentPlayState == RemoteControlClient.PLAYSTATE_PLAYING) {
                mPulse.start();
            }
        } else {
            stopListening();
            mPulse.stop();
        }
        if (mSurface != null) {
            mSurface.updateBar();
        }
    }

    public boolean isPulseEnabled() {
        return mPulseEnabled;
    }

    public boolean shouldDrawPulse() {
        return mPulseEnabled && (mCurrentPlayState == RemoteControlClient.PLAYSTATE_PLAYING)
                && !mKeyguardShowing;
    }

    @Override
    public Canvas onDrawNx(Canvas canvas) {
        return mPulse.onDrawNx(canvas);
    }

    @Override
    public void onSetNxSurface(NxSurface surface) {
        mSurface = surface;
        mPulse.onSetNxSurface(surface);
    }
}
