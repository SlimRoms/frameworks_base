/*
 * Copyright (C) 2008 The Android Open Source Project
 * Copyright (C) 2014 The TeamEos Project
 *
 * Contributor: Randall Rushing aka Bigrushdog
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
 *
 * AOSP based Softkey navigation implementation and action executor
 *
 */

package com.android.systemui.statusbar.phone;

import android.animation.LayoutTransition;
import android.animation.LayoutTransition.TransitionListener;
import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.app.StatusBarManager;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import com.android.systemui.R;
import com.android.systemui.statusbar.BaseNavigationBar;
import com.android.systemui.statusbar.BaseStatusBar;
import com.android.systemui.statusbar.policy.DeadZone;
import com.android.systemui.statusbar.policy.KeyButtonView;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;

public class NavigationBarView extends BaseNavigationBar {
    final static boolean DEBUG = false;
    final static String TAG = "PhoneStatusBar/NavigationBarView";
    boolean mShowMenu;
    private boolean mKeyguardShowing;
    int mNavigationIconHints = 0;

    private BackButtonDrawable mBackIcon, mBackLandIcon;
    private Drawable mHomeIcon, mHomeLandIcon;
    private Drawable mRecentIcon, mRecentLandIcon;
    private Drawable mMenuIcon, mMenuLandIcon;

    private DeadZone mDeadZone;
    private NavigationBarViewTaskSwitchHelper mTaskSwitchHelper;
    private final NavigationBarTransitions mBarTransitions;

    // performs manual animation in sync with layout transitions
    private final NavTransitionListener mTransitionListener = new NavTransitionListener();

    private boolean mIsLayoutRtl;
    private boolean mForceShowMenuFromUser;
    private boolean mDelegateIntercepted;
    private SoftkeyActionHandler mSoftkeyHandler;
    private NavbarObserver mObserver;

    private class NavbarObserver extends ContentObserver {

        public NavbarObserver(Handler handler) {
            super(handler);
            // TODO Auto-generated constructor stub
        }

