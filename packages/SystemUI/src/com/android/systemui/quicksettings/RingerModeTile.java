/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Vibrator;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnLongClickListener;

import com.android.systemui.R;
import com.android.systemui.statusbar.phone.QuickSettingsController;
import com.android.systemui.statusbar.phone.QuickSettingsContainerView;

public class RingerModeTile extends QuickSettingsTile {

    private static final String SEPARATOR = "OV=I=XseparatorX=I=VO";

    // Define the available ringer modes
    private final Ringer mSilentRinger = new Ringer(AudioManager.RINGER_MODE_SILENT, false);
    private final Ringer mVibrateRinger = new Ringer(AudioManager.RINGER_MODE_VIBRATE, true);
    private final Ringer mSoundRinger = new Ringer(AudioManager.RINGER_MODE_NORMAL, false);
    private final Ringer mSoundVibrateRinger = new Ringer(AudioManager.RINGER_MODE_NORMAL, true);
    private final Ringer[] mRingers = new Ringer[] {
            mSilentRinger, mVibrateRinger, mSoundRinger, mSoundVibrateRinger
    };

    private int mRingersIndex;
    private int[] mRingerValues = new int[] {
            0, 1, 2, 3
    };
    private int mRingerValuesIndex;

    private AudioManager mAudioManager;
    public static RingerModeTile mInstance;

    public static QuickSettingsTile getInstance(Context context, LayoutInflater inflater,
            QuickSettingsContainerView container, final QuickSettingsController qsc, Handler handler, String id) {
        mInstance = null;
        mInstance = new RingerModeTile(context, inflater, container, qsc);
        return mInstance;
    }

    public RingerModeTile(Context context, LayoutInflater inflater,
            QuickSettingsContainerView container, QuickSettingsController qsc) {
        super(context, inflater, container, qsc);

        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        new Handler();

        // Load the available ringer modes
        updateSettings(mContext.getContentResolver());

        // Make sure we show the initial state correctly
        updateState();

        // Tile actions
        mOnClick = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleState();
                applyVibrationChanges();
            }
        };

        mOnLongClick = new OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                startSettingsActivity(android.provider.Settings.ACTION_SOUND_SETTINGS);
                return true;
            }
        };
        qsc.registerAction(AudioManager.RINGER_MODE_CHANGED_ACTION, this);
        qsc.registerObservedContent(Settings.System.getUriFor(Settings.System.EXPANDED_RING_MODE)
                , this);
        qsc.registerObservedContent(Settings.System.getUriFor(Settings.System.VIBRATE_WHEN_RINGING)
                , this);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.e("\r\n\r\nRingerModeTile","RINGER_MODE_CHANGED_ACTION\r\n");
        applyVibrationChanges();
    }

    @Override
    public void onChangeUri(ContentResolver resolver, Uri uri) {
        updateSettings(mContext.getContentResolver());
        applyVibrationChanges();
    }

    private void applyVibrationChanges(){
        updateState();
        updateQuickSettings();
    }

    protected void updateState() {
        // The title does not change
        mLabel = mContext.getString(R.string.quick_settings_ringer_normal);

        // The icon will change depending on index
        findCurrentState();
        switch (mRingersIndex) {
            case 0:
                mDrawable = R.drawable.ic_qs_ring_off;
                break;
            case 1:
                mDrawable = R.drawable.ic_qs_vibrate_on;
                break;
            case 2:
                mDrawable = R.drawable.ic_qs_ring_on;
                break;
            case 3:
                mDrawable = R.drawable.ic_qs_ring_vibrate_on;
                break;
        }

        for (int i = 0; i < mRingerValues.length; i++) {
            if (mRingersIndex == mRingerValues[i]) {
                mRingerValuesIndex = i;
                break;
            }
        }
    }

    protected void toggleState() {
        mRingerValuesIndex++;
        if (mRingerValuesIndex > mRingerValues.length - 1) {
            mRingerValuesIndex = 0;
        }

        mRingersIndex = mRingerValues[mRingerValuesIndex];
        if (mRingersIndex > mRingers.length - 1) {
            mRingersIndex = 0;
        }

        Ringer ringer = mRingers[mRingersIndex];
        ringer.execute(mContext);
    }

    public String[] parseStoredValue(CharSequence val) {
        if (TextUtils.isEmpty(val)) {
          return null;
        } else {
          return val.toString().split(SEPARATOR);
        }
    }

    private void updateSettings(ContentResolver resolver) {
        String[] modes = parseStoredValue(Settings.System.getString(
                resolver, Settings.System.EXPANDED_RING_MODE));
        if (modes == null || modes.length == 0) {
            mRingerValues = new int[] {
                    0, 1, 2, 3
            };
        } else {
            mRingerValues = new int[modes.length];
            for (int i = 0; i < modes.length; i++) {
                mRingerValues[i] = Integer.valueOf(modes[i]);
            }
        }
    }

    private void findCurrentState() {
        ContentResolver resolver = mContext.getContentResolver();
        boolean vibrateWhenRinging = Settings.System.getInt(resolver,
                Settings.System.VIBRATE_WHEN_RINGING, 0) == 1;
        Log.e("\r\n\r\nRingerModeTile","vibrateWhenRinging = "+vibrateWhenRinging);
        int ringerMode = mAudioManager.getRingerMode();
        Log.e("RingerModeTile","ringerMode = "+ringerMode+"\r\n");
        Ringer ringer = new Ringer(ringerMode, vibrateWhenRinging);
        for (int i = 0; i < mRingers.length; i++) {
            if (mRingers[i].equals(ringer)) {
                mRingersIndex = i;
                break;
            }
        }
    }

    private class Ringer {
        final boolean mVibrateWhenRinging;
        final int mRingerMode;

        Ringer( int ringerMode, boolean vibrateWhenRinging) {
            mVibrateWhenRinging = vibrateWhenRinging;
            mRingerMode = ringerMode;
        }

        void execute(Context context) {
            // If we are setting a vibrating state, vibrate to indicate it
            if (mVibrateWhenRinging) {
                Vibrator vibrator = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);
                vibrator.vibrate(250);
            }

            // Set the desired state
            ContentResolver resolver = context.getContentResolver();
            Settings.System.putInt(resolver, Settings.System.VIBRATE_WHEN_RINGING,
                    (mVibrateWhenRinging ? 1 : 0));
            mAudioManager.setRingerMode(mRingerMode);
        }

        @Override
        public boolean equals(Object o) {
            if (o == null) {
                return false;
            }
            if (o.getClass() != getClass()) {
                return false;
            }

            Ringer r = (Ringer) o;
            if (r.mRingerMode == AudioManager.RINGER_MODE_SILENT && this.mRingerMode == AudioManager.RINGER_MODE_SILENT) return true;
            else if (r.mRingerMode == AudioManager.RINGER_MODE_VIBRATE && this.mRingerMode == AudioManager.RINGER_MODE_VIBRATE) return true;
            else return r.mVibrateWhenRinging == mVibrateWhenRinging
                    && r.mRingerMode == mRingerMode;
        }
    }
}
