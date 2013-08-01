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
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Pair;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView.OnItemClickListener;

import com.android.systemui.R;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Date;

public class ShortcutPickerActivity extends Activity implements ShortcutPickHelper.OnPickListener{

    private final static String TAG = "ShortcutPickerActivity";
    private final boolean DBG = false;

    public final static String ICON_FILE = "icon_file";
    public final static String CONTACT_TEMPLATE = "content://com.android.contacts/contacts/lookup";
    public final static String PHONE_NUMBER_TEMPLATE = "tel:";
    public final static String SMS_TEMPLATE = "smsto:";

    private final static int CUSTOM_USER_ICON = 0;

    private String customUserIconPath = null;
    private File customUserImage;

    private String callingTileID;
    private ShortcutPickHelper mPicker;

    private File mImageTmp;
    private ImageButton mDialogIcon;
    private Button mDialogLabel;
    private Activity mActivity;
    private static String mEmptyLabel;

    private SharedPreferences prefs;
    private int mIconSize;
    private AsyncTask<Void, Void, Pair<String, Drawable>> mContactInfoTask;
    private String tempIconPath = null;
    private String mShortcutUri;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle os = this.getIntent().getExtras();
        callingTileID = os.getString("hashCode");
        customUserImage = new File(getFilesDir() + File.separator
                    + callingTileID + ".png");
        customUserIconPath = customUserImage.getAbsolutePath();
        mIconSize = os.getInt("iconSize");
        mPicker = new ShortcutPickHelper(this, this);
        mImageTmp = new File(getCacheDir() + "/target.tmp");
        mActivity = this;
        mEmptyLabel = getResources().getString(R.string.empty_tile_title);
        prefs = getSharedPreferences("quick_settings_custom_shortcut", 0);
        pickShortcut();
        if (DBG) Log.i(TAG, "onCreate finished! callingTileID="+callingTileID);
    }

    private void pickShortcut() {
        AlertDialog dialog;
        mShortcutUri = prefs.getString(callingTileID, null);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.quicksettings_shortcuts_edit_title);
        builder.setMessage(R.string.quicksettings_shortcuts_edit_msg);
        View view = View.inflate(this, R.layout.quick_settings_shortcuts_dialog, null);
        mDialogIcon = ((ImageButton) view.findViewById(R.id.icon));
        mDialogLabel = ((Button) view.findViewById(R.id.label));
        mDialogIcon.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!mDialogLabel.getText().equals(mEmptyLabel)) {
                    try {
                        mImageTmp.createNewFile();
                        mImageTmp.setWritable(true, false);
                        if (mShortcutUri != null) {
                            pickIcon();
                        }
                        else {
                            Toast.makeText(mActivity, R.string.qs_shortcut_error, Toast.LENGTH_SHORT).show();
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        });
        mDialogLabel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mPicker.pickShortcut(null, null, 0);
            }
        });
        if (mShortcutUri != null) {
            updateLabelAndIcon(true);
        } else {
            mDialogLabel.setText(mEmptyLabel);
            mDialogIcon.setImageResource(R.drawable.ic_empty);
        }
        builder.setView(view);
        builder.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (mShortcutUri != null) {
                    storeShortcut();
                }
                mActivity.finish();
            }
        });
        builder.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
                mActivity.finish();
            }
        });
        builder.setCancelable(false);
        dialog = builder.create();
        dialog.show();
        ((TextView)dialog.findViewById(android.R.id.message)).setTextAppearance(this,
                android.R.style.TextAppearance_DeviceDefault_Small);

    }

    private void pickIcon() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.icon_picker_type)
                .setItems(R.array.icon_types, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        switch(which) {
                            case 0: // Default
                                updateLabelAndIcon(false);
                                break;
                            case 1: // System defaults
                                ListView list = new ListView(mActivity);
                                list.setAdapter(new IconAdapter());
                                final Dialog holoDialog = new Dialog(mActivity);
                                holoDialog.setTitle(R.string.icon_picker_choose_icon_title);
                                holoDialog.setContentView(list);
                                list.setOnItemClickListener(new OnItemClickListener() {
                                    @Override
                                    public void onItemClick(AdapterView<?> parent, View view,
                                            int position, long id) {
                                        IconAdapter adapter = (IconAdapter) parent.getAdapter();
                                        tempIconPath = adapter.getItemReference(position);
                                        mDialogIcon.setImageDrawable(getDrawable(tempIconPath));
                                        holoDialog.cancel();
                                    }
                                });
                                holoDialog.show();
                                break;
                            case 2: // Custom user icon
                                Intent intent = new Intent(Intent.ACTION_GET_CONTENT, null);
                                intent.setType("image/*");
                                intent.putExtra("crop", "true");
                                intent.putExtra("scale", true);
                                intent.putExtra("scaleUpIfNeeded", true);
                                intent.putExtra("outputFormat", Bitmap.CompressFormat.PNG.toString());
                                intent.putExtra("aspectX", 1);
                                intent.putExtra("aspectY", 1);
                                intent.putExtra("outputX", mIconSize);
                                intent.putExtra("outputY", mIconSize);
                                try {
                                    mImageTmp.createNewFile();
                                    mImageTmp.setWritable(true, false);
                                    intent.putExtra(MediaStore.EXTRA_OUTPUT, Uri.fromFile(mImageTmp));
                                    intent.putExtra("return-data", false);
                                    startActivityForResult(intent, CUSTOM_USER_ICON);
                                } catch (IOException e) {
                                    // We could not write temp file
                                    e.printStackTrace();
                                } catch (ActivityNotFoundException e) {
                                    e.printStackTrace();
                                }
                                break;
                        }
                    }
                }
        );
        AlertDialog dialog = builder.create();
        dialog.show();
    }

    private void updateLabelAndIcon(boolean checkExtra) {
        try {
            if (ShortcutPickHelper.mContext == null) {
                ShortcutPickHelper.mContext = this;
            }
            String name = ShortcutPickHelper.getFriendlyNameForUri(mShortcutUri);
            Intent i = Intent.parseUri(mShortcutUri, 0);
            if (!checkExtra) {
                i.removeExtra(ICON_FILE);
                tempIconPath = null;
            }
            Drawable icon = null;
            if (i.getStringExtra(ICON_FILE)!=null){
                tempIconPath = i.getStringExtra(ICON_FILE);
                icon = getDrawable(tempIconPath);
                mDialogIcon.setImageDrawable(icon.mutate());
            } else {
                if (mShortcutUri.contains(CONTACT_TEMPLATE) || mShortcutUri.contains(PHONE_NUMBER_TEMPLATE)
                    || mShortcutUri.contains(SMS_TEMPLATE)) {
                    queryForContactInformation(i);
                } else {
                    tempIconPath = null;
                    PackageManager pm = getPackageManager();
                    ActivityInfo aInfo = i.resolveActivityInfo(pm, PackageManager.GET_ACTIVITIES);
                    if (aInfo != null) {
                        icon = aInfo.loadIcon(pm).mutate();
                    } else {
                        icon = getResources().getDrawable(android.R.drawable.sym_def_app_icon);
                    }
                    mDialogIcon.setImageDrawable(icon.mutate());
                }
            }
            if (name.contains(":")) {
                name = name.substring(name.indexOf(":")+1);
            }
            mDialogLabel.setText(name);
            mShortcutUri = i.toUri(0);
        } catch(Exception e){
            e.printStackTrace();
        }
    }

    private void storeShortcut() {
        try {
            Intent i = Intent.parseUri(mShortcutUri, 0);
            if (tempIconPath!=null && tempIconPath.equals(customUserIconPath) && mImageTmp.exists()) {
                mImageTmp.renameTo(customUserImage);
            }
            if (tempIconPath != null) {
                i.putExtra(ICON_FILE, tempIconPath);
                i.putExtra("LAST_CHANGED", new Date().toString());
            }
            mShortcutUri = i.toUri(0);
        } catch(Exception e){
            e.printStackTrace();
        }
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(callingTileID, mShortcutUri); // store selected shortcut
        editor.apply();
    }

    @Override
    public void shortcutPicked(String uri, String friendlyName, boolean isApplication) {
        this.mShortcutUri = uri;
        updateLabelAndIcon(false);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == CUSTOM_USER_ICON && resultCode == Activity.RESULT_OK){
            tempIconPath = customUserIconPath;
            mDialogIcon.setImageDrawable(getDrawable(mImageTmp.getAbsolutePath()));
        } else if (requestCode != Activity.RESULT_CANCELED && resultCode != Activity.RESULT_CANCELED) {
            mPicker.onActivityResult(requestCode, resultCode, data);
        }
    }

    public static Drawable getDrawable(String drawableName){
        int resourceId = Resources.getSystem().getIdentifier(drawableName, "drawable", "android");
        if (resourceId == 0) {
            Drawable d = Drawable.createFromPath(drawableName);
            return d;
        } else {
            return Resources.getSystem().getDrawable(resourceId);
        }
    }

    /*
     * This method was adapted from AOKP project
     */
    private void queryForContactInformation(Intent contactIntent) {
        final Intent lookupIntent = contactIntent;
        mContactInfoTask = new AsyncTask<Void, Void, Pair<String, Drawable>>() {
            @Override
            protected Pair<String, Drawable> doInBackground(Void... params) {
                Bitmap rawAvatar = null;
                Drawable avatar = null;
                String name = null;
                avatar = getResources().getDrawable(R.drawable.ic_qs_default_user);
                Uri res;
                String contactId = null;
                if (DBG) Log.e(TAG, lookupIntent.getDataString());
                if (mShortcutUri.contains(SMS_TEMPLATE) || mShortcutUri.contains(PHONE_NUMBER_TEMPLATE)) {
                    String phoneUri = lookupIntent.getDataString();
                    String phoneNumber = phoneUri.substring(phoneUri.indexOf(":")+1);
                    res = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, phoneNumber);
                } else {
                    res = ContactsContract.Contacts.lookupContact(getContentResolver(), lookupIntent.getData());
                }
                if (DBG) Log.e(TAG, res.toString());
                String[] projection = new String[] {
                    ContactsContract.Contacts.DISPLAY_NAME,
                    ContactsContract.Contacts.PHOTO_URI,
                    ContactsContract.Contacts.LOOKUP_KEY};
                final Cursor cursor = getContentResolver().query(res,projection,null,null,null);
                if (cursor != null) {
                    try {
                        if (cursor.moveToFirst()) {
                            name = cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME));
                            contactId = cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY));
                        }
                    } finally {
                        cursor.close();
                    }
                }
                if (DBG) Log.e(TAG, contactId);
                if (contactId != null) {
                    Uri lookupUri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, contactId);
                    res = ContactsContract.Contacts.lookupContact(getContentResolver(), lookupUri);
                    InputStream input = ContactsContract.Contacts.openContactPhotoInputStream(getContentResolver(), res, true);
                    if (input != null) {
                        rawAvatar = BitmapFactory.decodeStream(input);
                    }
                    if (rawAvatar != null) {
                        avatar = new BitmapDrawable(getResources(), rawAvatar);
                    }
                }
                return new Pair<String, Drawable>(name, avatar);
            }

            @Override
            protected void onPostExecute(Pair<String, Drawable> result) {
                super.onPostExecute(result);
                if (result.first != null && result.second != null) {
                    try {
                        mDialogLabel.setText(result.first);
                        mDialogIcon.setImageDrawable(result.second);
                        tempIconPath = customUserIconPath;
                        mImageTmp.createNewFile();
                        mImageTmp.setWritable(true, false);
                        Bitmap bitmap = ((BitmapDrawable)result.second).getBitmap();
                        FileOutputStream outStream = new FileOutputStream(mImageTmp);
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outStream);
                        outStream.close();
                    } catch(Exception e) {
                        e.printStackTrace();
                    }
                }
                mContactInfoTask = null;
            }
        };
        mContactInfoTask.execute();
    }

    public class IconAdapter extends BaseAdapter {

        TypedArray icons;
        String[] labels;

        public IconAdapter() {
            try {
                labels = getResources().getStringArray(R.array.quicksettings_shortcut_icon_picker_labels);
                icons = getResources().obtainTypedArray(R.array.quicksettings_shortcut_icon_picker_icons);
            } catch(Exception e){
                e.printStackTrace();
            }
        }

        @Override
        public int getCount() {
            return labels.length;
        }

        @Override
        public Object getItem(int position) {
            return icons.getDrawable(position);
        }

        public String getItemReference(int position) {
            String name = icons.getString(position);
            int separatorIndex = name.lastIndexOf(File.separator);
            int periodIndex = name.lastIndexOf('.');
            return name.substring(separatorIndex + 1, periodIndex);
        }

        @Override
        public long getItemId(int position) {
            return 0;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View iView = convertView;
            if (convertView == null) {
                iView = View.inflate(mActivity, android.R.layout.simple_list_item_1, null);
            }
            TextView tt = (TextView) iView.findViewById(android.R.id.text1);
            tt.setText(labels[position]);
            Drawable ic = ((Drawable) getItem(position)).mutate();
            tt.setCompoundDrawablePadding(15);
            tt.setCompoundDrawablesWithIntrinsicBounds(ic, null, null, null);
            return iView;
        }
    }
}
