/*
 * Copyright 2016, SlimRoms Project
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

package com.android.systemui.shortcuts;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import com.android.systemui.R;

import org.slim.provider.SlimSettings;

public class ChamberOfSecrets extends Activity  {

    private static final String TAG = "ChamberOfSecrets";

    public static final int SYSTEM_INT = 1;
    public static final int SECURE_INT = 2;
    public static final int SYSTEM_LONG = 3;
    public static final int SECURE_LONG = 4;
    public static final int SYSTEM_FLOAT = 5;
    public static final int SECURE_FLOAT = 6;
    public static final int GLOBAL_INT = 7;
    public static final int GLOBAL_LONG = 8;
    public static final int GLOBAL_FLOAT = 9;

    public static final int SLIM_SYSTEM_INT = 10;
    public static final int SLIM_SECURE_INT = 11;
    public static final int SLIM_SYSTEM_LONG = 12;
    public static final int SLIM_SECURE_LONG = 13;
    public static final int SLIM_SYSTEM_FLOAT = 14;
    public static final int SLIM_SECURE_FLOAT = 15;
    public static final int SLIM_GLOBAL_INT = 16;
    public static final int SLIM_GLOBAL_LONG = 17;
    public static final int SLIM_GLOBAL_FLOAT = 18;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onResume() {
        super.onResume();
        // Check to make sure this is enabled by user
        if (SlimSettings.Secure.getIntForUser(getContentResolver(),
                SlimSettings.Secure.CHAMBER_OF_SECRETS, 0,
                UserHandle.USER_CURRENT) == 1) {
            int type = getIntent().getIntExtra("type", 0);
            String setting = getIntent().getStringExtra("setting");
            String array = getIntent().getStringExtra("array");
            int curInt = 0;
            long curLong = 0;
            float curFloat = 0;

            Log.d(TAG, "type:" + type
                    + " setting:" + setting
                    + " array:" + array);

            if (setting != null && array != null) {
                switch (type) {
                    case SYSTEM_INT:
                        curInt = Settings.System.getIntForUser(getContentResolver(),
                                setting, 0, UserHandle.USER_CURRENT);
                        Settings.System.putInt(getContentResolver(),
                                setting, getNewInt(array, curInt));
                        break;
                    case SECURE_INT:
                        curInt = Settings.Secure.getIntForUser(getContentResolver(),
                                setting, 0, UserHandle.USER_CURRENT);
                        Settings.Secure.putInt(getContentResolver(),
                                setting, getNewInt(array, curInt));
                        break;
                    case SYSTEM_LONG:
                        curLong = Settings.System.getIntForUser(getContentResolver(),
                                setting, 0, UserHandle.USER_CURRENT);
                        Settings.System.putLong(getContentResolver(),
                                setting, getNewLong(array, curLong));
                        break;
                    case SECURE_LONG:
                        curLong = Settings.Secure.getLongForUser(getContentResolver(),
                                setting, 0, UserHandle.USER_CURRENT);
                        Settings.Secure.putLong(getContentResolver(),
                                setting, getNewLong(array, curLong));
                        break;
                    case SYSTEM_FLOAT:
                        curFloat = Settings.System.getLongForUser(getContentResolver(),
                                setting, 0, UserHandle.USER_CURRENT);
                        Settings.System.putFloat(getContentResolver(),
                                setting, getNewFloat(array, curFloat));
                        break;
                    case SECURE_FLOAT:
                        curFloat = Settings.Secure.getFloatForUser(getContentResolver(),
                                setting, 0, UserHandle.USER_CURRENT);
                        Settings.Secure.putFloat(getContentResolver(),
                                setting, getNewFloat(array, curFloat));
                        break;
                    case GLOBAL_INT:
                        curInt = Settings.Global.getInt(getContentResolver(),
                                setting, 0);
                        Settings.Global.putInt(getContentResolver(),
                                setting, getNewInt(array, curInt));
                        break;
                    case GLOBAL_LONG:
                        curLong = Settings.Global.getLong(getContentResolver(),
                                setting, 0);
                        Settings.Global.putLong(getContentResolver(),
                                setting, getNewLong(array, curLong));
                        break;
                    case GLOBAL_FLOAT:
                        curFloat = Settings.Global.getFloat(getContentResolver(),
                                setting, 0);
                        Settings.Global.putFloat(getContentResolver(),
                                setting, getNewFloat(array, curFloat));
                        break;
                    case SLIM_SYSTEM_INT:
                        curInt = SlimSettings.System.getIntForUser(getContentResolver(),
                                setting, 0, UserHandle.USER_CURRENT);
                        SlimSettings.System.putInt(getContentResolver(),
                                setting, getNewInt(array, curInt));
                        break;
                    case SLIM_SECURE_INT:
                        curInt = SlimSettings.Secure.getIntForUser(getContentResolver(),
                                setting, 0, UserHandle.USER_CURRENT);
                        SlimSettings.Secure.putInt(getContentResolver(),
                                setting, getNewInt(array, curInt));
                        break;
                    case SLIM_SYSTEM_LONG:
                        curLong = SlimSettings.System.getIntForUser(getContentResolver(),
                                setting, 0, UserHandle.USER_CURRENT);
                        SlimSettings.System.putLong(getContentResolver(),
                                setting, getNewLong(array, curLong));
                        break;
                    case SLIM_SECURE_LONG:
                        curLong = SlimSettings.Secure.getLongForUser(getContentResolver(),
                                setting, 0, UserHandle.USER_CURRENT);
                        SlimSettings.Secure.putLong(getContentResolver(),
                                setting, getNewLong(array, curLong));
                        break;
                    case SLIM_SYSTEM_FLOAT:
                        curFloat = SlimSettings.System.getLongForUser(getContentResolver(),
                                setting, 0, UserHandle.USER_CURRENT);
                        SlimSettings.System.putFloat(getContentResolver(),
                                setting, getNewFloat(array, curFloat));
                        break;
                    case SLIM_SECURE_FLOAT:
                        curFloat = SlimSettings.Secure.getFloatForUser(getContentResolver(),
                                setting, 0, UserHandle.USER_CURRENT);
                        SlimSettings.Secure.putFloat(getContentResolver(),
                                setting, getNewFloat(array, curFloat));
                        break;
                    case SLIM_GLOBAL_INT:
                        curInt = SlimSettings.Global.getInt(getContentResolver(),
                                setting, 0);
                        SlimSettings.Global.putInt(getContentResolver(),
                                setting, getNewInt(array, curInt));
                        break;
                    case SLIM_GLOBAL_LONG:
                        curLong = SlimSettings.Global.getLong(getContentResolver(),
                                setting, 0);
                        SlimSettings.Global.putLong(getContentResolver(),
                                setting, getNewLong(array, curLong));
                        break;
                    case SLIM_GLOBAL_FLOAT:
                        curFloat = SlimSettings.Global.getFloat(getContentResolver(),
                                setting, 0);
                        SlimSettings.Global.putFloat(getContentResolver(),
                                setting, getNewFloat(array, curFloat));
                        break;
                }
            }
        } else {
            Toast.makeText(this,
                    R.string.chamber_disabled,
                    Toast.LENGTH_LONG).show();
        }
        this.finish();
    }

    private int getNewInt(String array, int current) {
        int index = 0;
        String[] strArray = array.split(",");
        int[] intArray = new int[strArray.length];
        for (int i = 0; i < strArray.length; i++) {
            try {
                intArray[i] = Integer.parseInt(strArray[i]);
            } catch (NumberFormatException e) {
                try {
                    intArray[i] = Color.parseColor(strArray[i]);
                } catch (IllegalArgumentException ex) {
                    // We already checked this string
                    // parse won't fail
                }
            }
        }
        for (int i = 0; i < intArray.length; i++) {
            if (intArray[i] == current) {
                if (i == intArray.length - 1) {
                    index = 0;
                } else {
                    index = i + 1;
                }
            }
        }
        return intArray[index];
    }

    private long getNewLong(String array, long current) {
        int index = 0;
        String[] strArray = array.split(",");
        long[] longArray = new long[strArray.length];
        for (int i = 0; i < strArray.length; i++) {
            try {
                longArray[i] = Long.parseLong(strArray[i]);
            } catch (NumberFormatException e) {
                // We already checked this string
                // parse won't fail
            }
        }
        for (int i = 0; i < longArray.length; i++) {
            if (longArray[i] == current) {
                if (i == longArray.length - 1) {
                    index = 0;
                } else {
                    index = i + 1;
                }
            }
        }
        return longArray[index];
    }

    private float getNewFloat(String array, float current) {
        int index = 0;
        String[] strArray = array.split(",");
        float[] floatArray = new float[strArray.length];
        for (int i = 0; i < strArray.length; i++) {
            try {
                floatArray[i] = Float.parseFloat(strArray[i]);
            } catch (NumberFormatException e) {
                // We already checked this string
                // parse won't fail
            }
        }
        for (int i = 0; i < floatArray.length; i++) {
            if (floatArray[i] == current) {
                if (i == floatArray.length - 1) {
                    index = 0;
                } else {
                    index = i + 1;
                }
            }
        }
        return floatArray[index];
    }
}
