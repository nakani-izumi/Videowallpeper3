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
    @Override
    public Engine onCreateEngine() { return new VideoEngine(); }
    class VideoEngine extends Engine {
        private MediaPlayer mediaPlayer;
        private Bitmap lastFrame = null;
        private Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private Paint bgPaint = new Paint();
        private boolean isPlaying = false;
        private boolean videoFinished = false;
        private boolean isPrepared = false;
        private HandlerThread handlerThread;
        private Handler bgHandler;
        private Handler mainHandler = new Handler(Looper.getMainLooper());
        private BroadcastReceiver screenReceiver;
        private Uri cachedVideoUri = null;
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
                    String action = intent.getAction();
                    if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                        pauseVideo();
                        prepareVideoInAdvance();
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
            loadLastFrame();
        }
        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            if (mediaPlayer != null && isPrepared && !isPlaying && !videoFinished) {
                mediaPlayer.setSurface(holder.getSurface());
            }
        }
        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            if (visible) {
                if (isPrepared && !isPlaying && !videoFinished) {
                    mediaPlayer.setSurface(getSurfaceHolder().getSurface());
                    mediaPlayer.start();
                    isPlaying = true;
                } else if (!isPrepared && !isPlaying && !videoFinished) {
                    startVideoFromUri();
                } else if (videoFinished && lastFrame != null) {
                    drawLastFrame();
                }
            } else {
                if (!videoFinished) {
                    pauseVideo();
                    prepareVideoInAdvance();
                }
            }
        }
        private void loadLastFrame() {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String uriString = prefs.getString(KEY_VIDEO_URI, null);
            if (uriString == null) { drawBlackScreen(); return; }
            cachedVideoUri = Uri.parse(uriString);
            bgHandler.post(() -> {
                try {
                    MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                    retriever.setDataSource(VideoWallpaperService.this, cachedVideoUri);
                    String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    long duration = Long.parseLong(durationStr);
                    long frameTime = Math.max(0, (duration - 50)) * 1000;
                    Bitmap frame = retriever.getFrameAtTime(frameTime, MediaMetadataRetriever.OPTION_CLOSEST);
                    retriever.release();
                    if (frame != null) lastFrame = frame;
                } catch (Exception ignored) {}
                mainHandler.post(() -> {
                    drawLastFrame();
                    prepareVideoInAdvance();
                });
            });
        }
        private void prepareVideoInAdvance() {
            if (cachedVideoUri == null) {
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                String uriString = prefs.getString(KEY_VIDEO_URI, null);
                if (uriString == null) return;
                cachedVideoUri = Uri.parse(uriString);
            }
            releaseMediaPlayer();
            videoFinished = false;
            isPlaying = false;
            isPrepared = false;
            mediaPlayer = new MediaPlayer();
            try {
                mediaPlayer.setDataSource(VideoWallpaperService.this, cachedVideoUri);
                mediaPlayer.setVolume(0f, 0f);
                mediaPlayer.setLooping(false);
                mediaPlayer.setOnPreparedListener(mp -> {
                    isPrepared = true;
                });
                mediaPlayer.setOnCompletionListener(mp -> {
                    isPlaying = false;
                    videoFinished = true;
                    isPrepared = false;
                    mp.stop();
                    mainHandler.post(() -> drawLastFrame());
                });
                mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                    isPlaying = false; videoFinished = true; isPrepared = false;
                    drawBlackScreen(); return true;
                });
                mediaPlayer.prepareAsync();
            } catch (IOException e) { drawBlackScreen(); }
        }
        private void startVideoFromUri() {
            if (cachedVideoUri == null) {
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                String uriString = prefs.getString(KEY_VIDEO_URI, null);
                if (uriString == null) return;
                cachedVideoUri = Uri.parse(uriString);
            }
            releaseMediaPlayer();
            videoFinished = false;
            isPlaying = false;
            isPrepared = false;
            mediaPlayer = new MediaPlayer();
            try {
                mediaPlayer.setDataSource(VideoWallpaperService.this, cachedVideoUri);
                mediaPlayer.setSurface(getSurfaceHolder().getSurface());
                mediaPlayer.setVolume(0f, 0f);
                mediaPlayer.setLooping(false);
                mediaPlayer.setOnPreparedListener(mp -> {
                    isPrepared = true;
                    isPlaying = true;
                    mp.start();
                });
                mediaPlayer.setOnCompletionListener(mp -> {
                    isPlaying = false;
                    videoFinished = true;
                    isPrepared = false;
                    mp.stop();
                    mainHandler.post(() -> drawLastFrame());
                });
                mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                    isPlaying = false; videoFinished = true; isPrepared = false;
                    drawBlackScreen(); return true;
                });
                mediaPlayer.prepareAsync();
            } catch (IOException e) { drawBlackScreen(); }
        }
        private void drawLastFrame() {
            if (lastFrame == null) { drawBlackScreen(); return; }
            SurfaceHolder holder = getSurfaceHolder();
            Canvas canvas = null;
            try {
                canvas = holder.lockCanvas();
                if (canvas != null) {
                    int cW = canvas.getWidth(), cH = canvas.getHeight();
                    canvas.drawRect(0, 0, cW, cH, bgPaint);
                    float scaleX = (float)cW / lastFrame.getWidth();
                    float scaleY = (float)cH / lastFrame.getHeight();
                    float scale = Math.max(scaleX, scaleY);
                    float sW = lastFrame.getWidth() * scale;
                    float sH = lastFrame.getHeight() * scale;
                    Matrix matrix = new Matrix();
                    matrix.setScale(scale, scale);
                    matrix.postTranslate((cW - sW) / 2f, (cH - sH) / 2f);
                    canvas.drawBitmap(lastFrame, matrix, paint);
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
        private void pauseVideo() {
            if (mediaPlayer != null && isPlaying) try { mediaPlayer.pause(); } catch (Exception ignored) {}
            isPlaying = false;
        }
        private void releaseMediaPlayer() {
            if (mediaPlayer != null) {
                try { mediaPlayer.stop(); } catch (Exception ignored) {}
                try { mediaPlayer.release(); } catch (Exception ignored) {}
                mediaPlayer = null;
            }
            isPlaying = false;
            isPrepared = false;
        }
        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) { super.onSurfaceDestroyed(holder); releaseMediaPlayer(); }
        @Override
        public void onDestroy() {
            super.onDestroy();
            releaseMediaPlayer();
            if (screenReceiver != null) try { unregisterReceiver(screenReceiver); } catch (Exception ignored) {}
            if (handlerThread != null) handlerThread.quitSafely();
            if (lastFrame != null && !lastFrame.isRecycled()) { lastFrame.recycle(); lastFrame = null; }
        }
    }
}
