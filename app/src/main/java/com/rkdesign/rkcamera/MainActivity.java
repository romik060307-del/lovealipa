package com.rkdesign.rkcamera;

import android.Manifest;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.QualitySelector.FallbackStrategy;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import androidx.media3.common.MediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.effect.MatrixTransformation;
import androidx.media3.effect.Brightness;
import androidx.media3.effect.Contrast;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;

import com.google.common.util.concurrent.ListenableFuture;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfFloat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.imgproc.Imgproc;
import org.opencv.video.Video;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;

import android.media.MediaMetadataRetriever;

/**
 * RK Camera AI. Camera preview intentionally remains unstabilized and lightweight.
 * Video stabilization is analyzed and rendered after recording has stopped.
 */
public class MainActivity extends AppCompatActivity {
    private static final int SAMPLE_MAX_WIDTH = 320;
    private static final int SAMPLE_MAX_HEIGHT = 426;

    private PreviewView previewView;
    private TextView statusText, flashButton, zoomButton, photoModeButton, videoModeButton;
    private FrameLayout shutterButton, shutterInner;
    private View shutterInnerView;
    private ImageView launcherMark;
    private ProcessCameraProvider cameraProvider;
    private ImageCapture imageCapture;
    private VideoCapture<Recorder> videoCapture;
    private Recording recording;
    private Camera camera;
    private boolean videoMode = false;
    private boolean frontCamera = false;
    private boolean flashOn = false;
    private volatile boolean isProcessingVideo = false;
    private String pendingVideoStamp;
    private Uri lastSavedUri;
    private String lastSavedMime = "image/jpeg";
    private Transformer activeTransformer;
    private volatile int analyzedSampleWidth = SAMPLE_MAX_WIDTH;
    private volatile int analyzedSampleHeight = SAMPLE_MAX_HEIGHT;
    private final ExecutorService processingExecutor = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<String[]> permissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean cameraGranted = Boolean.TRUE.equals(result.get(Manifest.permission.CAMERA));
                boolean storageGranted = Build.VERSION.SDK_INT > 28 ||
                        Boolean.TRUE.equals(result.get(Manifest.permission.WRITE_EXTERNAL_STORAGE));
                if (cameraGranted && storageGranted) {
                    startCamera();
                } else {
                    setStatus("Izin kamera diperlukan untuk membuka kamera.");
                    toast("Izinkan akses kamera agar RK Camera AI dapat digunakan.");
                }
            });

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.rgb(8, 9, 11));
        getWindow().setNavigationBarColor(Color.rgb(8, 9, 11));
        buildUi();
        if (hasPermissions()) startCamera(); else requestPermissionsNow();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(5, 6, 8));

        previewView = new PreviewView(this);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        previewView.setBackgroundColor(Color.rgb(5, 6, 8));
        root.addView(previewView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Top bar, floating above a real camera preview.
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setPadding(dp(18), dp(8), dp(18), dp(8));

        launcherMark = new ImageView(this);
        launcherMark.setImageResource(com.rkdesign.rkcamera.R.drawable.ic_camera_mark);
        top.addView(launcherMark, new LinearLayout.LayoutParams(dp(34), dp(34)));

        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        titleBlock.setPadding(dp(10), 0, 0, 0);
        TextView title = label("RK CAMERA", 14, Color.WHITE, true);
        title.setLetterSpacing(0.12f);
        TextView subtitle = label("NATURAL TONE", 9, Color.rgb(199, 207, 214), true);
        subtitle.setLetterSpacing(0.12f);
        titleBlock.addView(title);
        titleBlock.addView(subtitle);
        top.addView(titleBlock, new LinearLayout.LayoutParams(0, -2, 1f));

        zoomButton = roundButton("1×", 13);
        top.addView(zoomButton, new LinearLayout.LayoutParams(dp(48), dp(42)));
        flashButton = roundButton("ϟ", 21);
        LinearLayout.LayoutParams flashParams = new LinearLayout.LayoutParams(dp(48), dp(42));
        flashParams.leftMargin = dp(8);
        top.addView(flashButton, flashParams);

        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(-1, dp(64), Gravity.TOP);
        topParams.topMargin = dp(3);
        root.addView(top, topParams);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);
        bottom.setPadding(dp(20), dp(12), dp(20), dp(12));
        GradientDrawable bottomBg = new GradientDrawable();
        bottomBg.setColor(Color.rgb(8, 9, 12));
        bottomBg.setCornerRadii(new float[]{dp(22),dp(22),dp(22),dp(22),0,0,0,0});
        bottom.setBackground(bottomBg);

        statusText = label("Menyiapkan kamera…", 11, Color.rgb(220, 225, 231), false);
        statusText.setGravity(Gravity.CENTER);
        statusText.setSingleLine(false);
        statusText.setMaxLines(2);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, dp(31));
        statusParams.bottomMargin = dp(7);
        bottom.addView(statusText, statusParams);

        LinearLayout modes = new LinearLayout(this);
        modes.setGravity(Gravity.CENTER);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        photoModeButton = modeButton("FOTO");
        videoModeButton = modeButton("VIDEO");
        LinearLayout.LayoutParams modeP = new LinearLayout.LayoutParams(dp(96), dp(34));
        modes.addView(photoModeButton, modeP);
        LinearLayout.LayoutParams modeP2 = new LinearLayout.LayoutParams(dp(96), dp(34));
        modeP2.leftMargin = dp(8);
        modes.addView(videoModeButton, modeP2);
        bottom.addView(modes);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams controlsParams = new LinearLayout.LayoutParams(-1, dp(88));
        controlsParams.topMargin = dp(7);
        bottom.addView(controls, controlsParams);

        TextView gallery = roundButton("▧", 25);
        LinearLayout.LayoutParams sideParams = new LinearLayout.LayoutParams(dp(54), dp(54));
        sideParams.rightMargin = dp(36);
        controls.addView(gallery, sideParams);

        shutterButton = new FrameLayout(this);
        GradientDrawable outer = new GradientDrawable();
        outer.setShape(GradientDrawable.OVAL);
        outer.setColor(Color.TRANSPARENT);
        outer.setStroke(dp(4), Color.WHITE);
        shutterButton.setBackground(outer);
        shutterButton.setElevation(dp(3));
        shutterInnerView = new View(this);
        shutterInnerView.setBackground(circleDrawable(Color.WHITE));
        FrameLayout.LayoutParams innerParams = new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER);
        shutterButton.addView(shutterInnerView, innerParams);
        controls.addView(shutterButton, new LinearLayout.LayoutParams(dp(76), dp(76)));

        TextView flip = roundButton("⟳", 27);
        LinearLayout.LayoutParams flipParams = new LinearLayout.LayoutParams(dp(54), dp(54));
        flipParams.leftMargin = dp(36);
        controls.addView(flip, flipParams);

        TextView footer = label("PREVIEW ORIGINAL  •  STABILISASI SETELAH REKAM", 8,
                Color.rgb(132, 140, 150), true);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footerP = new LinearLayout.LayoutParams(-1, dp(20));
        footerP.topMargin = dp(2);
        bottom.addView(footer, footerP);

        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(-1, dp(230), Gravity.BOTTOM);
        root.addView(bottom, bottomParams);
        setContentView(root);

        setModeUi();
        photoModeButton.setOnClickListener(v -> switchMode(false));
        videoModeButton.setOnClickListener(v -> switchMode(true));
        shutterButton.setOnClickListener(v -> {
            if (videoMode) {
                if (isProcessingVideo && recording == null) {
                    toast("Video sebelumnya masih diproses. Tunggu sampai selesai sebelum merekam lagi.");
                    return;
                }
                toggleRecording();
            } else {
                takePhoto();
            }
        });
        flashButton.setOnClickListener(v -> {
            flashOn = !flashOn;
            updateFlashUi();
            updateFlashForCurrentMode();
        });
        zoomButton.setOnClickListener(v -> toggleZoom());
        flip.setOnClickListener(v -> {
            if (recording != null) { toast("Hentikan rekaman terlebih dahulu."); return; }
            frontCamera = !frontCamera;
            startCamera();
        });
        gallery.setOnClickListener(v -> openLastMediaOrPicker());

        // Tap-to-focus: this does not add EIS or change the live preview processing.
        previewView.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                focusAt(event.getX(), event.getY());
            }
            return true;
        });
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        return t;
    }

    private TextView roundButton(String text, int size) {
        TextView t = label(text, size, Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.argb(205, 28, 31, 36));
        bg.setStroke(dp(1), Color.argb(70, 255, 255, 255));
        t.setBackground(bg);
        return t;
    }

    private TextView modeButton(String text) {
        TextView t = label(text, 11, Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private GradientDrawable circleDrawable(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    private GradientDrawable pillDrawable(int color, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(18));
        g.setColor(color);
        if (strokeColor != Color.TRANSPARENT) g.setStroke(dp(1), strokeColor);
        return g;
    }

    private void setModeUi() {
        photoModeButton.setBackground(pillDrawable(videoMode ? Color.rgb(26,28,32) : Color.rgb(230,234,238), Color.TRANSPARENT));
        videoModeButton.setBackground(pillDrawable(videoMode ? Color.rgb(230,234,238) : Color.rgb(26,28,32), Color.TRANSPARENT));
        photoModeButton.setTextColor(videoMode ? Color.rgb(195,200,207) : Color.rgb(12,14,17));
        videoModeButton.setTextColor(videoMode ? Color.rgb(12,14,17) : Color.rgb(195,200,207));
        if (shutterInnerView != null) {
            if (videoMode) {
                int size = recording != null ? 24 : 28;
                shutterInnerView.setBackground(recording != null ? roundedDrawable(Color.rgb(242,62,70), dp(5)) : circleDrawable(Color.rgb(242,62,70)));
                FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(size), dp(size), Gravity.CENTER);
                shutterInnerView.setLayoutParams(p);
            } else {
                shutterInnerView.setBackground(circleDrawable(Color.WHITE));
                shutterInnerView.setLayoutParams(new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER));
            }
        }
    }

    private GradientDrawable roundedDrawable(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private void switchMode(boolean toVideo) {
        if (recording != null) { toast("Hentikan rekaman terlebih dahulu."); return; }
        if (videoMode == toVideo) return;
        videoMode = toVideo;
        setModeUi();
        startCamera();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void setStatus(String message) {
        if (statusText != null) statusText.setText(message);
    }

    private boolean hasPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return false;
        return Build.VERSION.SDK_INT > 28 || ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissionsNow() {
        if (Build.VERSION.SDK_INT <= 28) {
            permissionLauncher.launch(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE});
        } else {
            permissionLauncher.launch(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO});
        }
    }

    private void startCamera() {
        if (!hasPermissions()) { requestPermissionsNow(); return; }
        setStatus("Menyiapkan kamera…");
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindUseCases();
            } catch (ExecutionException | InterruptedException e) {
                setStatus("Kamera gagal dibuka. Periksa izin kamera.");
                toast("Kamera gagal dibuka: " + readableError(e));
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindUseCases() {
        if (cameraProvider == null) return;
        CameraSelector selector = new CameraSelector.Builder()
                .requireLensFacing(frontCamera ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK)
                .build();
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        try {
            cameraProvider.unbindAll();
            if (videoMode) {
                List<CameraInfo> lensInfos = selector.filter(cameraProvider.getAvailableCameraInfos());
                if (lensInfos.isEmpty()) throw new IllegalStateException("Kamera yang dipilih tidak tersedia");
                List<Quality> supported = QualitySelector.getSupportedQualities(lensInfos.get(0));
                if (supported == null || supported.isEmpty()) {
                    videoMode = false;
                    setModeUi();
                    bindPhotoMode(selector, preview);
                    setStatus("Foto aktif • perangkat tidak melaporkan profil video CameraX");
                    toast("Mode foto tetap aktif, tetapi kamera ini tidak melaporkan kualitas video yang didukung.");
                    return;
                }
                List<Quality> preferences = new ArrayList<>();
                for (Quality q : Arrays.asList(Quality.HD, Quality.SD, Quality.FHD, Quality.UHD)) {
                    if (supported.contains(q)) preferences.add(q);
                }
                if (preferences.isEmpty()) preferences.addAll(supported);
                QualitySelector qualitySelector = QualitySelector.fromOrderedList(
                        preferences,
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD));
                Recorder recorder = new Recorder.Builder().setQualitySelector(qualitySelector).build();
                videoCapture = VideoCapture.withOutput(recorder);
                imageCapture = null;
                camera = cameraProvider.bindToLifecycle(this, selector, preview, videoCapture);
                setStatus("Video siap • preview tanpa stabilisasi tambahan");
            } else {
                bindPhotoMode(selector, preview);
            }
            updateFlashForCurrentMode();
            updateZoomLabel();
        } catch (Exception e) {
            // Do not leave a black screen when a video stream combination/profile fails.
            try { cameraProvider.unbindAll(); } catch (Exception ignored) { }
            videoCapture = null;
            if (videoMode) {
                videoMode = false;
                setModeUi();
                try {
                    Preview photoPreview = new Preview.Builder().build();
                    photoPreview.setSurfaceProvider(previewView.getSurfaceProvider());
                    bindPhotoMode(selector, photoPreview);
                    setStatus("Foto aktif • konfigurasi video belum didukung kamera ini");
                    toast("Kamera tetap dibuka dalam mode foto. Video gagal diinisialisasi: " + readableError(e));
                    return;
                } catch (Exception photoError) {
                    setStatus("Kamera tidak dapat dibuka. Coba tutup aplikasi lain yang memakai kamera.");
                    toast("Gagal membuka kamera: " + readableError(photoError));
                }
            } else {
                setStatus("Kamera tidak dapat dibuka. Coba tutup aplikasi lain yang memakai kamera.");
                toast("Gagal membuka kamera: " + readableError(e));
            }
        }
    }

    private void bindPhotoMode(CameraSelector selector, Preview preview) {
        imageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setJpegQuality(95)
                .build();
        videoCapture = null;
        camera = cameraProvider.bindToLifecycle(this, selector, preview, imageCapture);
        imageCapture.setFlashMode(flashOn ? ImageCapture.FLASH_MODE_ON : ImageCapture.FLASH_MODE_OFF);
        setStatus("Foto siap • fokus dengan mengetuk layar");
    }

    private String readableError(Exception e) {
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) return e.getClass().getSimpleName();
        if (m.length() > 120) m = m.substring(0, 120);
        return m;
    }

    private void updateFlashUi() {
        if (flashButton != null) {
            flashButton.setText(flashOn ? "ϟ ON" : "ϟ");
            flashButton.setTextColor(flashOn ? Color.rgb(255, 219, 89) : Color.WHITE);
        }
    }

    private void updateFlashForCurrentMode() {
        if (camera == null) return;
        boolean available = camera.getCameraInfo().hasFlashUnit();
        flashButton.setEnabled(available);
        flashButton.setAlpha(available ? 1f : 0.35f);
        if (!available) {
            flashOn = false;
            updateFlashUi();
            return;
        }
        try {
            if (videoMode) camera.getCameraControl().enableTorch(flashOn);
            else if (imageCapture != null) imageCapture.setFlashMode(flashOn ? ImageCapture.FLASH_MODE_ON : ImageCapture.FLASH_MODE_OFF);
        } catch (Exception ignored) { }
        updateFlashUi();
    }

    private void toggleZoom() {
        if (camera == null) return;
        try {
            androidx.camera.core.ZoomState zoomState = camera.getCameraInfo().getZoomState().getValue();
            if (zoomState == null) return;
            float target = zoomState.getZoomRatio() < 1.5f ? Math.min(2f, zoomState.getMaxZoomRatio()) : 1f;
            camera.getCameraControl().setZoomRatio(target);
            zoomButton.setText(target > 1.1f ? "2×" : "1×");
        } catch (Exception ignored) { }
    }

    private void updateZoomLabel() {
        if (camera == null || zoomButton == null) return;
        try {
            androidx.camera.core.ZoomState z = camera.getCameraInfo().getZoomState().getValue();
            zoomButton.setText(z != null && z.getZoomRatio() > 1.5f ? "2×" : "1×");
        } catch (Exception ignored) { }
    }

    private void focusAt(float x, float y) {
        if (camera == null || previewView == null) return;
        try {
            androidx.camera.core.MeteringPointFactory factory = previewView.getMeteringPointFactory();
            MeteringPoint point = factory.createPoint(x, y);
            FocusMeteringAction action = new FocusMeteringAction.Builder(point,
                    FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
                    .build();
            camera.getCameraControl().startFocusAndMetering(action);
        } catch (Exception ignored) { }
    }

    private String stamp() {
        return new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new java.util.Date());
    }

    private void takePhoto() {
        if (imageCapture == null) {
            toast("Mode foto sedang disiapkan. Coba lagi.");
            return;
        }
        String id = stamp();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "RKCamera_RAW_" + id + ".jpg");
        values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/RK Camera AI");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        }
        ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions.Builder(
                getContentResolver(), MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build();
        setStatus("Mengambil foto…");
        imageCapture.takePicture(options, ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
            @Override public void onImageSaved(@NonNull ImageCapture.OutputFileResults result) {
                Uri rawUri = result.getSavedUri();
                if (rawUri == null) {
                    setStatus("Foto dibuat, tetapi lokasi file tidak diketahui.");
                    toast("Tidak dapat menemukan file foto hasil kamera.");
                    return;
                }
                lastSavedUri = rawUri;
                lastSavedMime = "image/jpeg";
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues ready = new ContentValues();
                    ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    getContentResolver().update(rawUri, ready, null, null);
                }
                setStatus("Foto tersimpan • mengolah tone natural…");
                enhancePhotoTone(rawUri, id);
            }
            @Override public void onError(@NonNull ImageCaptureException e) {
                setStatus("Foto gagal diambil");
                toast("Gagal mengambil foto: " + readableError(e));
            }
        });
    }

    /** Small, restrained tonal curve; processing is off the camera thread and keeps the original if it fails. */
    private void enhancePhotoTone(Uri rawUri, String id) {
        processingExecutor.execute(() -> {
            File temp = new File(getCacheDir(), "tone_" + id + ".jpg");
            Bitmap bitmap = null;
            boolean ok = false;
            try (InputStream input = getContentResolver().openInputStream(rawUri)) {
                if (input == null) throw new IllegalStateException("Tidak dapat membuka hasil foto");
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                options.inMutable = true;
                bitmap = BitmapFactory.decodeStream(input, null, options);
                if (bitmap == null) throw new IllegalStateException("Format foto tidak dapat diproses");
                int width = bitmap.getWidth();
                int[] row = new int[width];
                for (int y = 0; y < bitmap.getHeight(); y++) {
                    bitmap.getPixels(row, 0, width, 0, y, width, 1);
                    for (int x = 0; x < width; x++) {
                        int c = row[x];
                        int a = Color.alpha(c);
                        float r = Color.red(c) / 255f;
                        float g = Color.green(c) / 255f;
                        float b = Color.blue(c) / 255f;
                        float luma = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                        // Gentle shadow lift/highlight compression; no aggressive saturation or fake detail.
                        float tuned = luma + 0.11f * luma * (1f - luma) * (1f - 2f * luma);
                        float saturation = 1.025f;
                        int rr = Math.round(clamp01(tuned + (r - luma) * saturation) * 255f);
                        int gg = Math.round(clamp01(tuned + (g - luma) * saturation) * 255f);
                        int bb = Math.round(clamp01(tuned + (b - luma) * saturation) * 255f);
                        row[x] = Color.argb(a, rr, gg, bb);
                    }
                    bitmap.setPixels(row, 0, width, 0, y, width, 1);
                }
                try (FileOutputStream out = new FileOutputStream(temp)) {
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) throw new IllegalStateException("JPEG tidak dapat ditulis");
                }
                Uri finalUri = publishFile(temp, "RKCamera_" + id + ".jpg", "image/jpeg", true);
                if (finalUri == null) throw new IllegalStateException("Hasil tone tidak dapat disimpan");
                getContentResolver().delete(rawUri, null, null);
                lastSavedUri = finalUri;
                lastSavedMime = "image/jpeg";
                ok = true;
            } catch (OutOfMemoryError oom) {
                // Older phones should retain the untouched camera output rather than crash.
            } catch (Exception ignored) {
                // Preserve raw image on any processing/export problem.
            } finally {
                if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                if (temp.exists()) temp.delete();
            }
            final boolean completed = ok;
            runOnUiThread(() -> {
                if (completed) {
                    setStatus("Foto tersimpan • tone natural selesai");
                    toast("Foto tersimpan di galeri.");
                } else {
                    setStatus("Foto asli tersimpan • pemrosesan tone dilewati");
                    toast("Foto asli tetap aman; pemrosesan tone dilewati pada foto ini.");
                }
            });
        });
    }

    private float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }

    private void toggleRecording() {
        if (videoCapture == null) {
            toast("Video tidak tersedia pada konfigurasi kamera ini. Foto masih dapat digunakan.");
            return;
        }
        if (recording != null) {
            recording.stop();
            shutterInnerView.setBackground(roundedDrawable(Color.rgb(242,62,70), dp(5)));
            setStatus("Menyelesaikan video…");
            return;
        }
        pendingVideoStamp = stamp();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "RKCamera_" + pendingVideoStamp + ".mp4");
        values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        if (Build.VERSION.SDK_INT >= 29) values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/RK Camera AI");
        MediaStoreOutputOptions output = new MediaStoreOutputOptions.Builder(
                getContentResolver(), MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(values).build();
        PendingRecording pending = videoCapture.getOutput().prepareRecording(this, output);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending = pending.withAudioEnabled();
        }
        try {
            recording = pending.start(ContextCompat.getMainExecutor(this), event -> {
                if (event instanceof VideoRecordEvent.Start) {
                    setModeUi();
                    setStatus("MEREKAM • preview ringan, tanpa stabilisasi live");
                } else if (event instanceof VideoRecordEvent.Status) {
                    // Keep the preview free of expensive real-time image processing.
                } else if (event instanceof VideoRecordEvent.Finalize) {
                    VideoRecordEvent.Finalize fin = (VideoRecordEvent.Finalize) event;
                    recording = null;
                    setModeUi();
                    if (fin.hasError()) {
                        setStatus("Rekaman gagal diselesaikan");
                        toast("Video gagal disimpan. Kode: " + fin.getError());
                    } else {
                        Uri rawVideo = fin.getOutputResults().getOutputUri();
                        if (rawVideo == null) {
                            setStatus("Video tersimpan, tetapi file tidak ditemukan untuk stabilisasi.");
                            return;
                        }
                        lastSavedUri = rawVideo;
                        lastSavedMime = "video/mp4";
                        setStatus("Video direkam • menganalisis gerakan untuk stabilisasi…");
                        postStabilizeVideo(rawVideo, pendingVideoStamp == null ? stamp() : pendingVideoStamp);
                    }
                }
            });
            setModeUi();
        } catch (Exception e) {
            recording = null;
            setModeUi();
            setStatus("Tidak dapat memulai rekaman");
            toast("Gagal memulai video: " + readableError(e));
        }
    }

    private static final class RawMotion {
        final long timeUs;
        final double x, y, angle;
        RawMotion(long timeUs, double x, double y, double angle) { this.timeUs = timeUs; this.x = x; this.y = y; this.angle = angle; }
    }

    private static final class MotionSample {
        final long timeUs;
        final float x, y, angle;
        MotionSample(long timeUs, float x, float y, float angle) { this.timeUs = timeUs; this.x = x; this.y = y; this.angle = angle; }
    }

    /** Analyze sparse frames using optical flow, then smooth the camera path. This works without camera EIS support. */
    private List<MotionSample> analyzeMotion(Uri source) throws Exception {
        if (!OpenCVLoader.initLocal()) throw new IllegalStateException("Mesin analisis gambar tidak dapat dimuat");
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        Mat previousGray = null;
        MatOfPoint2f previousPoints = null;
        ArrayList<RawMotion> raw = new ArrayList<>();
        int sampleWidth = SAMPLE_MAX_WIDTH;
        int sampleHeight = SAMPLE_MAX_HEIGHT;
        double cumulativeX = 0, cumulativeY = 0, cumulativeAngle = 0;
        int trackedPairs = 0;
        try {
            retriever.setDataSource(this, source);
            String d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durationMs = d == null ? 0 : Long.parseLong(d);
            if (durationMs < 400) throw new IllegalStateException("Video terlalu pendek untuk dianalisis");
            // Keep up to about 620 samples across the entire clip, including longer videos.
            long intervalMs = Math.max(160, (long) Math.ceil(durationMs / 620.0));
            int count = 0;
            for (long tMs = 0; tMs <= durationMs && count < 650; tMs += intervalMs) {
                Bitmap frame = retriever.getFrameAtTime(tMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST);
                if (frame == null) continue;
                int fw = frame.getWidth(), fh = frame.getHeight();
                float factor = Math.min((float) SAMPLE_MAX_WIDTH / fw, (float) SAMPLE_MAX_HEIGHT / fh);
                int sw = Math.max(1, Math.round(fw * factor));
                int sh = Math.max(1, Math.round(fh * factor));
                Bitmap scaled = Bitmap.createScaledBitmap(frame, sw, sh, true);
                if (scaled != frame) frame.recycle();
                sampleWidth = sw;
                sampleHeight = sh;

                Mat rgba = new Mat();
                Mat gray = new Mat();
                Utils.bitmapToMat(scaled, rgba);
                Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
                rgba.release();
                scaled.recycle();

                if (previousGray != null && previousPoints != null && previousPoints.rows() >= 8) {
                    MatOfPoint2f nextPoints = new MatOfPoint2f();
                    MatOfByte pointStatus = new MatOfByte();
                    MatOfFloat errors = new MatOfFloat();
                    Video.calcOpticalFlowPyrLK(previousGray, gray, previousPoints, nextPoints, pointStatus, errors);
                    Point[] before = previousPoints.toArray();
                    Point[] after = nextPoints.toArray();
                    byte[] good = pointStatus.toArray();
                    ArrayList<Double> xs = new ArrayList<>();
                    ArrayList<Double> ys = new ArrayList<>();
                    double bx = 0, by = 0, ax = 0, ay = 0;
                    int n = Math.min(Math.min(before.length, after.length), good.length);
                    int valid = 0;
                    for (int i = 0; i < n; i++) {
                        if (good[i] == 0) continue;
                        Point p = before[i], q = after[i];
                        if (q.x < 0 || q.y < 0 || q.x >= sw || q.y >= sh) continue;
                        xs.add(q.x - p.x);
                        ys.add(q.y - p.y);
                        bx += p.x; by += p.y; ax += q.x; ay += q.y;
                        valid++;
                    }
                    if (valid >= 6) {
                        trackedPairs++;
                        double dx = median(xs), dy = median(ys);
                        bx /= valid; by /= valid; ax /= valid; ay /= valid;
                        double cross = 0, dot = 0;
                        for (int i = 0; i < n; i++) {
                            if (good[i] == 0) continue;
                            Point p = before[i], q = after[i];
                            if (q.x < 0 || q.y < 0 || q.x >= sw || q.y >= sh) continue;
                            double px = p.x - bx, py = p.y - by;
                            double qx = q.x - ax, qy = q.y - ay;
                            cross += px * qy - py * qx;
                            dot += px * qx + py * qy;
                        }
                        double angle = Math.atan2(cross, dot);
                        if (Math.abs(dx) < sw * 0.20 && Math.abs(dy) < sh * 0.20 && Math.abs(angle) < Math.toRadians(12)) {
                            cumulativeX += dx;
                            cumulativeY += dy;
                            cumulativeAngle += angle;
                        }
                    }
                    nextPoints.release(); pointStatus.release(); errors.release();
                }
                raw.add(new RawMotion(tMs * 1000L, cumulativeX, cumulativeY, cumulativeAngle));
                count++;

                MatOfPoint corners = new MatOfPoint();
                Imgproc.goodFeaturesToTrack(gray, corners, 160, 0.012, 5.0);
                MatOfPoint2f freshPoints = new MatOfPoint2f();
                freshPoints.fromArray(corners.toArray());
                corners.release();
                if (previousPoints != null) previousPoints.release();
                previousPoints = freshPoints;
                if (previousGray != null) previousGray.release();
                previousGray = gray;
            }
        } finally {
            if (previousGray != null) previousGray.release();
            if (previousPoints != null) previousPoints.release();
            retriever.release();
        }
        if (raw.size() < 3 || trackedPairs < 2) throw new IllegalStateException("Tidak cukup detail untuk menganalisis gerakan video");
        analyzedSampleWidth = sampleWidth;
        analyzedSampleHeight = sampleHeight;
        ArrayList<MotionSample> smoothed = new ArrayList<>();
        int radius = 4;
        for (int i = 0; i < raw.size(); i++) {
            double sx = 0, sy = 0, sa = 0;
            int from = Math.max(0, i - radius), to = Math.min(raw.size() - 1, i + radius), total = 0;
            for (int j = from; j <= to; j++) { sx += raw.get(j).x; sy += raw.get(j).y; sa += raw.get(j).angle; total++; }
            RawMotion current = raw.get(i);
            double cx = clamp(sx / total - current.x, -sampleWidth * 0.045, sampleWidth * 0.045);
            double cy = clamp(sy / total - current.y, -sampleHeight * 0.045, sampleHeight * 0.045);
            double ca = clamp(sa / total - current.angle, -Math.toRadians(3.0), Math.toRadians(3.0));
            smoothed.add(new MotionSample(current.timeUs, (float) cx, (float) cy, (float) Math.toDegrees(ca)));
        }
        return smoothed;
    }

    private double median(List<Double> values) {
        if (values.isEmpty()) return 0;
        Collections.sort(values);
        int middle = values.size() / 2;
        if (values.size() % 2 == 1) return values.get(middle);
        return (values.get(middle - 1) + values.get(middle)) / 2.0;
    }

    private double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }

    private MotionSample interpolate(List<MotionSample> path, long timeUs) {
        if (timeUs <= path.get(0).timeUs) return path.get(0);
        for (int i = 1; i < path.size(); i++) {
            MotionSample a = path.get(i - 1), b = path.get(i);
            if (timeUs <= b.timeUs) {
                long span = Math.max(1, b.timeUs - a.timeUs);
                float f = Math.max(0f, Math.min(1f, (timeUs - a.timeUs) / (float) span));
                return new MotionSample(timeUs, a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f,
                        a.angle + (b.angle - a.angle) * f);
            }
        }
        return path.get(path.size() - 1);
    }

    private void postStabilizeVideo(Uri rawVideo, String id) {
        if (isProcessingVideo) {
            setStatus("Video asli tersimpan • proses stabilisasi lain masih berjalan");
            return;
        }
        isProcessingVideo = true;
        processingExecutor.execute(() -> {
            List<MotionSample> path;
            try {
                path = analyzeMotion(rawVideo);
            } catch (Exception | OutOfMemoryError e) {
                isProcessingVideo = false;
                runOnUiThread(() -> {
                    setStatus("Video asli tersimpan • stabilisasi otomatis tidak selesai");
                    toast("Video asli aman. Stabilisasi belum bisa dilakukan pada klip ini: " + e.getClass().getSimpleName());
                });
                return;
            }
            runOnUiThread(() -> startVideoExport(rawVideo, id, path));
        });
    }

    private void startVideoExport(Uri rawVideo, String id, List<MotionSample> path) {
        File output = new File(getCacheDir(), "RKCamera_stable_" + id + ".mp4");
        if (output.exists()) output.delete();
        setStatus("Stabilisasi software • merender video hasil…");
        MatrixTransformation stabilizingEffect = presentationTimeUs -> {
            MotionSample motion = interpolate(path, presentationTimeUs);
            Matrix matrix = new Matrix();
            // Reserve edge margin, then counter the smoothed camera-path deviation.
            matrix.postScale(1.10f, 1.10f);
            matrix.postRotate(motion.angle);
            matrix.postTranslate(2f * motion.x / (float) analyzedSampleWidth, 2f * motion.y / (float) analyzedSampleHeight);
            return matrix;
        };
        List<androidx.media3.common.Effect> videoEffects = new ArrayList<>();
        videoEffects.add(stabilizingEffect);
        // Subtle SDR tone tuning, avoiding the over-saturated look of heavy filters.
        videoEffects.add(new Contrast(0.035f));
        videoEffects.add(new Brightness(0.008f));
        Effects effects = new Effects(Collections.emptyList(), videoEffects);
        EditedMediaItem editedItem = new EditedMediaItem.Builder(MediaItem.fromUri(rawVideo))
                .setEffects(effects)
                .build();
        activeTransformer = new Transformer.Builder(getApplicationContext())
                .addListener(new Transformer.Listener() {
                    @Override public void onCompleted(Composition composition, ExportResult exportResult) {
                        processingExecutor.execute(() -> {
                            boolean published = false;
                            Uri stableUri = null;
                            try {
                                stableUri = publishFile(output, "RKCamera_STABLE_" + id + ".mp4", "video/mp4", false);
                                published = stableUri != null;
                                if (published) getContentResolver().delete(rawVideo, null, null);
                            } catch (Exception ignored) { }
                            final boolean done = published;
                            final Uri resultUri = stableUri;
                            if (output.exists()) output.delete();
                            runOnUiThread(() -> {
                                isProcessingVideo = false;
                                activeTransformer = null;
                                if (done) {
                                    lastSavedUri = resultUri;
                                    lastSavedMime = "video/mp4";
                                    setStatus("Video stabil tersimpan di Galeri");
                                    toast("Selesai. Video stabil telah disimpan di Galeri.");
                                } else {
                                    setStatus("Video asli tetap tersimpan • gagal menyalin hasil stabil");
                                    toast("Video asli tidak dihapus. Hasil stabil gagal disimpan.");
                                }
                            });
                        });
                    }
                    @Override public void onError(Composition composition, ExportResult exportResult, ExportException exportException) {
                        isProcessingVideo = false;
                        if (output.exists()) output.delete();
                        runOnUiThread(() -> {
                            activeTransformer = null;
                            setStatus("Video asli tersimpan • encoder tidak mendukung pemrosesan ini");
                            toast("Video asli aman. Pemrosesan stabil gagal pada perangkat ini.");
                        });
                    }
                }).build();
        try {
            activeTransformer.start(editedItem, output.getAbsolutePath());
        } catch (Exception e) {
            isProcessingVideo = false;
            if (output.exists()) output.delete();
            setStatus("Video asli tersimpan • ekspor stabil gagal");
            toast("Video asli aman; tidak dapat merender hasil stabil pada perangkat ini.");
        }
    }

    /** Copy an already-processed file into the public media library. The source is left untouched on failure. */
    private Uri publishFile(File source, String displayName, String mimeType, boolean image) throws Exception {
        if (source == null || !source.exists() || source.length() == 0) return null;
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, image ? Environment.DIRECTORY_DCIM + "/RK Camera AI" : Environment.DIRECTORY_MOVIES + "/RK Camera AI");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        }
        Uri collection = image ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        Uri result = getContentResolver().insert(collection, values);
        if (result == null) return null;
        boolean successful = false;
        try (InputStream in = new FileInputStream(source); OutputStream out = getContentResolver().openOutputStream(result, "w")) {
            if (out == null) throw new IllegalStateException("Tidak dapat membuka tujuan penyimpanan");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            out.flush();
            successful = true;
        } finally {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues finish = new ContentValues();
                finish.put(MediaStore.MediaColumns.IS_PENDING, successful ? 0 : 1);
                getContentResolver().update(result, finish, null, null);
            }
            if (!successful) getContentResolver().delete(result, null, null);
        }
        return successful ? result : null;
    }

    private void openLastMediaOrPicker() {
        try {
            if (lastSavedUri != null) {
                Intent view = new Intent(Intent.ACTION_VIEW);
                view.setDataAndType(lastSavedUri, lastSavedMime);
                view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(view);
            } else {
                Intent picker = new Intent(Intent.ACTION_GET_CONTENT);
                picker.setType("image/*");
                startActivity(Intent.createChooser(picker, "Pilih foto"));
            }
        } catch (Exception e) {
            toast("Tidak ada aplikasi galeri yang dapat membuka media ini.");
        }
    }

    @Override protected void onDestroy() {
        boolean wasRecording = recording != null;
        if (recording != null) {
            try { recording.stop(); } catch (Exception ignored) { }
            recording = null;
        }
        // Keep the worker alive for a pending recording-finalize callback or active export.
        if (!isProcessingVideo && !wasRecording) processingExecutor.shutdown();
        super.onDestroy();
    }
}
