/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.view.accessibility;

import static android.accessibilityservice.AccessibilityServiceInfo.FLAG_ENABLE_ACCESSIBILITY_VOLUME;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SystemService;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;
import android.view.IWindow;
import android.view.View;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IntPair;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * System level service that serves as an event dispatch for {@link AccessibilityEvent}s,
 * and provides facilities for querying the accessibility state of the system.
 * Accessibility events are generated when something notable happens in the user interface,
 * for example an {@link android.app.Activity} starts, the focus or selection of a
 * {@link android.view.View} changes etc. Parties interested in handling accessibility
 * events implement and register an accessibility service which extends
 * {@link android.accessibilityservice.AccessibilityService}.
 *
 * @see AccessibilityEvent
 * @see AccessibilityNodeInfo
 * @see android.accessibilityservice.AccessibilityService
 * @see Context#getSystemService
 * @see Context#ACCESSIBILITY_SERVICE
 */
@SystemService(Context.ACCESSIBILITY_SERVICE)
public final class AccessibilityManager {
    private static final boolean DEBUG = false;

    private static final String LOG_TAG = "AccessibilityManager";

    /** @hide */
    public static final int STATE_FLAG_ACCESSIBILITY_ENABLED = 0x00000001;

    /** @hide */
    public static final int STATE_FLAG_TOUCH_EXPLORATION_ENABLED = 0x00000002;

    /** @hide */
    public static final int STATE_FLAG_HIGH_TEXT_CONTRAST_ENABLED = 0x00000004;

    /** @hide */
    public static final int DALTONIZER_DISABLED = -1;

    /** @hide */
    public static final int DALTONIZER_SIMULATE_MONOCHROMACY = 0;

    /** @hide */
    public static final int DALTONIZER_CORRECT_DEUTERANOMALY = 12;

    /** @hide */
    public static final int AUTOCLICK_DELAY_DEFAULT = 600;

