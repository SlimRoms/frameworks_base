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

package com.android.systemui.statusbar.powerwidget;

import com.android.systemui.R;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;
import android.os.PowerManager;


public class FChargeButton extends PowerButton {

    public static final String FAST_CHARGE_DIR = "/sys/kernel/fast_charge";
    public static final String FAST_CHARGE_FILE = "force_fast_charge";
    protected boolean on = false;

    private static final List<Uri> OBSERVED_URIS = new ArrayList<Uri>();
    static {
        OBSERVED_URIS.add(Settings.System.getUriFor(Settings.System.FCHARGE_ENABLED));
    }

    public FChargeButton() { mType = BUTTON_FCHARGE; }

    @Override
    protected void updateState(Context context) {
        on = isFastChargeOn();
        if (on) {
            mIcon = R.drawable.toggle_fcharge;
            mState = STATE_ENABLED;
        } else {
            mIcon = R.drawable.toggle_fcharge_off;
            mState = STATE_DISABLED;
        }
    }

    @Override
    protected void toggleState(Context context) {
    try {
            on = !isFastChargeOn();
            File fastcharge = new File(FAST_CHARGE_DIR, FAST_CHARGE_FILE);
            FileWriter fwriter = new FileWriter(fastcharge);
            BufferedWriter bwriter = new BufferedWriter(fwriter);
            bwriter.write(on ? "1" : "0");
            bwriter.close();
            Settings.System.putInt(mContext.getContentResolver(),
                     Settings.System.FCHARGE_ENABLED, on ? 1 : 0);
        } catch (IOException e) {
            Log.e("FChargeToggle", "Couldn't write fast_charge file");
            Settings.System.putInt(mContext.getContentResolver(),
                 Settings.System.FCHARGE_ENABLED, 0);
        }

    }

    @Override
    protected boolean handleLongClick(Context context) {
        return false;
    }

    public void checkState()
    {

    }

    public boolean isFastChargeOn() {
        try {
            File fastcharge = new File(FAST_CHARGE_DIR, FAST_CHARGE_FILE);
            FileReader reader = new FileReader(fastcharge);
            BufferedReader breader = new BufferedReader(reader);
            String line = breader.readLine();
            breader.close();
            return (line.equals("1"));
        } catch (IOException e) {
            Log.e("FChargeToggle", "Couldn't read fast_charge file");
            Settings.System.putInt(mContext.getContentResolver(),
                 Settings.System.FCHARGE_ENABLED, 0);
            return false;
        }
    }

    @Override
    protected List<Uri> getObservedUris() {
        return OBSERVED_URIS;
    }

}
