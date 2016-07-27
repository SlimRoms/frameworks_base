/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.systemui.tuner;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Fragment;
import android.content.ClipData;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.PorterDuff.Mode;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.Secure;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.view.DragEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SubMenu;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnDragListener;
import android.view.View.OnTouchListener;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.R;
import com.android.systemui.qs.QSPanel;
import com.android.systemui.qs.QSTile;
import com.android.systemui.qs.QSTile.Host.Callback;
import com.android.systemui.qs.QSTile.ResourceIcon;
import com.android.systemui.qs.QSTileView;
import com.android.systemui.qs.tiles.IntentTile;
import com.android.systemui.statusbar.phone.QSTileHost;
import com.android.systemui.statusbar.policy.SecurityController;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import org.slim.provider.SlimSettings;
import org.slim.utils.QSUtil;

public class QsTuner extends Fragment implements Callback {

    private static final String TAG = "QsTuner";

    private static final int MENU_RESET = Menu.FIRST;

    private static final int MENU_QS_NUM_COLUMNS = Menu.FIRST + 1;
    private static final int SUBMENU_COLUMNS_3 = Menu.FIRST + 2;
    private static final int SUBMENU_COLUMNS_4 = Menu.FIRST + 3;
    private static final int SUBMENU_COLUMNS_5 = Menu.FIRST + 4;

    private DraggableQsPanel mQsPanel;
    private CustomHost mTileHost;

    private FrameLayout mDropTarget;

    private ScrollView mScrollRoot;

    private FrameLayout mAddTarget;

    private View mSpacer;

    public interface TopRowCallback {
        void topRowChanged();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        int numRows = SlimSettings.Secure.getInt(getActivity().getContentResolver(),
                SlimSettings.Secure.QS_NUM_TILE_COLUMNS, 3);

        SubMenu qsNumRows = menu.addSubMenu(0,
                MENU_QS_NUM_COLUMNS, 0, R.string.qs_num_columns_title);

        qsNumRows.add(1, SUBMENU_COLUMNS_3, 3, R.string.qs_num_columns_entries_three)
                .setChecked(numRows == 3);
        qsNumRows.add(1, SUBMENU_COLUMNS_4, 4, R.string.qs_num_columns_entries_four)
                .setChecked(numRows == 4);
        qsNumRows.add(1, SUBMENU_COLUMNS_5, 5, R.string.qs_num_columns_entries_five)
                .setChecked(numRows == 5);
        qsNumRows.setGroupCheckable(1, true, true);

        menu.add(0, MENU_RESET, 0, com.android.internal.R.string.reset);
    }

    public void onResume() {
        super.onResume();
        MetricsLogger.visibility(getContext(), MetricsLogger.TUNER_QS, true);
    }

