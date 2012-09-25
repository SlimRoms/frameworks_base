package com.android.systemui.statusbar.powerwidget;

import com.android.systemui.R;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.util.Log;
import android.os.PowerManager;


public class FChargeButton extends PowerButton {

    public static final String FAST_CHARGE_DIR = "/sys/kernel/fast_charge";
    public static final String FAST_CHARGE_FILE = "force_fast_charge";
	protected boolean on = false;

    public FChargeButton() { mType = BUTTON_FCHARGE; }

    @Override
    protected void updateState(Context context) {
        if (isFastChargeOn()) {
            mIcon = R.drawable.toggle_fcharge;
            mState = STATE_ENABLED;
			on = true;
        } else {
            mIcon = R.drawable.toggle_fcharge_off;
            mState = STATE_DISABLED;
			on = false;
        }
    }

    @Override
    protected void toggleState(Context context) {
	try {
            File fastcharge = new File(FAST_CHARGE_DIR, FAST_CHARGE_FILE);
            FileWriter fwriter = new FileWriter(fastcharge);
            BufferedWriter bwriter = new BufferedWriter(fwriter);
            bwriter.write(on ? "1" : "0");
            bwriter.close();
        } catch (IOException e) {
            Log.e("FChargeToggle", "Couldn't write fast_charge file");
        }

    }
   
	@Override
    protected boolean handleLongClick(Context context) {
        return false;
    }

    public boolean isFastChargeOn() {
        try {
            File fastcharge = new File(FAST_CHARGE_DIR, FAST_CHARGE_FILE);
            FileReader reader = new FileReader(fastcharge);
            BufferedReader breader = new BufferedReader(reader);
            return (breader.readLine().equals("1"));
        } catch (IOException e) {
            Log.e("FChargeToggle", "Couldn't read fast_charge file");
            return false;
        }
    }

}