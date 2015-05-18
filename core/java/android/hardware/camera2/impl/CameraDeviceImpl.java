/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.hardware.camera2.impl;

import static android.hardware.camera2.CameraAccessException.CAMERA_IN_USE;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.ICameraDeviceCallbacks;
import android.hardware.camera2.ICameraDeviceUser;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.utils.CameraBinderDecorator;
import android.hardware.camera2.utils.CameraRuntimeException;
import android.hardware.camera2.utils.LongParcelable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;
import android.view.Surface;

import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;

/**
 * HAL2.1+ implementation of CameraDevice. Use CameraManager#open to instantiate
 */
public class CameraDeviceImpl extends CameraDevice {
    private final String TAG;
    private final boolean DEBUG;

    private static final int REQUEST_ID_NONE = -1;

    // TODO: guard every function with if (!mRemoteDevice) check (if it was closed)
    private ICameraDeviceUser mRemoteDevice;

    // Lock to synchronize cross-thread access to device public interface
    final Object mInterfaceLock = new Object(); // access from this class and Session only!
    private final CameraDeviceCallbacks mCallbacks = new CameraDeviceCallbacks();

    private final StateCallback mDeviceCallback;
    private volatile StateCallbackKK mSessionStateCallback;
    private final Handler mDeviceHandler;

    private volatile boolean mClosing = false;
    private boolean mInError = false;
    private boolean mIdle = true;

    /** map request IDs to callback/request data */
    private final SparseArray<CaptureCallbackHolder> mCaptureCallbackMap =
            new SparseArray<CaptureCallbackHolder>();

    private int mRepeatingRequestId = REQUEST_ID_NONE;
    private final ArrayList<Integer> mRepeatingRequestIdDeletedList = new ArrayList<Integer>();
    // Map stream IDs to Surfaces
    private final SparseArray<Surface> mConfiguredOutputs = new SparseArray<Surface>();

    private final String mCameraId;
    private final CameraCharacteristics mCharacteristics;
    private final int mTotalPartialCount;

    /**
     * A list tracking request and its expected last frame.
     * Updated when calling ICameraDeviceUser methods.
     */
    private final List<SimpleEntry</*frameNumber*/Long, /*requestId*/Integer>>
            mFrameNumberRequestPairs = new ArrayList<SimpleEntry<Long, Integer>>();

    /**
     * An object tracking received frame numbers.
     * Updated when receiving callbacks from ICameraDeviceCallbacks.
     */
    private final FrameNumberTracker mFrameNumberTracker = new FrameNumberTracker();

    private CameraCaptureSessionImpl mCurrentSession;
    private int mNextSessionId = 0;

    // Runnables for all state transitions, except error, which needs the
    // error code argument

    private final Runnable mCallOnOpened = new Runnable() {
        @Override
        public void run() {
            StateCallbackKK sessionCallback = null;
            synchronized(mInterfaceLock) {
                if (mRemoteDevice == null) return; // Camera already closed

                sessionCallback = mSessionStateCallback;
            }
            if (sessionCallback != null) {
                sessionCallback.onOpened(CameraDeviceImpl.this);
            }
            mDeviceCallback.onOpened(CameraDeviceImpl.this);
        }
    };

    private final Runnable mCallOnUnconfigured = new Runnable() {
        @Override
        public void run() {
            StateCallbackKK sessionCallback = null;
            synchronized(mInterfaceLock) {
                if (mRemoteDevice == null) return; // Camera already closed

                sessionCallback = mSessionStateCallback;
            }
            if (sessionCallback != null) {
                sessionCallback.onUnconfigured(CameraDeviceImpl.this);
            }
        }
    };

    private final Runnable mCallOnActive = new Runnable() {
        @Override
        public void run() {
            StateCallbackKK sessionCallback = null;
            synchronized(mInterfaceLock) {
                if (mRemoteDevice == null) return; // Camera already closed

                sessionCallback = mSessionStateCallback;
            }
            if (sessionCallback != null) {
                sessionCallback.onActive(CameraDeviceImpl.this);
            }
        }
    };

    private final Runnable mCallOnBusy = new Runnable() {
        @Override
        public void run() {
            StateCallbackKK sessionCallback = null;
            synchronized(mInterfaceLock) {
                if (mRemoteDevice == null) return; // Camera already closed

                sessionCallback = mSessionStateCallback;
            }
            if (sessionCallback != null) {
                sessionCallback.onBusy(CameraDeviceImpl.this);
            }
        }
    };

    private final Runnable mCallOnClosed = new Runnable() {
        private boolean mClosedOnce = false;

        @Override
        public void run() {
            if (mClosedOnce) {
                throw new AssertionError("Don't post #onClosed more than once");
            }
            StateCallbackKK sessionCallback = null;
            synchronized(mInterfaceLock) {
                sessionCallback = mSessionStateCallback;
            }
            if (sessionCallback != null) {
                sessionCallback.onClosed(CameraDeviceImpl.this);
            }
            mDeviceCallback.onClosed(CameraDeviceImpl.this);
            mClosedOnce = true;
        }
    };

    private final Runnable mCallOnIdle = new Runnable() {
        @Override
        public void run() {
            StateCallbackKK sessionCallback = null;
            synchronized(mInterfaceLock) {
                if (mRemoteDevice == null) return; // Camera already closed

                sessionCallback = mSessionStateCallback;
            }
            if (sessionCallback != null) {
                sessionCallback.onIdle(CameraDeviceImpl.this);
            }
        }
    };

