/*
 * Copyright (C) 2012 Slimroms Project
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
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;

import com.android.systemui.R;
import com.android.systemui.statusbar.phone.QuickSettingsContainerView;
import com.android.systemui.statusbar.phone.QuickSettingsController;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class FChargeTile extends QuickSettingsTile {

    public static FChargeTile mInstance;

    public static final String FAST_CHARGE_DIR = "/sys/kernel/fast_charge";
    public static final String FAST_CHARGE_FILE = "force_fast_charge";

    protected boolean enabled = false;

    public static QuickSettingsTile getInstance(Context context, LayoutInflater inflater,
            QuickSettingsContainerView container, final QuickSettingsController qsc, Handler handler, String id) {
        if (mInstance == null) mInstance = new FChargeTile(context, inflater, container, qsc, handler);
        else {mInstance.updateTileState(); qsc.registerObservedContent(Settings.System.getUriFor(Settings.System.FCHARGE_ENABLED), mInstance);}
        return mInstance;
    }

    public FChargeTile(Context context, LayoutInflater inflater,
            QuickSettingsContainerView container,
            QuickSettingsController qsc, Handler handler) {
        super(context, inflater, container, qsc);

        updateTileState();

        mOnClick = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                        enabled = !isFastChargeOn();
                        File fastcharge = new File(FAST_CHARGE_DIR, FAST_CHARGE_FILE);
                        FileWriter fwriter = new FileWriter(fastcharge);
                        BufferedWriter bwriter = new BufferedWriter(fwriter);
                        bwriter.write(enabled ? "1" : "0");
                        bwriter.close();
                        Settings.System.putInt(mContext.getContentResolver(),
                             Settings.System.FCHARGE_ENABLED, enabled ? 1 : 0);
                    } catch (IOException e) {
                        Log.e("FChargeToggle", "Couldn't write fast_charge file");
                        Settings.System.putInt(mContext.getContentResolver(),
                             Settings.System.FCHARGE_ENABLED, 0);
                    }
            }
        };

        mOnLongClick = new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                return true;
            }
        };

        qsc.registerObservedContent(Settings.System.getUriFor(Settings.System.FCHARGE_ENABLED), this);
    }


    public boolean isFastChargeOn() {
        try {
            File fastcharge = new File(FAST_CHARGE_DIR, FAST_CHARGE_FILE);
            FileReader reader = new FileReader(fastcharge);
            BufferedReader breader = new BufferedReader(reader);
            String line = breader.readLine();
            breader.close();
            Settings.System.putInt(mContext.getContentResolver(),
                    Settings.System.FCHARGE_ENABLED, line.equals("1") ? 1 : 0);
            return (line.equals("1"));
        } catch (IOException e) {
            Log.e("FChargeToggle", "Couldn't read fast_charge file");
            Settings.System.putInt(mContext.getContentResolver(),
                 Settings.System.FCHARGE_ENABLED, 0);
            return false;
        }
    }

    private void updateTileState() {
        enabled = isFastChargeOn();
        String label = mContext.getString(R.string.quick_settings_fcharge);

        if(enabled) {
            mDrawable = R.drawable.ic_qs_fcharge_on;
            mLabel = label;
        } else {
            mDrawable = R.drawable.ic_qs_fcharge_off;
            mLabel = label + " " + mContext.getString(R.string.quick_settings_label_disabled);
        }
    }

    @Override
    public void onChangeUri(ContentResolver resolver, Uri uri) {
        updateTileState();
        updateQuickSettings();
    }
}
