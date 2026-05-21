package com.animwallpaper;
import android.Manifest;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
public class MainActivity extends AppCompatActivity {
    private TextView tvStatus, tvVideoName;
    private Button btnPickVideo, btnSetWallpaper;
    private Uri selectedVideoUri = null;
    private ActivityResultLauncher<Intent> videoPickerLauncher;
    private ActivityResultLauncher<String[]> permissionLauncher;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        tvStatus = findViewById(R.id.tv_status);
        tvVideoName = findViewById(R.id.tv_video_name);
        btnPickVideo = findViewById(R.id.btn_pick_video);
        btnSetWallpaper = findViewById(R.id.btn_set_wallpaper);
        SharedPreferences prefs = getSharedPreferences(VideoWallpaperService.PREFS_NAME, Context.MODE_PRIVATE);
        String savedUri = prefs.getString(VideoWallpaperService.KEY_VIDEO_URI, null);
        if (savedUri != null) {
            selectedVideoUri = Uri.parse(savedUri);
            updateVideoName(selectedVideoUri);
            btnSetWallpaper.setEnabled(true);
            tvStatus.setText("Vidéo déjà sélectionnée. Prêt !");
        }
        videoPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        selectedVideoUri = uri;
                        prefs.edit().putString(VideoWallpaperService.KEY_VIDEO_URI, uri.toString()).apply();
                        updateVideoName(uri);
                        btnSetWallpaper.setEnabled(true);
                        tvStatus.setText("Vidéo sélectionnée ! Appuie sur Définir comme fond d'écran");
                    }
                }
            });
        permissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(),
            permissions -> {
                boolean granted = !permissions.containsValue(false);
                if (granted) openVideoPicker();
                else Toast.makeText(this, "Permission refusée", Toast.LENGTH_LONG).show();
            });
        btnPickVideo.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED)
                    openVideoPicker();
                else permissionLauncher.launch(new String[]{Manifest.permission.READ_MEDIA_VIDEO});
            } else {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
                    openVideoPicker();
                else permissionLauncher.launch(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE});
            }
        });
        btnSetWallpaper.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
                intent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, new ComponentName(this, VideoWallpaperService.class));
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(this, "Erreur: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }
    private void openVideoPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        videoPickerLauncher.launch(intent);
    }
    private void updateVideoName(Uri uri) {
        String name = null;
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = cursor.getString(idx);
            }
        } catch (Exception ignored) {}
        tvVideoName.setText("📹 " + (name != null ? name : uri.getLastPathSegment()));
        tvVideoName.setVisibility(View.VISIBLE);
    }
}
