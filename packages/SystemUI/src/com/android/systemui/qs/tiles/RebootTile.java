/*
 * Copyright (C) 2013 Slimroms
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

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.PowerManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ListView;

import android.util.Log;

import com.android.systemui.R;
import com.android.systemui.qs.QSDetailItems.Item;
import com.android.systemui.qs.QSDetailItemsList;
import com.android.systemui.qs.QSTile;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

public class RebootTile extends QSTile<QSTile.BooleanState> {

    private final RebootDetailAdapter mDetailAdapter;

    public RebootTile(Host host) {
        super(host);
        mDetailAdapter = new RebootDetailAdapter();
    }

    @Override
    public DetailAdapter getDetailAdapter() {
        return mDetailAdapter;
    }

    @Override
    protected BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleClick() {
        showDetail(true);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.visible = true;
        state.label = mContext.getString(R.string.quick_settings_reboot_label);
        state.icon = ResourceIcon.get(R.drawable.ic_qs_reboot);
    }

    @Override
    public void setListening(boolean listening) {
    }

    private class RebootDetailAdapter implements DetailAdapter, AdapterView.OnItemClickListener {

        private QSDetailItemsList mDetails;
        private QSDetailItemsList.QSDetailListAdapter mAdapter;

        private final List<Item> mRebootList = new ArrayList<>();

        private int clickedPosition = -1;

        @Override
        public int getTitle() {
            return R.string.quick_settings_reboot_label;
        }

        @Override
        public void setToggleState(boolean state) {
        }

        @Override
        public View createDetailView(Context context, View convertView, ViewGroup parent) {

            rebuildRebootList(true);

            mDetails = QSDetailItemsList.convertOrInflate(context, convertView, parent);
            ListView list = mDetails.getListView();
            list.setOnItemClickListener(this);
            list.setAdapter(mAdapter =
                    new QSDetailItemsList.QSDetailListAdapter(context, mRebootList));
            mDetails.setEmptyState(R.drawable.ic_qs_reboot, getTitle());

            return mDetails;
        }

        @Override
        public Intent getSettingsIntent() {
            return null;
        }

        @Override
        public Boolean getToggleState() {
            return null;
        }

        private void rebuildRebootList(boolean populate) {
            mRebootList.clear();
            String actions[] = mContext.getResources().getStringArray(
                    com.android.internal.R.array.shutdown_reboot_actions);
            String options[] = mContext.getResources().getStringArray(
                    com.android.internal.R.array.shutdown_reboot_options);
            if (populate) {
                for (int i = 0; i < options.length; i++) {
                    final Item item = new Item();
                    item.tag = actions[i];
                    item.line1 = options[i];
                    mRebootList.add(item);
                }
            }
            if (mAdapter != null) mAdapter.notifyDataSetChanged();
        }

        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            Item item = (Item) parent.getItemAtPosition(position);
            if (item == null) return;
            if (position != clickedPosition) {
                for (int i = 0; i < mRebootList.size(); i++) {
                    Item it = (Item) parent.getItemAtPosition(i);
                    if (it != null) it.line2 = null;
                }
                clickedPosition = position;
                item.line2 = mContext.getString(R.string.quick_settings_reboot_click_confirm);
                mAdapter.notifyDataSetChanged();
                return;
            }
            final String action = (String) item.tag;
            clickedPosition = -1;
            mHost.collapsePanels();
            Handler handler = new Handler();
            handler.postDelayed(new Runnable() {
                public void run() {
                    PowerManager pm =
                        (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
                    pm.reboot(action);
                }
            }, 500);
        }
    }

}
