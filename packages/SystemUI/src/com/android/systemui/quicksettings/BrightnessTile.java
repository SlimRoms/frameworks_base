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

import android.app.Dialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.Handler;
import android.os.RemoteException;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.widget.ImageView;

import com.android.systemui.R;
import com.android.systemui.statusbar.phone.QuickSettingsController;
import com.android.systemui.statusbar.phone.QuickSettingsContainerView;
import com.android.systemui.statusbar.policy.BrightnessController;
import com.android.systemui.statusbar.policy.BrightnessController.BrightnessStateChangeCallback;
import com.android.systemui.statusbar.policy.ToggleSlider;

public class BrightnessTile extends QuickSettingsTile implements BrightnessStateChangeCallback {

    private final int mBrightnessDialogLongTimeout;
    private final int mBrightnessDialogShortTimeout;
    private Dialog mBrightnessDialog;
    private BrightnessController mBrightnessController;
    private final Handler mHandler;
    private boolean autoBrightness = true;
    public static QuickSettingsTile mInstance;

    public static QuickSettingsTile getInstance(Context context, LayoutInflater inflater,
            QuickSettingsContainerView container, final QuickSettingsController qsc, Handler handler) {
        if (mInstance == null) mInstance = new BrightnessTile(context, inflater, container, qsc, handler);
        return mInstance;
    }

    public BrightnessTile(Context context, LayoutInflater inflater,
            QuickSettingsContainerView container, final QuickSettingsController qsc, Handler handler) {
        super(context, inflater, container, qsc);

        mHandler = handler;

        mBrightnessDialogLongTimeout = mContext.getResources().getInteger(R.integer.quick_settings_brightness_dialog_long_timeout);
        mBrightnessDialogShortTimeout = mContext.getResources().getInteger(R.integer.quick_settings_brightness_dialog_short_timeout);

        mOnClick = new OnClickListener() {

            @Override
            public void onClick(View v) {
                qsc.mBar.collapseAllPanels(true);
                showBrightnessDialog();
            }
        };
        qsc.registerObservedContent(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS)
                , this);
        qsc.registerObservedContent(Settings.System.getUriFor(Settings.System
                .SCREEN_BRIGHTNESS_MODE), this);
        onBrightnessLevelChanged();
    }

    private void showBrightnessDialog() {
        if (mBrightnessDialog == null) {
            mBrightnessDialog = new Dialog(mContext);
            mBrightnessDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            mBrightnessDialog.setContentView(R.layout.quick_settings_brightness_dialog);
            mBrightnessDialog.setCanceledOnTouchOutside(true);

            mBrightnessController = new BrightnessController(mContext,
                    (ImageView) mBrightnessDialog.findViewById(R.id.brightness_icon),
                    (ToggleSlider) mBrightnessDialog.findViewById(R.id.brightness_slider));
            mBrightnessDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface dialog) {
                    mBrightnessController = null;
                }
            });

            mBrightnessDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
            mBrightnessDialog.getWindow().getAttributes().privateFlags |=
                    WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
            mBrightnessDialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        if (!mBrightnessDialog.isShowing()) {
            try {
                WindowManagerGlobal.getWindowManagerService().dismissKeyguard();
            } catch (RemoteException e) {
            }
            mBrightnessDialog.show();
            dismissBrightnessDialog(mBrightnessDialogLongTimeout);
        }
    }

    private void dismissBrightnessDialog(int timeout) {
        if (mBrightnessDialog != null) {
            mHandler.postDelayed(mDismissBrightnessDialogRunnable, timeout);
        }
    }

    private final Runnable mDismissBrightnessDialogRunnable = new Runnable() {
        @Override
        public void run() {
            if (mBrightnessDialog != null && mBrightnessDialog.isShowing()) {
                mBrightnessDialog.dismiss();
            }
        };
    };

    @Override
    public void onBrightnessLevelChanged() {
        int mode;
        try {
            mode = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            autoBrightness =
                    (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
            mDrawable = autoBrightness
                    ? R.drawable.ic_qs_brightness_auto_on
                    : R.drawable.ic_qs_brightness_auto_off;
            mLabel = mContext.getString(R.string.quick_settings_brightness_label);
        } catch (SettingNotFoundException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        if(mTile != null){
            updateQuickSettings();
        }
    }

    @Override
    public void onChangeUri(ContentResolver resolver, Uri uri) {
        onBrightnessLevelChanged();
    }
}