        void observe() {
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NAVIGATION_BAR_FORCE_SHOW_MENU), false, this, UserHandle.USER_ALL);
        }

        void unobserve() {
            mContext.getContentResolver().unregisterContentObserver(this);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            Uri showMenuUri = Settings.System.getUriFor(Settings.System.NAVIGATION_BAR_FORCE_SHOW_MENU);
            if (showMenuUri != null && uri.equals(showMenuUri)) {
                mForceShowMenuFromUser = Settings.System.getIntForUser(mContext.getContentResolver(),
                        Settings.System.NAVIGATION_BAR_FORCE_SHOW_MENU, 0, UserHandle.USER_CURRENT) == 1;
                final boolean hideOverride = shouldForceShowMenu();
                setMenuVisibility(hideOverride, true);
            }
        }
    };

    private class NavTransitionListener implements TransitionListener {
        private boolean mBackTransitioning;
        private boolean mHomeAppearing;
        private long mStartDelay;
        private long mDuration;
        private TimeInterpolator mInterpolator;

        @Override
        public void startTransition(LayoutTransition transition, ViewGroup container,
                View view, int transitionType) {
            if (view.getId() == R.id.back) {
                mBackTransitioning = true;
            } else if (view.getId() == R.id.home && transitionType == LayoutTransition.APPEARING) {
                mHomeAppearing = true;
                mStartDelay = transition.getStartDelay(transitionType);
                mDuration = transition.getDuration(transitionType);
                mInterpolator = transition.getInterpolator(transitionType);
            }
        }

        @Override
        public void endTransition(LayoutTransition transition, ViewGroup container,
                View view, int transitionType) {
            if (view.getId() == R.id.back) {
                mBackTransitioning = false;
            } else if (view.getId() == R.id.home && transitionType == LayoutTransition.APPEARING) {
                mHomeAppearing = false;
            }
        }

        public void onBackAltCleared() {
            // When dismissing ime during unlock, force the back button to run the same appearance
            // animation as home (if we catch this condition early enough).
            if (!mBackTransitioning && getBackButton().getVisibility() == VISIBLE
                    && mHomeAppearing && getHomeButton().getAlpha() == 0) {
                getBackButton().setAlpha(0);
                ValueAnimator a = ObjectAnimator.ofFloat(getBackButton(), "alpha", 0, 1);
                a.setStartDelay(mStartDelay);
                a.setDuration(mDuration);
                a.setInterpolator(mInterpolator);
                a.start();
            }
        }
    }

    private final OnClickListener mImeSwitcherClickListener = new OnClickListener() {
        @Override
        public void onClick(View view) {
            ((InputMethodManager) mContext.getSystemService(Context.INPUT_METHOD_SERVICE))
                    .showInputMethodPicker();
        }
    };

    public NavigationBarView(Context context, AttributeSet attrs) {
        super(context, attrs);

        final Resources res = getContext().getResources();
        mShowMenu = false;
        mTaskSwitchHelper = new NavigationBarViewTaskSwitchHelper(context);

        getIcons(res);
        mSoftkeyHandler = new SoftkeyActionHandler(this);
        mBarTransitions = new NavigationBarTransitions(this);
        mForceShowMenuFromUser = Settings.System.getIntForUser(context.getContentResolver(),
                Settings.System.NAVIGATION_BAR_FORCE_SHOW_MENU, 0, UserHandle.USER_CURRENT) == 1;
        mObserver = new NavbarObserver(new Handler());
        mObserver.observe();
    }

    public BarTransitions getBarTransitions() {
        return mBarTransitions;
    }

    public void setBar(BaseStatusBar phoneStatusBar) {
        super.setBar(phoneStatusBar);
        mTaskSwitchHelper.setBar(phoneStatusBar);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        initDownStates(event);
        if (!mDelegateIntercepted && mTaskSwitchHelper.onTouchEvent(event)) {
            return true;
        }
        if (mDeadZone != null && event.getAction() == MotionEvent.ACTION_OUTSIDE) {
            mDeadZone.poke(event);
        }
        if (mDelegateHelper != null && mDelegateIntercepted) {
            boolean ret = mDelegateHelper.onInterceptTouchEvent(event);
            if (ret) return true;
        }
        return super.onTouchEvent(event);
    }

    private void initDownStates(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            mDelegateIntercepted = false;
        }
    }    

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        initDownStates(event);
        boolean intercept = mTaskSwitchHelper.onInterceptTouchEvent(event);
        if (!intercept) {
            mDelegateIntercepted = mDelegateHelper.onInterceptTouchEvent(event);
            intercept = mDelegateIntercepted;
        } else {
            MotionEvent cancelEvent = MotionEvent.obtain(event);
            cancelEvent.setAction(MotionEvent.ACTION_CANCEL);
            mDelegateHelper.onInterceptTouchEvent(cancelEvent);
            cancelEvent.recycle();
        }
        return intercept;
    }

    @Override
    public void setKeyButtonListeners(OnTouchListener homeActionListener, OnTouchListener userAutoHideListener) {
        super.setKeyButtonListeners(homeActionListener, userAutoHideListener);
        setOnTouchListener(mUserAutoHideListener);
        ((KeyButtonView) getHomeButton()).setHomeActionListener(mHomeActionListener);
    }

    @Override
    public void setLeftInLandscape(boolean leftInLandscape) {
        super.setLeftInLandscape(leftInLandscape);
//        mDeadZone.setStartFromRight(leftInLandscape);
    }

    public View getRecentsButton() {
        return mCurrentView.findViewById(R.id.recent_apps);
    }

    public View getMenuButton() {
        return mCurrentView.findViewById(R.id.menu);
    }

    public View getBackButton() {
        return mCurrentView.findViewById(R.id.back);
    }

    public View getHomeButton() {
        return mCurrentView.findViewById(R.id.home);
    }

    public View getImeSwitchButton() {
        return mCurrentView.findViewById(R.id.ime_switcher);
    }

    private void getIcons(Resources res) {
        mBackIcon = new BackButtonDrawable(res.getDrawable(R.drawable.ic_sysbar_back));
        mBackLandIcon = new BackButtonDrawable(res.getDrawable(R.drawable.ic_sysbar_back_land));
        mRecentIcon = res.getDrawable(R.drawable.ic_sysbar_recent);
        mRecentLandIcon = res.getDrawable(R.drawable.ic_sysbar_recent_land);
        mHomeIcon = res.getDrawable(R.drawable.ic_sysbar_home);
        mHomeLandIcon = res.getDrawable(R.drawable.ic_sysbar_home_land);
        mMenuIcon = res.getDrawable(R.drawable.ic_sysbar_menu);
        mMenuLandIcon = res.getDrawable(R.drawable.ic_sysbar_menu_land);
    }

    @Override
    protected void onUpdateResources(Resources res) {
        getIcons(res);
    }

    @Override
    protected void onUpdateRotatedView(ViewGroup container, Resources res){
        ImageView ime = (ImageView) container.findViewById(R.id.ime_switcher);
        if (ime != null) {
            ime.setImageDrawable(null);
            ime.setImageDrawable(res.getDrawable(R.drawable.ic_ime_switcher_default));
        }
    }

    @Override
    public void setLayoutDirection(int layoutDirection) {
        getIcons(getAvailableResources());
        super.setLayoutDirection(layoutDirection);
    }

    public void setNavigationIconHints(int hints) {
        setNavigationIconHints(hints, false);
    }

    public void setNavigationIconHints(int hints, boolean force) {
        if (!force && hints == mNavigationIconHints) return;
        final boolean backAlt = (hints & StatusBarManager.NAVIGATION_HINT_BACK_ALT) != 0;
        if ((mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_BACK_ALT) != 0 && !backAlt) {
            mTransitionListener.onBackAltCleared();
        }
        if (DEBUG) {
            android.widget.Toast.makeText(getContext(),
                "Navigation icon hints = " + hints,
                500).show();
        }

        mNavigationIconHints = hints;

        ((ImageView)getBackButton()).setImageDrawable(null);
        ((ImageView)getBackButton()).setImageDrawable(mVertical ? mBackLandIcon : mBackIcon);
        mBackLandIcon.setImeVisible(backAlt);
        mBackIcon.setImeVisible(backAlt);

        ((ImageView)getRecentsButton()).setImageDrawable(mVertical ? mRecentLandIcon : mRecentIcon);
        ((ImageView)getHomeButton()).setImageDrawable(mVertical ? mHomeLandIcon : mHomeIcon);
        ((ImageView)getMenuButton()).setImageDrawable(mVertical ? mMenuLandIcon : mMenuIcon);

        final boolean showImeButton = ((hints & StatusBarManager.NAVIGATION_HINT_IME_SHOWN) != 0);
        getImeSwitchButton().setVisibility(showImeButton ? View.VISIBLE : View.INVISIBLE);
        // Update menu button in case the IME state has changed.
        setMenuVisibility(mShowMenu, true);

        setDisabledFlags(mDisabledFlags, true);
    }

    public void setDisabledFlags(int disabledFlags, boolean force) {
        super.setDisabledFlags(disabledFlags, force);

        final boolean disableHome = ((disabledFlags & View.STATUS_BAR_DISABLE_HOME) != 0);
        final boolean disableRecent = ((disabledFlags & View.STATUS_BAR_DISABLE_RECENT) != 0);
        final boolean disableBack = ((disabledFlags & View.STATUS_BAR_DISABLE_BACK) != 0)
                && ((mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_BACK_ALT) == 0);

        ViewGroup navButtons = (ViewGroup) mCurrentView.findViewById(R.id.nav_buttons);
        if (navButtons != null) {
            LayoutTransition lt = navButtons.getLayoutTransition();
            if (lt != null) {
                if (!lt.getTransitionListeners().contains(mTransitionListener)) {
                    lt.addTransitionListener(mTransitionListener);
                }
                if (!mScreenOn && mCurrentView != null) {
                    lt.disableTransitionType(
                            LayoutTransition.CHANGE_APPEARING |
                            LayoutTransition.CHANGE_DISAPPEARING |
                            LayoutTransition.APPEARING |
                            LayoutTransition.DISAPPEARING);
                }
            }
        }

        getBackButton()   .setVisibility(disableBack       ? View.INVISIBLE : View.VISIBLE);
        getHomeButton()   .setVisibility(disableHome       ? View.INVISIBLE : View.VISIBLE);
        getRecentsButton().setVisibility(disableRecent     ? View.INVISIBLE : View.VISIBLE);

        mBarTransitions.applyBackButtonQuiescentAlpha(mBarTransitions.getMode(), true /*animate*/);
    }

    @Override
    protected void onKeyguardShowing(boolean showing) {
        mSoftkeyHandler.setKeyguardShowing(showing);
        setMenuVisibility(shouldForceShowMenu(), true);
    }

    private boolean shouldForceShowMenu() {
        return mForceShowMenuFromUser
                && ((mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_IME_SHOWN) == 0)
                && !mKeyguardShowing;
    }

    public void setMenuVisibility(final boolean show) {
        setMenuVisibility(show, false);
    }

    public void setMenuVisibility(final boolean show, final boolean force) {
        boolean showOverride = shouldForceShowMenu();
        if (showOverride) {
            if (!(getMenuButton().getVisibility() == View.VISIBLE)) {
                mShowMenu = true;
                getMenuButton().setVisibility(View.VISIBLE);
                return;
            }
        } else {
            if (!force && mShowMenu == show)
                return;

            mShowMenu = show;

            // Only show Menu if IME switcher not shown.
            final boolean shouldShow = mShowMenu &&
                    ((mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_IME_SHOWN) == 0);
            getMenuButton().setVisibility(shouldShow ? View.VISIBLE : View.INVISIBLE);
        }
    }

    @Override
    public void onFinishInflate() {
        super.onFinishInflate();
        mSoftkeyHandler.init();
        getImeSwitchButton().setOnClickListener(mImeSwitcherClickListener);
        updateRTLOrder();
    }

    public void reorient() {
        super.reorient();

        getImeSwitchButton().setOnClickListener(mImeSwitcherClickListener);

        mDeadZone = (DeadZone) mCurrentView.findViewById(R.id.deadzone);
//        mDeadZone.setStartFromRight(mLeftInLandscape);

        // force the low profile & disabled states into compliance
        mBarTransitions.init(mVertical);
        setDisabledFlags(mDisabledFlags, true /* force */);
        setMenuVisibility(mShowMenu, true /* force */);

        if (DEBUG) {
            Log.d(TAG, "reorient(): rot=" + mDisplay.getRotation());
        }

        // swap to x coordinate if orientation is not in vertical
        if (mDelegateHelper != null) {
            mDelegateHelper.setSwapXY(mVertical);
        }
        updateTaskSwitchHelper();

        setNavigationIconHints(mNavigationIconHints, true);
    }

    private void updateTaskSwitchHelper() {
        boolean isRtl = (getLayoutDirection() == View.LAYOUT_DIRECTION_RTL);
        mTaskSwitchHelper.setBarState(mVertical, isRtl);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        mDelegateHelper.setInitialTouchRegion(getHomeButton(), getBackButton(), getRecentsButton());
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateRTLOrder();
        updateTaskSwitchHelper();
    }

    /**
     * In landscape, the LinearLayout is not auto mirrored since it is vertical. Therefore we
     * have to do it manually
     */
    private void updateRTLOrder() {
        boolean isLayoutRtl = getResources().getConfiguration()
                .getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        if (mIsLayoutRtl != isLayoutRtl) {

            // We swap all children of the 90 and 270 degree layouts, since they are vertical
            View rotation90 = mRotatedViews[Surface.ROTATION_90];
            swapChildrenOrderIfVertical(rotation90.findViewById(R.id.nav_buttons));
            adjustExtraKeyGravity(rotation90, isLayoutRtl);

            View rotation270 = mRotatedViews[Surface.ROTATION_270];
            if (rotation90 != rotation270) {
                swapChildrenOrderIfVertical(rotation270.findViewById(R.id.nav_buttons));
                adjustExtraKeyGravity(rotation270, isLayoutRtl);
            }
            mIsLayoutRtl = isLayoutRtl;
        }
    }

    private void adjustExtraKeyGravity(View navBar, boolean isLayoutRtl) {
        View menu = navBar.findViewById(R.id.menu);
        View imeSwitcher = navBar.findViewById(R.id.ime_switcher);
        if (menu != null) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) menu.getLayoutParams();
            lp.gravity = isLayoutRtl ? Gravity.BOTTOM : Gravity.TOP;
            menu.setLayoutParams(lp);
        }
        if (imeSwitcher != null) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) imeSwitcher.getLayoutParams();
            lp.gravity = isLayoutRtl ? Gravity.BOTTOM : Gravity.TOP;
            imeSwitcher.setLayoutParams(lp);
        }
    }

    /**
     * Swaps the children order of a LinearLayout if it's orientation is Vertical
     *
     * @param group The LinearLayout to swap the children from.
     */
    private void swapChildrenOrderIfVertical(View group) {
        if (group instanceof LinearLayout) {
            LinearLayout linearLayout = (LinearLayout) group;
            if (linearLayout.getOrientation() == VERTICAL) {
                int childCount = linearLayout.getChildCount();
                ArrayList<View> childList = new ArrayList<>(childCount);
                for (int i = 0; i < childCount; i++) {
                    childList.add(linearLayout.getChildAt(i));
                }
                linearLayout.removeAllViews();
                for (int i = childCount - 1; i >= 0; i--) {
                    linearLayout.addView(childList.get(i));
                }
            }
        }
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        super.dump(fd, pw, args);

        dumpButton(pw, "back", getBackButton());
        dumpButton(pw, "home", getHomeButton());
        dumpButton(pw, "rcnt", getRecentsButton());
        dumpButton(pw, "menu", getMenuButton());

        pw.println("    }");
    }

    @Override
    protected void onDispose() {
        mObserver.unobserve();
        mSoftkeyHandler.onDispose();
    }
}
