package es.ricardopch.microscopio;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Range;
import android.util.Size;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.Camera2Interop;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExposureState;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.core.ResolutionInfo;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.core.ViewPort;
import androidx.camera.core.ZoomState;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Microscopio Móvil para Android.
 *
 * La interfaz es la app web (index.html), empaquetada dentro del APK y servida desde un origen
 * https local. El diagnóstico de cámaras usa el WebView; el visor del microscopio usa la cámara
 * del teléfono directamente (CameraX), detrás del WebView transparente. Así la app tiene el zoom
 * real de la cámara (con cambio de lente cuando el teléfono lo permite), el autoenfoque del sistema
 * y fotos a resolución completa. Por encima del zoom real, el aumento es digital (escalando la vista).
 */
@OptIn(markerClass = ExperimentalCamera2Interop.class)
public class MainActivity extends ComponentActivity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/assets/index.html";
    private static final int REQ_CAMERA = 7;
    private static final int REQ_SCOPE = 8;
    private static final int INK = 0xFF0A0E10;

    private WebView web;
    private PreviewView previewView;
    private PermissionRequest pendingRequest;
    private String pendingScopeId;
    private final Map<String, Uri> savedImages = new ConcurrentHashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService analysisExec = Executors.newSingleThreadExecutor();
    private final ExecutorService ioExec = Executors.newSingleThreadExecutor();

    // Cámara del visor. Se usa desde el hilo principal, salvo los campos volatile.
    private ProcessCameraProvider provider;
    private Camera camera;
    private ImageCapture imageCapture;
    private boolean scopeOpen;
    private String scopeId = "";
    private float lastZoom = 1f;
    private boolean torchWanted;
    private int bindAttempts;
    private volatile float digital = 1f;
    private volatile int afState;
    private volatile float focusDiopters = -1f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(INK);

        previewView = new PreviewView(this);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE); // admite escalado (zoom digital)
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        previewView.setBackgroundColor(Color.BLACK);
        previewView.setVisibility(View.GONE);
        root.addView(previewView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        web = new WebView(this);
        web.setBackgroundColor(Color.TRANSPARENT);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets i = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(i.left, i.top, i.right, i.bottom);
                return WindowInsets.CONSUMED;
            });
        }

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);

        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new LocalClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> handlePermission(request));
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (web == null) {
                    finish();
                    return;
                }
                web.evaluateJavascript("(window.__back && window.__back()) ? 1 : 0", value -> {
                    if (!"1".equals(value)) finish();
                });
            }
        });

        web.loadUrl(START_URL);
    }

    /* ---------- Permisos ---------- */

    private void handlePermission(PermissionRequest request) {
        boolean wantsVideo = Arrays.asList(request.getResources())
                .contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE);
        if (!wantsVideo) {
            request.deny();
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
        } else {
            if (pendingRequest != null) pendingRequest.deny();
            pendingRequest = request;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_CAMERA && pendingRequest != null) {
            if (granted) pendingRequest.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
            else pendingRequest.deny();
            pendingRequest = null;
        } else if (requestCode == REQ_SCOPE) {
            String id = pendingScopeId;
            pendingScopeId = null;
            if (granted) startCamera(id);
            else jsError("No hay permiso para usar la cámara. Actívalo en Ajustes › Aplicaciones › Microscopio › Permisos.");
        }
    }

    /* ---------- Ciclo de vida ---------- */

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
        // Al volver a la app, la cámara se reabre sola; se recuperan el zoom y la linterna.
        if (scopeOpen) main.postDelayed(this::reapplyCameraState, 600);
    }

    @Override
    protected void onDestroy() {
        if (provider != null) provider.unbindAll();
        analysisExec.shutdown();
        ioExec.shutdown();
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    /* ---------- Cámara del visor (CameraX) ---------- */

    private void startCamera(String id) {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingScopeId = id == null ? "" : id;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_SCOPE);
            return;
        }
        scopeOpen = true;
        scopeId = id == null ? "" : id;
        digital = 1f;
        lastZoom = 1f;
        torchWanted = false;
        previewView.setScaleX(1f);
        previewView.setScaleY(1f);
        previewView.setVisibility(View.VISIBLE);
        final ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                provider = future.get();
            } catch (Exception e) {
                jsError("No se ha podido iniciar la cámara (" + e.getMessage() + ").");
                return;
            }
            bindAttempts = 0;
            previewView.post(() -> bindCamera(scopeId));
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCamera(String id) {
        if (!scopeOpen || provider == null) return;
        if ((previewView.getWidth() == 0 || previewView.getHeight() == 0) && bindAttempts++ < 20) {
            main.postDelayed(() -> bindCamera(id), 50);
            return;
        }
        provider.unbindAll();
        camera = null;
        imageCapture = null;

        ResolutionSelector rs43 = new ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build();

        Preview.Builder pb = new Preview.Builder().setResolutionSelector(rs43);
        new Camera2Interop.Extender<>(pb).setSessionCaptureCallback(new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request,
                                           @NonNull TotalCaptureResult result) {
                Integer af = result.get(CaptureResult.CONTROL_AF_STATE);
                Float fd = result.get(CaptureResult.LENS_FOCUS_DISTANCE);
                afState = af == null ? 0 : af;
                focusDiopters = fd == null ? -1f : fd;
            }
        });
        Preview preview = pb.build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageCapture capture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setResolutionSelector(rs43)
                .build();

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(new ResolutionStrategy(new Size(1280, 960),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                        .build())
                .build();
        analysis.setAnalyzer(analysisExec, new Analyzer());

        ViewPort viewPort = previewView.getViewPort();
        CameraSelector selector = selectorFor(id);
        try {
            camera = provider.bindToLifecycle(this, selector, group(viewPort, preview, capture, analysis));
        } catch (Exception first) {
            if (!id.isEmpty()) {
                // Esa cámara no se puede abrir: se usa la trasera por defecto.
                scopeId = "";
                bindCamera("");
                return;
            }
            try {
                // Algunos teléfonos no admiten los tres usos a la vez: sin análisis de imagen.
                provider.unbindAll();
                camera = provider.bindToLifecycle(this, selector, group(viewPort, preview, capture, null));
            } catch (Exception second) {
                jsError("No se ha podido abrir la cámara (" + second.getMessage() + ").");
                return;
            }
        }
        imageCapture = capture;
        reportOpened(preview);
    }

    private static UseCaseGroup group(ViewPort vp, Preview p, ImageCapture c, ImageAnalysis a) {
        UseCaseGroup.Builder b = new UseCaseGroup.Builder().addUseCase(p).addUseCase(c);
        if (a != null) b.addUseCase(a);
        if (vp != null) b.setViewPort(vp);
        return b.build();
    }

    private CameraSelector selectorFor(String id) {
        if (id == null || id.isEmpty()) return CameraSelector.DEFAULT_BACK_CAMERA;
        return new CameraSelector.Builder().addCameraFilter(infos -> {
            List<CameraInfo> out = new ArrayList<>();
            for (CameraInfo ci : infos) {
                try {
                    if (id.equals(Camera2CameraInfo.from(ci).getCameraId())) out.add(ci);
                } catch (Exception ignored) {
                }
            }
            return out;
        }).build();
    }

    private void reportOpened(Preview preview) {
        if (camera == null) return;
        try {
            CameraInfo info = camera.getCameraInfo();
            ZoomState zs = info.getZoomState().getValue();
            float zMin = zs != null ? zs.getMinZoomRatio() : 1f;
            float zMax = zs != null ? zs.getMaxZoomRatio() : 1f;
            float zoom = zs != null ? zs.getZoomRatio() : 1f;

            JSONObject o = new JSONObject();
            float minFocusCm = 0f;
            boolean fdCal = false;
            try {
                Camera2CameraInfo c2 = Camera2CameraInfo.from(info);
                scopeId = c2.getCameraId();
                Float mf = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
                if (mf != null && mf > 0) minFocusCm = 100f / mf;
                Integer cal = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION);
                fdCal = cal != null && cal != CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED;
            } catch (Exception ignored) {
            }
            o.put("id", scopeId);
            o.put("zMin", num(zMin));
            o.put("zMax", num(zMax));
            o.put("zoom", num(zoom));
            o.put("flash", info.hasFlashUnit());
            o.put("minFocusCm", num(minFocusCm));
            o.put("fdCal", fdCal);

            ResolutionInfo ri = preview.getResolutionInfo();
            if (ri != null) {
                Size sz = ri.getResolution();
                boolean swap = ri.getRotationDegrees() % 180 != 0;
                o.put("pw", swap ? sz.getHeight() : sz.getWidth());
                o.put("ph", swap ? sz.getWidth() : sz.getHeight());
            }

            ExposureState es = info.getExposureState();
            if (es.isExposureCompensationSupported()) {
                Range<Integer> r = es.getExposureCompensationRange();
                float step = es.getExposureCompensationStep().floatValue();
                if (step > 0) {
                    JSONObject e = new JSONObject();
                    e.put("min", num(r.getLower() * step));
                    e.put("max", num(r.getUpper() * step));
                    e.put("step", num(step));
                    o.put("exp", e);
                }
            }
            o.put("cams", backCameras());
            js("onOpened", o.toString());
        } catch (Exception e) {
            jsError("No se han podido leer los datos de la cámara (" + e.getMessage() + ").");
        }
    }

    private JSONArray backCameras() {
        JSONArray a = new JSONArray();
        if (provider == null) return a;
        for (CameraInfo ci : provider.getAvailableCameraInfos()) {
            try {
                Camera2CameraInfo c2 = Camera2CameraInfo.from(ci);
                Integer facing = c2.getCameraCharacteristic(CameraCharacteristics.LENS_FACING);
                if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) continue;
                JSONObject o = new JSONObject();
                o.put("id", c2.getCameraId());
                ZoomState z = ci.getZoomState().getValue();
                if (z != null) o.put("zMax", num(z.getMaxZoomRatio()));
                o.put("flash", ci.hasFlashUnit());
                a.put(o);
            } catch (Exception ignored) {
            }
        }
        return a;
    }

    private void stopCamera() {
        scopeOpen = false;
        if (provider != null) provider.unbindAll();
        camera = null;
        imageCapture = null;
        digital = 1f;
        previewView.setScaleX(1f);
        previewView.setScaleY(1f);
        previewView.setVisibility(View.GONE);
    }

    private void reapplyCameraState() {
        if (!scopeOpen || camera == null) return;
        final float z = lastZoom;
        camera.getCameraControl().setZoomRatio(z);
        if (torchWanted && camera.getCameraInfo().hasFlashUnit()) camera.getCameraControl().enableTorch(true);
        js("onZoom", fmt(z));
    }

    /** Mide nitidez, brillo y movimiento en el centro de la imagen, unas 10 veces por segundo. */
    private final class Analyzer implements ImageAnalysis.Analyzer {
        private float[] prev;
        private int prevN;
        private long last;

        @Override
        public void analyze(@NonNull ImageProxy img) {
            try {
                long now = SystemClock.uptimeMillis();
                if (now - last < 90) return;
                last = now;
                ImageProxy.PlaneProxy plane = img.getPlanes()[0];
                ByteBuffer buf = plane.getBuffer();
                int rs = plane.getRowStride();
                int ps = plane.getPixelStride();
                int w = img.getWidth();
                int h = img.getHeight();
                Rect cr = img.getCropRect();
                if (cr == null || cr.width() <= 0 || cr.height() <= 0) cr = new Rect(0, 0, w, h);

                float sc = Math.max(1f, digital);
                int side = Math.round(Math.min(cr.width(), cr.height()) * 0.5f / sc);
                side = Math.max(16, Math.min(side, Math.min(w, h)));
                int n = Math.max(32, Math.min(160, side));
                float step = side / (float) n;
                int x0 = Math.max(0, Math.min(w - side, cr.centerX() - side / 2));
                int y0 = Math.max(0, Math.min(h - side, cr.centerY() - side / 2));

                float[] g = new float[n * n];
                double sum = 0;
                int lim = buf.limit();
                for (int j = 0; j < n; j++) {
                    int row = (y0 + (int) (j * step)) * rs;
                    for (int i = 0; i < n; i++) {
                        int idx = row + (x0 + (int) (i * step)) * ps;
                        int v = idx < lim ? (buf.get(idx) & 0xFF) : 0;
                        g[j * n + i] = v;
                        sum += v;
                    }
                }
                double ls = 0, ls2 = 0, gs = 0;
                int c = 0;
                for (int y = 1; y < n - 1; y++) {
                    for (int x = 1; x < n - 1; x++) {
                        int i = y * n + x;
                        double l = 4 * g[i] - g[i - 1] - g[i + 1] - g[i - n] - g[i + n];
                        ls += l;
                        ls2 += l * l;
                        gs += Math.abs(g[i + 1] - g[i - 1]) + Math.abs(g[i + n] - g[i - n]);
                        c++;
                    }
                }
                double varL = ls2 / c - (ls / c) * (ls / c);
                double grad = gs / c / 4;
                String motion = "null";
                if (prev != null && prevN == n) {
                    double md = 0;
                    int k = 0;
                    for (int i = 0; i < g.length; i += 3) {
                        md += Math.abs(g[i] - prev[i]);
                        k++;
                    }
                    motion = fmt((md / k) / (grad + 1.5));
                }
                prev = g;
                prevN = n;
                js("onSample", "{\"t\":" + now + ",\"s\":" + fmt(varL) + ",\"b\":" + fmt(sum / g.length)
                        + ",\"m\":" + motion + ",\"af\":" + afState + ",\"fd\":" + fmt(focusDiopters) + "}");
            } catch (Exception ignored) {
            } finally {
                img.close();
            }
        }
    }

    /* ---------- Fotos ---------- */

    private void takePhoto(String name, float scale) {
        if (imageCapture == null || camera == null) {
            js("onCaptured", errJson("la cámara no está lista"));
            return;
        }
        final File dir = new File(getCacheDir(), "capturas");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        final File raw = new File(dir, "raw-" + name);
        ImageCapture.OutputFileOptions opts = new ImageCapture.OutputFileOptions.Builder(raw).build();
        imageCapture.takePicture(opts, ioExec, new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults results) {
                finishPhoto(raw, dir, name, scale);
            }

            @Override
            public void onError(@NonNull ImageCaptureException e) {
                js("onCaptured", errJson(e.getMessage()));
            }
        });
    }

    private void finishPhoto(File raw, File dir, String name, float scale) {
        try {
            File fin = raw;
            if (scale > 1.02f) {
                // Zoom digital: se guarda solo lo que se ve en pantalla.
                fin = new File(dir, "crop-" + name);
                cropCenter(raw, fin, scale);
                //noinspection ResultOfMethodCallIgnored
                raw.delete();
            }
            int orient = new ExifInterface(fin.getPath())
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            Uri uri = insertIntoGallery(fin, name);
            savedImages.put(name, uri);
            File[] old = dir.listFiles();
            if (old != null) {
                for (File f : old) {
                    if (!f.equals(fin) && !f.getName().startsWith("raw-")) //noinspection ResultOfMethodCallIgnored
                        f.delete();
                }
            }
            int[] wh = makePreview(fin, new File(dir, name), orient);
            //noinspection ResultOfMethodCallIgnored
            fin.delete();
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("url", "https://" + HOST + "/captura/" + name);
            o.put("w", wh[0]);
            o.put("h", wh[1]);
            js("onCaptured", o.toString());
        } catch (Throwable e) {
            js("onCaptured", errJson(e.getMessage()));
        }
    }

    @SuppressWarnings("deprecation")
    private static void cropCenter(File src, File dst, float scale) throws IOException {
        BitmapFactory.Options b = new BitmapFactory.Options();
        b.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(src.getPath(), b);
        int w = b.outWidth, h = b.outHeight;
        if (w <= 0 || h <= 0) throw new IOException("foto no válida");
        int cw = Math.max(1, Math.round(w / scale));
        int ch = Math.max(1, Math.round(h / scale));
        int l = (w - cw) / 2, t = (h - ch) / 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 1;
        while ((long) (cw / o.inSampleSize) * (ch / o.inSampleSize) > 16_000_000L) o.inSampleSize *= 2;
        BitmapRegionDecoder dec = BitmapRegionDecoder.newInstance(src.getPath(), false);
        Bitmap bmp;
        try {
            bmp = dec.decodeRegion(new Rect(l, t, l + cw, t + ch), o);
        } finally {
            dec.recycle();
        }
        if (bmp == null) throw new IOException("no se ha podido recortar la foto");
        try (FileOutputStream fos = new FileOutputStream(dst)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 94, fos);
        } finally {
            bmp.recycle();
        }
        String orientation = new ExifInterface(src.getPath()).getAttribute(ExifInterface.TAG_ORIENTATION);
        if (orientation != null) {
            ExifInterface out = new ExifInterface(dst.getPath());
            out.setAttribute(ExifInterface.TAG_ORIENTATION, orientation);
            out.saveAttributes();
        }
    }

    /** Copia reducida y derecha para mostrarla en pantalla. Devuelve el tamaño real de la foto. */
    private static int[] makePreview(File src, File dst, int orient) throws IOException {
        BitmapFactory.Options b = new BitmapFactory.Options();
        b.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(src.getPath(), b);
        int w = b.outWidth, h = b.outHeight;
        int sample = 1;
        while (Math.max(w, h) / sample > 2048) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap bmp = BitmapFactory.decodeFile(src.getPath(), o);
        if (bmp == null) throw new IOException("no se ha podido leer la foto");
        int deg = orient == ExifInterface.ORIENTATION_ROTATE_90 ? 90
                : orient == ExifInterface.ORIENTATION_ROTATE_180 ? 180
                : orient == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
        if (deg != 0) {
            Matrix m = new Matrix();
            m.postRotate(deg);
            Bitmap r = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (r != bmp) bmp.recycle();
            bmp = r;
        }
        try (FileOutputStream fos = new FileOutputStream(dst)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 88, fos);
        } finally {
            bmp.recycle();
        }
        boolean swap = deg % 180 != 0;
        return new int[]{swap ? h : w, swap ? w : h};
    }

    private Uri insertIntoGallery(File file, String name) throws IOException {
        ContentResolver cr = getContentResolver();
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Microscopio");
        cv.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) throw new IOException("la galería no acepta la imagen");
        try (OutputStream os = cr.openOutputStream(uri); InputStream in = new FileInputStream(file)) {
            if (os == null) throw new IOException("sin acceso a la galería");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.Images.Media.IS_PENDING, 0);
        cr.update(uri, done, null, null);
        return uri;
    }

    /** Guarda en la galería una imagen que llega en base64 desde la página (modo básico). */
    private synchronized Uri saveBase64(String base64, String name) throws IOException {
        Uri existing = savedImages.get(name);
        if (existing != null) return existing;
        int comma = base64.indexOf(',');
        if (comma >= 0) base64 = base64.substring(comma + 1);
        byte[] data = Base64.decode(base64, Base64.DEFAULT);
        File tmp = new File(getCacheDir(), "b64-" + name);
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(data);
        }
        try {
            Uri uri = insertIntoGallery(tmp, name);
            savedImages.put(name, uri);
            return uri;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private void share(Uri uri) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("image/jpeg");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        final Intent chooser = Intent.createChooser(send, "Compartir imagen");
        runOnUiThread(() -> startActivity(chooser));
    }

    /* ---------- Comunicación con la página ---------- */

    private void js(String fn, String arg) {
        final String code = "window.__native&&window.__native." + fn + "(" + arg + ")";
        main.post(() -> {
            if (web != null) web.evaluateJavascript(code, null);
        });
    }

    private void jsError(String msg) {
        js("onError", JSONObject.quote(msg));
    }

    private static String errJson(String msg) {
        return "{\"ok\":false,\"error\":" + JSONObject.quote(msg == null ? "error desconocido" : msg) + "}";
    }

    private static String fmt(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "0";
        return String.format(Locale.US, "%.3f", v);
    }

    private static double num(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? 0 : Math.round(v * 1000.0) / 1000.0;
    }

    private static float parseF(String s, float def) {
        try {
            float v = Float.parseFloat(s);
            return Float.isNaN(v) || Float.isInfinite(v) ? def : v;
        } catch (Exception e) {
            return def;
        }
    }

    private static String safeName(String name) {
        String n = name == null ? "" : name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (n.isEmpty()) n = "microscopio-" + System.currentTimeMillis();
        if (!n.toLowerCase(Locale.ROOT).endsWith(".jpg")) n += ".jpg";
        return n;
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    /* ---------- Servir la app empaquetada desde un origen https local ---------- */

    private class LocalClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri u = request.getUrl();
            if (!HOST.equals(u.getHost())) return null;
            String path = u.getPath();
            if (path == null) return notFound();
            if (path.startsWith("/captura/")) {
                String n = path.substring("/captura/".length());
                if (n.isEmpty() || n.contains("/") || n.contains("..")) return notFound();
                try {
                    File f = new File(new File(getCacheDir(), "capturas"), n);
                    return new WebResourceResponse("image/jpeg", null, new FileInputStream(f));
                } catch (IOException e) {
                    return notFound();
                }
            }
            if (!path.startsWith("/assets/")) return notFound();
            String name = path.substring("/assets/".length());
            if (name.isEmpty() || name.contains("..")) return notFound();
            try {
                InputStream in = getAssets().open(name);
                String mime = mimeOf(name);
                boolean text = mime.startsWith("text/") || mime.endsWith("javascript") || mime.endsWith("json");
                WebResourceResponse r = new WebResourceResponse(mime, text ? "utf-8" : null, in);
                Map<String, String> headers = new HashMap<>();
                headers.put("Cache-Control", "no-cache");
                r.setResponseHeaders(headers);
                return r;
            } catch (IOException e) {
                return notFound();
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri u = request.getUrl();
            if (HOST.equals(u.getHost())) return false;
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, u));
            } catch (Exception ignored) {
            }
            return true;
        }
    }

    private static WebResourceResponse notFound() {
        WebResourceResponse r = new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
        r.setStatusCodeAndReasonPhrase(404, "Not Found");
        return r;
    }

    private static String mimeOf(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".html")) return "text/html";
        if (n.endsWith(".js")) return "application/javascript";
        if (n.endsWith(".css")) return "text/css";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".webmanifest") || n.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    /* ---------- Puente con la página (se llama desde un hilo propio del WebView) ---------- */

    private class Bridge {
        @JavascriptInterface
        public String deviceInfo() {
            JSONObject o = new JSONObject();
            try {
                o.put("fabricante", Build.MANUFACTURER);
                o.put("marca", Build.BRAND);
                o.put("modelo", Build.MODEL);
                o.put("dispositivo", Build.DEVICE);
                o.put("android", Build.VERSION.RELEASE);
                o.put("api", Build.VERSION.SDK_INT);
                o.put("version", appVersion());
            } catch (Exception ignored) {
            }
            return o.toString();
        }

        @JavascriptInterface
        public String cameraInfo() {
            return CameraSpecs.collect(MainActivity.this).toString();
        }

        @JavascriptInterface
        public void openScope(String id) {
            main.post(() -> startCamera(id));
        }

        @JavascriptInterface
        public void closeScope() {
            main.post(MainActivity.this::stopCamera);
        }

        @JavascriptInterface
        public void setZoom(String value) {
            final float r = parseF(value, 1f);
            main.post(() -> {
                if (camera == null) {
                    js("onZoom", "null");
                    return;
                }
                ZoomState zs = camera.getCameraInfo().getZoomState().getValue();
                final float v = zs == null ? r : Math.max(zs.getMinZoomRatio(), Math.min(zs.getMaxZoomRatio(), r));
                lastZoom = v;
                camera.getCameraControl().setZoomRatio(v)
                        .addListener(() -> js("onZoom", fmt(v)), ContextCompat.getMainExecutor(MainActivity.this));
            });
        }

        @JavascriptInterface
        public void setDigital(String value) {
            final float s = Math.max(1f, parseF(value, 1f));
            main.post(() -> {
                digital = s;
                previewView.setScaleX(s);
                previewView.setScaleY(s);
            });
        }

        @JavascriptInterface
        public void focusAt(String xs, String ys) {
            final float nx = parseF(xs, 0.5f), ny = parseF(ys, 0.5f);
            main.post(() -> {
                if (camera == null) return;
                float w = previewView.getWidth(), h = previewView.getHeight();
                float s = Math.max(1f, digital);
                // La vista está escalada desde el centro por el zoom digital.
                float x = w * (0.5f + (nx - 0.5f) / s);
                float y = h * (0.5f + (ny - 0.5f) / s);
                MeteringPoint p = previewView.getMeteringPointFactory().createPoint(x, y);
                FocusMeteringAction a = new FocusMeteringAction.Builder(p,
                        FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                        .setAutoCancelDuration(4, TimeUnit.SECONDS)
                        .build();
                camera.getCameraControl().startFocusAndMetering(a);
            });
        }

        @JavascriptInterface
        public void setTorch(String on) {
            main.post(() -> {
                torchWanted = "1".equals(on);
                if (camera != null && camera.getCameraInfo().hasFlashUnit())
                    camera.getCameraControl().enableTorch(torchWanted);
            });
        }

        @JavascriptInterface
        public void setExposure(String value) {
            final float ev = parseF(value, 0f);
            main.post(() -> {
                if (camera == null) return;
                ExposureState es = camera.getCameraInfo().getExposureState();
                if (!es.isExposureCompensationSupported()) return;
                float step = es.getExposureCompensationStep().floatValue();
                if (step <= 0) return;
                Range<Integer> r = es.getExposureCompensationRange();
                int idx = Math.max(r.getLower(), Math.min(r.getUpper(), Math.round(ev / step)));
                camera.getCameraControl().setExposureCompensationIndex(idx);
            });
        }

        @JavascriptInterface
        public void capture(String name, String scale) {
            final String n = safeName(name);
            final float s = Math.max(1f, parseF(scale, 1f));
            main.post(() -> takePhoto(n, s));
        }

        @JavascriptInterface
        public String shareSaved(String name) {
            Uri uri = savedImages.get(safeName(name));
            if (uri == null) return "error: no encontrada";
            share(uri);
            return "ok";
        }

        @JavascriptInterface
        public void haptic(String kind) {
            main.post(() -> {
                if (web == null) return;
                int c = "limit".equals(kind)
                        ? (Build.VERSION.SDK_INT >= 30 ? HapticFeedbackConstants.REJECT : HapticFeedbackConstants.LONG_PRESS)
                        : HapticFeedbackConstants.CLOCK_TICK;
                web.performHapticFeedback(c);
            });
        }

        @JavascriptInterface
        public String saveImage(String base64, String name) {
            try {
                saveBase64(base64, safeName(name));
                return "ok";
            } catch (Exception e) {
                return "error: " + e.getMessage();
            }
        }

        @JavascriptInterface
        public String shareImage(String base64, String name) {
            try {
                share(saveBase64(base64, safeName(name)));
                return "ok";
            } catch (Exception e) {
                return "error: " + e.getMessage();
            }
        }
    }
}
