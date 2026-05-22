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
        private Bitmap freezeFrame = null;
        private Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private Paint bgPaint = new Paint();
        private boolean isPlaying = false;
        private boolean videoFinished = false;
        private boolean isFrozen = false;
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
                        resetForNextUnlock();
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
                // Arrivée sur l'écran d'accueil
                if (!isPlaying && !isFrozen) {
                    startVideoFromBeginning();
                } else if (isFrozen) {
                    drawFreezeFrame();
                }
            } else {
                // Quitte l'écran d'accueil vers verrouillage
                if (isFrozen) {
                    // Continue la vidéo de FREEZE_TIME_MS jusqu'à la fin (écran noir)
                    continueVideoToEnd();
                } else if (isPlaying) {
                    // Vidéo encore en cours, on pause et reset
                    resetForNextUnlock();
                }
            }
        }
        private void continueVideoToEnd() {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String uriString = prefs.getString(KEY_VIDEO_URI, null);
            if (uriString == null) return;
            releaseMediaPlayer();
            isFrozen = false;
            videoFinished = false;
            isPlaying = false;
            mediaPlayer = new MediaPlayer();
            try {
                mediaPlayer.setDataSource(VideoWallpaperService.this, Uri.parse(uriString));
                // Pas de surface - joue en arrière-plan silencieusement
                mediaPlayer.setVolume(0f, 0f);
                mediaPlayer.setLooping(false);
                mediaPlayer.setOnPreparedListener(mp -> {
                    mp.seekTo(FREEZE_TIME_MS);
                    mp.start();
                    isPlaying = true;
                });
                mediaPlayer.setOnCompletionListener(mp -> {
                    isPlaying = false;
                    videoFinished = true;
                    resetForNextUnlock();
                });
                mediaPlayer.setOnErrorListener((mp, w, e) -> {
                    resetForNextUnlock(); return true;
                });
                mediaPlayer.prepareAsync();
            } catch (IOException e) { resetForNextUnlock(); }
        }
        private void resetForNextUnlock() {
            cancelFreezeRunnable();
            releaseMediaPlayer();
            videoFinished = false;
            isPlaying = false;
            isFrozen = false;
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
                mainHandler.post(() -> drawBlackScreen());
            });
        }
        private void startVideoFromBeginning() {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String uriString = prefs.getString(KEY_VIDEO_URI, null);
            if (uriString == null) return;
            releaseMediaPlayer();
            videoFinished = false;
            isFrozen = false;
            mediaPlayer = new MediaPlayer();
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
                    isFrozen = true;
                    mainHandler.post(() -> drawFreezeFrame());
                });
                mediaPlayer.setOnErrorListener((mp, w, e) -> {
                    isPlaying = false;
                    drawBlackScreen();
                    return true;
                });
                mediaPlayer.prepareAsync();
            } catch (IOException e) { drawBlackScreen(); }
        }
        private void scheduleFreezeAt(int ms) {
            cancelFreezeRunnable();
            freezeRunnable = () -> {
                if (mediaPlayer != null && isPlaying) {
                    try { mediaPlayer.pause(); } catch (Exception ignored) {}
                    isPlaying = false;
                    isFrozen = true;
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
        private void releaseMediaPlayer() {
            cancelFreezeRunnable();
            if (mediaPlayer != null) {
                try { mediaPlayer.stop(); } catch (Exception ignored) {}
                try { mediaPlayer.release(); } catch (Exception ignored) {}
                mediaPlayer = null;
            }
            isPlaying = false;
        }
        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            super.onSurfaceDestroyed(holder);
            releaseMediaPlayer();
        }
        @Override
        public void onDestroy() {
            super.onDestroy();
            releaseMediaPlayer();
            if (screenReceiver != null) try { unregisterReceiver(screenReceiver); } catch (Exception ignored) {}
            if (handlerThread != null) handlerThread.quitSafely();
            if (freezeFrame != null && !freezeFrame.isRecycled()) { freezeFrame.recycle(); freezeFrame = null; }
        }
    }
}
