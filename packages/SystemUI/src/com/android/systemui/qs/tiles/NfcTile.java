/*
 * Copyright (C) 2015 The CyanogenMod Project
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

package com.android.systemui.qs.tiles;

import android.content.pm.PackageManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.nfc.NfcAdapter;
import android.util.Log;
import android.provider.Settings;

import com.android.systemui.R;
import com.android.systemui.qs.QSTile;

import com.android.internal.logging.MetricsLogger;

public class NfcTile extends QSTile<QSTile.BooleanState> {

    private boolean mListening;
    private boolean mSupported;

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshState();
        }
    };

    public NfcTile(Host host) {
        super(host);
        mSupported = deviceSupportsNfc();
    }

    @Override
    protected BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleClick() {
        setState(!getState().value);
        refreshState();
    }

    @Override
    protected void handleLongClick() {
        mHost.startActivityDismissingKeyguard(new Intent(Settings.ACTION_NFC_SETTINGS));
    }

    private void setState(boolean enable) {
        NfcAdapter adapter = getAdapter();

        if (adapter == null) {
            Log.e(TAG, "tried to set NFC state, but no NFC adapter is available");
            return;
        }

        if (enable) {
            adapter.enable();
        } else {
            adapter.disable();
        }
    }

    private boolean isEnabled() {
        int state = getNfcState();
        switch (state) {
            case NfcAdapter.STATE_TURNING_ON:
            case NfcAdapter.STATE_ON:
                return true;
            case NfcAdapter.STATE_TURNING_OFF:
            case NfcAdapter.STATE_OFF:
            default:
                return false;
        }
    }

    private int getNfcState() {
        NfcAdapter adapter = getAdapter();
        if (adapter == null) {
            return NfcAdapter.STATE_OFF;
        }
        return adapter.getAdapterState();
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.visible = mSupported;
        state.value = mSupported && isEnabled();
        state.icon = state.value ? ResourceIcon.get(R.drawable.ic_qs_nfc_on)
                : ResourceIcon.get(R.drawable.ic_qs_nfc_off);
        state.label = mContext.getString(R.string.quick_settings_nfc_label);
    }

    @Override
    public int getMetricsCategory() {
        return MetricsLogger.NFC_BEAM;
    }

    @Override
    public void setListening(boolean listening) {
        if (mListening == listening) return;
        mListening = listening;
        if (listening) {
            mContext.registerReceiver(mReceiver,
                    new IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED));
            refreshState();
        } else {
            mContext.unregisterReceiver(mReceiver);
        }
    }

    private boolean deviceSupportsNfc() {
        PackageManager pm = mContext.getPackageManager();
        return pm.hasSystemFeature(PackageManager.FEATURE_NFC);
    }

    private NfcAdapter getAdapter() {
        try {
            return NfcAdapter.getNfcAdapter(mContext);
        } catch (UnsupportedOperationException e) {
            return null;
        }
    }
}
