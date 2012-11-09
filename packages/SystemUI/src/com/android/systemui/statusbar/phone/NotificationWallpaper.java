package com.android.systemui.statusbar.phone;

import java.io.File;
import java.io.IOException;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.util.AttributeSet;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;
import com.android.systemui.R;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.content.ContentResolver;


class NotificationWallpaper extends FrameLayout {

    private final String TAG = "NotificationWallpaperUpdater";

	private final String NOTIF_WALLPAPER_IMAGE_PATH = "/data/data/com.android.settings/files/notification_wallpaper.jpg";

    private ImageView mNotificationWallpaperImage;
    private float wallpaperAlpha;

	Context mContext;

    Bitmap bitmapWallpaper;

    public NotificationWallpaper(Context context, AttributeSet attrs) {
        super(context);
	mContext = context;
        setNotificationWallpaper();
	SettingsObserver observer = new SettingsObserver(new Handler());
        observer.observe();
    }

    public void setNotificationWallpaper() {
        File file = new File(NOTIF_WALLPAPER_IMAGE_PATH);

        if (file.exists()) {
		    removeAllViews();
 		    wallpaperAlpha = Settings.System.getFloat(getContext()
         	   .getContentResolver(), Settings.System.NOTIF_WALLPAPER_ALPHA, 0.0f);

            mNotificationWallpaperImage = new ImageView(getContext());
            mNotificationWallpaperImage.setScaleType(ScaleType.CENTER);
            addView(mNotificationWallpaperImage, -1, -1);
            bitmapWallpaper = BitmapFactory.decodeFile(NOTIF_WALLPAPER_IMAGE_PATH);
            Drawable d = new BitmapDrawable(getResources(), bitmapWallpaper);
            d.setAlpha((int) ((1-wallpaperAlpha) * 255));
            mNotificationWallpaperImage.setImageDrawable(d);
        } else {
            removeAllViews();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (bitmapWallpaper != null)
            bitmapWallpaper.recycle();

        System.gc();
        super.onDetachedFromWindow();
    }

class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
		ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
					Settings.System.NOTIF_WALLPAPER_ALPHA), false, this);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
		
    		wallpaperAlpha = Settings.System.getFloat(getContext()
            .getContentResolver(), Settings.System.NOTIF_WALLPAPER_ALPHA, 0.0f);
           setNotificationWallpaper();
        }
    }
}