    private final Runnable mCallOnDisconnected = new Runnable() {
        @Override
        public void run() {
            StateCallbackKK sessionCallback = null;
            synchronized(mInterfaceLock) {
                if (mRemoteDevice == null) return; // Camera already closed

                sessionCallback = mSessionStateCallback;
            }
            if (sessionCallback != null) {
                sessionCallback.onDisconnected(CameraDeviceImpl.this);
            }
            mDeviceCallback.onDisconnected(CameraDeviceImpl.this);
        }
    };

    public CameraDeviceImpl(String cameraId, StateCallback callback, Handler handler,
                        CameraCharacteristics characteristics) {
        if (cameraId == null || callback == null || handler == null || characteristics == null) {
            throw new IllegalArgumentException("Null argument given");
        }
        mCameraId = cameraId;
        mDeviceCallback = callback;
        mDeviceHandler = handler;
        mCharacteristics = characteristics;

        final int MAX_TAG_LEN = 23;
        String tag = String.format("CameraDevice-JV-%s", mCameraId);
        if (tag.length() > MAX_TAG_LEN) {
            tag = tag.substring(0, MAX_TAG_LEN);
        }
        TAG = tag;
        DEBUG = Log.isLoggable(TAG, Log.DEBUG);

        Integer partialCount =
                mCharacteristics.get(CameraCharacteristics.REQUEST_PARTIAL_RESULT_COUNT);
        if (partialCount == null) {
            // 1 means partial result is not supported.
            mTotalPartialCount = 1;
        } else {
            mTotalPartialCount = partialCount;
        }
    }

    public CameraDeviceCallbacks getCallbacks() {
        return mCallbacks;
    }

    public void setRemoteDevice(ICameraDeviceUser remoteDevice) {
        synchronized(mInterfaceLock) {
            // TODO: Move from decorator to direct binder-mediated exceptions
            // If setRemoteFailure already called, do nothing
            if (mInError) return;

            mRemoteDevice = CameraBinderDecorator.newInstance(remoteDevice);

            mDeviceHandler.post(mCallOnOpened);
            mDeviceHandler.post(mCallOnUnconfigured);
        }
    }

