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

import android.app.AlarmManager;
import android.app.PendingIntent;
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
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.Vibrator;
import android.provider.Settings;
import android.provider.Settings.Secure;
import android.service.dreams.DreamService;
import android.util.Log;
import android.view.Display;

import com.android.systemui.R;
import com.android.systemui.SystemUIApplication;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.DozeParameters.PulseSchedule;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Date;

import org.slim.provider.SlimSettings;

public class DozeService extends DreamService {
    private static final String TAG = "DozeService";
//    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final boolean DEBUG = true;

    private static final String ACTION_BASE = "com.android.systemui.doze";
    private static final String PULSE_ACTION = ACTION_BASE + ".pulse";
    private static final String NOTIFICATION_PULSE_ACTION = ACTION_BASE + ".notification_pulse";
    private static final String EXTRA_INSTANCE = "instance";
    private static final int POCKET_DELTA_NS = 1000 * 1000 * 1000;

    /**
     * Earliest time we pulse due to a notification light after the service started.
     *
     * <p>Incoming notification light events during the blackout period are
     * delayed to the earliest time defined by this constant.</p>
     *
     * <p>This delay avoids a pulse immediately after screen off, at which
     * point the notification light is re-enabled again by NoMan.</p>
     */
    private static final int EARLIEST_LIGHT_PULSE_AFTER_START_MS = 10 * 1000;

    private final String mTag = String.format(TAG + ".%08x", hashCode());
    private final Context mContext = this;
    private final DozeParameters mDozeParameters = new DozeParameters(mContext);
    private final Handler mHandler = new Handler();

    private DozeHost mHost;
    protected SensorManager mSensors;
    private TriggerSensor mSigMotionSensor;
    private TriggerSensor mHandWaveSensor;
    private TriggerSensor mPocketSensor;
    private TriggerSensor mPickupSensor;
    private PowerManager mPowerManager;
    private PowerManager.WakeLock mWakeLock;
    private AlarmManager mAlarmManager;
    private UiModeManager mUiModeManager;
    private boolean mDreaming;
    private boolean mPulsing;
    private boolean mBroadcastReceiverRegistered;
    private boolean mDisplayStateSupported;
    private boolean mNotificationLightOn;
    private boolean mPowerSaveActive;
    private boolean mCarMode;
    private long mNotificationPulseTime;
    private long mLastScheduleResetTime;
    private long mEarliestPulseDueToLight;
    private int mScheduleResetsRemaining;

    private boolean mDozeTriggerPickup;
    private boolean mDozeTriggerSigmotion;
    private boolean mDozeTriggerNotification;
    private boolean mDozeSchedule;

    private PulseSchedule mSchedule = null;

    protected boolean mDozeTriggerHandWave;
    protected boolean mDozeTriggerPocket;
    protected boolean isHandWave;
    protected boolean isPocket;
    private static final String DOZE_INTENT = "com.android.systemui.doze.pulse";

    public DozeService() {
        if (DEBUG) Log.d(mTag, "new DozeService()");
        setDebug(DEBUG);
    }

    @Override
    protected void dumpOnHandler(FileDescriptor fd, PrintWriter pw, String[] args) {
        super.dumpOnHandler(fd, pw, args);
        pw.print("  mDreaming: "); pw.println(mDreaming);
        pw.print("  mPulsing: "); pw.println(mPulsing);
        pw.print("  mWakeLock: held="); pw.println(mWakeLock.isHeld());
        pw.print("  mHost: "); pw.println(mHost);
        pw.print("  mBroadcastReceiverRegistered: "); pw.println(mBroadcastReceiverRegistered);
        pw.print("  mSigMotionSensor: "); pw.println(mSigMotionSensor);
        pw.print("  mHandWaveSensor: "); pw.println(mHandWaveSensor);
        pw.print("  mPocketSensor: "); pw.println(mPocketSensor);
        pw.print("  mPickupSensor:"); pw.println(mPickupSensor);
        pw.print("  mDisplayStateSupported: "); pw.println(mDisplayStateSupported);
        pw.print("  mNotificationLightOn: "); pw.println(mNotificationLightOn);
        pw.print("  mPowerSaveActive: "); pw.println(mPowerSaveActive);
        pw.print("  mCarMode: "); pw.println(mCarMode);
        pw.print("  mNotificationPulseTime: "); pw.println(mNotificationPulseTime);
        pw.print("  mScheduleResetsRemaining: "); pw.println(mScheduleResetsRemaining);
        mDozeParameters.dump(pw);
    }