    /**
     * Activity action: Launch UI to manage which accessibility service or feature is assigned
     * to the navigation bar Accessibility button.
     * <p>
     * Input: Nothing.
     * </p>
     * <p>
     * Output: Nothing.
     * </p>
     *
     * @hide
     */
    @SdkConstant(SdkConstant.SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_CHOOSE_ACCESSIBILITY_BUTTON =
            "com.android.internal.intent.action.CHOOSE_ACCESSIBILITY_BUTTON";

    static final Object sInstanceSync = new Object();

    private static AccessibilityManager sInstance;

    private final Object mLock = new Object();

    private IAccessibilityManager mService;

    final int mUserId;

    final Handler mHandler;

    final Handler.Callback mCallback;

    boolean mIsEnabled;

    int mRelevantEventTypes = AccessibilityEvent.TYPES_ALL_MASK;

    boolean mIsTouchExplorationEnabled;

    boolean mIsHighTextContrastEnabled;

    private final ArrayMap<AccessibilityStateChangeListener, Handler>
            mAccessibilityStateChangeListeners = new ArrayMap<>();

    private final ArrayMap<TouchExplorationStateChangeListener, Handler>
            mTouchExplorationStateChangeListeners = new ArrayMap<>();

    private final ArrayMap<HighTextContrastChangeListener, Handler>
            mHighTextContrastStateChangeListeners = new ArrayMap<>();

    private final ArrayMap<AccessibilityServicesStateChangeListener, Handler>
            mServicesStateChangeListeners = new ArrayMap<>();

    /**
     * Map from a view's accessibility id to the list of request preparers set for that view
     */
    private SparseArray<List<AccessibilityRequestPreparer>> mRequestPreparerLists;

    /**
     * Listener for the system accessibility state. To listen for changes to the
     * accessibility state on the device, implement this interface and register
     * it with the system by calling {@link #addAccessibilityStateChangeListener}.
     */
    public interface AccessibilityStateChangeListener {

        /**
         * Called when the accessibility enabled state changes.
         *
         * @param enabled Whether accessibility is enabled.
         */
        void onAccessibilityStateChanged(boolean enabled);
    }

    /**
     * Listener for the system touch exploration state. To listen for changes to
     * the touch exploration state on the device, implement this interface and
     * register it with the system by calling
     * {@link #addTouchExplorationStateChangeListener}.
     */
    public interface TouchExplorationStateChangeListener {

        /**
         * Called when the touch exploration enabled state changes.
         *
         * @param enabled Whether touch exploration is enabled.
         */
        void onTouchExplorationStateChanged(boolean enabled);
    }

    /**
     * Listener for changes to the state of accessibility services. Changes include services being
     * enabled or disabled, or changes to the {@link AccessibilityServiceInfo} of a running service.
     * {@see #addAccessibilityServicesStateChangeListener}.
     *
     * @hide
     */
    public interface AccessibilityServicesStateChangeListener {

        /**
         * Called when the state of accessibility services changes.
         *
         * @param manager The manager that is calling back
         */
        void onAccessibilityServicesStateChanged(AccessibilityManager manager);
    }

    /**
     * Listener for the system high text contrast state. To listen for changes to
     * the high text contrast state on the device, implement this interface and
     * register it with the system by calling
     * {@link #addHighTextContrastStateChangeListener}.
     *
     * @hide
     */
    public interface HighTextContrastChangeListener {

        /**
         * Called when the high text contrast enabled state changes.
         *
         * @param enabled Whether high text contrast is enabled.
         */
        void onHighTextContrastStateChanged(boolean enabled);
    }

    private final IAccessibilityManagerClient.Stub mClient =
            new IAccessibilityManagerClient.Stub() {
        @Override
        public void setState(int state) {
            // We do not want to change this immediately as the application may
            // have already checked that accessibility is on and fired an event,
            // that is now propagating up the view tree, Hence, if accessibility
            // is now off an exception will be thrown. We want to have the exception
            // enforcement to guard against apps that fire unnecessary accessibility
            // events when accessibility is off.
            mHandler.obtainMessage(MyCallback.MSG_SET_STATE, state, 0).sendToTarget();
        }

        @Override
        public void notifyServicesStateChanged() {
            final ArrayMap<AccessibilityServicesStateChangeListener, Handler> listeners;
            synchronized (mLock) {
                if (mServicesStateChangeListeners.isEmpty()) {
                    return;
                }
                listeners = new ArrayMap<>(mServicesStateChangeListeners);
            }

            int numListeners = listeners.size();
            for (int i = 0; i < numListeners; i++) {
                final AccessibilityServicesStateChangeListener listener =
                        mServicesStateChangeListeners.keyAt(i);
                mServicesStateChangeListeners.valueAt(i).post(() -> listener
                        .onAccessibilityServicesStateChanged(AccessibilityManager.this));
            }
        }

        @Override
        public void setRelevantEventTypes(int eventTypes) {
            mRelevantEventTypes = eventTypes;
        }
    };

    /**
     * Get an AccessibilityManager instance (create one if necessary).
     *
     * @param context Context in which this manager operates.
     *
     * @hide
     */
    public static AccessibilityManager getInstance(Context context) {
        synchronized (sInstanceSync) {
            if (sInstance == null) {
                final int userId;
                if (Binder.getCallingUid() == Process.SYSTEM_UID
                        || context.checkCallingOrSelfPermission(
                                Manifest.permission.INTERACT_ACROSS_USERS)
                                        == PackageManager.PERMISSION_GRANTED
                        || context.checkCallingOrSelfPermission(
                                Manifest.permission.INTERACT_ACROSS_USERS_FULL)
                                        == PackageManager.PERMISSION_GRANTED) {
                    userId = UserHandle.USER_CURRENT;
                } else {
                    userId = UserHandle.myUserId();
                }
                sInstance = new AccessibilityManager(context, null, userId);
            }
        }
        return sInstance;
    }

    /**
     * Create an instance.
     *
     * @param context A {@link Context}.
     * @param service An interface to the backing service.
     * @param userId User id under which to run.
     *
     * @hide
     */
    public AccessibilityManager(Context context, IAccessibilityManager service, int userId) {
        // Constructor can't be chained because we can't create an instance of an inner class
        // before calling another constructor.
        mCallback = new MyCallback();
        mHandler = new Handler(context.getMainLooper(), mCallback);
        mUserId = userId;
        synchronized (mLock) {
            tryConnectToServiceLocked(service);
        }
    }

    /**
     * Create an instance.
     *
     * @param handler The handler to use
     * @param service An interface to the backing service.
     * @param userId User id under which to run.
     *
     * @hide
     */
    public AccessibilityManager(Handler handler, IAccessibilityManager service, int userId) {
        mCallback = new MyCallback();
        mHandler = handler;
        mUserId = userId;
        synchronized (mLock) {
            tryConnectToServiceLocked(service);
        }
    }

    /**
     * @hide
     */
    public IAccessibilityManagerClient getClient() {
        return mClient;
    }

    /**
     * @hide
     */
    @VisibleForTesting
    public Handler.Callback getCallback() {
        return mCallback;
    }

    /**
     * Returns if the accessibility in the system is enabled.
     *
     * @return True if accessibility is enabled, false otherwise.
     */
    public boolean isEnabled() {
        synchronized (mLock) {
            IAccessibilityManager service = getServiceLocked();
            if (service == null) {
                return false;
            }
            return mIsEnabled;
        }
    }

    /**
     * Returns if the touch exploration in the system is enabled.
     *
     * @return True if touch exploration is enabled, false otherwise.
     */
    public boolean isTouchExplorationEnabled() {
        synchronized (mLock) {
            IAccessibilityManager service = getServiceLocked();
            if (service == null) {
                return false;
            }
            return mIsTouchExplorationEnabled;
        }
    }

    /**
     * Returns if the high text contrast in the system is enabled.
     * <p>
     * <strong>Note:</strong> You need to query this only if you application is
     * doing its own rendering and does not rely on the platform rendering pipeline.
     * </p>
     *
     * @return True if high text contrast is enabled, false otherwise.
     *
     * @hide
     */
    public boolean isHighTextContrastEnabled() {
        synchronized (mLock) {
            IAccessibilityManager service = getServiceLocked();
            if (service == null) {
                return false;
            }
            return mIsHighTextContrastEnabled;
        }
    }

    /**
     * Sends an {@link AccessibilityEvent}.
     *
     * @param event The event to send.
     *
     * @throws IllegalStateException if accessibility is not enabled.
     *
     * <strong>Note:</strong> The preferred mechanism for sending custom accessibility
     * events is through calling
     * {@link android.view.ViewParent#requestSendAccessibilityEvent(View, AccessibilityEvent)}
     * instead of this method to allow predecessors to augment/filter events sent by
     * their descendants.
     */
    public void sendAccessibilityEvent(AccessibilityEvent event) {
        final IAccessibilityManager service;
        final int userId;
        synchronized (mLock) {
            service = getServiceLocked();
            if (service == null) {
                return;
            }
            if (!mIsEnabled) {
                Looper myLooper = Looper.myLooper();
                if (myLooper == Looper.getMainLooper()) {
                    throw new IllegalStateException(
                            "Accessibility off. Did you forget to check that?");
                } else {
                    // If we're not running on the thread with the main looper, it's possible for
                    // the state of accessibility to change between checking isEnabled and
                    // calling this method. So just log the error rather than throwing the
                    // exception.
                    Log.e(LOG_TAG, "AccessibilityEvent sent with accessibility disabled");
                    return;
                }
            }
            if ((event.getEventType() & mRelevantEventTypes) == 0) {
                if (DEBUG) {
                    Log.i(LOG_TAG, "Not dispatching irrelevant event: " + event
                            + " that is not among "
                            + AccessibilityEvent.eventTypeToString(mRelevantEventTypes));
                }
                return;
            }
            userId = mUserId;
        }
        try {
            event.setEventTime(SystemClock.uptimeMillis());
            // it is possible that this manager is in the same process as the service but
            // client using it is called through Binder from another process. Example: MMS
            // app adds a SMS notification and the NotificationManagerService calls this method
            long identityToken = Binder.clearCallingIdentity();
            service.sendAccessibilityEvent(event, userId);
            Binder.restoreCallingIdentity(identityToken);
            if (DEBUG) {
                Log.i(LOG_TAG, event + " sent");
            }
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error during sending " + event + " ", re);
        } finally {
            event.recycle();
        }
    }

    /**
     * Requests feedback interruption from all accessibility services.
     */
    public void interrupt() {
        final IAccessibilityManager service;
        final int userId;
        synchronized (mLock) {
            service = getServiceLocked();
            if (service == null) {
                return;
            }
            if (!mIsEnabled) {
                Looper myLooper = Looper.myLooper();
                if (myLooper == Looper.getMainLooper()) {
                    throw new IllegalStateException(
                            "Accessibility off. Did you forget to check that?");
                } else {
                    // If we're not running on the thread with the main looper, it's possible for
                    // the state of accessibility to change between checking isEnabled and
                    // calling this method. So just log the error rather than throwing the
                    // exception.
                    Log.e(LOG_TAG, "Interrupt called with accessibility disabled");
                    return;
                }
            }
            userId = mUserId;
        }
        try {
            service.interrupt(userId);
            if (DEBUG) {
                Log.i(LOG_TAG, "Requested interrupt from all services");
            }
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error while requesting interrupt from all services. ", re);
        }
    }

    /**
     * Returns the {@link ServiceInfo}s of the installed accessibility services.
     *
     * @return An unmodifiable list with {@link ServiceInfo}s.
     *
     * @deprecated Use {@link #getInstalledAccessibilityServiceList()}
     */
    @Deprecated
    public List<ServiceInfo> getAccessibilityServiceList() {
        List<AccessibilityServiceInfo> infos = getInstalledAccessibilityServiceList();
        List<ServiceInfo> services = new ArrayList<>();
        final int infoCount = infos.size();
        for (int i = 0; i < infoCount; i++) {
            AccessibilityServiceInfo info = infos.get(i);
            services.add(info.getResolveInfo().serviceInfo);
        }
        return Collections.unmodifiableList(services);
    }

    /**
     * Returns the {@link AccessibilityServiceInfo}s of the installed accessibility services.
     *
     * @return An unmodifiable list with {@link AccessibilityServiceInfo}s.
     */
    public List<AccessibilityServiceInfo> getInstalledAccessibilityServiceList() {
        final IAccessibilityManager service;
        final int userId;
        synchronized (mLock) {
            service = getServiceLocked();
            if (service == null) {
                return Collections.emptyList();
            }
            userId = mUserId;
        }

        List<AccessibilityServiceInfo> services = null;
        try {
            services = service.getInstalledAccessibilityServiceList(userId);
            if (DEBUG) {
                Log.i(LOG_TAG, "Installed AccessibilityServices " + services);
            }
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error while obtaining the installed AccessibilityServices. ", re);
        }
        if (services != null) {
            return Collections.unmodifiableList(services);
        } else {
            return Collections.emptyList();
        }
    }

    /**
     * Returns the {@link AccessibilityServiceInfo}s of the enabled accessibility services
     * for a given feedback type.
     *
     * @param feedbackTypeFlags The feedback type flags.
     * @return An unmodifiable list with {@link AccessibilityServiceInfo}s.
     *
     * @see AccessibilityServiceInfo#FEEDBACK_AUDIBLE
     * @see AccessibilityServiceInfo#FEEDBACK_GENERIC
     * @see AccessibilityServiceInfo#FEEDBACK_HAPTIC
     * @see AccessibilityServiceInfo#FEEDBACK_SPOKEN
     * @see AccessibilityServiceInfo#FEEDBACK_VISUAL
     * @see AccessibilityServiceInfo#FEEDBACK_BRAILLE
     */
    public List<AccessibilityServiceInfo> getEnabledAccessibilityServiceList(
            int feedbackTypeFlags) {
        final IAccessibilityManager service;
        final int userId;
        synchronized (mLock) {
            service = getServiceLocked();
            if (service == null) {
                return Collections.emptyList();
            }
            userId = mUserId;
        }

        List<AccessibilityServiceInfo> services = null;
        try {
            services = service.getEnabledAccessibilityServiceList(feedbackTypeFlags, userId);
            if (DEBUG) {
                Log.i(LOG_TAG, "Installed AccessibilityServices " + services);
            }
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error while obtaining the installed AccessibilityServices. ", re);
        }
        if (services != null) {
            return Collections.unmodifiableList(services);
        } else {
            return Collections.emptyList();
        }
    }

    /**
     * Registers an {@link AccessibilityStateChangeListener} for changes in
     * the global accessibility state of the system. Equivalent to calling
     * {@link #addAccessibilityStateChangeListener(AccessibilityStateChangeListener, Handler)}
     * with a null handler.
     *
     * @param listener The listener.
     * @return Always returns {@code true}.
     */
    public boolean addAccessibilityStateChangeListener(
            @NonNull AccessibilityStateChangeListener listener) {
        addAccessibilityStateChangeListener(listener, null);
        return true;
    }

    /**
     * Registers an {@link AccessibilityStateChangeListener} for changes in
     * the global accessibility state of the system. If the listener has already been registered,
     * the handler used to call it back is updated.
     *
     * @param listener The listener.
     * @param handler The handler on which the listener should be called back, or {@code null}
     *                for a callback on the process's main handler.
     */
    public void addAccessibilityStateChangeListener(
            @NonNull AccessibilityStateChangeListener listener, @Nullable Handler handler) {
        synchronized (mLock) {
            mAccessibilityStateChangeListeners
                    .put(listener, (handler == null) ? mHandler : handler);
        }
    }

    /**
     * Unregisters an {@link AccessibilityStateChangeListener}.
     *
     * @param listener The listener.
     * @return True if the listener was previously registered.
     */
    public boolean removeAccessibilityStateChangeListener(
            @NonNull AccessibilityStateChangeListener listener) {
        synchronized (mLock) {
            int index = mAccessibilityStateChangeListeners.indexOfKey(listener);
            mAccessibilityStateChangeListeners.remove(listener);
            return (index >= 0);
        }
    }

    /**
     * Registers a {@link TouchExplorationStateChangeListener} for changes in
     * the global touch exploration state of the system. Equivalent to calling
     * {@link #addTouchExplorationStateChangeListener(TouchExplorationStateChangeListener, Handler)}
     * with a null handler.
     *
     * @param listener The listener.
     * @return Always returns {@code true}.
     */
    public boolean addTouchExplorationStateChangeListener(
            @NonNull TouchExplorationStateChangeListener listener) {
        addTouchExplorationStateChangeListener(listener, null);
        return true;
    }

    /**
     * Registers an {@link TouchExplorationStateChangeListener} for changes in
     * the global touch exploration state of the system. If the listener has already been
     * registered, the handler used to call it back is updated.
     *
     * @param listener The listener.
     * @param handler The handler on which the listener should be called back, or {@code null}
     *                for a callback on the process's main handler.
     */
    public void addTouchExplorationStateChangeListener(
            @NonNull TouchExplorationStateChangeListener listener, @Nullable Handler handler) {
        synchronized (mLock) {
            mTouchExplorationStateChangeListeners
                    .put(listener, (handler == null) ? mHandler : handler);
        }
    }

    /**
     * Unregisters a {@link TouchExplorationStateChangeListener}.
     *
     * @param listener The listener.
     * @return True if listener was previously registered.
     */
    public boolean removeTouchExplorationStateChangeListener(
            @NonNull TouchExplorationStateChangeListener listener) {
        synchronized (mLock) {
            int index = mTouchExplorationStateChangeListeners.indexOfKey(listener);
            mTouchExplorationStateChangeListeners.remove(listener);
            return (index >= 0);
        }
    }

    /**
     * Registers a {@link AccessibilityServicesStateChangeListener}.
     *
     * @param listener The listener.
     * @param handler The handler on which the listener should be called back, or {@code null}
     *                for a callback on the process's main handler.
     * @hide
     */
    public void addAccessibilityServicesStateChangeListener(
            @NonNull AccessibilityServicesStateChangeListener listener, @Nullable Handler handler) {
        synchronized (mLock) {
            mServicesStateChangeListeners
                    .put(listener, (handler == null) ? mHandler : handler);
        }
    }

    /**
     * Unregisters a {@link AccessibilityServicesStateChangeListener}.
     *
     * @param listener The listener.
     *
     * @hide
     */
    public void removeAccessibilityServicesStateChangeListener(
            @NonNull AccessibilityServicesStateChangeListener listener) {
        // Final CopyOnWriteArrayList - no lock needed.
        mServicesStateChangeListeners.remove(listener);
    }

    /**
     * Registers a {@link AccessibilityRequestPreparer}.
     */
    public void addAccessibilityRequestPreparer(AccessibilityRequestPreparer preparer) {
        if (mRequestPreparerLists == null) {
            mRequestPreparerLists = new SparseArray<>(1);
        }
        int id = preparer.getView().getAccessibilityViewId();
        List<AccessibilityRequestPreparer> requestPreparerList = mRequestPreparerLists.get(id);
        if (requestPreparerList == null) {
            requestPreparerList = new ArrayList<>(1);
            mRequestPreparerLists.put(id, requestPreparerList);
        }
        requestPreparerList.add(preparer);
    }

    /**
     * Unregisters a {@link AccessibilityRequestPreparer}.
     */
    public void removeAccessibilityRequestPreparer(AccessibilityRequestPreparer preparer) {
        if (mRequestPreparerLists == null) {
            return;
        }
        int viewId = preparer.getView().getAccessibilityViewId();
        List<AccessibilityRequestPreparer> requestPreparerList = mRequestPreparerLists.get(viewId);
        if (requestPreparerList != null) {
            requestPreparerList.remove(preparer);
            if (requestPreparerList.isEmpty()) {
                mRequestPreparerLists.remove(viewId);
            }
        }
    }

    /**
     * Get the preparers that are registered for an accessibility ID
     *
     * @param id The ID of interest
     * @return The list of preparers, or {@code null} if there are none.
     *
     * @hide
     */
    public List<AccessibilityRequestPreparer> getRequestPreparersForAccessibilityId(int id) {
        if (mRequestPreparerLists == null) {
            return null;
        }
        return mRequestPreparerLists.get(id);
    }

    /**
     * Registers a {@link HighTextContrastChangeListener} for changes in
     * the global high text contrast state of the system.
     *
     * @param listener The listener.
     *
     * @hide
     */
    public void addHighTextContrastStateChangeListener(
            @NonNull HighTextContrastChangeListener listener, @Nullable Handler handler) {
        synchronized (mLock) {
            mHighTextContrastStateChangeListeners
                    .put(listener, (handler == null) ? mHandler : handler);
        }
    }

    /**
     * Unregisters a {@link HighTextContrastChangeListener}.
     *
     * @param listener The listener.
     *
     * @hide
     */
    public void removeHighTextContrastStateChangeListener(
            @NonNull HighTextContrastChangeListener listener) {
        synchronized (mLock) {
            mHighTextContrastStateChangeListeners.remove(listener);
        }
    }

    /**
     * Check if the accessibility volume stream is active.
     *
     * @return True if accessibility volume is active (i.e. some service has requested it). False
     * otherwise.
     * @hide
     */
    public boolean isAccessibilityVolumeStreamActive() {
        List<AccessibilityServiceInfo> serviceInfos =
                getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        for (int i = 0; i < serviceInfos.size(); i++) {
            if ((serviceInfos.get(i).flags & FLAG_ENABLE_ACCESSIBILITY_VOLUME) != 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Report a fingerprint gesture to accessibility. Only available for the system process.
     *
     * @param keyCode The key code of the gesture
     * @return {@code true} if accessibility consumes the event. {@code false} if not.
     * @hide
     */
    public boolean sendFingerprintGesture(int keyCode) {
        final IAccessibilityManager service;
        synchronized (mLock) {
            service = getServiceLocked();
            if (service == null) {
                return false;
            }
        }
        try {
            return service.sendFingerprintGesture(keyCode);
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Sets the current state and notifies listeners, if necessary.
     *
     * @param stateFlags The state flags.
     */
    private void setStateLocked(int stateFlags) {
        final boolean enabled = (stateFlags & STATE_FLAG_ACCESSIBILITY_ENABLED) != 0;
        final boolean touchExplorationEnabled =
                (stateFlags & STATE_FLAG_TOUCH_EXPLORATION_ENABLED) != 0;
        final boolean highTextContrastEnabled =
                (stateFlags & STATE_FLAG_HIGH_TEXT_CONTRAST_ENABLED) != 0;

        final boolean wasEnabled = mIsEnabled;
        final boolean wasTouchExplorationEnabled = mIsTouchExplorationEnabled;
        final boolean wasHighTextContrastEnabled = mIsHighTextContrastEnabled;

        // Ensure listeners get current state from isZzzEnabled() calls.
        mIsEnabled = enabled;
        mIsTouchExplorationEnabled = touchExplorationEnabled;
        mIsHighTextContrastEnabled = highTextContrastEnabled;

        if (wasEnabled != enabled) {
            notifyAccessibilityStateChanged();
        }

        if (wasTouchExplorationEnabled != touchExplorationEnabled) {
            notifyTouchExplorationStateChanged();
        }

        if (wasHighTextContrastEnabled != highTextContrastEnabled) {
            notifyHighTextContrastStateChanged();
        }
    }

    /**
     * Find an installed service with the specified {@link ComponentName}.
     *
     * @param componentName The name to match to the service.
     *
     * @return The info corresponding to the installed service, or {@code null} if no such service
     * is installed.
     * @hide
     */
    public AccessibilityServiceInfo getInstalledServiceInfoWithComponentName(
            ComponentName componentName) {
        final List<AccessibilityServiceInfo> installedServiceInfos =
                getInstalledAccessibilityServiceList();
        if ((installedServiceInfos == null) || (componentName == null)) {
            return null;
        }
        for (int i = 0; i < installedServiceInfos.size(); i++) {
            if (componentName.equals(installedServiceInfos.get(i).getComponentName())) {
                return installedServiceInfos.get(i);
            }
        }
        return null;
    }

    /**
     * Adds an accessibility interaction connection interface for a given window.
     * @param windowToken The window token to which a connection is added.
     * @param connection The connection.
     *
     * @hide
     */
    public int addAccessibilityInteractionConnection(IWindow windowToken,
            String packageName, IAccessibilityInteractionConnection connection) {
        final IAccessibilityManager service;
        final int userId;
        synchronized (mLock) {
            service = getServiceLocked();
            if (service == null) {
                return View.NO_ID;
            }
            userId = mUserId;
        }
        try {
            return service.addAccessibilityInteractionConnection(windowToken, connection,
                    packageName, userId);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error while adding an accessibility interaction connection. ", re);
        }
        return View.NO_ID;
    }

    /**
     * Removed an accessibility interaction connection interface for a given window.
     * @param windowToken The window token to which a connection is removed.
     *
     * @hide
     */
    public void removeAccessibilityInteractionConnection(IWindow windowToken) {
        final IAccessibilityManager service;
        synchronized (mLock) {
            service = getServiceLocked();
            if (service == null) {
                return;
            }
        }
        try {
            service.removeAccessibilityInteractionConnection(windowToken);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error while removing an accessibility interaction connection. ", re);
        }
    }

    /**
     * Perform the accessibility shortcut if the caller has permission.
     *
     * @hide
     */
    public void performAccessibilityShortcut() {
        final IAccessibilityManager service;
        synchronized (mLock) {
            service = getServiceLocked();
            if (service == null) {
                return;
            }
        }
        try {
            service.performAccessibilityShortcut();
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error performing accessibility shortcut. ", re);
        }
    }

    /**
     * Notifies that the accessibility button in the system's navigation area has been clicked
     *
     * @hide
     */
    public void notifyAccessibilityButtonClicked() {
        final IAccessibilityManager service;
        synchronized (mLock) {
            service = getServiceLocked();
            if (service == null) {
                return;
            }
        }
        try {
            service.notifyAccessibilityButtonClicked();
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error while dispatching accessibility button click", re);
        }
    }

    /**
     * Notifies that the visibility of the accessibility button in the system's navigation area
     * has changed.
     *
     * @param shown {@code true} if the accessibility button is visible within the system
     *                  navigation area, {@code false} otherwise
     * @hide
     */
    public void notifyAccessibilityButtonVisibilityChanged(boolean shown) {
        final IAccessibilityManager service;
        synchronized (mLock) {
            service = getServiceLocked();
            if (service == null) {
                return;
            }
        }
        try {
            service.notifyAccessibilityButtonVisibilityChanged(shown);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error while dispatching accessibility button visibility change", re);
        }
    }

    /**
     * Set an IAccessibilityInteractionConnection to replace the actions of a picture-in-picture
     * window. Intended for use by the System UI only.
     *
     * @param connection The connection to handle the actions. Set to {@code null} to avoid
     * affecting the actions.
     *
     * @hide
     */
    public void setPictureInPictureActionReplacingConnection(
            @Nullable IAccessibilityInteractionConnection connection) {
        final IAccessibilityManager service;
        synchronized (mLock) {
            service = getServiceLocked();
            if (service == null) {
                return;
            }
        }
        try {
            service.setPictureInPictureActionReplacingConnection(connection);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error setting picture in picture action replacement", re);
        }
    }

    private IAccessibilityManager getServiceLocked() {
        if (mService == null) {
            tryConnectToServiceLocked(null);
        }
        return mService;
    }

    private void tryConnectToServiceLocked(IAccessibilityManager service) {
        if (service == null) {
            IBinder iBinder = ServiceManager.getService(Context.ACCESSIBILITY_SERVICE);
            if (iBinder == null) {
                return;
            }
            service = IAccessibilityManager.Stub.asInterface(iBinder);
        }

        try {
            final long userStateAndRelevantEvents = service.addClient(mClient, mUserId);
            setStateLocked(IntPair.first(userStateAndRelevantEvents));
            mRelevantEventTypes = IntPair.second(userStateAndRelevantEvents);
            mService = service;
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "AccessibilityManagerService is dead", re);
        }
    }

    /**
     * Notifies the registered {@link AccessibilityStateChangeListener}s.
     */
    private void notifyAccessibilityStateChanged() {
        final boolean isEnabled;
        final ArrayMap<AccessibilityStateChangeListener, Handler> listeners;
        synchronized (mLock) {
            if (mAccessibilityStateChangeListeners.isEmpty()) {
                return;
            }
            isEnabled = mIsEnabled;
            listeners = new ArrayMap<>(mAccessibilityStateChangeListeners);
        }

        int numListeners = listeners.size();
        for (int i = 0; i < numListeners; i++) {
            final AccessibilityStateChangeListener listener =
                    mAccessibilityStateChangeListeners.keyAt(i);
            mAccessibilityStateChangeListeners.valueAt(i)
                    .post(() -> listener.onAccessibilityStateChanged(isEnabled));
        }
    }

    /**
     * Notifies the registered {@link TouchExplorationStateChangeListener}s.
     */
    private void notifyTouchExplorationStateChanged() {
        final boolean isTouchExplorationEnabled;
        final ArrayMap<TouchExplorationStateChangeListener, Handler> listeners;
        synchronized (mLock) {
            if (mTouchExplorationStateChangeListeners.isEmpty()) {
                return;
            }
            isTouchExplorationEnabled = mIsTouchExplorationEnabled;
            listeners = new ArrayMap<>(mTouchExplorationStateChangeListeners);
        }

        int numListeners = listeners.size();
        for (int i = 0; i < numListeners; i++) {
            final TouchExplorationStateChangeListener listener =
                    mTouchExplorationStateChangeListeners.keyAt(i);
            mTouchExplorationStateChangeListeners.valueAt(i)
                    .post(() -> listener.onTouchExplorationStateChanged(isTouchExplorationEnabled));
        }
    }

    /**
     * Notifies the registered {@link HighTextContrastChangeListener}s.
     */
    private void notifyHighTextContrastStateChanged() {
        final boolean isHighTextContrastEnabled;
        final ArrayMap<HighTextContrastChangeListener, Handler> listeners;
        synchronized (mLock) {
            if (mHighTextContrastStateChangeListeners.isEmpty()) {
                return;
            }
            isHighTextContrastEnabled = mIsHighTextContrastEnabled;
            listeners = new ArrayMap<>(mHighTextContrastStateChangeListeners);
        }

        int numListeners = listeners.size();
        for (int i = 0; i < numListeners; i++) {
            final HighTextContrastChangeListener listener =
                    mHighTextContrastStateChangeListeners.keyAt(i);
            mHighTextContrastStateChangeListeners.valueAt(i)
                    .post(() -> listener.onHighTextContrastStateChanged(isHighTextContrastEnabled));
        }
    }

    /**
     * Determines if the accessibility button within the system navigation area is supported.
     *
     * @return {@code true} if the accessibility button is supported on this device,
     * {@code false} otherwise
     */
    public static boolean isAccessibilityButtonSupported() {
        final Resources res = Resources.getSystem();
        return res.getBoolean(com.android.internal.R.bool.config_showNavigationBar);
    }

    private final class MyCallback implements Handler.Callback {
        public static final int MSG_SET_STATE = 1;

        @Override
        public boolean handleMessage(Message message) {
            switch (message.what) {
                case MSG_SET_STATE: {
                    // See comment at mClient
                    final int state = message.arg1;
                    synchronized (mLock) {
                        setStateLocked(state);
                    }
                } break;
            }
            return true;
        }
    }
}
