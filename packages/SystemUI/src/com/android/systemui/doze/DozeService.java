/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.doze;

import android.app.ActivityManager;
import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.service.dreams.DreamService;
import android.util.Log;

import com.android.systemui.Dependency;
import com.android.systemui.plugins.Plugin;
import com.android.systemui.plugins.PluginManager;

import com.android.internal.hardware.AmbientDisplayConfiguration;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.systemui.R;
import com.android.systemui.SystemUIApplication;
import com.android.systemui.statusbar.phone.DozeParameters;

import slim.provider.SlimSettings;

import java.io.FileDescriptor;
import java.io.PrintWriter;

public class DozeService extends DreamService implements DozeMachine.Service {
    private static final String TAG = "DozeService";
    static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private DozeMachine mDozeMachine;

    private static final String ACTION_BASE = "com.android.systemui.doze";
    private static final String PULSE_ACTION = ACTION_BASE + ".pulse";

    /**
     * If true, reregisters all trigger sensors when the screen turns off.
     */
    private static final boolean REREGISTER_ALL_SENSORS_ON_SCREEN_OFF = true;

    private final String mTag = String.format(TAG + ".%08x", hashCode());
    private final Context mContext = this;
    private final DozeParameters mDozeParameters = new DozeParameters(mContext);
    private final Handler mHandler = new Handler();

    private DozeHost mHost;
    private SensorManager mSensorManager;
    private TriggerSensor[] mSensors;
    private TriggerSensor mPickupSensor;
    private TriggerSensor mSigMotionSensor;
//    private TriggerSensor mDoubleTapSensor;
    private PowerManager mPowerManager;
    private PowerManager.WakeLock mWakeLock;
    private UiModeManager mUiModeManager;
    private boolean mDreaming;
    private boolean mPulsing;
    private boolean mBroadcastReceiverRegistered;
    private boolean mDisplayStateSupported;
    private boolean mPowerSaveActive;
    private boolean mCarMode;
    private long mNotificationPulseTime;

    private AmbientDisplayConfiguration mConfig;

    private boolean mDozeEnabled;
    private boolean mDozeTriggerPickup;
    private boolean mDozeTriggerSigmotion;
    private boolean mDozeTriggerNotification;
//    private boolean mDozeTriggerDoubleTap;

