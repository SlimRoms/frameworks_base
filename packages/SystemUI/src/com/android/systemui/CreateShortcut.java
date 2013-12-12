/*
 * Copyright (C) 2016, SlimRoms Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.app.LauncherActivity;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.text.InputFilter;
import android.text.Spanned;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.Filter;
import android.widget.ListView;
import android.widget.Toast;

import com.android.systemui.R;
import com.android.systemui.shortcuts.ChamberOfSecrets;

import java.lang.Character;
import java.lang.CharSequence;
import java.lang.IllegalArgumentException;
import java.lang.NumberFormatException;

import org.slim.provider.SlimSettings;
import org.slim.provider.SlimSettings.SlimSettingNotFoundException;

public class CreateShortcut extends LauncherActivity {

    private static final String TAG = "CreateShortcut";

    private static final int DLG_SECRET = 0;
    private static final int DLG_SECRET_CHK = 1;
    private static final int DLG_SECRET_INT = 2;
    private static final int DLG_SECRET_NAME = 3;
    private static final int DLG_TOGGLE = 4;
    private static final int DLG_SECRET_SLIM = 5;

    private int mSettingType = 0;
    private int mSlimSettingType = 0;
    private boolean mIsSlimSetting;

    private Intent mShortcutIntent;
    private Intent mIntent;

    @Override
    protected Intent getTargetIntent() {
        Intent targetIntent = new Intent(Intent.ACTION_MAIN, null);
        if (SlimSettings.Secure.getIntForUser(getContentResolver(),
                SlimSettings.Secure.CHAMBER_OF_SECRETS, 0,
                UserHandle.USER_CURRENT) == 1) {
            targetIntent.addCategory("com.android.systemui.SHORTCUT_COS");
        }
        targetIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return targetIntent;
    }

    @Override
    protected void onListItemClick(ListView list, View view, int position, long id) {
        mShortcutIntent = intentForPosition(position);

        String intentClass = mShortcutIntent.getComponent().getClassName();
        String className = intentClass.substring(intentClass.lastIndexOf(".") + 1);
        String intentAction = mShortcutIntent.getAction();

        mShortcutIntent = new Intent();
        mShortcutIntent.setClassName(this, intentClass);
        mShortcutIntent.setAction(intentAction);

        mIntent = new Intent();
        mIntent.putExtra(Intent.EXTRA_SHORTCUT_ICON,
                BitmapFactory.decodeResource(getResources(), returnIconResId(className)));
        mIntent.putExtra(Intent.EXTRA_SHORTCUT_NAME, itemForPosition(position).label);
        if (className.equals("ChamberOfSecrets")) {
            showDialogSetting(DLG_SECRET);
        } else {
            finalizeIntent();
        }
    }

    private int returnIconResId(String c) {
        if (c.equals("ChamberOfSecrets")) {
            return org.slim.framework.internal.R.drawable.ic_shortcut_action_theme_switch;
        } else {
            // Oh-Noes, you found a wild derp.
            return org.slim.framework.internal.R.drawable.ic_shortcut_action_null;
        }
    }

    private void finalizeIntent() {
        mIntent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, mShortcutIntent);
        setResult(RESULT_OK, mIntent);
        finish();
    }

    private void setSettingString(String set) {
        mShortcutIntent.putExtra("setting", set);
        mShortcutIntent.putExtra("type", mSettingType);
        showDialogSetting(DLG_SECRET_CHK);
    }

    private void updateSettingType(String value) {
        // Necessary ugly code.  Do it here so we don't have to again.

        // try slim provider
        try {
            SlimSettings.Global.getInt(
                    getContentResolver(), value);
            mSlimSettingType = ChamberOfSecrets.SLIM_GLOBAL_INT;
        } catch (SlimSettingNotFoundException p) {
            try {
                SlimSettings.Global.getLong(
                        getContentResolver(), value);
                mSlimSettingType = ChamberOfSecrets.SLIM_GLOBAL_LONG;
            } catch (SlimSettingNotFoundException q) {
                try {
                    SlimSettings.Global.getFloat(
                            getContentResolver(), value);
                    mSlimSettingType = ChamberOfSecrets.SLIM_GLOBAL_FLOAT;
                } catch (SlimSettingNotFoundException r) {
                    try {
                        SlimSettings.System.getIntForUser(
                                getContentResolver(),
                                value, UserHandle.USER_CURRENT);
                        mSlimSettingType = ChamberOfSecrets.SLIM_SYSTEM_INT;
                    } catch (SlimSettingNotFoundException a) {
                        try {
                            SlimSettings.Secure.getIntForUser(
                                    getContentResolver(),
                                    value, UserHandle.USER_CURRENT);
                            mSlimSettingType = ChamberOfSecrets.SLIM_SECURE_INT;
                        } catch (SlimSettingNotFoundException b) {
                            try {
                                SlimSettings.System.getLongForUser(
                                        getContentResolver(),
                                        value, UserHandle.USER_CURRENT);
                                mSlimSettingType = ChamberOfSecrets.SLIM_SYSTEM_LONG;
                            } catch (SlimSettingNotFoundException c) {
                                try {
                                    SlimSettings.Secure.getLongForUser(
                                            getContentResolver(),
                                            value, UserHandle.USER_CURRENT);
                                    mSlimSettingType = ChamberOfSecrets.SLIM_SECURE_LONG;
                                } catch (SlimSettingNotFoundException d) {
                                    try {
                                        SlimSettings.System.getFloatForUser(
                                                getContentResolver(),
                                                value, UserHandle.USER_CURRENT);
                                        mSlimSettingType = ChamberOfSecrets.SLIM_SYSTEM_FLOAT;
                                    } catch (SlimSettingNotFoundException e) {
                                        try {
                                            SlimSettings.Secure.getFloatForUser(
                                                    getContentResolver(),
                                                    value, UserHandle.USER_CURRENT);
                                            mSlimSettingType = ChamberOfSecrets.SLIM_SECURE_FLOAT;
                                        } catch (SlimSettingNotFoundException f) {
                                            Log.d(TAG, "updateSettingType(): " + value +
                                                    " not found in SLIM settings provider.");
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // try aosp provider
        try {
            Settings.Global.getInt(
                    getContentResolver(), value);
            mSettingType = ChamberOfSecrets.GLOBAL_INT;
        } catch (SettingNotFoundException p) {
            try {
                Settings.Global.getLong(
                        getContentResolver(), value);
                mSettingType = ChamberOfSecrets.GLOBAL_LONG;
            } catch (SettingNotFoundException q) {
                try {
                    Settings.Global.getFloat(
                            getContentResolver(), value);
                    mSettingType = ChamberOfSecrets.GLOBAL_FLOAT;
                } catch (SettingNotFoundException r) {
                    try {
                        Settings.System.getIntForUser(
                                getContentResolver(),
                                value, UserHandle.USER_CURRENT);
                        mSettingType = ChamberOfSecrets.SYSTEM_INT;
                    } catch (SettingNotFoundException a) {
                        try {
                            Settings.Secure.getIntForUser(
                                    getContentResolver(),
                                    value, UserHandle.USER_CURRENT);
                            mSettingType = ChamberOfSecrets.SECURE_INT;
                        } catch (SettingNotFoundException b) {
                            try {
                                Settings.System.getLongForUser(
                                        getContentResolver(),
                                        value, UserHandle.USER_CURRENT);
                                mSettingType = ChamberOfSecrets.SYSTEM_LONG;
                            } catch (SettingNotFoundException c) {
                                try {
                                    Settings.Secure.getLongForUser(
                                            getContentResolver(),
                                            value, UserHandle.USER_CURRENT);
                                    mSettingType = ChamberOfSecrets.SECURE_LONG;
                                } catch (SettingNotFoundException d) {
                                    try {
                                        Settings.System.getFloatForUser(
                                                getContentResolver(),
                                                value, UserHandle.USER_CURRENT);
                                        mSettingType = ChamberOfSecrets.SYSTEM_FLOAT;
                                    } catch (SettingNotFoundException e) {
                                        try {
                                            Settings.Secure.getFloatForUser(
                                                    getContentResolver(),
                                                    value, UserHandle.USER_CURRENT);
                                            mSettingType = ChamberOfSecrets.SECURE_FLOAT;
                                        } catch (SettingNotFoundException f) {
                                            Log.d(TAG, "updateSettingType(): " + value +
                                                    " not found in AOSP settings provider.");
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Log.d(TAG, "updateSettingType()"
                + " value:" + value
                + " mSettingType:" + mSettingType
                + " mSlimSettingType:" + mSlimSettingType);
    }

    private void setCheck(boolean isCheck) {
        if (isCheck) {
            String check = "0,1";
            mShortcutIntent.putExtra("array", check);
            showDialogSetting(DLG_SECRET_NAME);
        } else {
            showDialogSetting(DLG_SECRET_INT);
        }
    }

    private void setSlim(boolean isSlim) {
        if (isSlim) {
            mIsSlimSetting = true;
        } else {
            mIsSlimSetting = false;
        }
    }

    private void setSettingArray(String array) {
        mShortcutIntent.putExtra("array", array);
        showDialogSetting(DLG_SECRET_NAME);
    }

    private void checkIntentName(String name) {
        if (name != null && name.length() > 0) {
            mIntent.putExtra(Intent.EXTRA_SHORTCUT_NAME, name);
        } else {
            Toast.makeText(CreateShortcut.this,
                    R.string.chamber_name_toast,
                    Toast.LENGTH_LONG).show();
        }
        finalizeIntent();
    }

    private void showDialogSetting(int id) {
        switch (id) {
            case DLG_SECRET:
                final EditText input = new EditText(this);

                AlertDialog.Builder alert = new AlertDialog.Builder(this);
                alert.setTitle(R.string.chamber_title)
                .setMessage(R.string.chamber_message)
                .setView(input)
                .setNegativeButton(R.string.cancel,
                    new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        finish();
                    }
                })
                .setPositiveButton(R.string.dlg_ok,
                    new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        String value = input.getText().toString().toLowerCase();
                        updateSettingType(value);
                        boolean existsAsSetting = mSettingType != 0;
                        boolean existsAsSlimSetting = mSlimSettingType != 0;
                        boolean resultOk = existsAsSetting || existsAsSlimSetting;

                        if (existsAsSetting && existsAsSlimSetting) {
                            // if setting exists in both providers, prompt user for decision
                            showDialogSetting(DLG_SECRET_SLIM);
                        } else if (existsAsSlimSetting && !existsAsSetting) {
                            // if setting only appears in slim provider, it's a slim setting
                            mIsSlimSetting = true;
                        } else {
                            mIsSlimSetting = false;
                        }

                        if (mIsSlimSetting) {
                            mSettingType = mSlimSettingType;
                        }

                        if (resultOk) {
                            setSettingString(value);
                        } else {
                            Toast.makeText(CreateShortcut.this,
                                    R.string.chamber_invalid,
                                    Toast.LENGTH_LONG).show();
                            Toast.makeText(CreateShortcut.this,
                                    R.string.chamber_invalid_setting,
                                    Toast.LENGTH_LONG).show();
                            finish();
                        }
                    }
                });
                alert.show();
                break;
            case DLG_SECRET_CHK:
                AlertDialog.Builder alertChk = new AlertDialog.Builder(this);
                alertChk.setTitle(R.string.chamber_checkbox)
                .setMessage(R.string.chamber_chk_message)
                .setNegativeButton(R.string.no,
                    new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        setCheck(false);
                    }
                })
                .setPositiveButton(R.string.yes,
                    new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        setCheck(true);
                    }
                });
                alertChk.show();
                break;
            case DLG_SECRET_SLIM:
                AlertDialog.Builder alertSlim = new AlertDialog.Builder(this);
                alertSlim.setTitle(R.string.chamber_slim_title)
                .setMessage(R.string.chamber_slim_message)
                .setNegativeButton(R.string.no,
                    new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        setSlim(false);
                    }
                })
                .setPositiveButton(R.string.yes,
                    new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        setSlim(true);
                    }
                });
                alertSlim.show();
                break;
            case DLG_SECRET_INT:
                final EditText edit = new EditText(this);
                edit.setHorizontallyScrolling(true);
                AlertDialog.Builder alertInt = new AlertDialog.Builder(this);
                alertInt.setTitle(R.string.chamber_int)
                .setMessage(R.string.chamber_int_message)
                .setView(edit)
                .setNegativeButton(R.string.cancel,
                    new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        finish();
                    }
                })
                .setPositiveButton(R.string.dlg_ok,
                    new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        String str = edit.getText().toString();
                        str = str.replaceAll("\\s+", "");
                        String[] strArray = str.split(",");
                        boolean resultOk = true;

                        switch (mSettingType) {
                            case ChamberOfSecrets.SYSTEM_INT:
                            case ChamberOfSecrets.SECURE_INT:
                            case ChamberOfSecrets.GLOBAL_INT:
                            case ChamberOfSecrets.SLIM_SYSTEM_INT:
                            case ChamberOfSecrets.SLIM_SECURE_INT:
                            case ChamberOfSecrets.SLIM_GLOBAL_INT:
                                int[] intArray = new int[strArray.length];
                                for (int i = 0; i < strArray.length; i++) {
                                    try {
                                        intArray[i] = Integer.parseInt(strArray[i]);
                                    } catch (NumberFormatException e) {
                                        try {
                                            intArray[i] = Color.parseColor(strArray[i]);
                                        } catch (IllegalArgumentException ex) {
                                            resultOk = false;
                                        }
                                    }
                                }
                                break;
                            case ChamberOfSecrets.SYSTEM_LONG:
                            case ChamberOfSecrets.SECURE_LONG:
                            case ChamberOfSecrets.GLOBAL_LONG:
                            case ChamberOfSecrets.SLIM_SYSTEM_LONG:
                            case ChamberOfSecrets.SLIM_SECURE_LONG:
                            case ChamberOfSecrets.SLIM_GLOBAL_LONG:
                                long[] longArray = new long[strArray.length];
                                for (int i = 0; i < strArray.length; i++) {
                                    try {
                                        longArray[i] = Long.parseLong(strArray[i]);
                                    } catch (NumberFormatException e) {
                                        resultOk = false;
                                    }
                                }
                                break;
                            case ChamberOfSecrets.SYSTEM_FLOAT:
                            case ChamberOfSecrets.SECURE_FLOAT:
                            case ChamberOfSecrets.GLOBAL_FLOAT:
                            case ChamberOfSecrets.SLIM_SYSTEM_FLOAT:
                            case ChamberOfSecrets.SLIM_SECURE_FLOAT:
                            case ChamberOfSecrets.SLIM_GLOBAL_FLOAT:
                                float[] floatArray = new float[strArray.length];
                                for (int i = 0; i < strArray.length; i++) {
                                    try {
                                        floatArray[i] = Float.parseFloat(strArray[i]);
                                    } catch (NumberFormatException ex) {
                                        resultOk = false;
                                    }
                                }
                                break;
                        }

                        if (resultOk) {
                            // Set to string.  Launcher doesn't persist array
                            // extras after a reboot.
                            setSettingArray(str);
                        } else {
                            Toast.makeText(CreateShortcut.this,
                                    R.string.chamber_invalid,
                                    Toast.LENGTH_LONG).show();
                            finish();
                        }
                    }
                });
                alertInt.show();
                break;
            case DLG_SECRET_NAME:
                final EditText inputName = new EditText(this);

                AlertDialog.Builder alertName = new AlertDialog.Builder(this);
                alertName.setTitle(R.string.chamber_name_title)
                .setMessage(R.string.chamber_name_message)
                .setView(inputName)
                .setNegativeButton(R.string.cancel,
                    new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        checkIntentName(null);
                    }
                })
                .setPositiveButton(R.string.dlg_ok,
                    new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        String name = inputName.getText().toString();
                        checkIntentName(name);
                    }
                });
                alertName.show();
                break;
            case DLG_TOGGLE:
                final CharSequence[] items = {
                    getResources().getString(R.string.off),
                    getResources().getString(R.string.on),
                    getResources().getString(R.string.toggle),
                };
                AlertDialog.Builder alertToggle = new AlertDialog.Builder(this);
                alertToggle.setTitle(R.string.shortcut_toggle_title)
                .setNegativeButton(R.string.cancel,
                    new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        finish();
                    }
                })
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, final int item) {
                        mShortcutIntent.putExtra("value", item);
                        mIntent.putExtra(Intent.EXTRA_SHORTCUT_NAME,
                                /*mName + " " + */items[item]);
                        finalizeIntent();
                    }
                });
                alertToggle.show();
                break;
        }
    }
}