    @Override
    public void onCreate() {
        if (DEBUG) Log.d(mTag, "onCreate");
        super.onCreate();

        if (getApplication() instanceof SystemUIApplication) {
            final SystemUIApplication app = (SystemUIApplication) getApplication();
            mHost = app.getComponent(DozeHost.class);
        }
        if (mHost == null) Log.w(TAG, "No doze service host found.");

        setWindowless(true);

        mSensors = (SensorManager) mContext.getSystemService(Context.SENSOR_SERVICE);
        mSigMotionSensor = new TriggerSensor(Sensor.TYPE_SIGNIFICANT_MOTION,
                mDozeParameters.getPulseOnSigMotion(), mDozeParameters.getVibrateOnSigMotion(),
                DozeLog.PULSE_REASON_SENSOR_SIGMOTION);

        mHandWaveSensor = new TriggerSensor(Sensor.TYPE_PROXIMITY,
                mDozeParameters.getPulseOnHandWave(), false,
                DozeLog.PULSE_REASON_SENSOR_HAND_WAVE);

        mPocketSensor = new TriggerSensor(Sensor.TYPE_PROXIMITY,
                mDozeParameters.getPulseOnPocket(), false,
                DozeLog.PULSE_REASON_SENSOR_POCKET);

        IntentFilter screenStateFilter = new IntentFilter();
        screenStateFilter.addAction(Intent.ACTION_SCREEN_ON);
        screenStateFilter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(mScreenStateReceiver, screenStateFilter);

        mPickupSensor = new TriggerSensor(Sensor.TYPE_PICK_UP_GESTURE,
                mDozeParameters.getPulseOnPickup(), mDozeParameters.getVibrateOnPickup(),
                DozeLog.PULSE_REASON_SENSOR_PICKUP);

        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        mWakeLock.setReferenceCounted(true);
        mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        mDisplayStateSupported = mDozeParameters.getDisplayStateSupported();
        mUiModeManager = (UiModeManager) mContext.getSystemService(Context.UI_MODE_SERVICE);
        turnDisplayOff();
    }

    @Override
    public void onAttachedToWindow() {
        if (DEBUG) Log.d(mTag, "onAttachedToWindow()");
        super.onAttachedToWindow();
    }

    @Override
    public void onDreamingStarted() {
        if (DEBUG) Log.d(mTag, "onDreamingStarted()");
        super.onDreamingStarted();

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

        // Settings observer
        SettingsObserver observer = new SettingsObserver(mHandler);
        observer.observe();

        mDreaming = true;
        rescheduleNotificationPulse(false /*predicate*/);  // cancel any pending pulse alarms
        mEarliestPulseDueToLight = System.currentTimeMillis() + EARLIEST_LIGHT_PULSE_AFTER_START_MS;
        listenForPulseSignals(true);

        // Ask the host to get things ready to start dozing.
        // Once ready, we call startDozing() at which point the CPU may suspend
        // and we will need to acquire a wakelock to do work.
        mHost.startDozing(new Runnable() {
            @Override
            public void run() {
                if (mDreaming) {
                    startDozing();

                    // From this point until onDreamingStopped we will need to hold a
                    // wakelock whenever we are doing work.  Note that we never call
                    // stopDozing because can we just keep dozing until the bitter end.
                }
            }
        });
    }

    @Override
    public void onDreamingStopped() {
        if (DEBUG) Log.d(mTag, "onDreamingStopped isDozing=" + isDozing());
        super.onDreamingStopped();

        if (mHost == null) {
            return;
        }

        mDreaming = false;
        listenForPulseSignals(false);

        // Tell the host that it's over.
        mHost.stopDozing();

    }

