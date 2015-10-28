/*
 * Copyright (C) 2015 The CyanogenMod Open Source Project
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

package com.android.systemui.slimnavrings;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Vibrator;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.TextView;
import android.database.Cursor;
import android.widget.ArrayAdapter;
import android.net.Uri;
import android.widget.Toast;

import com.android.systemui.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.android.internal.util.slim.ActionConstants.*;
import com.android.internal.util.slim.DeviceUtils;
import com.android.internal.util.slim.DeviceUtils.FilteredDeviceFeaturesArray;
import com.android.internal.util.slim.TaskerIntent;

public class ShortcutPickHelper {
    private static final String SETTINGS_METADATA_NAME = "com.android.settings";
    private final Context mContext;
    private final AppPickAdapter mAdapter;
    private TaskerPickAdapter mTaskerAdapter = null;
    private final Intent mBaseIntent;
    private final int mIconSize;
    private OnPickListener mListener;
    private PackageManager mPackageManager;
    private ActionHolder mActions;

    public interface OnPickListener {
        void shortcutPicked(String uri);
        void taskPicked(String taskName);
    }

    public ShortcutPickHelper(Context context, OnPickListener listener) {
        mContext = context;
        mPackageManager = context.getPackageManager();
        mBaseIntent = new Intent(Intent.ACTION_MAIN);
        mBaseIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        mAdapter = new AppPickAdapter();
        mTaskerAdapter = new TaskerPickAdapter(mContext, R.layout.pick_item,
            R.id.tvPickItem, new ArrayList<String>());
        mListener = listener;
        mIconSize = context.getResources().getDimensionPixelSize(android.R.dimen.app_icon_size);
        createActionList();
    }

    private class AppPickAdapter extends BaseAdapter {

        private final List<ResolveInfo> mItems;

        AppPickAdapter() {
            mItems = mPackageManager.queryIntentActivities(mBaseIntent, 0);
            Collections.sort(mItems, new ResolveInfo.DisplayNameComparator(mPackageManager));
        }

        @Override
        public int getCount() {
            return mItems.size();
        }

        @Override
        public Object getItem(int position) {
            return mItems.get(position);
        }

        @Override
        public long getItemId(int position) {
            return 0;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = View.inflate(mContext, R.layout.pick_item, null);
            }

            ResolveInfo item = (ResolveInfo) getItem(position);
            TextView textView = (TextView) convertView;
            textView.setText(item.loadLabel(mPackageManager));
            Drawable icon = item.loadIcon(mPackageManager);
            icon.setBounds(0, 0, mIconSize, mIconSize);
            textView.setCompoundDrawables(icon, null, null, null);

            return convertView;
        }
    }

    private class TaskerPickAdapter extends ArrayAdapter<String> {

        TaskerPickAdapter(Context context, int layout, int textViewResId,
            List<String> items) {
            super(context, layout, textViewResId, items);
        }
    }

    private void pickApp() {
        AlertDialog.Builder builder = new AlertDialog.Builder(mContext)
                .setTitle(R.string.navbar_dialog_title)
                .setAdapter(mAdapter, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        ResolveInfo resolveInfo = (ResolveInfo) mAdapter.getItem(which);
                        Intent intent = new Intent(mBaseIntent);
                        intent.setClassName(resolveInfo.activityInfo.packageName,
                                resolveInfo.activityInfo.name);
                        mListener.shortcutPicked(intent.toUri(0));
                        dialog.dismiss();
                    }
                })
                .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        mListener.shortcutPicked(null);
                        dialog.cancel();
                    }
                });

        Dialog dialog = builder.create();
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL);
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
    }

    private void pickTasker()
    {
        mTaskerAdapter.clear();
        final TaskerIntent.Status taskerStatus = TaskerIntent.testStatus(mContext);
        if (taskerStatus.equals(TaskerIntent.Status.OK)) {
            final Cursor cursor = mContext.getContentResolver()
                .query(Uri.parse(TaskerIntent.TASKER_TASKS_URI), null, null, null, null);
            if (cursor != null) {
                final int index = cursor.getColumnIndex(TaskerIntent.COLUMN_NAME);
                while (cursor.moveToNext()) {
                    mTaskerAdapter.add(cursor.getString(index));
                }
                cursor.close();
            }
        } else {
            Toast.makeText(mContext,
                TaskerIntent.getStatusStringRes(taskerStatus),
                Toast.LENGTH_LONG).show();
            return;
        }
        mTaskerAdapter.notifyDataSetChanged();
        if (mTaskerAdapter.getCount() > 0) {
            AlertDialog.Builder builder = new AlertDialog.Builder(mContext)
                .setTitle(R.string.navbar_dialog_title)
                .setAdapter(mTaskerAdapter, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        mListener.taskPicked(mTaskerAdapter.getItem(which));
                        dialog.dismiss();
                    }
                })
                .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        mListener.taskPicked(null);
                        dialog.cancel();
                    }
                });

            Dialog dialog = builder.create();
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL);
            dialog.setCanceledOnTouchOutside(false);
            dialog.show();
        } else {
            Toast.makeText(mContext,
                R.string.tasker_no_tasks_found,
                Toast.LENGTH_LONG).show();
        }
    }

    public void pickShortcut(boolean showNone) {
        if (showNone) {
            mActions.addAction(ACTION_NULL, R.string.navring_action_none, 0);
        } else {
            mActions.removeAction(ACTION_NULL);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(mContext)
                .setTitle(mContext.getString(R.string.navbar_dialog_title))
                .setItems(mActions.getEntries(), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String item = mActions.getAction(which);
                        if (item.equals(ACTION_APP)) {
                            pickApp();
                            dialog.dismiss();
                        } else if (item.equals(ACTION_TASKER)) {
                            pickTasker();
                            dialog.dismiss();
                        } else {
                            mListener.shortcutPicked(item);
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        mListener.shortcutPicked(null);
                        dialog.cancel();
                    }
                });

        Dialog dialog = builder.create();
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL);
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
    }

    private void createActionList() {
        mActions = new ActionHolder();

        PackageManager pm = mContext.getPackageManager();
        if (pm == null) {
            mActions.addAction(ACTION_NULL, R.string.navring_action_none, 0);
            return;
        }

        Resources res = mContext.getResources();
        try {
            res = pm.getResourcesForApplication(SETTINGS_METADATA_NAME);
        } catch (Exception e) {
            Log.e("ShortcutHelper:", "can't access settings resources",e);
            mActions.addAction(ACTION_NULL, R.string.navring_action_none, 0);
            return;
        }

        FilteredDeviceFeaturesArray mFinalActionDialogArray = new FilteredDeviceFeaturesArray();
        mFinalActionDialogArray = DeviceUtils.filterUnsupportedDeviceFeatures(mContext,
                res.getStringArray(res.getIdentifier(
                        "shortcut_action_navring_values", "array", SETTINGS_METADATA_NAME)),
                res.getStringArray(res.getIdentifier(
                        "shortcut_action_navring_entries", "array", SETTINGS_METADATA_NAME)));

        mActions.addAction(mFinalActionDialogArray);
    }

    private class ActionHolder {
        private ArrayList<String> mAvailableEntries = new ArrayList<String>();
        private ArrayList<String> mAvailableValues = new ArrayList<String>();

        public void addAction(FilteredDeviceFeaturesArray filteredDeviceFeaturesArray) {
            Collections.addAll(mAvailableEntries, filteredDeviceFeaturesArray.entries);
            Collections.addAll(mAvailableValues, filteredDeviceFeaturesArray.values);
        }

        public void addAction(String action, int entryResId, int index) {
            int itemIndex = getActionIndex(action);
            if (itemIndex != -1) {
                return;
            }
            mAvailableEntries.add(index, mContext.getString(entryResId));
            mAvailableValues.add(index, action);
        }

        public void addAction(String action, int entryResId) {
            int index = getActionIndex(action);
            if (index != -1) {
                return;
            }
            mAvailableEntries.add(mContext.getString(entryResId));
            mAvailableValues.add(action);
        }

        public void removeAction(String action) {
            int index = getActionIndex(action);
            if (index != -1) {
                mAvailableEntries.remove(index);
                mAvailableValues.remove(index);
            }
        }

        public int getActionIndex(String action) {
            int count = mAvailableValues.size();
            for (int i = 0; i < count; i++) {
                if (TextUtils.equals(mAvailableValues.get(i), action)) {
                    return i;
                }
            }
            return -1;
        }
        public String getAction(int index) {
            if (index > mAvailableValues.size()) {
                return null;
            }
            return mAvailableValues.get(index);
        }
        public String[] getEntries() {
            return mAvailableEntries.toArray(new String[mAvailableEntries.size()]);
        }
    }
}
