package com.android.systemui.slim;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.DisplayInfo;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import com.android.internal.util.ArrayUtils;
import org.slim.action.ActionConfig;
import org.slim.action.ActionConstants;
import org.slim.action.ActionHelper;
import org.slim.utils.DeviceUtils;
import org.slim.utils.DeviceUtils.FilteredDeviceFeaturesArray;
import org.slim.utils.ImageHelper;
import com.android.systemui.R;
import com.android.systemui.statusbar.phone.NavigationBarView;
import com.android.systemui.statusbar.policy.KeyButtonView;

import java.util.ArrayList;

public class NavigationBarEditor implements View.OnTouchListener {

    public static final String NAVBAR_EDIT_ACTION = "android.intent.action.NAVBAR_EDIT";

    private static final String SETTINGS_METADATA_NAME = "com.android.settings";

    private Context mContext;
    private NavigationBarView mNavBar;

    private FilteredDeviceFeaturesArray mActionsArray;

    private KeyButtonView mAddButton;

    private boolean mEditing;
    private boolean mLongPressed;

    public static final int[] SMALL_BUTTON_IDS = { R.id.menu, R.id.menu_left, R.id.ime_switcher };

     // start point of the current drag operation
    private float mDragOrigin;

    // just to avoid reallocations
    private static final int[] sLocation = new int[2];

    // Button chooser dialog
    private AlertDialog mDialog;

    private AlertDialog mActionDialog;

    /**
     * Longpress runnable to assign buttons in edit mode
     */
    private Runnable mCheckLongPress = new Runnable() {
        public void run() {
            if (mEditing) {
                mLongPressed = true;
                mNavBar.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            }
        }
    };

    public NavigationBarEditor(NavigationBarView navBar) {
        mContext = navBar.getContext();
        mNavBar = navBar;
        createAddButton();
        initActionsArray();
    }

    private void createAddButton() {
        mAddButton = mNavBar.generateKey(false, "", "", "");
        Drawable d = mContext.getResources().getDrawable(R.drawable.ic_action_add);
        mAddButton.setImageDrawable(d);
    }

    private void initActionsArray() {
        PackageManager pm = mContext.getPackageManager();
        Resources settingsResources = getSettingsResources();
        mActionsArray = new FilteredDeviceFeaturesArray();
        mActionsArray = DeviceUtils.filterUnsupportedDeviceFeatures(mContext,
                settingsResources.getStringArray(
                        settingsResources.getIdentifier(SETTINGS_METADATA_NAME
                        + ":array/shortcut_action_values", null, null)),
                settingsResources.getStringArray(
                        settingsResources.getIdentifier(SETTINGS_METADATA_NAME
                        + ":array/shortcut_action_entries", null, null)));
    }

    private void updateButton(KeyButtonView v) {
        v.setEditing(mEditing);
        v.setOnTouchListener(mEditing ? this : null);
        v.setOnClickListener(null);
        v.setOnLongClickListener(null);
    }

    public void setEditing(boolean editing) {
        mEditing = editing;
        if (mEditing) {
            mNavBar.addButton(mAddButton);
        } else {
            mNavBar.removeButton(mAddButton);
            save();
        }
        for (KeyButtonView key : mNavBar.getButtons()) {
            updateButton(key);
        }
    }

