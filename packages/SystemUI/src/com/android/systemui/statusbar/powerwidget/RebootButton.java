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

package com.android.systemui.statusbar.powerwidget;

import com.android.systemui.R;


import android.content.Context;
import android.os.PowerManager;

import com.android.systemui.statusbar.phone.PanelBarCollapseListener;

public class RebootButton extends PowerButton implements PanelBarCollapseListener{

    public static String TAG = "RebootButton";

    private boolean rebootToRecovery = false;

    public RebootButton() { mType = BUTTON_REBOOT; }

    @Override
    protected void updateState(Context context) {
        if(rebootToRecovery) {
            mIcon = R.drawable.ic_qs_reboot_recovery;
        } else {
            mIcon = R.drawable.ic_qs_reboot;
        }
        mState = STATE_DISABLED;
    }

    @Override
    protected void toggleState(Context context) {
        rebootToRecovery = !rebootToRecovery;
    }

    @Override
    protected boolean handleLongClick(Context context) {
        mBar.registerListener(TAG, this);
        mBar.collapseAllPanels(true);
        return true;
    }

    public void onAllPanelsCollapsed() {
        mBar.unRegisterListener(TAG);
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
