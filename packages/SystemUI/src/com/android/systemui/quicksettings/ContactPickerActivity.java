/*
 * Copyright (C) 2012 The Slim Bean Project
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

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

import android.provider.ContactsContract;
import android.provider.ContactsContract.Contacts;
import android.content.CursorLoader;
import android.database.Cursor;
import android.net.Uri;

import android.preference.PreferenceManager;

import android.util.Log;

public class ContactPickerActivity extends Activity {

    private static final int CONTACT_PICKER_RESULT = 1001;
    int callingTile;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle os = this.getIntent().getExtras();
        callingTile = os.getInt("hashCode");
        Log.e("\r\n\r\n --------", "callingTile = "+callingTile+"\r\n\r\n");
        Intent contactPickerIntent = new Intent(Intent.ACTION_PICK, Contacts.CONTENT_URI);
        startActivityForResult(contactPickerIntent, CONTACT_PICKER_RESULT);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == Activity.RESULT_OK) {
            if (requestCode == CONTACT_PICKER_RESULT) {
                Uri contactData = data.getData();
                String[] projection = new String[] {ContactsContract.Contacts.LOOKUP_KEY};
                String selection = ContactsContract.Contacts.DISPLAY_NAME + " IS NOT NULL";
                CursorLoader cursorLoader =  new CursorLoader(this, contactData, projection, selection, null, null);
                Cursor cursor = cursorLoader.loadInBackground();
                if (cursor != null) {
                    try {
                        if (cursor.moveToFirst()) {
                            String lookup_key = cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY));
                            Log.e("\r\n\r\n --------", "storing lookup_key "+lookup_key+"\r\n\r\n");
                            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                            SharedPreferences.Editor editor = prefs.edit();
                            editor.putString(callingTile+"", lookup_key); // store selected contact
                            editor.apply();
                            /*Settings.System.putString(this.getContentResolver(),
                                Settings.System.QUICK_TOGGLE_FAV_CONTACT, lookup_key);*/
                        }
                    } finally {
                        cursor.close();
                    }
                }
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
        finish();
    }
}
