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

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnLongClickListener;

import com.android.systemui.R;
import com.android.systemui.statusbar.phone.QuickSettingsController;
import com.android.systemui.statusbar.phone.QuickSettingsContainerView;

public class MobileDataTile extends QuickSettingsTile {
    public static MobileDataTile mInstance;

    public static QuickSettingsTile getInstance(Context context, LayoutInflater inflater,
            QuickSettingsContainerView container, final QuickSettingsController qsc, Handler handler) {
        if (mInstance == null) mInstance = new MobileDataTile(context, inflater, container, qsc, handler);
        else {mInstance.updateTileState(); qsc.registerObservedContent(Settings.Global.getUriFor(Settings.Global.MOBILE_DATA), mInstance);}
        return mInstance;
    }

    public MobileDataTile(Context context, LayoutInflater inflater,
            QuickSettingsContainerView container,
            QuickSettingsController qsc, Handler handler) {
        super(context, inflater, container, qsc);

        mOnClick = new View.OnClickListener() {

            @Override
            public void onClick(View v) {
                ConnectivityManager conMan = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
                boolean mDataEnabled = Settings.Global.getInt(
                     mContext.getContentResolver(), Settings.Global.MOBILE_DATA, 0) == 1;
                if(mDataEnabled){
                    conMan.setMobileDataEnabled(false);
                }else{
                    conMan.setMobileDataEnabled(true);
                }
            }
        };
        mOnLongClick = new OnLongClickListener() {

            @Override
            public boolean onLongClick(View v) {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(
                        "com.android.settings",
                        "com.android.settings.Settings$DataUsageSummaryActivity"));
                startSettingsActivity(intent);
                return true;
            }
        };
        qsc.registerObservedContent(Settings.Global.getUriFor(Settings.Global.MOBILE_DATA), this);
    }

    boolean deviceSupportsTelephony() {
        PackageManager pm = mContext.getPackageManager();
        return pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY);
    }

    private void updateTileState() {
        boolean enabled = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.MOBILE_DATA, 0) == 1;
        if(enabled) {
            mLabel = mContext.getString(R.string.quick_settings_mobile_data_on);
            mDrawable = R.drawable.ic_qs_mobildata_on;
        } else {
            mLabel = mContext.getString(R.string.quick_settings_mobile_data_off);
            mDrawable = R.drawable.ic_qs_mobildata_off;
        }
    }

    @Override
    public void onChangeUri(ContentResolver resolver, Uri uri) {
        updateTileState();
        updateQuickSettings();
    }

}
