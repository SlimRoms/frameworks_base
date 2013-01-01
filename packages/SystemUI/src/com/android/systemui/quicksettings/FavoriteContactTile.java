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

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.view.View;

import com.android.systemui.R;
import com.android.systemui.statusbar.phone.QuickSettingsController;
import com.android.systemui.statusbar.phone.QuickSettingsContainerView;

import java.io.InputStream;

import android.provider.ContactsContract;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.database.Cursor;
import android.util.Pair;
import android.widget.TextView;
import android.widget.ImageView;

public class FavoriteContactTile extends QuickSettingsTile implements SharedPreferences.OnSharedPreferenceChangeListener{

    private AsyncTask<Void, Void, Pair<String, Drawable>> mFavContactInfoTask;
    private Drawable avatar = null;
    private String name = "";
    SharedPreferences prefs;
    public static int instanceCount = 0;
    private int tileID;

    public static QuickSettingsTile getInstance(Context context, LayoutInflater inflater,
            QuickSettingsContainerView container, final QuickSettingsController qsc, Handler handler) {
        return new FavoriteContactTile(context, inflater, container, qsc, handler);
    }

    public FavoriteContactTile(Context context, LayoutInflater inflater,
            QuickSettingsContainerView container,
            QuickSettingsController qsc, Handler handler) {
        super(context, inflater, container, qsc);
        mTileLayout = R.layout.quick_settings_tile_user;
        tileID = instanceCount++;
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.registerOnSharedPreferenceChangeListener(this);
        queryForFavContactInformation();
        mOnClick = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                 String lookupKey = prefs.getString(tileID+"", null);
                 if (lookupKey != null && lookupKey.length() > 0) {
                    Uri lookupUri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, lookupKey);
                    Uri res = ContactsContract.Contacts.lookupContact(mContext.getContentResolver(), lookupUri);
                    Intent intent = ContactsContract.QuickContact.composeQuickContactsIntent(
                           mContext, v, res, ContactsContract.QuickContact.MODE_LARGE, null);
                    startSettingsActivity(intent);
               }
            }
        };
        mOnLongClick = new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                Intent i=new Intent(mContext,ContactPickerActivity.class);
                i.putExtra("hashCode", tileID);
                startSettingsActivity(i);
                return true;
            }
        };
    }

    @Override
    void updateQuickSettings(){
        ImageView iv = (ImageView) mTile.findViewById(R.id.user_imageview);
        TextView tv = (TextView) mTile.findViewById(R.id.user_textview);
        if (avatar != null)  iv.setImageDrawable(avatar);
        if (name == null || name.equals("")) tv.setText("long click");
        else tv.setText(name);
    }

    /*
     * This method was taken from AOKP project
     */
    private void queryForFavContactInformation() {
        mFavContactInfoTask = new AsyncTask<Void, Void, Pair<String, Drawable>>() {
            @Override
            protected Pair<String, Drawable> doInBackground(Void... params) {
                Bitmap rawAvatar = null;
                avatar = mContext.getResources().getDrawable(R.drawable.ic_qs_default_user);
                String lookupKey = prefs.getString(tileID+"", null);
                if (lookupKey != null && lookupKey.length() > 0) {
                    Uri lookupUri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, lookupKey);
                    Uri res = ContactsContract.Contacts.lookupContact(mContext.getContentResolver(), lookupUri);
                    String[] projection = new String[] {
                        ContactsContract.Contacts.DISPLAY_NAME,
                        ContactsContract.Contacts.PHOTO_URI,
                        ContactsContract.Contacts.LOOKUP_KEY};

                    final Cursor cursor = mContext.getContentResolver().query(res,projection,null,null,null);
                    if (cursor != null) {
                        try {
                            if (cursor.moveToFirst()) {
                                name = cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME));
                            }
                        } finally {
                            cursor.close();
                        }
                    }
                    InputStream input = ContactsContract.Contacts.openContactPhotoInputStream(mContext.getContentResolver(), res, true);
                    if (input != null) {
                        rawAvatar = BitmapFactory.decodeStream(input);
                    }

                    if (rawAvatar != null) {
                        avatar = new BitmapDrawable(mContext.getResources(), rawAvatar);
                    }
                }
                return new Pair<String, Drawable>(name, avatar);
            }

            @Override
            protected void onPostExecute(Pair<String, Drawable> result) {
                super.onPostExecute(result);
                updateQuickSettings();
                mFavContactInfoTask = null;
            }
        };
        mFavContactInfoTask.execute();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key.equals(tileID+"")) queryForFavContactInformation();
    }

    public static void resetContent(Context context) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().apply();
    }
}