    @Override
    public boolean onTouch(final View view, MotionEvent event) {
        if (!mEditing || (mDialog != null && mDialog.isShowing())) {
            return false;
        }

        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            view.setPressed(true);
            view.getLocationOnScreen(sLocation);
            mDragOrigin = sLocation[mNavBar.isVertical() ? 1 : 0];
            view.postDelayed(mCheckLongPress, ViewConfiguration.getLongPressTimeout());
        } else if (event.getAction() == MotionEvent.ACTION_MOVE) {
            view.setPressed(false);

            if (!mLongPressed || ArrayUtils.contains(SMALL_BUTTON_IDS, view.getId())
                    || view == mAddButton) {
                return false;
            }

            ViewGroup viewParent = (ViewGroup) view.getParent();
            float pos = mNavBar.isVertical() ? event.getRawY() : event.getRawX();
            float buttonSize = mNavBar.isVertical() ? view.getHeight() : view.getWidth();
            float min = mNavBar.isVertical() ? viewParent.getTop() : (viewParent.getLeft() - buttonSize / 2);
            float max = mNavBar.isVertical() ? (viewParent.getTop() + viewParent.getHeight())
                    : (viewParent.getLeft() + viewParent.getWidth());

            // Prevents user from dragging view outside of bounds
            if (pos < min || pos > max) {
                //return false;
            }
            if (true) {
                view.setX(pos - viewParent.getLeft() - buttonSize / 2);
            } else {
                view.setY(pos - viewParent.getTop() - buttonSize / 2);
            }
            View affectedView = findInterceptingView(pos, view);
            if (affectedView == null) {
                return false;
            }
            moveButton(affectedView, view);
        } else if (event.getAction() == MotionEvent.ACTION_UP
                || event.getAction() == MotionEvent.ACTION_CANCEL) {
            view.setPressed(false);
            view.removeCallbacks(mCheckLongPress);

            if (!mLongPressed) {

                if (view == mAddButton) {
                    AlertDialog.Builder builder = new AlertDialog.Builder(mContext)
                            .setTitle(mContext.getString(R.string.navbar_dialog_title))
                            .setItems(mActionsArray.entries, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    String action = mActionsArray.values[which];
                                    String description = mActionsArray.entries[which];
                                    addButton(action, description);
                                }
                            })
                            .setNegativeButton(android.R.string.cancel,
                                    new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int id) {
                                    dialog.cancel();
                                }
                            });

                    mDialog = builder.create();
                    mDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG);
                    mDialog.setCanceledOnTouchOutside(false);
                    mDialog.show();
                } else {
                    editAction((KeyButtonView) view);
                }
            } else {
                // Reset the dragged view to its original location
                ViewGroup parent = (ViewGroup) view.getParent();

                if (!mNavBar.isVertical()) {
                    view.setX(mDragOrigin - parent.getLeft());
                } else {
                    view.setY(mDragOrigin - parent.getTop());
                }
            }
            mLongPressed = false;
        }
        return true;
    }

    private void moveButton(View targetView, View view) {
        ViewGroup parent = (ViewGroup) view.getParent();

        targetView.getLocationOnScreen(sLocation);
        if (true) {
            targetView.setX(mDragOrigin - parent.getLeft());
            mDragOrigin = sLocation[0];
        } else {
            targetView.setY(mDragOrigin - parent.getTop());
            mDragOrigin = sLocation[1];
        }
        
    }

    public boolean isEditing() {
        return mEditing;
    }

    /**
     * Find intersecting view in mButtonViews
     * @param pos - pointer location
     * @param v - view being dragged
     * @return intersecting view or null
     */
    private View findInterceptingView(float pos, View v) {
        for (KeyButtonView otherView : mNavBar.getButtons()) {
            if (otherView == v) {
                continue;
            }

            if (ArrayUtils.contains(SMALL_BUTTON_IDS, otherView.getId())
                    || otherView == mAddButton) {
                continue;
            }

            otherView.getLocationOnScreen(sLocation);
            float otherPos = sLocation[mNavBar.isVertical() ? 1 : 0];
            float otherDimension = mNavBar.isVertical() ? v.getHeight() : v.getWidth();

            if (pos > (otherPos + otherDimension / 4) && pos < (otherPos + otherDimension)) {
                return otherView;
            }
        }
        return null;
    }

    public View getAddButton() {
        return mAddButton;
    }

    private void addButton(String action, String description) {
        ActionConfig actionConfig = new ActionConfig(
                action, description,
                ActionConstants.ACTION_NULL,
                getSettingsResources().getString(
                        getSettingsResources().getIdentifier(SETTINGS_METADATA_NAME
                        + ":string/shortcut_action_none", null, null)),
                ActionConstants.ICON_EMPTY);

        KeyButtonView v = mNavBar.generateKey(mNavBar.isVertical(),
                actionConfig.getClickAction(),
                actionConfig.getLongpressAction(),
                actionConfig.getIcon());
        v.setConfig(actionConfig);

        updateButton(v);

        mNavBar.addButton(v, mNavBar.getNavButtons().indexOfChild(mAddButton));
    }

    private Resources getSettingsResources() {
        try {
            return mContext.getPackageManager().getResourcesForApplication(SETTINGS_METADATA_NAME);
        } catch (Exception e) {
            return null;
        }
    }

    public void save() {

       ArrayList<ActionConfig> buttons = new ArrayList<>();

        for (View v : mNavBar.getButtons()) {
            if (v instanceof KeyButtonView) {
                KeyButtonView key = (KeyButtonView) v;

                if (ArrayUtils.contains(SMALL_BUTTON_IDS, v.getId()) || mAddButton == v) {
                    continue;
                }

                ActionConfig config = key.getConfig();
                buttons.add(config);
            }
        }

        ActionHelper.setNavBarConfig(mContext, buttons, false);
    }

    private void editAction(KeyButtonView key) {
        AlertDialog.Builder builder = new AlertDialog.Builder(mContext);

        View view = View.inflate(mContext, R.layout.dialog_action, null);

        TextView single = (TextView) view.findViewById(R.id.click_action_description);
        TextView longPress = (TextView) view.findViewById(R.id.longpress_action_description);
        TextView doubleTap = (TextView) view.findViewById(R.id.doubletap_action_description);

        ActionConfig config = key.getConfig();

        single.setText(config.getClickActionDescription());
        longPress.setText(config.getLongpressActionDescription());

        builder.setView(view);

        builder.setNegativeButton(android.R.string.cancel, null);

        mActionDialog = builder.create();
        mActionDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG);
        mActionDialog.setCanceledOnTouchOutside(false);
        mActionDialog.show();
    }
            
}
