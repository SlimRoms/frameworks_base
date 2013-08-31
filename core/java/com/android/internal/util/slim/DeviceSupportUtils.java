/*
* Copyright (C) 2013 SlimRoms Project
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package com.android.internal.util.slim;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.hardware.display.DisplayManager;
import android.hardware.display.WifiDisplayStatus;
import android.net.ConnectivityManager;
import android.nfc.NfcAdapter;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.internal.telephony.PhoneConstants;

import java.util.ArrayList;
import java.util.List;

public class DeviceSupportUtils {

    private static final String SETTINGS_METADATA_NAME = "com.android.settings";

    public static boolean deviceSupportsUsbTether(Context context) {
        ConnectivityManager cm =
            (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        return (cm.getTetherableUsbRegexs().length != 0);
    }

    public static boolean deviceSupportsWifiDisplay(Context context) {
        DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        return (dm.getWifiDisplayStatus().getFeatureState()
                    != WifiDisplayStatus.FEATURE_STATE_UNAVAILABLE);
    }

    public static boolean deviceSupportsMobileData(Context context) {
        ConnectivityManager cm =
            (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        return cm.isNetworkSupported(ConnectivityManager.TYPE_MOBILE);
    }

    public static boolean deviceSupportsBluetooth() {
        return (BluetoothAdapter.getDefaultAdapter() != null);
    }

    public static boolean deviceSupportsNfc(Context context) {
        return NfcAdapter.getDefaultAdapter(context) != null;
    }

    public static boolean deviceSupportsLte(Context context) {
        final TelephonyManager tm =
            (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        return (tm.getLteOnCdmaMode() == PhoneConstants.LTE_ON_CDMA_TRUE)
                    || tm.getLteOnGsmMode() != 0;
    }

    public static boolean deviceSupportsTorch(Context context) {
        PackageManager pm = context.getPackageManager();
        Resources settingsResources = null;
        try {
            settingsResources = pm.getResourcesForApplication(SETTINGS_METADATA_NAME);
        } catch (Exception e) {
            Log.e("DeviceSupportUtils:", "can't access settings resources",e);
            return false;
        }
        int resId = settingsResources.getIdentifier(
                  SETTINGS_METADATA_NAME + ":bool/has_led_flash", null, null);
        return resId > 0 && settingsResources.getBoolean(resId);
    }

    public static FilteredDeviceFeaturesArray filterUnsupportedDeviceFeatures(Context context,
            String[] valuesArray, String[] entriesArray) {
        if (valuesArray == null || entriesArray == null || context == null) {
            return null;
        }
        List<String> finalEntries = new ArrayList<String>();
        List<String> finalValues = new ArrayList<String>();
        FilteredDeviceFeaturesArray filteredDeviceFeaturesArray =
            new FilteredDeviceFeaturesArray();

        for (int i = 0; i < valuesArray.length; i++) {
            if (isSupportedFeature(context, valuesArray[i])) {
                finalEntries.add(entriesArray[i]);
                finalValues.add(valuesArray[i]);
            }
        }
        filteredDeviceFeaturesArray.entries =
            finalEntries.toArray(new String[finalEntries.size()]);
        filteredDeviceFeaturesArray.values =
            finalValues.toArray(new String[finalValues.size()]);
        return filteredDeviceFeaturesArray;
    }

    private static boolean isSupportedFeature(Context context, String action) {
        if (action.equals(ButtonsConstants.ACTION_TORCH) && !deviceSupportsTorch(context)) {
            return false;
        }
        return true;
    }

    public static class FilteredDeviceFeaturesArray {
        public String[] entries;
        public String[] values;
    }

}