    public DozeService() {
        setDebug(DEBUG);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        setWindowless(true);

        if (DozeFactory.getHost(this) == null) {
            finish();
            return;
        }

        mSensorManager = (SensorManager) mContext.getSystemService(Context.SENSOR_SERVICE);
        mConfig = new AmbientDisplayConfiguration(mContext);
        mSensors = new TriggerSensor[] {
                mSigMotionSensor = new TriggerSensor(
                        mSensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION),
                        null /* setting */,
                        mDozeParameters.getPulseOnSigMotion(),
                        mDozeParameters.getVibrateOnSigMotion(),
                        DozeLog.PULSE_REASON_SENSOR_SIGMOTION),
                mPickupSensor = new TriggerSensor(
                        mSensorManager.getDefaultSensor(Sensor.TYPE_PICK_UP_GESTURE),
                        Settings.Secure.DOZE_PULSE_ON_PICK_UP,
                        mConfig.pulseOnPickupAvailable(), mDozeParameters.getVibrateOnPickup(),
                        DozeLog.PULSE_REASON_SENSOR_PICKUP),
//                mDoubleTapSensor = new TriggerSensor(
                new TriggerSensor(
                        findSensorWithType(mConfig.doubleTapSensorType()),
                        Settings.Secure.DOZE_PULSE_ON_DOUBLE_TAP, true,
                        mDozeParameters.getVibrateOnPickup(),
                        DozeLog.PULSE_REASON_SENSOR_DOUBLE_TAP)
        };
        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        mWakeLock.setReferenceCounted(true);
        mDisplayStateSupported = mDozeParameters.getDisplayStateSupported();
        mUiModeManager = (UiModeManager) mContext.getSystemService(Context.UI_MODE_SERVICE);
        turnDisplayOff();
    }

        mDozeMachine = new DozeFactory().assembleMachine(this);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        mDozeMachine.requestState(DozeMachine.State.INITIALIZED);
        startDozing();

        if (mHost == null) {
            finish();
            return;
        }

        mPowerSaveActive = mHost.isPowerSaveActive();
        mCarMode = mUiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_CAR;
        if (DEBUG) Log.d(mTag, "onDreamingStarted canDoze=" + canDoze() + " mPowerSaveActive="
                + mPowerSaveActive + " mCarMode=" + mCarMode);
        if (mPowerSaveActive) {
            finishToSavePower();
            return;
        }
        if (mCarMode) {
            finishForCarMode();
            return;
        }

        updateDozeSettings();

        mDreaming = true;
        listenForPulseSignals(mDozeEnabled);

        // Ask the host to get things ready to start dozing.
        // Once ready, we call startDozing() at which point the CPU may suspend
        // and we will need to acquire a wakelock to do work.
        mHost.startDozing(mWakeLock.wrap(() -> {
            if (mDreaming) {
                startDozing();

                // From this point until onDreamingStopped we will need to hold a
                // wakelock whenever we are doing work.  Note that we never call
                // stopDozing because can we just keep dozing until the bitter end.
            }
        }));
    }

    @Override
    public void onDreamingStopped() {
        super.onDreamingStopped();
        mDozeMachine.requestState(DozeMachine.State.FINISH);
    }

    @Override
    protected void dumpOnHandler(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mDozeMachine != null) {
            mDozeMachine.dump(pw);

    private void turnDisplayOn() {
        if (DEBUG) Log.d(mTag, "Display on");
        setDozeScreenState(mDisplayStateSupported ? Display.STATE_DOZE : Display.STATE_ON);
    }

    private void finishToSavePower() {
        Log.w(mTag, "Exiting ambient mode due to low power battery saver");
        finish();
    }

    private void finishForCarMode() {
        Log.w(mTag, "Exiting ambient mode, not allowed in car mode");
        finish();
    }

    private void listenForPulseSignals(boolean listen) {
        if (DEBUG) Log.d(mTag, "listenForPulseSignals: " + listen);
        for (TriggerSensor s : mSensors) {
            if ((s == mPickupSensor && !mDozeTriggerPickup) ||
                    (s == mSigMotionSensor && !mDozeTriggerSigmotion)    ) {
//                    (s == mSigMotionSensor && !mDozeTriggerSigmotion) ||
//                    (s == mDoubleTapSensor && !mDozeTriggerDoubleTap)    ) {
                s.setListening(false);
                continue;
            }
            s.setListening(listen);
        }
        listenForBroadcasts(listen);
        if (mDozeTriggerNotification) {
            listenForNotifications(listen);
        }
    }

    private void updateDozeSettings() {
        ContentResolver resolver = mContext.getContentResolver();

        // Get preferences
        mDozeEnabled = (Settings.Secure.getInt(resolver,
                Settings.Secure.DOZE_ENABLED, 1) == 1);
        mDozeTriggerPickup = (SlimSettings.System.getIntForUser(resolver,
                SlimSettings.System.DOZE_TRIGGER_PICKUP, 1,
                UserHandle.USER_CURRENT) == 1);
        mDozeTriggerSigmotion = (SlimSettings.System.getIntForUser(resolver,
                SlimSettings.System.DOZE_TRIGGER_SIGMOTION, 1,
                UserHandle.USER_CURRENT) == 1);
        mDozeTriggerNotification = (SlimSettings.System.getIntForUser(resolver,
                SlimSettings.System.DOZE_TRIGGER_NOTIFICATION, 1,
                UserHandle.USER_CURRENT) == 1);
//        mDozeTriggerDoubleTap = (SlimSettings.System.getIntForUser(resolver,
//                SlimSettings.System.DOZE_TRIGGER_DOUBLETAP, 1,
//                UserHandle.USER_CURRENT) == 1);
    }

    private void reregisterAllSensors() {
        for (TriggerSensor s : mSensors) {
            s.setListening(false);
        }
        for (TriggerSensor s : mSensors) {
            s.setListening(true);
        }
    }

    private void listenForBroadcasts(boolean listen) {
        if (listen) {
            final IntentFilter filter = new IntentFilter(PULSE_ACTION);
            filter.addAction(UiModeManager.ACTION_ENTER_CAR_MODE);
            filter.addAction(Intent.ACTION_USER_SWITCHED);
            mContext.registerReceiver(mBroadcastReceiver, filter);

            for (TriggerSensor s : mSensors) {
                if (s.mConfigured && !TextUtils.isEmpty(s.mSetting)) {
                    mContext.getContentResolver().registerContentObserver(
                            Settings.Secure.getUriFor(s.mSetting), false /* descendants */,
                            mSettingsObserver, UserHandle.USER_ALL);
                }
            }
            mBroadcastReceiverRegistered = true;
        } else {
            if (mBroadcastReceiverRegistered) {
                mContext.unregisterReceiver(mBroadcastReceiver);
                mContext.getContentResolver().unregisterContentObserver(mSettingsObserver);
            }
            mBroadcastReceiverRegistered = false;
        }
    }

    private void listenForNotifications(boolean listen) {
        if (listen) {
            mHost.addCallback(mHostCallback);
        } else {
            mHost.removeCallback(mHostCallback);
        }
    }

    private void requestNotificationPulse() {
        if (DEBUG) Log.d(mTag, "requestNotificationPulse");
        if (!mConfig.pulseOnNotificationEnabled(UserHandle.USER_CURRENT)) return;
        mNotificationPulseTime = SystemClock.elapsedRealtime();
        requestPulse(DozeLog.PULSE_REASON_NOTIFICATION);
    }

    private static String triggerEventToString(TriggerEvent event) {
        if (event == null) return null;
        final StringBuilder sb = new StringBuilder("TriggerEvent[")
                .append(event.timestamp).append(',')
                .append(event.sensor.getName());
        if (event.values != null) {
            for (int i = 0; i < event.values.length; i++) {
                sb.append(',').append(event.values[i]);
            }
        }
        return sb.append(']').toString();
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (PULSE_ACTION.equals(intent.getAction())) {
                if (DEBUG) Log.d(mTag, "Received pulse intent");
                requestPulse(DozeLog.PULSE_REASON_INTENT);
            }
            if (UiModeManager.ACTION_ENTER_CAR_MODE.equals(intent.getAction())) {
                mCarMode = true;
                if (mCarMode && mDreaming) {
                    finishForCarMode();
                }
            }
            if (Intent.ACTION_USER_SWITCHED.equals(intent.getAction())) {
                for (TriggerSensor s : mSensors) {
                    s.updateListener();
                }
            }
        }
    };

    private final ContentObserver mSettingsObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
            if (userId != ActivityManager.getCurrentUser()) {
                return;
            }
            for (TriggerSensor s : mSensors) {
                s.updateListener();
            }
        }
    };

    private final DozeHost.Callback mHostCallback = new DozeHost.Callback() {
        @Override
        public void onNewNotifications() {
            if (DEBUG) Log.d(mTag, "onNewNotifications (noop)");
            // noop for now
        }

        @Override
        public void onBuzzBeepBlinked() {
            if (DEBUG) Log.d(mTag, "onBuzzBeepBlinked");
            requestNotificationPulse();
        }

        @Override
        public void onNotificationLight(boolean on) {
            if (DEBUG) Log.d(mTag, "onNotificationLight (noop) on=" + on);
            // noop for now
        }

        @Override
        public void onPowerSaveChanged(boolean active) {
            mPowerSaveActive = active;
            if (mPowerSaveActive && mDreaming) {
                finishToSavePower();
            }
        }
    };

    private Sensor findSensorWithType(String type) {
        if (TextUtils.isEmpty(type)) {
            return null;
        }
        List<Sensor> sensorList = mSensorManager.getSensorList(Sensor.TYPE_ALL);
        for (Sensor s : sensorList) {
            if (type.equals(s.getStringType())) {
                return s;
            }
        }
        return null;
    }

    private class TriggerSensor extends TriggerEventListener {
        final Sensor mSensor;
        final boolean mConfigured;
        final boolean mDebugVibrate;
        final int mPulseReason;
        final String mSetting;

        private boolean mRequested;
        private boolean mRegistered;
        private boolean mDisabled;

        public TriggerSensor(Sensor sensor, String setting, boolean configured,
                boolean debugVibrate, int pulseReason) {
            mSensor = sensor;
            mSetting = setting;
            mConfigured = configured;
            mDebugVibrate = debugVibrate;
            mPulseReason = pulseReason;
        }

        public void setListening(boolean listen) {
            if (mRequested == listen) return;
            mRequested = listen;
            updateListener();
        }

        public void setDisabled(boolean disabled) {
            if (mDisabled == disabled) return;
            mDisabled = disabled;
            updateListener();
        }

        public void updateListener() {
            if (!mConfigured || mSensor == null) return;
            if (mRequested && !mDisabled && enabledBySetting() && !mRegistered) {
                mRegistered = mSensorManager.requestTriggerSensor(this, mSensor);
                if (DEBUG) Log.d(mTag, "requestTriggerSensor " + mRegistered);
            } else if (mRegistered) {
                final boolean rt = mSensorManager.cancelTriggerSensor(this, mSensor);
                if (DEBUG) Log.d(mTag, "cancelTriggerSensor " + rt);
                mRegistered = false;
            }
        }

        private boolean enabledBySetting() {
            if (TextUtils.isEmpty(mSetting)) {
                return true;
            }
            return Settings.Secure.getIntForUser(mContext.getContentResolver(), mSetting, 1,
                    UserHandle.USER_CURRENT) != 0;
        }

        @Override
        public String toString() {
            return new StringBuilder("{mRegistered=").append(mRegistered)
                    .append(", mRequested=").append(mRequested)
                    .append(", mDisabled=").append(mDisabled)
                    .append(", mConfigured=").append(mConfigured)
                    .append(", mDebugVibrate=").append(mDebugVibrate)
                    .append(", mSensor=").append(mSensor).append("}").toString();
        }

        @Override
        public void onTrigger(TriggerEvent event) {
            mWakeLock.acquire();
            try {
                if (DEBUG) Log.d(mTag, "onTrigger: " + triggerEventToString(event));
                boolean sensorPerformsProxCheck = false;
                if (mSensor.getType() == Sensor.TYPE_PICK_UP_GESTURE) {
                    int subType = (int) event.values[0];
                    MetricsLogger.action(mContext, MetricsEvent.ACTION_AMBIENT_GESTURE, subType);
                    sensorPerformsProxCheck = mDozeParameters.getPickupSubtypePerformsProxCheck(
                            subType);
                }
                if (mDebugVibrate) {
                    final Vibrator v = (Vibrator) mContext.getSystemService(
                            Context.VIBRATOR_SERVICE);
                    if (v != null) {
                        v.vibrate(1000, new AudioAttributes.Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build());
                    }
                }

                mRegistered = false;
                requestPulse(mPulseReason, sensorPerformsProxCheck);
                updateListener();  // reregister, this sensor only fires once

                // record pickup gesture, also keep track of whether we might have been triggered
                // by recent vibration.
                final long timeSinceNotification = SystemClock.elapsedRealtime()
                        - mNotificationPulseTime;
                final boolean withinVibrationThreshold =
                        timeSinceNotification < mDozeParameters.getPickupVibrationThreshold();
                if (mSensor.getType() == Sensor.TYPE_PICK_UP_GESTURE) {
                    DozeLog.tracePickupPulse(mContext, withinVibrationThreshold);
                }
            } finally {
                mWakeLock.release();
            }
        }
    }

    @Override
    public void requestWakeUp() {
        PowerManager pm = getSystemService(PowerManager.class);
        pm.wakeUp(SystemClock.uptimeMillis(), "com.android.systemui:NODOZE");
    }
}
