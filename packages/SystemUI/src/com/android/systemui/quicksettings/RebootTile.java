/*
 * Copyright (C) 2012 Slimroms
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

import android.content.Context;
import android.os.Handler;
import android.os.PowerManager;
import android.view.LayoutInflater;
import android.view.View;

import com.android.systemui.R;
import com.android.systemui.statusbar.phone.QuickSettingsController;
import com.android.systemui.statusbar.phone.QuickSettingsContainerView;
import com.android.systemui.statusbar.phone.PanelBarCollapseListener;

public class RebootTile extends QuickSettingsTile implements PanelBarCollapseListener{
    public static String TAG = "RebootTile";
    public static RebootTile mInstance;
    private boolean rebootToRecovery = false;

    public static QuickSettingsTile getInstance(Context context, LayoutInflater inflater,
            QuickSettingsContainerView container, final QuickSettingsController qsc, Handler handler, String id) {
        mInstance = null;
        mInstance = new RebootTile(context, inflater, container, qsc);
        return mInstance;
    }

    public RebootTile(Context context, LayoutInflater inflater,
            QuickSettingsContainerView container,
            QuickSettingsController qsc) {
        super(context, inflater, container, qsc);
        updateTileState();
        mOnClick = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
               rebootToRecovery = !rebootToRecovery;
               updateTileState();
               updateQuickSettings();
            }
        };
        mOnLongClick = new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                mQsc.mBar.registerListener(TAG, mInstance);
                mQsc.mBar.collapseAllPanels(true);
                return true;
            }
        };
    }

    private void updateTileState() {
        if(rebootToRecovery) {
            mLabel = mContext.getString(R.string.quick_settings_reboot_recovery);
            mDrawable = R.drawable.ic_qs_reboot_recovery;
        } else {
            mLabel = mContext.getString(R.string.quick_settings_reboot);
            mDrawable = R.drawable.ic_qs_reboot;
        }
    }

    public void onAllPanelsCollapsed() {
        mQsc.mBar.unRegisterListener(TAG);
        try{
            // give the animation a second to finish
            Thread.sleep(1000);
        }
        catch(Exception e){
            e.printStackTrace();
        }
        PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        pm.reboot(rebootToRecovery? "recovery" : "");
    }
}
