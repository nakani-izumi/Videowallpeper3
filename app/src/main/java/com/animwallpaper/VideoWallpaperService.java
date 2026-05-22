package com.animwallpaper;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;
import java.io.IOException;
public class VideoWallpaperService extends WallpaperService {
    public static final String PREFS_NAME = "AnimWallpaperPrefs";
    public static final String KEY_VIDEO_URI = "video_uri";
    private static final int FREEZE_TIME_MS = 1064;
    @Override
    public Engine onCreateEngine() { return new VideoEngine(); }
    class VideoEngine extends Engine {
        private MediaPlayer mediaPlayer;
        private MediaPlayer bgMediaPlayer;
        private Bitmap freezeFrame = null;
        private Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private Paint bgPaint = new Paint();
        private boolean isPlaying = false;
        private boolean videoFinished = false;
        private HandlerThread handlerThread;
        private Handler bgHandler;
        private Handler mainHandler = new Handler(Looper.getMainLooper());
        private BroadcastReceiver screenReceiver;
        private Runnable freezeRunnable;
        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            bgPaint.setColor(Color.BLACK);
            handlerThread = new HandlerThread("WallpaperBg");
            handlerThread.start();
            bgHandler = new Handler(handlerThread.getLooper());
            screenReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                        onScreenOff();
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            registerReceiver(screenReceiver, filter);
        }
        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            loadFreezeFrame();
        }
        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            if (visible) {
                    startVideo();
                } else if (videoFinished) {
                    drawFreezeFrame();
                }
            } else {
                cancelFreezeRunnable();
                if (isPlaying) {
                    pauseMainVideo();
                }
            }
        }
        private void onScreenOff() {
            cancelFreezeRunnable();
            releaseMainPlayer();
            videoFinished = false;
            isPlaying = false;
            playBackgroundToEnd();
        }
        private void playBackgroundToEnd() {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String uriString = prefs.getString(KEY_VIDEO_URI, null);
            if (uriString == null) return;
            releaseBgPlayer();
            bgMediaPlayer = new MediaPlayer();
            try {
                bgMediaPlayer.setDataSource(VideoWallpaperService.this, Uri.parse(uriString));
                bgMediaPlayer.setVolume(0f, 0f);
                bgMediaPlayer.setLooping(false);
                bgMediaPlayer.setOnPreparedListener(mp -> {
                    mp.seekTo(FREEZE_TIME_MS);
                    mp.start();
                });
                bgMediaPlayer.setOnCompletionListener(mp -> releaseBgPlayer());
                bgMediaPlayer.setOnErrorListener((mp, w, e) -> { releaseBgPlayer(); return true; });
                bgMediaPlayer.prepareAsync();
            } catch (IOException e) { releaseBgPlayer(); }
        }
        private void loadFreezeFrame() {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String uriString = prefs.getString(KEY_VIDEO_URI, null);
            if (uriString == null) { drawBlackScreen(); return; }
            Uri videoUri = Uri.parse(uriString);
            bgHandler.post(() -> {
                try {
                    MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                    retriever.setDataSource(VideoWallpaperService.this, videoUri);
                    Bitmap frame = retriever.getFrameAtTime(FREEZE_TIME_MS * 1000L, MediaMetadataRetriever.OPTION_CLOSEST);
                    retriever.release();
                    if (frame != null) freezeFrame = frame;
                } catch (Exception ignored) {}
                mainHandler.post(() -> drawFreezeFrame());
            });
        }
        private void startVideo() {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String uriString = prefs.getString(KEY_VIDEO_URI, null);
            if (uriString == null) return;
            releaseMainPlayer();
            mediaPlayer = new MediaPlayer();
            videoFinished = false;
            try {
                mediaPlayer.setDataSource(VideoWallpaperService.this, Uri.parse(uriString));
                mediaPlayer.setSurface(getSurfaceHolder().getSurface());
                mediaPlayer.setVolume(0f, 0f);
                mediaPlayer.setLooping(false);
                mediaPlayer.setOnPreparedListener(mp -> {
                    isPlaying = true;
                    mp.start();
                    scheduleFreezeAt(FREEZE_TIME_MS);
                });
                mediaPlayer.setOnCompletionListener(mp -> {
                    isPlaying = false;
                    videoFinished = true;
                    mainHandler.post(() -> drawFreezeFrame());
                });
                mediaPlayer.setOnErrorListener((mp, w, e) -> {
                    isPlaying = false; videoFinished = true;
                    drawBlackScreen(); return true;
                });
                mediaPlayer.prepareAsync();
            } catch (IOException e) { drawBlackScreen(); }
        }
        private void scheduleFreezeAt(int ms) {
            cancelFreezeRunnable();
            freezeRunnable = () -> {
                if (isPlaying && mediaPlayer != null) {
                    try { mediaPlayer.pause(); } catch (Exception ignored) {}
                    isPlaying = false;
                    videoFinished = true;
                    drawFreezeFrame();
                }
            };
            mainHandler.postDelayed(freezeRunnable, ms);
        }
        private void cancelFreezeRunnable() {
            if (freezeRunnable != null) {
                mainHandler.removeCallbacks(freezeRunnable);
                freezeRunnable = null;
            }
        }
        private void drawFreezeFrame() {
            if (freezeFrame == null) { drawBlackScreen(); return; }
            SurfaceHolder holder = getSurfaceHolder();
            Canvas canvas = null;
            try {
                canvas = holder.lockCanvas();
                if (canvas != null) {
                    int cW = canvas.getWidth(), cH = canvas.getHeight();
                    canvas.drawRect(0, 0, cW, cH, bgPaint);
                    float scaleX = (float)cW / freezeFrame.getWidth();
                    float scaleY = (float)cH / freezeFrame.getHeight();
                    float scale = Math.max(scaleX, scaleY);
                    float sW = freezeFrame.getWidth() * scale;
                    float sH = freezeFrame.getHeight() * scale;
                    Matrix matrix = new Matrix();
                    matrix.setScale(scale, scale);
                    matrix.postTranslate((cW - sW) / 2f, (cH - sH) / 2f);
                    canvas.drawBitmap(freezeFrame, matrix, paint);
                }
            } finally {
                if (canvas != null) try { holder.unlockCanvasAndPost(canvas); } catch (Exception ignored) {}
            }
        }
        private void drawBlackScreen() {
            SurfaceHolder holder = getSurfaceHolder();
            Canvas canvas = null;
            try {
                canvas = holder.lockCanvas();
                if (canvas != null) canvas.drawRect(0, 0, canvas.getWidth(), canvas.getHeight(), bgPaint);
            } finally {
                if (canvas != null) try { holder.unlockCanvasAndPost(canvas); } catch (Exception ignored) {}
            }
        }
        private void pauseMainVideo() {
            if (mediaPlayer != null && isPlaying) try { mediaPlayer.pause(); } catch (Exception ignored) {}
            isPlaying = false;
        }
        private void releaseMainPlayer() {
            cancelFreezeRunnable();
            if (mediaPlayer != null) {
                try { mediaPlayer.stop(); } catch (Exception ignored) {}
                try { mediaPlayer.release(); } catch (Exception ignored) {}
                mediaPlayer = null;
            }
            isPlaying = false;
        }
        private void releaseBgPlayer() {
            if (bgMediaPlayer != null) {
                try { bgMediaPlayer.stop(); } catch (Exception ignored) {}
                try { bgMediaPlayer.release(); } catch (Exception ignored) {}
                bgMediaPlayer = null;
            }
        }
        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            super.onSurfaceDestroyed(holder);
            releaseMainPlayer();
            releaseBgPlayer();
        }
        @Override
        public void onDestroy() {
            super.onDestroy();
            releaseMainPlayer();
            releaseBgPlayer();
            if (screenReceiver != null) try { unregisterReceiver(screenReceiver); } catch (Exception ignored) {}
            if (handlerThread != null) handlerThread.quitSafely();
        }
    }
}