    private void requestPulse(final int reason) {
        if (DEBUG) Log.d(TAG, "requestPulse(), reason=" + reason);
        if (mHost != null && mDreaming && !mPulsing) {
            // Let the host know we want to pulse.  Wait for it to be ready, then
            // turn the screen on.  When finished, turn the screen off again.
            // Here we need a wakelock to stay awake until the pulse is finished.
            mWakeLock.acquire();
            mPulsing = true;
            if (!mDozeParameters.getProxCheckBeforePulse()) {
                // skip proximity check
                continuePulsing(reason);
                return;
            }
            final long start = SystemClock.uptimeMillis();
            final boolean nonBlocking = reason == DozeLog.PULSE_REASON_SENSOR_PICKUP
                        && mDozeParameters.getPickupPerformsProxCheck();
                        if (DEBUG && nonBlocking) Log.d(TAG, "requestPulse(), nonBlocking");
            isHandWave = reason == DozeLog.PULSE_REASON_SENSOR_HAND_WAVE
                        && mDozeParameters.getPulseOnHandWave();
                        if (DEBUG && isHandWave) Log.d(TAG, "requestPulse(), isHandWave");
            isPocket = reason == DozeLog.PULSE_REASON_SENSOR_POCKET
                        && mDozeParameters.getPulseOnPocket();
                        if (DEBUG && isPocket) Log.d(TAG, "requestPulse(), isPocket");

            if (nonBlocking || isHandWave || isPocket) {
                // proximity check is only done to capture statistics, continue pulsing
                if (DEBUG) Log.d(TAG, "requestPulse(), nonBlocking||isHandWave||isPocket, continuePulsing()");
                continuePulsing(reason);
            }
            // perform a proximity check
            new ProximityCheck() {
                @Override
                public void onProximityResult(int result) {
                    final boolean isNear = result == RESULT_NEAR;
                    final long end = SystemClock.uptimeMillis();
                    DozeLog.traceProximityResult(mContext, isNear, end - start, reason);
                    if (DEBUG) Log.d(TAG, "requestPulse(), ProximityCheck()");
                    if (nonBlocking) {
                        // we already continued
                        return;
                    }
                    // avoid pulsing in pockets
                    if (isNear) {
                        if (DEBUG) Log.d(TAG, "requestPulse(), ProximityCheck(), isNear");
                        mPulsing = false;
                        mWakeLock.release();
                        return;
                    }

                    // not in-pocket, continue pulsing
                    if (DEBUG) Log.d(TAG, "requestPulse(), ProximityCheck(), not in pocket, continuePulsing() reason=" + reason);
                    continuePulsing(reason);
                }
            }.check();
        }
    }

    private void continuePulsing(int reason) {
        if (mHost.isPulsingBlocked()) {
            if (DEBUG) Log.d(TAG, "continuePusling() mHost.isPulsingBlocked()=" + mHost.isPulsingBlocked());
            mPulsing = false;
            mWakeLock.release();
            return;
        }
        mHost.pulseWhileDozing(new DozeHost.PulseCallback() {
            @Override
            public void onPulseStarted() {
                if (DEBUG) Log.d(TAG, "continuePulsing() onPulseStarted()");
                if (mPulsing && mDreaming) {
                    turnDisplayOn();
                }
            }

            @Override
            public void onPulseFinished() {
                if (DEBUG) Log.d(TAG, "continuePulsing() onPulseFinished()");
                if (mPulsing && mDreaming) {
                    mPulsing = false;
                    turnDisplayOff();
                }
                mWakeLock.release(); // needs to be unconditional to balance acquire
            }
        }, reason);
        if (DEBUG) Log.d(TAG, "continuePulsing() mPulsing=" + mPulsing + " mDreaming=" + mDreaming);
    }

    private void turnDisplayOff() {
        if (DEBUG) Log.d(mTag, "Display off");
        setDozeScreenState(Display.STATE_OFF);
    }

    private void turnDisplayOn() {
        if (DEBUG) Log.d(mTag, "Display on");
        setDozeScreenState(mDisplayStateSupported ? Display.STATE_DOZE : Display.STATE_ON);
    }

