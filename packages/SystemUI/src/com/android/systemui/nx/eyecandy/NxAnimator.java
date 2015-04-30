package com.android.systemui.nx.eyecandy;

import java.util.*;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Bitmap.Config;
import android.os.Handler;
import android.os.Message;
import android.provider.Settings;
import android.util.Log;
import android.view.MotionEvent;

public class NxAnimator {
	public interface BufferListener {
		public void onPrepareToDraw();
		public void onBufferUpdated(Bitmap buffer);
	}

	// tunable defaults
	public static final float STARTING_ALPHA = 0.8f;
	public static final int ALPHA_DECAY_RATE = 500;  //ms
	public static final int MAX_TRAILS = 20;
	public static final int TOUCH_SLOP = 75;   // minumum distance between trails, squared, we don't want heavy sqrt ops

	private static final int ANIM_FPS = 30;

	private static final int MSG_REQUEST_SELF_STOP = 7593;
	private static final int MSG_PREPARE_DRAW = 7594;
	private static final int MSG_BUFFER_UPDATED = 7595;
	
	private List<Point> mVectorBuffer = new ArrayList<Point>();
	private volatile boolean mKeepRunning;

	private boolean mEnabled;
	private boolean mSelfStop;

 	private BufferListener mListener;
 	private Context mContext;

	private int bWidth;
	private int bHeight;
	private float pWidth;
	private float pHeight;
	private Bitmap mBuffer;
	private Bitmap mPointer;
	private Canvas mBufferCanvas;
	private AnimThread mAnimThread;
	private float mLastX = 0f;
	private float mLastY = 0f;
	private float mCurrentX = 0f;
	private float mCurrentY = 0f;
	private float mAlphaDecay = ALPHA_DECAY_RATE;
	private float mAlpha = STARTING_ALPHA * 255;
	private float mMaxTrails = MAX_TRAILS;
	private float mTouchSlop = TOUCH_SLOP * TOUCH_SLOP;

	private Handler mUiHandler = new Handler() {
		public void handleMessage(Message m) {
			switch (m.what) {
			case MSG_REQUEST_SELF_STOP:
				stopAnimation();
				break;
			case MSG_PREPARE_DRAW:
				mListener.onPrepareToDraw();
				break;
			case MSG_BUFFER_UPDATED:
				mListener.onBufferUpdated(mBuffer);
				break;
			}
		}
	};

	private class AnimThread extends Thread {
		List<Point> mVectors = new ArrayList<Point>();
		List<Point> mVectorsToRemove = new ArrayList<Point>();

		public void run() {
			while (mKeepRunning) {
				if (mVectors.size() > mMaxTrails) {
					mVectors.remove(0);
				}
				// THIS IS MY ONLY CONCERN HERE! possible concurrent access exceptions
				// can we synchronize without heavily locking up the buffer?
				if (mVectorBuffer.size() > 0) {
					mVectors.add(mVectorBuffer.get(0));
					removeOldestPoint();
				}
				mBuffer = Bitmap
						.createBitmap(bWidth, bHeight, Config.ARGB_8888);
				if (mVectors.size() > 0) {
					mBufferCanvas = new Canvas(mBuffer);
					for (int i = mVectors.size() - 1; i >= 0; i = i - 1) {
						Point p = mVectors.get(i);
						p.updateAlpha();
						if (p.shouldRemove()) {
							mVectorsToRemove.add(p);
						}
						float px = p.x - pWidth;
						float py = p.y - pHeight;
						mBufferCanvas.drawBitmap(mPointer, px, py, p.getPaint());
					}
					mVectors.removeAll(mVectorsToRemove);
					mVectorsToRemove.clear();
				} else {
					if (mSelfStop) {
						mKeepRunning = false;
					}
				}
				updateUiThread(MSG_PREPARE_DRAW);
				try {
					sleep(1000 / 30);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				updateUiThread(MSG_BUFFER_UPDATED);
			}
			mVectors.clear();
			updateUiThread(MSG_REQUEST_SELF_STOP);
		}
	}

	public NxAnimator(Context context, int bufferWidth, int bufferHeight, Bitmap pointer,
			BufferListener listener) {
		mContext = context;
		mListener = listener;
		updateResources(bufferWidth, bufferHeight, pointer);
		updateSettings();
	};

	public Bitmap getBuffer() {
		return mBuffer;
	}