    public void onPause() {
        super.onPause();
        MetricsLogger.visibility(getContext(), MetricsLogger.TUNER_QS, false);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_RESET:
                mTileHost.reset();
                break;
            case android.R.id.home:
                getFragmentManager().popBackStack();
                break;
            case SUBMENU_COLUMNS_3:
            case SUBMENU_COLUMNS_4:
            case SUBMENU_COLUMNS_5:
                item.setChecked(true);
                updateNumColumns(item.getOrder());
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    private void updateNumColumns(int cols) {
        SlimSettings.Secure.putInt(getActivity().getContentResolver(),
                SlimSettings.Secure.QS_NUM_TILE_COLUMNS, cols);
        mQsPanel.updateNumColumns();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        mScrollRoot = (ScrollView) inflater.inflate(R.layout.tuner_qs, container, false);

        mSpacer = mScrollRoot.findViewById(R.id.spacer);
        setupSpacer();

        mQsPanel = new DraggableQsPanel(getContext());
        mQsPanel.setTopRowCallback(new TopRowCallback() {
            @Override
            public void topRowChanged() {
                    updateSpacer();
            }
        });
        mTileHost = new CustomHost(getContext(), mQsPanel);
        mTileHost.setCallback(this);
        mQsPanel.setTiles(mTileHost.getTiles());
        mQsPanel.setHost(mTileHost);
        mQsPanel.refreshAllTiles();
        ((ViewGroup) mScrollRoot.findViewById(R.id.all_details)).addView(mQsPanel, 1);

        mDropTarget = (FrameLayout) mScrollRoot.findViewById(R.id.remove_target);
        setupDropTarget();
        mAddTarget = (FrameLayout) mScrollRoot.findViewById(R.id.add_target);
        setupAddTarget();
        return mScrollRoot;
    }

    @Override
    public void onDestroyView() {
        mTileHost.destroy();
        super.onDestroyView();
    }

    private void setupSpacer() {
        new DragHelper(mSpacer, new DropListener() {
            @Override
            public void onDrop(DraggableTile sourceTile) {
                SlimSettings.System.putIntForUser(getContext().getContentResolver(),
                        "num_top_rows", 1, UserHandle.USER_CURRENT);
                updateSpacer();
                mTileHost.updateTiles(sourceTile.mSpec);
            }
        });
        updateSpacer();
    }

    private void updateSpacer() {
        int cols = SlimSettings.System.getIntForUser(getContext().getContentResolver(),
                    "num_top_rows", 2, UserHandle.USER_CURRENT);
        if (cols < 1) {
            mSpacer.setVisibility(View.VISIBLE);
        } else if (cols > 0) {
            mSpacer.setVisibility(View.GONE);
        }
    }

    private void setupDropTarget() {
        QSTileView tileView = new CustomTileView(getContext());
        QSTile.State state = new QSTile.State();
        state.visible = true;
        state.icon = ResourceIcon.get(R.drawable.ic_delete);
        state.label = getString(com.android.internal.R.string.delete);
        tileView.onStateChanged(state);
        mDropTarget.addView(tileView);
        mDropTarget.setVisibility(View.GONE);
        new DragHelper(tileView, new DropListener() {
            @Override
            public void onDrop(DraggableTile sourceTile) {
                mTileHost.remove(sourceTile);
                mQsPanel.refreshAllTiles();
            }
        });
    }

    private void setupAddTarget() {
        QSTileView tileView = new CustomTileView(getContext());
        QSTile.State state = new QSTile.State();
        state.visible = true;
        state.icon = ResourceIcon.get(R.drawable.ic_add_circle_qs);
        state.label = getString(R.string.add_tile);
        tileView.onStateChanged(state);
        mAddTarget.addView(tileView);
        tileView.setClickable(true);
        tileView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                mTileHost.showAddDialog();
            }
        });
    }

    public void onStartDrag() {
        mDropTarget.post(new Runnable() {
            @Override
            public void run() {
                mDropTarget.setVisibility(View.VISIBLE);
                mAddTarget.setVisibility(View.GONE);
            }
        });
    }

    public void stopDrag() {
        mDropTarget.post(new Runnable() {
            @Override
            public void run() {
                mDropTarget.setVisibility(View.GONE);
                mAddTarget.setVisibility(View.VISIBLE);
            }
        });
    }

    @Override
    public void onTilesChanged() {
        mQsPanel.setTiles(mTileHost.getTiles());
        mQsPanel.refreshAllTiles();
    }

    private static int getLabelResource(String spec) {
        if (spec.equals("wifi")) return R.string.quick_settings_wifi_label;
        else if (spec.equals("bt")) return R.string.quick_settings_bluetooth_label;
        else if (spec.equals("inversion")) return R.string.quick_settings_inversion_label;
        else if (spec.equals("cell")) return R.string.quick_settings_cellular_detail_title;
        else if (spec.equals("airplane")) return R.string.airplane_mode;
        else if (spec.equals("dnd")) return R.string.quick_settings_dnd_label;
        else if (spec.equals("rotation")) return R.string.quick_settings_rotation_locked_label;
        else if (spec.equals("flashlight")) return R.string.quick_settings_flashlight_label;
        else if (spec.equals("location")) return R.string.quick_settings_location_label;
        else if (spec.equals("cast")) return R.string.quick_settings_cast_title;
        else if (spec.equals("hotspot")) return R.string.quick_settings_hotspot_label;
        else if (spec.equals("usb_tether")) return R.string.quick_settings_usb_tether_label;
        else if (spec.equals("ambient_display")) return R.string.quick_settings_ambient_display_label;
        else if (spec.equals("screenshot")) return R.string.quick_settings_screenshot_label;
        else if (spec.equals("nfc")) return R.string.quick_settings_nfc_label;
        else if (spec.equals("screenoff")) return R.string.quick_settings_screen_off;
        else if (spec.equals("sync")) return R.string.quick_settings_sync_label;
        else if (spec.equals("timeout")) return R.string.quick_settings_timeout_label;
        else if (spec.equals("brightness")) return R.string.quick_settings_brightness;
        else if (spec.equals("music")) return R.string.quick_settings_music_label;
        else if (spec.equals("reboot")) return R.string.quick_settings_reboot_label;
        else if (spec.equals("battery_saver")) return R.string.quick_settings_battery_saver;
        else if (spec.equals("compass")) return R.string.quick_settings_compass_label;
        else if (spec.equals("ime")) return R.string.quick_settings_ime_label;
        else if (spec.equals("volume")) return R.string.quick_settings_volume_panel_label;
        else if (spec.equals("sound")) return R.string.quick_settings_sound_label;
        else if (spec.equals("caffeine")) return R.string.quick_settings_caffeine_label;
        return 0;
    }

    private static int getIcon(String spec) {
        if (spec.equals("wifi")) return R.drawable.ic_qs_wifi_full_3;
        else if (spec.equals("bt")) return R.drawable.ic_qs_bluetooth_connected;
        else if (spec.equals("inversion")) return R.drawable.ic_invert_colors_enable;
        else if (spec.equals("cell")) return R.drawable.ic_qs_signal_full_3;
        else if (spec.equals("airplane")) return R.drawable.ic_signal_airplane_enable;
        else if (spec.equals("dnd")) return R.drawable.ic_qs_dnd_on;
        else if (spec.equals("rotation")) return R.drawable.ic_portrait_from_auto_rotate;
        else if (spec.equals("flashlight")) return R.drawable.ic_signal_flashlight_enable;
        else if (spec.equals("location")) return R.drawable.ic_signal_location_enable;
        else if (spec.equals("cast")) return R.drawable.ic_qs_cast_on;
        else if (spec.equals("hotspot")) return R.drawable.ic_hotspot_enable;
        else if (spec.equals("usb_tether")) return R.drawable.ic_qs_usb_tether_off;
        else if (spec.equals("ambient_display")) return R.drawable.ic_qs_ambientdisplay_on;
        else if (spec.equals("screenshot")) return R.drawable.ic_qs_screenshot;
        else if (spec.equals("nfc")) return R.drawable.ic_qs_nfc_on;
        else if (spec.equals("screenoff")) return R.drawable.ic_qs_power;
        else if (spec.equals("sync")) return R.drawable.ic_qs_sync_on;
        else if (spec.equals("timeout")) return R.drawable.ic_qs_screen_timeout_vector;
        else if (spec.equals("brightness")) return R.drawable.ic_qs_brightness_auto_on_alpha;
        else if (spec.equals("music")) return R.drawable.ic_qs_media_play;
        else if (spec.equals("reboot")) return R.drawable.ic_qs_reboot;
        else if (spec.equals("battery_saver")) return R.drawable.ic_qs_battery_saver_on;
        else if (spec.equals("compass")) return R.drawable.ic_qs_compass_on;
        else if (spec.equals("ime")) return R.drawable.ic_qs_ime;
        else if (spec.equals("volume")) return R.drawable.ic_qs_volume_panel;
        else if (spec.equals("sound")) return R.drawable.ic_qs_ringer_audible;
        else if (spec.equals("caffeine")) return R.drawable.ic_qs_caffeine_on;
        return R.drawable.android;
    }

    private static class CustomHost extends QSTileHost {

        DraggableQsPanel mPanel;

        public CustomHost(Context context, DraggableQsPanel panel) {
            super(context, null, null, null, null, null, null, null, null, null,
                    null, null, new BlankSecurityController());

            mPanel = panel;
        }

        @Override
        protected QSTile<?> createTile(String tileSpec) {
            return new DraggableTile(this, tileSpec);
        }

        protected DraggableQsPanel getPanel() {
            return mPanel;
        }

        public void replace(String oldTile, String newTile) {
            if (oldTile.equals(newTile)) {
                return;
            }

            MetricsLogger.action(getContext(), MetricsLogger.TUNER_QS_REORDER, oldTile + ","
                    + newTile);
            List<String> order = new ArrayList<>(mTileSpecs);
            int index = order.indexOf(oldTile);
            if (index < 0) {
                Log.e(TAG, "Can't find " + oldTile);
                return;
            }
            order.remove(newTile);
            order.add(index, newTile);
            setTiles(order);
        }

        public void remove(DraggableTile tile) {
            String spec = tile.mSpec;
            MetricsLogger.action(getContext(), MetricsLogger.TUNER_QS_REMOVE, spec);
            List<String> tiles = new ArrayList<>(mTileSpecs);
            tiles.remove(spec);
            setTiles(tiles);
        }

        public void updateTiles(String newTile) {
            replace(mTileSpecs.get(0), newTile);
        }

        public void add(String tile, int location) {
            MetricsLogger.action(getContext(), MetricsLogger.TUNER_QS_ADD, tile);
            List<String> tiles = new ArrayList<>(mTileSpecs);
            tiles.add(location, tile);
            setTiles(tiles);
        }

        public void add(String tile) {
            MetricsLogger.action(getContext(), MetricsLogger.TUNER_QS_ADD, tile);
            List<String> tiles = new ArrayList<>(mTileSpecs);
            tiles.add(tile);
            setTiles(tiles);
        }

        public void reset() {
            Secure.putStringForUser(getContext().getContentResolver(), TILES_SETTING,
                    "wifi,bt,dnd,cell,airplane,rotation,flashlight,location,cast",
                    ActivityManager.getCurrentUser());
            SlimSettings.System.putIntForUser(getContext().getContentResolver(),
                    "num_top_rows", 2, UserHandle.USER_CURRENT);
            SlimSettings.Secure.putIntForUser(getContext().getContentResolver(),
                    SlimSettings.Secure.QS_NUM_TILE_COLUMNS, 3, UserHandle.USER_CURRENT);
            mPanel.mCallback.topRowChanged();
            mPanel.refreshAllTiles();
        }

        private void setTiles(List<String> tiles) {
            Secure.putStringForUser(getContext().getContentResolver(), TILES_SETTING,
                    TextUtils.join(",", tiles), ActivityManager.getCurrentUser());
        }

        public void showAddDialog() {
            final List<String> availableTiles = new ArrayList<>();
            final ArrayList<String> available = new ArrayList<>();
            List<String> currentTiles = mTileSpecs;
            final Collection<String> tiles = QSUtil.getAvailableTiles(getContext());
            tiles.removeAll(currentTiles);

            final Iterator<String> i = tiles.iterator();
            while(i.hasNext()) {
                final String spec = i.next();
                int resource = getLabelResource(spec);
                if (resource != 0) {
                    availableTiles.add(getContext().getString(resource));
                    available.add(spec);
                }
            }
            availableTiles.add(getContext().getString(R.string.broadcast_tile));
            new AlertDialog.Builder(getContext())
                    .setTitle(R.string.add_tile)
                    .setAdapter(new IconAdapter(getContext(), available),
                            new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            if (which < available.size()) {
                                add(available.get(which));
                            } else {
                                showBroadcastTileDialog();
                            }
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        public void showBroadcastTileDialog() {
            final EditText editText = new EditText(getContext());
            new AlertDialog.Builder(getContext())
                    .setTitle(R.string.broadcast_tile)
                    .setView(editText)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            String action = editText.getText().toString();
                            if (isValid(action)) {
                                add(IntentTile.PREFIX + action + ')');
                            }
                        }
                    }).show();
        }

        private boolean isValid(String action) {
            for (int i = 0; i < action.length(); i++) {
                char c = action.charAt(i);
                if (!Character.isAlphabetic(c) && !Character.isDigit(c) && c != '.') {
                    return false;
                }
            }
            return true;
        }

        private static class BlankSecurityController implements SecurityController {
            @Override
            public boolean hasDeviceOwner() {
                return false;
            }

            @Override
            public boolean hasProfileOwner() {
                return false;
            }

            @Override
            public String getDeviceOwnerName() {
                return null;
            }

            @Override
            public String getProfileOwnerName() {
                return null;
            }

            @Override
            public boolean isVpnEnabled() {
                return false;
            }

            @Override
            public String getPrimaryVpnName() {
                return null;
            }

            @Override
            public String getProfileVpnName() {
                return null;
            }

            @Override
            public void onUserSwitched(int newUserId) {
            }

            @Override
            public void addCallback(SecurityControllerCallback callback) {
            }

            @Override
            public void removeCallback(SecurityControllerCallback callback) {
            }
        }
    }

    private static class IconAdapter extends BaseAdapter {
        ArrayList<String> mTileSpecs = new ArrayList<>();
        Context mContext;
        int mIconColor;

        IconAdapter(Context ctx, List<String> specs) {
            mContext = ctx;
            mTileSpecs.addAll(specs);
            mIconColor = ctx.getResources().getColor(R.color.qs_edit_tile_icon_color);
        }

        @Override
        public int getCount() {
            return mTileSpecs.size();
        }

        @Override
        public String getItem(int position) {
            return mTileSpecs.get(position);
        }

        @Override
        public long getItemId(int position) {
            return 0;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = View.inflate(mContext, R.layout.qs_add_item, null);
            }
            TextView txtView = (TextView) convertView.findViewById(R.id.title);
            txtView.setText(
                    mContext.getResources().getString(getLabelResource(mTileSpecs.get(position))));
            ImageView imgView = (ImageView) convertView.findViewById(R.id.icon);
            imgView.setImageDrawable(
                    mContext.getResources().getDrawable(getIcon(mTileSpecs.get(position))));
            imgView.setColorFilter(mIconColor, Mode.MULTIPLY);
            return convertView;
        }
    }

    private static class DraggableTile extends QSTile<QSTile.State>
            implements DropListener {
        private String mSpec;
        private QSTileView mView;

        protected DraggableTile(QSTile.Host host, String tileSpec) {
            super(host);
            mSpec = tileSpec;
        }

        @Override
        public QSTileView createTileView(Context context) {
            mView = new CustomTileView(context);
            return mView;
        }

        @Override
        public void setListening(boolean listening) {
        }

        @Override
        protected QSTile.State newTileState() {
            return new QSTile.State();
        }

        @Override
        protected void handleClick() {
        }

        @Override
        protected void handleUpdateState(QSTile.State state, Object arg) {
            state.visible = true;
            state.icon = ResourceIcon.get(getIcon(mSpec));
            state.label = getLabel();
        }

        private String getLabel() {
            int resource = getLabelResource(mSpec);
            if (resource != 0) {
                return mContext.getString(resource);
            }
            if (mSpec.startsWith(IntentTile.PREFIX)) {
                int lastDot = mSpec.lastIndexOf('.');
                if (lastDot >= 0) {
                    return mSpec.substring(lastDot + 1, mSpec.length() - 1);
                } else {
                    return mSpec.substring(IntentTile.PREFIX.length(), mSpec.length() - 1);
                }
            }
            return mSpec;
        }

        @Override
        public int getMetricsCategory() {
            return 20000;
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof DraggableTile) {
                return mSpec.equals(((DraggableTile) o).mSpec);
            }
            return false;
        }

        @Override
        public void onDrop(DraggableTile sourceTile) {
            ((CustomHost) mHost).getPanel().replace(this, sourceTile);
        }

    }

    private static class CustomTileView extends QSTileView {

        private int mTextColor;
        private int mIconColor;
        private int mDividerColor;

        protected CustomTileView(Context context) {
            super(context);

            mIconColor = context.getResources().getColor(R.color.qs_edit_tile_icon_color);
            mTextColor = context.getResources().getColor(R.color.qs_edit_tile_text_color);
            mDividerColor = context.getResources().getColor(R.color.qs_edit_divider_color);

            setColors();
        }

        @Override
        protected void recreateLabel() {
            super.recreateLabel();
            setColors();
        }

        private void setColors() {
            if (mDualLabel != null) {
                mDualLabel.setTextColor(mTextColor);
            }
            if (mLabel != null) {
                mLabel.setTextColor(mTextColor);
            }
            if (mDivider != null) {
                mDivider.setBackgroundColor(mDividerColor);
            }
        }

        @Override
        protected void setIcon(ImageView iv, QSTile.State state) {
            super.setIcon(iv, state);
            iv.setColorFilter(mIconColor, Mode.MULTIPLY);
        }
    }

    private class DragHelper implements OnDragListener {

        private final View mView;
        private final DropListener mListener;

        public DragHelper(View view, DropListener dropListener) {
            mView = view;
            mListener = dropListener;
            mView.setOnDragListener(this);
        }

        @Override
        public boolean onDrag(View v, DragEvent event) {
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_ENTERED:
                    mView.setBackgroundColor(0x77ffffff);
                    break;
                case DragEvent.ACTION_DRAG_ENDED:
                    stopDrag();
                case DragEvent.ACTION_DRAG_EXITED:
                    mView.setBackgroundColor(0x0);
                    break;
                case DragEvent.ACTION_DROP:
                    stopDrag();
                    DraggableTile tile = (DraggableTile) event.getLocalState();
                    mListener.onDrop(tile);
                    break;
            }
            return true;
        }

    }

    public interface DropListener {
        void onDrop(DraggableTile sourceTile);
    }

    private class DraggableQsPanel extends QSPanel implements OnTouchListener {

        private TopRowCallback mCallback;

        public DraggableQsPanel(Context context) {
            super(context);
            mBrightnessView.setVisibility(View.GONE);
        }

        public void setTopRowCallback(TopRowCallback callback) {
            mCallback = callback;
        }

        @Override
        public void setTiles(Collection<QSTile<?>> tiles) {
            super.setTiles(tiles);
        }

        @Override
        protected int getRowTop(int row) {
            row = (mTopColumns == 0) ? row - 1 : row;
            if (row <= 0) return 0;
            return mLargeCellHeight - mDualTileUnderlap + (row - 1) * mCellHeight;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            for (TileRecord r : mRecords) {
                new DragHelper(r.tileView, (DraggableTile) r.tile);
                r.tileView.setTag(r.tile);
                r.tileView.setOnTouchListener(this);

                for (int i = 0; i < r.tileView.getChildCount(); i++) {
                    r.tileView.getChildAt(i).setClickable(false);
                }
            }
        }

        public void replace(DraggableTile oldTile, DraggableTile newTile) {

            int oldR = -1, newR = -1, oldC = -1, newC = -1;

            for (TileRecord r : mRecords) {
                if (r.tile == oldTile) {
                    oldR = r.row;
                    oldC = r.col;
                } else if (r.tile == newTile) {
                    newR = r.row;
                    newC = r.col;
                }
            }

            if (oldR != -1 && newR != -1) {
                int newTopColumns = SlimSettings.System.getIntForUser(
                        getContext().getContentResolver(),
                        "num_top_rows", 2, UserHandle.USER_CURRENT);

                if (newR == 0) {
                    if (newTopColumns > 0) {
                        newTopColumns--;
                    }
                }
                if (oldR == 0) {
                    if (newTopColumns < 3) {
                        newTopColumns++;
                    }
                }

                SlimSettings.System.putIntForUser(getContext().getContentResolver(),
                        "num_top_rows", newTopColumns, UserHandle.USER_CURRENT);

                mCallback.topRowChanged();
            }

            ((CustomHost) mHost).replace(oldTile.mSpec, newTile.mSpec);
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    DraggableTile tile = (DraggableTile) v.getTag();
                    String tileSpec = (String) tile.mSpec;
                    ClipData data = ClipData.newPlainText(tileSpec, tileSpec);
                    v.startDrag(data, new View.DragShadowBuilder(v), tile, 0);
                    onStartDrag();
                    return true;
            }
            return false;
        }
    }

}