    /**
     * Call to indicate failed connection to a remote camera device.
     *
     * <p>This places the camera device in the error state and informs the callback.
     * Use in place of setRemoteDevice() when startup fails.</p>
     */
    public void setRemoteFailure(final CameraRuntimeException failure) {
        int failureCode = StateCallback.ERROR_CAMERA_DEVICE;
        boolean failureIsError = true;

        switch (failure.getReason()) {
            case CameraAccessException.CAMERA_IN_USE:
                failureCode = StateCallback.ERROR_CAMERA_IN_USE;
                break;
            case CameraAccessException.MAX_CAMERAS_IN_USE:
                failureCode = StateCallback.ERROR_MAX_CAMERAS_IN_USE;
                break;
            case CameraAccessException.CAMERA_DISABLED:
                failureCode = StateCallback.ERROR_CAMERA_DISABLED;
                break;
            case CameraAccessException.CAMERA_DISCONNECTED:
                failureIsError = false;
                break;
            case CameraAccessException.CAMERA_ERROR:
                failureCode = StateCallback.ERROR_CAMERA_DEVICE;
                break;
            default:
                Log.wtf(TAG, "Unknown failure in opening camera device: " + failure.getReason());
                break;
        }
        final int code = failureCode;
        final boolean isError = failureIsError;
        synchronized(mInterfaceLock) {
            mInError = true;
            mDeviceHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (isError) {
                        mDeviceCallback.onError(CameraDeviceImpl.this, code);
                    } else {
                        mDeviceCallback.onDisconnected(CameraDeviceImpl.this);
                    }
                }
            });
        }
    }

    @Override
    public String getId() {
        return mCameraId;
    }

    public void configureOutputs(List<Surface> outputs) throws CameraAccessException {
        // Leave this here for backwards compatibility with older code using this directly
        configureOutputsChecked(outputs);
    }

    /**
     * Attempt to configure the outputs; the device goes to idle and then configures the
     * new outputs if possible.
     *
     * <p>The configuration may gracefully fail, if there are too many outputs, if the formats
     * are not supported, or if the sizes for that format is not supported. In this case this
     * function will return {@code false} and the unconfigured callback will be fired.</p>
     *
     * <p>If the configuration succeeds (with 1 or more outputs), then the idle callback is fired.
     * Unconfiguring the device always fires the idle callback.</p>
     *
     * @param outputs a list of one or more surfaces, or {@code null} to unconfigure
     * @return whether or not the configuration was successful
     *
     * @throws CameraAccessException if there were any unexpected problems during configuration
     */
    public boolean configureOutputsChecked(List<Surface> outputs) throws CameraAccessException {
        // Treat a null input the same an empty list
        if (outputs == null) {
            outputs = new ArrayList<Surface>();
        }
        boolean success = false;

        synchronized(mInterfaceLock) {
            checkIfCameraClosedOrInError();

            HashSet<Surface> addSet = new HashSet<Surface>(outputs);    // Streams to create
            List<Integer> deleteList = new ArrayList<Integer>();        // Streams to delete

            // Determine which streams need to be created, which to be deleted
            for (int i = 0; i < mConfiguredOutputs.size(); ++i) {
                int streamId = mConfiguredOutputs.keyAt(i);
                Surface s = mConfiguredOutputs.valueAt(i);

                if (!outputs.contains(s)) {
                    deleteList.add(streamId);
                } else {
                    addSet.remove(s);  // Don't create a stream previously created
                }
            }

            mDeviceHandler.post(mCallOnBusy);
            stopRepeating();

            try {
                waitUntilIdle();

                mRemoteDevice.beginConfigure();
                // Delete all streams first (to free up HW resources)
                for (Integer streamId : deleteList) {
                    mRemoteDevice.deleteStream(streamId);
                    mConfiguredOutputs.delete(streamId);
                }

                // Add all new streams
                for (Surface s : addSet) {
                    // TODO: remove width,height,format since we are ignoring
                    // it.
                    int streamId = mRemoteDevice.createStream(0, 0, 0, s);
                    mConfiguredOutputs.put(streamId, s);
                }

                try {
                    mRemoteDevice.endConfigure();
                }
                catch (IllegalArgumentException e) {
                    // OK. camera service can reject stream config if it's not supported by HAL
                    // This is only the result of a programmer misusing the camera2 api.
                    Log.w(TAG, "Stream configuration failed");
                    return false;
                }

                success = true;
            } catch (CameraRuntimeException e) {
                if (e.getReason() == CAMERA_IN_USE) {
                    throw new IllegalStateException("The camera is currently busy."
                            " You must wait until the previous operation completes.");
                }

                throw e.asChecked();
            } catch (RemoteException e) {
                // impossible
                return false;
            } finally {
                if (success && outputs.size() > 0) {
                    mDeviceHandler.post(mCallOnIdle);
                } else {
                    // Always return to the 'unconfigured' state if we didn't hit a fatal error
                    mDeviceHandler.post(mCallOnUnconfigured);
                }
            }
        }

        return success;
    }

    @Override
    public void createCaptureSession(List<Surface> outputs,
            CameraCaptureSession.StateCallback callback, Handler handler)
            throws CameraAccessException {
        synchronized(mInterfaceLock) {
            if (DEBUG) {
                Log.d(TAG, "createCaptureSession");
            }

            checkIfCameraClosedOrInError();

            // Notify current session that it's going away, before starting camera operations
            // After this call completes, the session is not allowed to call into CameraDeviceImpl
            if (mCurrentSession != null) {
                mCurrentSession.replaceSessionClose();
            }

            // TODO: dont block for this
            boolean configureSuccess = true;
            CameraAccessException pendingException = null;
            try {
                configureSuccess = configureOutputsChecked(outputs); // and then block until IDLE
            } catch (CameraAccessException e) {
                configureSuccess = false;
                pendingException = e;
                if (DEBUG) {
                    Log.v(TAG, "createCaptureSession - failed with exception ", e);
                }
            }

            // Fire onConfigured if configureOutputs succeeded, fire onConfigureFailed otherwise.
            CameraCaptureSessionImpl newSession =
                    new CameraCaptureSessionImpl(mNextSessionId++,
                            outputs, callback, handler, this, mDeviceHandler,
                            configureSuccess);

            // TODO: wait until current session closes, then create the new session
            mCurrentSession = newSession;

            if (pendingException != null) {
                throw pendingException;
            }

            mSessionStateCallback = mCurrentSession.getDeviceStateCallback();
        }
    }

    /**
     * For use by backwards-compatibility code only.
     */
    public void setSessionListener(StateCallbackKK sessionCallback) {
        synchronized(mInterfaceLock) {
            mSessionStateCallback = sessionCallback;
        }
    }

    @Override
    public CaptureRequest.Builder createCaptureRequest(int templateType)
            throws CameraAccessException {
        synchronized(mInterfaceLock) {
            checkIfCameraClosedOrInError();

            CameraMetadataNative templatedRequest = new CameraMetadataNative();

            try {
                mRemoteDevice.createDefaultRequest(templateType, /*out*/templatedRequest);
            } catch (CameraRuntimeException e) {
                throw e.asChecked();
            } catch (RemoteException e) {
                // impossible
                return null;
            }

            CaptureRequest.Builder builder =
                    new CaptureRequest.Builder(templatedRequest);

            return builder;
        }
    }

    public int capture(CaptureRequest request, CaptureCallback callback, Handler handler)
            throws CameraAccessException {
        if (DEBUG) {
            Log.d(TAG, "calling capture");
        }
        List<CaptureRequest> requestList = new ArrayList<CaptureRequest>();
        requestList.add(request);
        return submitCaptureRequest(requestList, callback, handler, /*streaming*/false);
    }

    public int captureBurst(List<CaptureRequest> requests, CaptureCallback callback,
            Handler handler) throws CameraAccessException {
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("At least one request must be given");
        }
        return submitCaptureRequest(requests, callback, handler, /*streaming*/false);
    }

    /**
     * This method checks lastFrameNumber returned from ICameraDeviceUser methods for
     * starting and stopping repeating request and flushing.
     *
     * <p>If lastFrameNumber is NO_FRAMES_CAPTURED, it means that the request was never
     * sent to HAL. Then onCaptureSequenceAborted is immediately triggered.
     * If lastFrameNumber is non-negative, then the requestId and lastFrameNumber pair
     * is added to the list mFrameNumberRequestPairs.</p>
     *
     * @param requestId the request ID of the current repeating request.
     *
     * @param lastFrameNumber last frame number returned from binder.
     */
    private void checkEarlyTriggerSequenceComplete(
            final int requestId, final long lastFrameNumber) {
        // lastFrameNumber being equal to NO_FRAMES_CAPTURED means that the request
        // was never sent to HAL. Should trigger onCaptureSequenceAborted immediately.
        if (lastFrameNumber == CaptureCallback.NO_FRAMES_CAPTURED) {
            final CaptureCallbackHolder holder;
            int index = mCaptureCallbackMap.indexOfKey(requestId);
            holder = (index >= 0) ? mCaptureCallbackMap.valueAt(index) : null;
            if (holder != null) {
                mCaptureCallbackMap.removeAt(index);
                if (DEBUG) {
                    Log.v(TAG, String.format(
                            "remove holder for requestId %d, "
                            + "because lastFrame is %d.",
                            requestId, lastFrameNumber));
                }
            }

            if (holder != null) {
                if (DEBUG) {
                    Log.v(TAG, "immediately trigger onCaptureSequenceAborted because"
                            + " request did not reach HAL");
                }

                Runnable resultDispatch = new Runnable() {
                    @Override
                    public void run() {
                        if (!CameraDeviceImpl.this.isClosed()) {
                            if (DEBUG) {
                                Log.d(TAG, String.format(
                                        "early trigger sequence complete for request %d",
                                        requestId));
                            }
                            if (lastFrameNumber < Integer.MIN_VALUE
                                    || lastFrameNumber > Integer.MAX_VALUE) {
                                throw new AssertionError(lastFrameNumber + " cannot be cast to int");
                            }
                            holder.getCallback().onCaptureSequenceAborted(
                                    CameraDeviceImpl.this,
                                    requestId);
                        }
                    }
                };
                holder.getHandler().post(resultDispatch);
            } else {
                Log.w(TAG, String.format(
                        "did not register callback to request %d",
                        requestId));
            }
        } else {
            mFrameNumberRequestPairs.add(
                    new SimpleEntry<Long, Integer>(lastFrameNumber,
                            requestId));
            // It is possible that the last frame has already arrived, so we need to check
            // for sequence completion right away
            checkAndFireSequenceComplete();
        }
    }

    private int submitCaptureRequest(List<CaptureRequest> requestList, CaptureCallback callback,
            Handler handler, boolean repeating) throws CameraAccessException {

        // Need a valid handler, or current thread needs to have a looper, if
        // callback is valid
        handler = checkHandler(handler, callback);

        // Make sure that there all requests have at least 1 surface; all surfaces are non-null
        for (CaptureRequest request : requestList) {
            if (request.getTargets().isEmpty()) {
                throw new IllegalArgumentException(
                        "Each request must have at least one Surface target");
            }

            for (Surface surface : request.getTargets()) {
                if (surface == null) {
                    throw new IllegalArgumentException("Null Surface targets are not allowed");
                }
            }
        }

        synchronized(mInterfaceLock) {
            checkIfCameraClosedOrInError();
            int requestId;

            if (repeating) {
                stopRepeating();
            }

            LongParcelable lastFrameNumberRef = new LongParcelable();
            try {
                requestId = mRemoteDevice.submitRequestList(requestList, repeating,
                        /*out*/lastFrameNumberRef);
                if (DEBUG) {
                    Log.v(TAG, "last frame number " + lastFrameNumberRef.getNumber());
                }
            } catch (CameraRuntimeException e) {
                throw e.asChecked();
            } catch (RemoteException e) {
                // impossible
                return -1;
            }

            if (callback != null) {
                mCaptureCallbackMap.put(requestId, new CaptureCallbackHolder(callback,
                        requestList, handler, repeating));
            } else {
                if (DEBUG) {
                    Log.d(TAG, "Listen for request " + requestId + " is null");
                }
            }

            long lastFrameNumber = lastFrameNumberRef.getNumber();

            if (repeating) {
                if (mRepeatingRequestId != REQUEST_ID_NONE) {
                    checkEarlyTriggerSequenceComplete(mRepeatingRequestId, lastFrameNumber);
                }
                mRepeatingRequestId = requestId;
            } else {
                mFrameNumberRequestPairs.add(
                        new SimpleEntry<Long, Integer>(lastFrameNumber, requestId));
            }

            if (mIdle) {
                mDeviceHandler.post(mCallOnActive);
            }
            mIdle = false;

            return requestId;
        }
    }

    public int setRepeatingRequest(CaptureRequest request, CaptureCallback callback,
            Handler handler) throws CameraAccessException {
        List<CaptureRequest> requestList = new ArrayList<CaptureRequest>();
        requestList.add(request);
        return submitCaptureRequest(requestList, callback, handler, /*streaming*/true);
    }

    public int setRepeatingBurst(List<CaptureRequest> requests, CaptureCallback callback,
            Handler handler) throws CameraAccessException {
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("At least one request must be given");
        }
        return submitCaptureRequest(requests, callback, handler, /*streaming*/true);
    }

    public void stopRepeating() throws CameraAccessException {

        synchronized(mInterfaceLock) {
            checkIfCameraClosedOrInError();
            if (mRepeatingRequestId != REQUEST_ID_NONE) {

                int requestId = mRepeatingRequestId;
                mRepeatingRequestId = REQUEST_ID_NONE;

                // Queue for deletion after in-flight requests finish
                if (mCaptureCallbackMap.get(requestId) != null) {
                    mRepeatingRequestIdDeletedList.add(requestId);
                }

                try {
                    LongParcelable lastFrameNumberRef = new LongParcelable();
                    mRemoteDevice.cancelRequest(requestId, /*out*/lastFrameNumberRef);
                    long lastFrameNumber = lastFrameNumberRef.getNumber();

                    checkEarlyTriggerSequenceComplete(requestId, lastFrameNumber);

                } catch (CameraRuntimeException e) {
                    throw e.asChecked();
                } catch (RemoteException e) {
                    // impossible
                    return;
                }
            }
        }
    }

    private void waitUntilIdle() throws CameraAccessException {

        synchronized(mInterfaceLock) {
            checkIfCameraClosedOrInError();

            if (mRepeatingRequestId != REQUEST_ID_NONE) {
                throw new IllegalStateException("Active repeating request ongoing");
            }
            try {
                mRemoteDevice.waitUntilIdle();
            } catch (CameraRuntimeException e) {
                throw e.asChecked();
            } catch (RemoteException e) {
                // impossible
                return;
            }
        }
    }

    public void flush() throws CameraAccessException {
        synchronized(mInterfaceLock) {
            checkIfCameraClosedOrInError();

            mDeviceHandler.post(mCallOnBusy);

            // If already idle, just do a busy->idle transition immediately, don't actually
            // flush.
            if (mIdle) {
                mDeviceHandler.post(mCallOnIdle);
                return;
            }
            try {
                LongParcelable lastFrameNumberRef = new LongParcelable();
                mRemoteDevice.flush(/*out*/lastFrameNumberRef);
                if (mRepeatingRequestId != REQUEST_ID_NONE) {
                    long lastFrameNumber = lastFrameNumberRef.getNumber();
                    checkEarlyTriggerSequenceComplete(mRepeatingRequestId, lastFrameNumber);
                    mRepeatingRequestId = REQUEST_ID_NONE;
                }
            } catch (CameraRuntimeException e) {
                throw e.asChecked();
            } catch (RemoteException e) {
                // impossible
                return;
            }
        }
    }

    @Override
    public void close() {
        synchronized (mInterfaceLock) {
            try {
                if (mRemoteDevice != null) {
                    mRemoteDevice.disconnect();
                }
            } catch (CameraRuntimeException e) {
                Log.e(TAG, "Exception while closing: ", e.asChecked());
            } catch (RemoteException e) {
                // impossible
            }

            // Only want to fire the onClosed callback once;
            // either a normal close where the remote device is valid
            // or a close after a startup error (no remote device but in error state)
            if (mRemoteDevice != null || mInError) {
                mDeviceHandler.post(mCallOnClosed);
            }

            mRemoteDevice = null;
            mInError = false;
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            close();
        }
        finally {
            super.finalize();
        }
    }

    /**
     * <p>A callback for tracking the progress of a {@link CaptureRequest}
     * submitted to the camera device.</p>
     *
     */
    public static abstract class CaptureCallback {

        /**
         * This constant is used to indicate that no images were captured for
         * the request.
         *
         * @hide
         */
        public static final int NO_FRAMES_CAPTURED = -1;

        /**
         * This method is called when the camera device has started capturing
         * the output image for the request, at the beginning of image exposure.
         *
         * @see android.media.MediaActionSound
         */
        public void onCaptureStarted(CameraDevice camera,
                CaptureRequest request, long timestamp, long frameNumber) {
            // default empty implementation
        }

        /**
         * This method is called when some results from an image capture are
         * available.
         *
         * @hide
         */
        public void onCapturePartial(CameraDevice camera,
                CaptureRequest request, CaptureResult result) {
            // default empty implementation
        }

        /**
         * This method is called when an image capture makes partial forward progress; some
         * (but not all) results from an image capture are available.
         *
         */
        public void onCaptureProgressed(CameraDevice camera,
                CaptureRequest request, CaptureResult partialResult) {
            // default empty implementation
        }

        /**
         * This method is called when an image capture has fully completed and all the
         * result metadata is available.
         */
        public void onCaptureCompleted(CameraDevice camera,
                CaptureRequest request, TotalCaptureResult result) {
            // default empty implementation
        }

        /**
         * This method is called instead of {@link #onCaptureCompleted} when the
         * camera device failed to produce a {@link CaptureResult} for the
         * request.
         */
        public void onCaptureFailed(CameraDevice camera,
                CaptureRequest request, CaptureFailure failure) {
            // default empty implementation
        }

        /**
         * This method is called independently of the others in CaptureCallback,
         * when a capture sequence finishes and all {@link CaptureResult}
         * or {@link CaptureFailure} for it have been returned via this callback.
         */
        public void onCaptureSequenceCompleted(CameraDevice camera,
                int sequenceId, long frameNumber) {
            // default empty implementation
        }

        /**
         * This method is called independently of the others in CaptureCallback,
         * when a capture sequence aborts before any {@link CaptureResult}
         * or {@link CaptureFailure} for it have been returned via this callback.
         */
        public void onCaptureSequenceAborted(CameraDevice camera,
                int sequenceId) {
            // default empty implementation
        }
    }

    /**
     * A callback for notifications about the state of a camera device, adding in the callbacks that
     * were part of the earlier KK API design, but now only used internally.
     */
    public static abstract class StateCallbackKK extends StateCallback {
        /**
         * The method called when a camera device has no outputs configured.
         *
         */
        public void onUnconfigured(CameraDevice camera) {
            // Default empty implementation
        }

        /**
         * The method called when a camera device begins processing
         * {@link CaptureRequest capture requests}.
         *
         */
        public void onActive(CameraDevice camera) {
            // Default empty implementation
        }

        /**
         * The method called when a camera device is busy.
         *
         */
        public void onBusy(CameraDevice camera) {
            // Default empty implementation
        }

        /**
         * The method called when a camera device has finished processing all
         * submitted capture requests and has reached an idle state.
         *
         */
        public void onIdle(CameraDevice camera) {
            // Default empty implementation
        }
    }

    static class CaptureCallbackHolder {

        private final boolean mRepeating;
        private final CaptureCallback mCallback;
        private final List<CaptureRequest> mRequestList;
        private final Handler mHandler;

        CaptureCallbackHolder(CaptureCallback callback, List<CaptureRequest> requestList,
                Handler handler, boolean repeating) {
            if (callback == null || handler == null) {
                throw new UnsupportedOperationException(
                    "Must have a valid handler and a valid callback");
            }
            mRepeating = repeating;
            mHandler = handler;
            mRequestList = new ArrayList<CaptureRequest>(requestList);
            mCallback = callback;
        }

        public boolean isRepeating() {
            return mRepeating;
        }

        public CaptureCallback getCallback() {
            return mCallback;
        }

        public CaptureRequest getRequest(int subsequenceId) {
            if (subsequenceId >= mRequestList.size()) {
                throw new IllegalArgumentException(
                        String.format(
                                "Requested subsequenceId %d is larger than request list size %d.",
                                subsequenceId, mRequestList.size()));
            } else {
                if (subsequenceId < 0) {
                    throw new IllegalArgumentException(String.format(
                            "Requested subsequenceId %d is negative", subsequenceId));
                } else {
                    return mRequestList.get(subsequenceId);
                }
            }
        }

        public CaptureRequest getRequest() {
            return getRequest(0);
        }

        public Handler getHandler() {
            return mHandler;
        }

    }

    /**
     * This class tracks the last frame number for submitted requests.
     */
    public class FrameNumberTracker {

        private long mCompletedFrameNumber = -1;
        private final TreeSet<Long> mFutureErrorSet = new TreeSet<Long>();
        /** Map frame numbers to list of partial results */
        private final HashMap<Long, List<CaptureResult>> mPartialResults = new HashMap<>();

        private void update() {
            Iterator<Long> iter = mFutureErrorSet.iterator();
            while (iter.hasNext()) {
                long errorFrameNumber = iter.next();
                if (errorFrameNumber == mCompletedFrameNumber + 1) {
                    mCompletedFrameNumber++;
                    iter.remove();
                } else {
                    break;
                }
            }
        }

        /**
         * This function is called every time when a result or an error is received.
         * @param frameNumber the frame number corresponding to the result or error
         * @param isError true if it is an error, false if it is not an error
         */
        public void updateTracker(long frameNumber, boolean isError) {
            if (isError) {
                mFutureErrorSet.add(frameNumber);
            } else {
                /**
                 * HAL cannot send an OnResultReceived for frame N unless it knows for
                 * sure that all frames prior to N have either errored out or completed.
                 * So if the current frame is not an error, then all previous frames
                 * should have arrived. The following line checks whether this holds.
                 */
                if (frameNumber != mCompletedFrameNumber + 1) {
                    Log.e(TAG, String.format(
                            "result frame number %d comes out of order, should be %d + 1",
                            frameNumber, mCompletedFrameNumber));
                    // Continue on to set the completed frame number to this frame anyway,
                    // to be robust to lower-level errors and allow for clean shutdowns.
                }
                mCompletedFrameNumber = frameNumber;
            }
            update();
        }

        /**
         * This function is called every time a result has been completed.
         *
         * <p>It keeps a track of all the partial results already created for a particular
         * frame number.</p>
         *
         * @param frameNumber the frame number corresponding to the result
         * @param result the total or partial result
         * @param partial {@true} if the result is partial, {@code false} if total
         */
        public void updateTracker(long frameNumber, CaptureResult result, boolean partial) {

            if (!partial) {
                // Update the total result's frame status as being successful
                updateTracker(frameNumber, /*isError*/false);
                // Don't keep a list of total results, we don't need to track them
                return;
            }

            if (result == null) {
                // Do not record blank results; this also means there will be no total result
                // so it doesn't matter that the partials were not recorded
                return;
            }

            // Partial results must be aggregated in-order for that frame number
            List<CaptureResult> partials = mPartialResults.get(frameNumber);
            if (partials == null) {
                partials = new ArrayList<>();
                mPartialResults.put(frameNumber, partials);
            }

            partials.add(result);
        }

        /**
         * Attempt to pop off all of the partial results seen so far for the {@code frameNumber}.
         *
         * <p>Once popped-off, the partial results are forgotten (unless {@code updateTracker}
         * is called again with new partials for that frame number).</p>
         *
         * @param frameNumber the frame number corresponding to the result
         * @return a list of partial results for that frame with at least 1 element,
         *         or {@code null} if there were no partials recorded for that frame
         */
        public List<CaptureResult> popPartialResults(long frameNumber) {
            return mPartialResults.remove(frameNumber);
        }

        public long getCompletedFrameNumber() {
            return mCompletedFrameNumber;
        }

    }

    private void checkAndFireSequenceComplete() {
        long completedFrameNumber = mFrameNumberTracker.getCompletedFrameNumber();
        Iterator<SimpleEntry<Long, Integer> > iter = mFrameNumberRequestPairs.iterator();
        while (iter.hasNext()) {
            final SimpleEntry<Long, Integer> frameNumberRequestPair = iter.next();
            if (frameNumberRequestPair.getKey() <= completedFrameNumber) {

                // remove request from mCaptureCallbackMap
                final int requestId = frameNumberRequestPair.getValue();
                final CaptureCallbackHolder holder;
                synchronized(mInterfaceLock) {
                    if (mRemoteDevice == null) {
                        Log.w(TAG, "Camera closed while checking sequences");
                        return;
                    }

                    int index = mCaptureCallbackMap.indexOfKey(requestId);
                    holder = (index >= 0) ? mCaptureCallbackMap.valueAt(index)
                            : null;
                    if (holder != null) {
                        mCaptureCallbackMap.removeAt(index);
                        if (DEBUG) {
                            Log.v(TAG, String.format(
                                    "remove holder for requestId %d, "
                                    + "because lastFrame %d is <= %d",
                                    requestId, frameNumberRequestPair.getKey(),
                                    completedFrameNumber));
                        }
                    }
                }
                iter.remove();

                // Call onCaptureSequenceCompleted
                if (holder != null) {
                    Runnable resultDispatch = new Runnable() {
                        @Override
                        public void run() {
                            if (!CameraDeviceImpl.this.isClosed()){
                                if (DEBUG) {
                                    Log.d(TAG, String.format(
                                            "fire sequence complete for request %d",
                                            requestId));
                                }

                                long lastFrameNumber = frameNumberRequestPair.getKey();
                                if (lastFrameNumber < Integer.MIN_VALUE
                                        || lastFrameNumber > Integer.MAX_VALUE) {
                                    throw new AssertionError(lastFrameNumber
                                            + " cannot be cast to int");
                                }
                                holder.getCallback().onCaptureSequenceCompleted(
                                    CameraDeviceImpl.this,
                                    requestId,
                                    lastFrameNumber);
                            }
                        }
                    };
                    holder.getHandler().post(resultDispatch);
                }

            }
        }
    }

    public class CameraDeviceCallbacks extends ICameraDeviceCallbacks.Stub {
        //
        // Constants below need to be kept up-to-date with
        // frameworks/av/include/camera/camera2/ICameraDeviceCallbacks.h
        //

        //
        // Error codes for onCameraError
        //

        /**
         * Camera has been disconnected
         */
        public static final int ERROR_CAMERA_DISCONNECTED = 0;
        /**
         * Camera has encountered a device-level error
         * Matches CameraDevice.StateCallback#ERROR_CAMERA_DEVICE
         */
        public static final int ERROR_CAMERA_DEVICE = 1;
        /**
         * Camera has encountered a service-level error
         * Matches CameraDevice.StateCallback#ERROR_CAMERA_SERVICE
         */
        public static final int ERROR_CAMERA_SERVICE = 2;
        /**
         * Camera has encountered an error processing a single request.
         */
        public static final int ERROR_CAMERA_REQUEST = 3;
        /**
         * Camera has encountered an error producing metadata for a single capture
         */
        public static final int ERROR_CAMERA_RESULT = 4;
        /**
         * Camera has encountered an error producing an image buffer for a single capture
         */
        public static final int ERROR_CAMERA_BUFFER = 5;

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        public void onDeviceError(final int errorCode, CaptureResultExtras resultExtras) {
            if (DEBUG) {
                Log.d(TAG, String.format(
                        "Device error received, code %d, frame number %d, request ID %d, subseq ID %d",
                        errorCode, resultExtras.getFrameNumber(), resultExtras.getRequestId(),
                        resultExtras.getSubsequenceId()));
            }

            synchronized(mInterfaceLock) {
                if (mRemoteDevice == null) {
                    return; // Camera already closed
                }

                switch (errorCode) {
                    case ERROR_CAMERA_DISCONNECTED:
                        CameraDeviceImpl.this.mDeviceHandler.post(mCallOnDisconnected);
                        break;
                    default:
                        Log.e(TAG, "Unknown error from camera device: " + errorCode);
                        // no break
                    case ERROR_CAMERA_DEVICE:
                    case ERROR_CAMERA_SERVICE:
                        mInError = true;
                        Runnable r = new Runnable() {
                            @Override
                            public void run() {
                                if (!CameraDeviceImpl.this.isClosed()) {
                                    mDeviceCallback.onError(CameraDeviceImpl.this, errorCode);
                                }
                            }
                        };
                        CameraDeviceImpl.this.mDeviceHandler.post(r);
                        break;
                    case ERROR_CAMERA_REQUEST:
                    case ERROR_CAMERA_RESULT:
                    case ERROR_CAMERA_BUFFER:
                        onCaptureErrorLocked(errorCode, resultExtras);
                        break;
                }
            }
        }

        @Override
        public void onDeviceIdle() {
            if (DEBUG) {
                Log.d(TAG, "Camera now idle");
            }
            synchronized(mInterfaceLock) {
                if (mRemoteDevice == null) return; // Camera already closed

                if (!CameraDeviceImpl.this.mIdle) {
                    CameraDeviceImpl.this.mDeviceHandler.post(mCallOnIdle);
                }
                CameraDeviceImpl.this.mIdle = true;
            }
        }

        @Override
        public void onCaptureStarted(final CaptureResultExtras resultExtras, final long timestamp) {
            int requestId = resultExtras.getRequestId();
            final long frameNumber = resultExtras.getFrameNumber();

            if (DEBUG) {
                Log.d(TAG, "Capture started for id " + requestId + " frame number " + frameNumber);
            }
            final CaptureCallbackHolder holder;

            synchronized(mInterfaceLock) {
                if (mRemoteDevice == null) return; // Camera already closed

                // Get the callback for this frame ID, if there is one
                holder = CameraDeviceImpl.this.mCaptureCallbackMap.get(requestId);

                if (holder == null) {
                    return;
                }

                if (isClosed()) return;

                // Dispatch capture start notice
                holder.getHandler().post(
                    new Runnable() {
                        @Override
                        public void run() {
                            if (!CameraDeviceImpl.this.isClosed()) {
                                holder.getCallback().onCaptureStarted(
                                    CameraDeviceImpl.this,
                                    holder.getRequest(resultExtras.getSubsequenceId()),
                                    timestamp, frameNumber);
                            }
                        }
                    });

            }
        }

        @Override
        public void onResultReceived(CameraMetadataNative result,
                CaptureResultExtras resultExtras) throws RemoteException {

            int requestId = resultExtras.getRequestId();
            long frameNumber = resultExtras.getFrameNumber();

            if (DEBUG) {
                Log.v(TAG, "Received result frame " + frameNumber + " for id "
                        + requestId);
            }

            synchronized(mInterfaceLock) {
                if (mRemoteDevice == null) return; // Camera already closed

                // TODO: Handle CameraCharacteristics access from CaptureResult correctly.
                result.set(CameraCharacteristics.LENS_INFO_SHADING_MAP_SIZE,
                        getCharacteristics().get(CameraCharacteristics.LENS_INFO_SHADING_MAP_SIZE));

                final CaptureCallbackHolder holder =
                        CameraDeviceImpl.this.mCaptureCallbackMap.get(requestId);

                boolean isPartialResult =
                        (resultExtras.getPartialResultCount() < mTotalPartialCount);

                // Check if we have a callback for this
                if (holder == null) {
                    if (DEBUG) {
                        Log.d(TAG,
                                "holder is null, early return at frame "
                                        + frameNumber);
                    }

                    mFrameNumberTracker.updateTracker(frameNumber, /*result*/null, isPartialResult);

                    return;
                }

                if (isClosed()) {
                    if (DEBUG) {
                        Log.d(TAG,
                                "camera is closed, early return at frame "
                                        + frameNumber);
                    }

                    mFrameNumberTracker.updateTracker(frameNumber, /*result*/null, isPartialResult);
                    return;
                }

                final CaptureRequest request = holder.getRequest(resultExtras.getSubsequenceId());

                Runnable resultDispatch = null;

                CaptureResult finalResult;

                // Either send a partial result or the final capture completed result
                if (isPartialResult) {
                    final CaptureResult resultAsCapture =
                            new CaptureResult(result, request, resultExtras);

                    // Partial result
                    resultDispatch = new Runnable() {
                        @Override
                        public void run() {
                            if (!CameraDeviceImpl.this.isClosed()){
                                holder.getCallback().onCaptureProgressed(
                                    CameraDeviceImpl.this,
                                    request,
                                    resultAsCapture);
                            }
                        }
                    };

                    finalResult = resultAsCapture;
                } else {
                    List<CaptureResult> partialResults =
                            mFrameNumberTracker.popPartialResults(frameNumber);

                    final TotalCaptureResult resultAsCapture =
                            new TotalCaptureResult(result, request, resultExtras, partialResults);

                    // Final capture result
                    resultDispatch = new Runnable() {
                        @Override
                        public void run() {
                            if (!CameraDeviceImpl.this.isClosed()){
                                holder.getCallback().onCaptureCompleted(
                                    CameraDeviceImpl.this,
                                    request,
                                    resultAsCapture);
                            }
                        }
                    };

                    finalResult = resultAsCapture;
                }

                holder.getHandler().post(resultDispatch);

                // Collect the partials for a total result; or mark the frame as totally completed
                mFrameNumberTracker.updateTracker(frameNumber, finalResult, isPartialResult);

                // Fire onCaptureSequenceCompleted
                if (!isPartialResult) {
                    checkAndFireSequenceComplete();
                }
            }
        }

        /**
         * Called by onDeviceError for handling single-capture failures.
         */
        private void onCaptureErrorLocked(int errorCode, CaptureResultExtras resultExtras) {

            final int requestId = resultExtras.getRequestId();
            final int subsequenceId = resultExtras.getSubsequenceId();
            final long frameNumber = resultExtras.getFrameNumber();
            final CaptureCallbackHolder holder =
                    CameraDeviceImpl.this.mCaptureCallbackMap.get(requestId);

            final CaptureRequest request = holder.getRequest(subsequenceId);

            // No way to report buffer errors right now
            if (errorCode == ERROR_CAMERA_BUFFER) {
                Log.e(TAG, String.format("Lost output buffer reported for frame %d", frameNumber));
                return;
            }

            boolean mayHaveBuffers = (errorCode == ERROR_CAMERA_RESULT);

            // This is only approximate - exact handling needs the camera service and HAL to
            // disambiguate between request failures to due abort and due to real errors.
            // For now, assume that if the session believes we're mid-abort, then the error
            // is due to abort.
            int reason = (mCurrentSession != null && mCurrentSession.isAborting()) ?
                    CaptureFailure.REASON_FLUSHED :
                    CaptureFailure.REASON_ERROR;

            final CaptureFailure failure = new CaptureFailure(
                request,
                reason,
                /*dropped*/ mayHaveBuffers,
                requestId,
                frameNumber);

            Runnable failureDispatch = new Runnable() {
                @Override
                public void run() {
                    if (!CameraDeviceImpl.this.isClosed()){
                        holder.getCallback().onCaptureFailed(
                            CameraDeviceImpl.this,
                            request,
                            failure);
                    }
                }
            };
            holder.getHandler().post(failureDispatch);

            // Fire onCaptureSequenceCompleted if appropriate
            if (DEBUG) {
                Log.v(TAG, String.format("got error frame %d", frameNumber));
            }
            mFrameNumberTracker.updateTracker(frameNumber, /*error*/true);
            checkAndFireSequenceComplete();
        }

    } // public class CameraDeviceCallbacks

    /**
     * Default handler management.
     *
     * <p>
     * If handler is null, get the current thread's
     * Looper to create a Handler with. If no looper exists, throw {@code IllegalArgumentException}.
     * </p>
     */
    static Handler checkHandler(Handler handler) {
        if (handler == null) {
            Looper looper = Looper.myLooper();
            if (looper == null) {
                throw new IllegalArgumentException(
                    "No handler given, and current thread has no looper!");
            }
            handler = new Handler(looper);
        }
        return handler;
    }

    /**
     * Default handler management, conditional on there being a callback.
     *
     * <p>If the callback isn't null, check the handler, otherwise pass it through.</p>
     */
    static <T> Handler checkHandler(Handler handler, T callback) {
        if (callback != null) {
            return checkHandler(handler);
        }
        return handler;
    }

    private void checkIfCameraClosedOrInError() throws CameraAccessException {
        if (mInError) {
            throw new CameraAccessException(CameraAccessException.CAMERA_ERROR,
                    "The camera device has encountered a serious error");
        }
        if (mRemoteDevice == null) {
            throw new IllegalStateException("CameraDevice was already closed");
        }
    }

    /** Whether the camera device has started to close (may not yet have finished) */
    private boolean isClosed() {
        return mClosing;
    }

    private CameraCharacteristics getCharacteristics() {
        return mCharacteristics;
    }
}