	public void stopAnimation() {
		if (mAnimThread != null) {
			mKeepRunning = false;
			mSelfStop = false;
			mAnimThread = null;
			mVectorBuffer.clear();
		}
	}

	public void startAnimation() {
		stopAnimation();
		mKeepRunning = true;
		mSelfStop = false;
		mVectorBuffer.clear();
		mAnimThread = new AnimThread();
		mAnimThread.start();
	}

	public void setEnabled(boolean enabled) {
		mEnabled = enabled;
	}

	public boolean isEnabled() {
		return mEnabled;
	}

	public boolean isAnimating() {
		return mAnimThread != null;
	}

	public void handleMotionEvent(int action, float x1, float y1) {
		final float x = x1;
		final float y = y1;
		switch (action) {
		case MotionEvent.ACTION_DOWN:
			onDownEvent(x, y);
			break;
		case MotionEvent.ACTION_MOVE:
			onMoveEvent(x, y);
			break;
		case MotionEvent.ACTION_UP:
			onUpEvent(x, y);
			break;
		}
	}

	private void updateSettings() {
		final ContentResolver resolver = mContext.getContentResolver();
		mEnabled = Settings.System.getInt(resolver, "eos_nx_trails_enabled", 1) == 1;
		mAlphaDecay = Settings.System.getFloat(resolver, "eos_nx_trails_alpha_decay", ALPHA_DECAY_RATE);
		mAlpha = Settings.System.getInt(resolver, "eos_nx_trails_alpha_level", Math.round((STARTING_ALPHA * 255) + 0.5f));
		mMaxTrails = Settings.System.getInt(resolver, "eos_nx_trails_max_trails", MAX_TRAILS);		
		int touchSlop = Settings.System.getInt(resolver, "eos_nx_trails_touch_slop", TOUCH_SLOP);
		mTouchSlop = touchSlop * touchSlop;		
	}

	private void updateUiThread(int msg) {
		Message m = mUiHandler.obtainMessage();
		m.what = msg;
		mUiHandler.sendMessage(m);
	}

	private static void log(String s) {
		Log.i("NxAnimator", s);
	}

	private void onDownEvent(float x, float y) {
		mCurrentX = x;
		mCurrentY = y;
		mLastX = mCurrentX;
		mLastY = mCurrentY;
		startAnimation();
	}

	private void onMoveEvent(float x, float y) {
		mCurrentX = x;
		mCurrentY = y;
		float dist = distSq(mCurrentX, mCurrentY, mLastX, mLastY);
		if (dist > mTouchSlop) {
			mLastX = mCurrentX;
			mLastY = mCurrentY;
			addPointToBuffer(new Point(mCurrentX, mCurrentY));
		}
	}

	private void addPointToBuffer(Point p) {
		mVectorBuffer.add(p);
	}

	private void removeOldestPoint() {
		if (mVectorBuffer.size() > 0)
			mVectorBuffer.remove(0);
	}

	private void onUpEvent(float x, float y) {
		mSelfStop = true;
	}

	private void updateResources(int bufferWidth, int bufferHeight,
			Bitmap pointer) {
		stopAnimation();
		mPointer = pointer;
		bWidth = bufferWidth;
		bHeight = bufferHeight;
		pWidth = ((float) mPointer.getWidth() / 2);
		pHeight = ((float) mPointer.getHeight() / 2);
		mBuffer = Bitmap.createBitmap(bWidth, bHeight, Config.ARGB_8888);
		mBufferCanvas = new Canvas(mBuffer);
	}

	private class Point {
		float x = 0f;
		float y = 0f;
		int alpha;
		int increment;
		Paint p;

		public Point(float x1, float y1) {
			x = x1;
			y = y1;
			increment = Math.round((mAlpha / ((mAlphaDecay / 1000) * ANIM_FPS)) + 0.5f);
			alpha = Math.round(mAlpha + 0.5f);
			p = new Paint();
		}

		void updateAlpha() {
			alpha -= increment;
			if (alpha < 0)
				alpha = 0;
			p.setAlpha(alpha);
		}

		boolean shouldRemove() {
			return alpha <= 0;
		}

		Paint getPaint() {
			return p;
		}
	}

	private static float distSq(float currentX, float currentY, float lastX,
			float lastY) {
		float dx = currentX - lastX, dy = currentY - lastY;
		return dx * dx + dy * dy;
	}

	private static float distSq(Point p1, Point p2) {
		float dx = p1.x - p2.x, dy = p1.y - p2.y;
		return dx * dx + dy * dy;
	}
}
