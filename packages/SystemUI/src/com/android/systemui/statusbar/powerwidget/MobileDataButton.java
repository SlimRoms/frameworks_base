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
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.provider.Settings;

import android.database.ContentObserver;
import android.content.ContentResolver;
import android.os.Handler;
import android.content.ComponentName;
import android.os.UserHandle;

import com.android.internal.telephony.TelephonyIntents;

public class MobileDataButton extends PowerButton {

    private ConnectivityManager cm;
    private Boolean mDataEnabled;

    public MobileDataButton() {
        mType = BUTTON_MOBILEDATA;
    }

    @Override
    public void afterInit(){
        if (cm == null) cm = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        mDataEnabled = Settings.Global.getInt(
               mContext.getContentResolver(), Settings.Global.MOBILE_DATA, 0) == 1;
        SettingsObserver settingsObserver = new SettingsObserver(new Handler());
        settingsObserver.observe();
    }

    @Override
    protected void updateState(Context context) {
        if (getDataState(context)) {
            mIcon = R.drawable.stat_data_on;
            mState = STATE_ENABLED;
        } else {
            mIcon = R.drawable.stat_data_off;
            mState = STATE_DISABLED;
        }
    }

    @Override
    protected void toggleState(Context context) {
        if (getDataState(context)) {
            cm.setMobileDataEnabled(false);
        } else {
            cm.setMobileDataEnabled(true);
        }
    }

    @Override
    protected boolean handleLongClick(Context context) {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(
           "com.android.settings",
           "com.android.settings.Settings$DataUsageSummaryActivity"));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        mContext.startActivityAsUser(intent, new UserHandle(UserHandle.USER_CURRENT));
        return true;
    }

    @Override
    protected IntentFilter getBroadcastIntentFilter() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(TelephonyIntents.ACTION_ANY_DATA_CONNECTION_STATE_CHANGED);
        return filter;
    }

    private boolean getDataState(Context context) {
        if (mDataEnabled == null) mDataEnabled = Settings.Global.getInt(
               mContext.getContentResolver(), Settings.Global.MOBILE_DATA, 0) == 1;
        return mDataEnabled;
    }

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.MOBILE_DATA), false, this);
        }

        @Override
        public void onChange(boolean selfChange) {
            int mUserDataEnabled = Settings.Global.getInt(
               mContext.getContentResolver(), Settings.Global.MOBILE_DATA, 0);
            if (mDataEnabled != (mUserDataEnabled == 1)) {
                mDataEnabled = (mUserDataEnabled == 1);
                update(mContext);
            }
        }
    }

}