    private BroadcastReceiver mScreenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                turnDisplayOff();
            } else if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                turnDisplayOn();
            }
        }
    };

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
        if (mDozeTriggerPickup) {
            mPickupSensor.setListening(listen);
        }
        if (mDozeTriggerSigmotion) {
            mSigMotionSensor.setListening(listen);
        }        
        if (mDozeTriggerHandWave) {
            mHandWaveSensor.setListening(listen);
        }
        if (mDozeTriggerPocket) {
            mPocketSensor.setListening(listen);
        }
        listenForBroadcasts(listen);
        if (mDozeTriggerNotification) {
            listenForNotifications(listen);
        }
    }

    private void listenForBroadcasts(boolean listen) {
        if (listen) {
            final IntentFilter filter = new IntentFilter(PULSE_ACTION);
            filter.addAction(NOTIFICATION_PULSE_ACTION);
            filter.addAction(UiModeManager.ACTION_ENTER_CAR_MODE);
            mContext.registerReceiver(mBroadcastReceiver, filter);
            mBroadcastReceiverRegistered = true;
        } else {
            if (mBroadcastReceiverRegistered) {
                mContext.unregisterReceiver(mBroadcastReceiver);
            }
            mBroadcastReceiverRegistered = false;
        }
    }

    private void listenForNotifications(boolean listen) {
        if (listen) {
            resetNotificationResets();
            mHost.addCallback(mHostCallback);

            // Continue to pulse for existing LEDs.
            mNotificationLightOn = mHost.isNotificationLightOn();
            if (mNotificationLightOn) {
                updateNotificationPulseDueToLight();
            }
        } else {
            mHost.removeCallback(mHostCallback);
        }
    }

    private void resetNotificationResets() {
        if (DEBUG) Log.d(mTag, "resetNotificationResets");
        mScheduleResetsRemaining = mDozeParameters.getPulseScheduleResets();
    }

    private void updateNotificationPulseDueToLight() {
        long timeMs = System.currentTimeMillis();
        timeMs = Math.max(timeMs, mEarliestPulseDueToLight);
        updateNotificationPulse(timeMs);
    }

    private void updateNotificationPulse(long notificationTimeMs) {
        if (DEBUG) Log.d(mTag, "updateNotificationPulse notificationTimeMs=" + notificationTimeMs);
        if (!mDozeParameters.getPulseOnNotifications()) return;
        if (mScheduleResetsRemaining <= 0) {
            if (DEBUG) Log.d(mTag, "No more schedule resets remaining");
            return;
        }
        final long pulseDuration = mDozeParameters.getPulseDuration(false /*pickup*/);
        boolean pulseImmediately = System.currentTimeMillis() >= notificationTimeMs;
        if ((notificationTimeMs - mLastScheduleResetTime) >= pulseDuration) {
            mScheduleResetsRemaining--;
            mLastScheduleResetTime = notificationTimeMs;
        } else if (!pulseImmediately){
            if (DEBUG) Log.d(mTag, "Recently updated, not resetting schedule");
            return;
        }
        if (DEBUG) Log.d(mTag, "mScheduleResetsRemaining = " + mScheduleResetsRemaining);
        mNotificationPulseTime = notificationTimeMs;
        if (pulseImmediately) {
            DozeLog.traceNotificationPulse(0);
            requestPulse(DozeLog.PULSE_REASON_NOTIFICATION);
        }
        // schedule the rest of the pulses
        rescheduleNotificationPulse(true /*predicate*/);
    }

    private PendingIntent notificationPulseIntent(long instance) {
        return PendingIntent.getBroadcast(mContext, 0,
                new Intent(NOTIFICATION_PULSE_ACTION)
                        .setPackage(getPackageName())
                        .putExtra(EXTRA_INSTANCE, instance)
                        .setFlags(Intent.FLAG_RECEIVER_FOREGROUND),
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void rescheduleNotificationPulse(boolean predicate) {
        if (DEBUG) Log.d(mTag, "rescheduleNotificationPulse predicate=" + predicate);
        final PendingIntent notificationPulseIntent = notificationPulseIntent(0);
        mAlarmManager.cancel(notificationPulseIntent);
        if (!predicate) {
            if (DEBUG) Log.d(mTag, "  don't reschedule: predicate is false");
            return;
        }
        if (mSchedule == null) {
            if (DEBUG) Log.d(mTag, "  don't reschedule: mSchedule is null");
            return;
        }
        final long now = System.currentTimeMillis();
        final long time = mSchedule.getNextTime(now, mNotificationPulseTime);
        if (time <= 0) {
            if (DEBUG) Log.d(mTag, "  don't reschedule: time is " + time);
            return;
        }
        final long delta = time - now;
        if (delta <= 0) {
            if (DEBUG) Log.d(mTag, "  don't reschedule: delta is " + delta);
            return;
        }
        final long instance = time - mNotificationPulseTime;
        if (DEBUG) Log.d(mTag, "Scheduling pulse " + instance + " in " + delta + "ms for "
                + new Date(time));
        mAlarmManager.setExact(AlarmManager.RTC_WAKEUP, time, notificationPulseIntent(instance));
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
            if (NOTIFICATION_PULSE_ACTION.equals(intent.getAction())) {
                final long instance = intent.getLongExtra(EXTRA_INSTANCE, -1);
                if (DEBUG) Log.d(mTag, "Received notification pulse intent instance=" + instance);
                DozeLog.traceNotificationPulse(instance);
                requestPulse(DozeLog.PULSE_REASON_NOTIFICATION);
                rescheduleNotificationPulse(mNotificationLightOn);
            }
            if (UiModeManager.ACTION_ENTER_CAR_MODE.equals(intent.getAction())) {
                mCarMode = true;
                if (mCarMode && mDreaming) {
                    finishForCarMode();
                }
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
            updateNotificationPulse(System.currentTimeMillis());
        }

        @Override
        public void onNotificationLight(boolean on) {
            if (DEBUG) Log.d(mTag, "onNotificationLight on=" + on);
            if (mNotificationLightOn == on) return;
            mNotificationLightOn = on;
            if (mNotificationLightOn) {
                updateNotificationPulseDueToLight();
            }
        }

        @Override
        public void onPowerSaveChanged(boolean active) {
            mPowerSaveActive = active;
            if (mPowerSaveActive && mDreaming) {
                finishToSavePower();
            }
        }
    };


    /**
     * Settingsobserver to take care of the user settings.
     */
    private class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(SlimSettings.System.getUriFor(
                    SlimSettings.System.DOZE_TRIGGER_PICKUP),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(SlimSettings.System.getUriFor(
                    SlimSettings.System.DOZE_TRIGGER_SIGMOTION),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(SlimSettings.System.getUriFor(
                    SlimSettings.System.DOZE_TRIGGER_HAND_WAVE),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(SlimSettings.System.getUriFor(
                    SlimSettings.System.DOZE_TRIGGER_POCKET),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(SlimSettings.System.getUriFor(
                    SlimSettings.System.DOZE_TRIGGER_NOTIFICATION),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(SlimSettings.System.getUriFor(
                    SlimSettings.System.DOZE_SCHEDULE),
                    false, this, UserHandle.USER_ALL);
            update();
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            update();
        }

        public void update() {
            ContentResolver resolver = mContext.getContentResolver();

            // Get preferences
            mDozeTriggerPickup = (SlimSettings.System.getIntForUser(resolver,
                    SlimSettings.System.DOZE_TRIGGER_PICKUP,
                    mContext.getResources().getBoolean(
                    R.bool.doze_pulse_on_pick_up) ? 1 : 0,
                    UserHandle.USER_CURRENT) == 1);
            mDozeTriggerSigmotion = (SlimSettings.System.getIntForUser(resolver,
                    SlimSettings.System.DOZE_TRIGGER_SIGMOTION,
                    mContext.getResources().getBoolean(
                    R.bool.doze_pulse_on_significant_motion) ? 1 : 0,
                    UserHandle.USER_CURRENT) == 1);
            mDozeTriggerHandWave = (SlimSettings.System.getIntForUser(resolver,
                    SlimSettings.System.DOZE_TRIGGER_HAND_WAVE,
                    mContext.getResources().getBoolean(
                    R.bool.doze_pulse_on_hand_wave) ? 1 : 0,
                    UserHandle.USER_CURRENT) == 1);
            mDozeTriggerPocket = (SlimSettings.System.getIntForUser(resolver,
                    SlimSettings.System.DOZE_TRIGGER_POCKET,
                    mContext.getResources().getBoolean(
                    R.bool.doze_pulse_on_pocket) ? 1 : 0,
                    UserHandle.USER_CURRENT) == 1);
            mDozeTriggerNotification = (SlimSettings.System.getIntForUser(resolver,
                    SlimSettings.System.DOZE_TRIGGER_NOTIFICATION, 1,
                    UserHandle.USER_CURRENT) == 1);
            mDozeSchedule = (SlimSettings.System.getIntForUser(resolver,
                    SlimSettings.System.DOZE_SCHEDULE, 1,
                    UserHandle.USER_CURRENT) == 1);

            updateDozeSchedule();
        }

        private void updateDozeSchedule() {
            if (mDozeSchedule) {
                mSchedule = mDozeParameters.getPulseSchedule();
            } else {
                mSchedule = mDozeParameters.getAlternatePulseSchedule();
            }
        }
    }

    private class TriggerSensor extends TriggerEventListener {
        private final Sensor mSensor;
        private final boolean mConfigured;
        private final boolean mDebugVibrate;
        private final int mPulseReason;

        private boolean mRequested;
        private boolean mRegistered;
        private boolean mDisabled;

        public TriggerSensor(int type, boolean configured, boolean debugVibrate, int pulseReason) {
            mSensor = mSensors.getDefaultSensor(type);
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

        private void updateListener() {
            if (!mConfigured || mSensor == null) return;
            if (mRequested && !mDisabled && !mRegistered) {
                mRegistered = mSensors.requestTriggerSensor(this, mSensor);
                if (DEBUG) Log.d(mTag, "requestTriggerSensor " + mRegistered);
            } else if (mRegistered) {
                final boolean rt = mSensors.cancelTriggerSensor(this, mSensor);
                if (DEBUG) Log.d(mTag, "cancelTriggerSensor " + rt);
                mRegistered = false;
            }
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
                if (mDebugVibrate) {
                    final Vibrator v = (Vibrator) mContext.getSystemService(
                            Context.VIBRATOR_SERVICE);
                    if (v != null) {
                        v.vibrate(1000, new AudioAttributes.Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build());
                    }
                }

                requestPulse(mPulseReason);
                mRegistered = false;
                updateListener();  // reregister, this sensor only fires once

                // reset the notification pulse schedule, but only if we think we were not triggered
                // by a notification-related vibration
                final long timeSinceNotification = System.currentTimeMillis()
                        - mNotificationPulseTime;
                final boolean withinVibrationThreshold =
                        timeSinceNotification < mDozeParameters.getPickupVibrationThreshold();
                if (withinVibrationThreshold) {
                   if (DEBUG) Log.d(mTag, "Not resetting schedule, recent notification");
                } else {
                    resetNotificationResets();
                }
                if (mSensor.getType() == Sensor.TYPE_PICK_UP_GESTURE) {
                    DozeLog.tracePickupPulse(withinVibrationThreshold);
                }
            } finally {
                mWakeLock.release();
            }
        }
    }

    private abstract class ProximityCheck implements SensorEventListener, Runnable {
        private static final int TIMEOUT_DELAY_MS = 500;

        protected static final int RESULT_UNKNOWN = 0;
        protected static final int RESULT_NEAR = 1;
        protected static final int RESULT_FAR = 2;

        private final String mTag = DozeService.this.mTag + ".ProximityCheck";

        private boolean mRegistered;
        private boolean mFinished;
        private boolean mSawNear = false;
        private long mInPocketTime = 0;
        private float mMaxRange;
        private Sensor mSensor;

        abstract public void onProximityResult(int result);

        public void check() {
            if (mFinished || mRegistered) return;
            final Sensor sensor = mSensors.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            if (sensor == null) {
                if (DEBUG) Log.d(mTag, "No sensor found");
                finishWithResult(RESULT_UNKNOWN);
                return;
            }
            // the pickup sensor interferes with the prox event, disable it until we have a result
            mPickupSensor.setDisabled(true);

            mMaxRange = sensor.getMaximumRange();
            mSensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, 0, mHandler);
            mHandler.postDelayed(this, TIMEOUT_DELAY_MS);
            mRegistered = true;
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.values.length == 0) {
                if (DEBUG) Log.d(mTag, "Event has no values!");
                finishWithResult(RESULT_UNKNOWN);
            } else {
                if (DEBUG) Log.d(mTag, "Event: value=" + event.values[0] + " max=" + mMaxRange);
                final boolean isNear = event.values[0] < mMaxRange;
                finishWithResult(isNear ? RESULT_NEAR : RESULT_FAR);

                if (mSawNear && !isNear) {
                    if (shouldPulse(event.timestamp)) {
                        launchDozePulse();
                    }
                } else {
                    mInPocketTime = event.timestamp;
                }
                mSawNear = isNear;
            }
        }

        private boolean shouldPulse(long timestamp) {
            long delta = timestamp - mInPocketTime;

            mDozeTriggerHandWave = SlimSettings.System.getInt(getContentResolver(),
                SlimSettings.System.DOZE_TRIGGER_HAND_WAVE, 0) == 1;
            if (DEBUG) Log.d(mTag, "shouldPulse() mDozeTriggerHandWave: " + mDozeTriggerHandWave);

            mDozeTriggerPocket = SlimSettings.System.getInt(getContentResolver(),
                SlimSettings.System.DOZE_TRIGGER_POCKET, 0) == 1;
            if (DEBUG) Log.d(mTag, "shouldPulse() mDozeTriggerPocket: " + mDozeTriggerPocket);

            if (mDozeTriggerHandWave && mDozeTriggerPocket) {
                return true;
            } else if (mDozeTriggerHandWave && !mDozeTriggerPocket) {
                return delta < POCKET_DELTA_NS;
            } else if (!mDozeTriggerHandWave && mDozeTriggerPocket) {
                return delta >= POCKET_DELTA_NS;
            }
            return false;
        }

        public void enable() {
            if (mDozeTriggerHandWave || mDozeTriggerPocket) {
                mSensors.registerListener(this, mSensor, SensorManager.SENSOR_DELAY_NORMAL);
            }
        }

        public void disable() {
            mSensors.unregisterListener(this, mSensor);
            // Unregister the screen state receiver
            // mContext.unregisterReceiver(mScreenStateReceiver);
        }

        private void launchDozePulse() {
            if (DEBUG) Log.d(mTag, "launchDozePulse() called");
            if (isHandWave) {
                if (DEBUG) Log.d(mTag, "launchDozePulse() isHandWave");
                requestPulse(DozeLog.PULSE_REASON_SENSOR_HAND_WAVE);
            } else if (isPocket) {
                if (DEBUG) Log.d(mTag, "launchDozePulse() isPocket");
                requestPulse(DozeLog.PULSE_REASON_SENSOR_POCKET);
            }
        }

        private boolean isDozeEnabled() {
            int value = Settings.Secure.getInt(mContext.getContentResolver(), Settings.Secure.DOZE_ENABLED, 1);
            return value != 0;
        }

        private void onDisplayOn() {
            if (DEBUG) Log.d(TAG, "Display on");
            disable();
            if (isDozeEnabled() && !mWakeLock.isHeld()) {
                if (DEBUG) Log.d(TAG, "Acquiring wakelock");
                mWakeLock.acquire();
            }
        }

        private void onDisplayOff() {
            if (DEBUG) Log.d(TAG, "Display off");
            if (isDozeEnabled()) {
                enable();
        }

        if (mWakeLock.isHeld()) {
            if (DEBUG) Log.d(TAG, "Releasing wakelock");
            mWakeLock.release();
        }

}
        @Override
        public void run() {
            if (DEBUG) Log.d(mTag, "No event received before timeout");
            finishWithResult(RESULT_UNKNOWN);
        }

        private void finishWithResult(int result) {
            if (mFinished) return;
            if (mRegistered) {
                mHandler.removeCallbacks(this);
                mSensors.unregisterListener(this);
                // we're done - reenable the pickup sensor
                mPickupSensor.setDisabled(false);
                mRegistered = false;
            }
            onProximityResult(result);
            mFinished = true;
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // noop
        }
    }
}
