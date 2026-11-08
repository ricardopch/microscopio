package es.ricardopch.microscopio;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Lee del sistema (Camera2) todas las cámaras del móvil: las públicas, las físicas que forman
 * una cámara lógica y las ocultas que responden a un identificador numérico.
 */
final class CameraSpecs {
    private CameraSpecs() {
    }

    static JSONObject collect(Context ctx) {
        JSONObject out = new JSONObject();
        JSONArray list = new JSONArray();
        Set<String> seen = new LinkedHashSet<>();
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            String[] ids = cm.getCameraIdList();
            for (String id : ids) {
                try {
                    add(list, seen, id, cm.getCameraCharacteristics(id), "publica", null);
                } catch (Exception ignored) {
                }
            }
            if (Build.VERSION.SDK_INT >= 28) {
                for (String id : ids) {
                    try {
                        CameraCharacteristics c = cm.getCameraCharacteristics(id);
                        for (String pid : c.getPhysicalCameraIds()) {
                            if (seen.contains(pid)) continue;
                            try {
                                add(list, seen, pid, cm.getCameraCharacteristics(pid), "fisica", id);
                            } catch (Exception ignored) {
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
            for (int i = 0; i < 16; i++) {
                String id = String.valueOf(i);
                if (seen.contains(id)) continue;
                try {
                    add(list, seen, id, cm.getCameraCharacteristics(id), "oculta", null);
                } catch (Exception ignored) {
                }
            }
            out.put("camaras", list);
        } catch (Exception e) {
            try {
                out.put("error", String.valueOf(e.getMessage()));
                out.put("camaras", list);
            } catch (JSONException ignored) {
            }
        }
        return out;
    }

    private static void add(JSONArray list, Set<String> seen, String id, CameraCharacteristics c,
                            String access, String parent) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("acceso", access);
        if (parent != null) o.put("parteDe", parent);

        Integer facing = c.get(CameraCharacteristics.LENS_FACING);
        String orient = "desconocida";
        if (facing != null) {
            if (facing == CameraCharacteristics.LENS_FACING_BACK) orient = "trasera";
            else if (facing == CameraCharacteristics.LENS_FACING_FRONT) orient = "frontal";
            else orient = "externa";
        }
        o.put("orientacion", orient);

        float[] focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        float[] apertures = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
        SizeF phys = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        Size pixels = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        Float minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        Integer calib = c.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION);
        int[] af = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        int[] ois = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
        Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
        Float maxDigital = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
        Integer level = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        int[] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        Range<Integer> iso = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        Range<Long> exposure = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);

        if (focals != null && focals.length > 0) o.put("focalMm", toArr(focals));
        if (apertures != null && apertures.length > 0) o.put("aperturas", toArr(apertures));
        if (phys != null) {
            o.put("sensorMm", new JSONArray().put(r2(phys.getWidth())).put(r2(phys.getHeight())));
        }
        if (pixels != null) {
            o.put("pixeles", new JSONArray().put(pixels.getWidth()).put(pixels.getHeight()));
            o.put("megapixeles", r2(pixels.getWidth() * (double) pixels.getHeight() / 1e6));
        }
        if (phys != null && focals != null && focals.length > 0 && focals[0] > 0) {
            double diag = Math.hypot(phys.getWidth(), phys.getHeight());
            double f = focals[0];
            if (diag > 0) o.put("focalEquiv35", Math.round(f * 43.27 / diag));
            double longSide = Math.max(phys.getWidth(), phys.getHeight());
            o.put("campoGrados", Math.round(Math.toDegrees(2 * Math.atan(longSide / (2 * f)))));
        }
        if (minFocus != null) {
            o.put("enfoqueMinDioptrias", r2(minFocus));
            if (minFocus > 0) o.put("enfoqueMinCm", r2(100.0 / minFocus));
        }
        o.put("enfoqueCalibrado", calib != null && calib != CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED);

        JSONArray afNames = new JSONArray();
        if (af != null) {
            for (int m : af) {
                switch (m) {
                    case CameraCharacteristics.CONTROL_AF_MODE_AUTO: afNames.put("auto"); break;
                    case CameraCharacteristics.CONTROL_AF_MODE_MACRO: afNames.put("macro"); break;
                    case CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_VIDEO: afNames.put("continuo vídeo"); break;
                    case CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE: afNames.put("continuo foto"); break;
                    case CameraCharacteristics.CONTROL_AF_MODE_EDOF: afNames.put("profundidad extendida"); break;
                    default: break;
                }
            }
        }
        o.put("af", afNames);

        boolean hasOis = false;
        if (ois != null) for (int m : ois) if (m == CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON) hasOis = true;
        o.put("ois", hasOis);
        o.put("flash", flash != null && flash);
        if (maxDigital != null) o.put("zoomDigitalMax", r2(maxDigital));
        if (Build.VERSION.SDK_INT >= 30) {
            Range<Float> zr = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            if (zr != null) o.put("zoomRango", new JSONArray().put(r2(zr.getLower())).put(r2(zr.getUpper())));
        }

        String levelName = "desconocido";
        if (level != null) {
            switch (level) {
                case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY: levelName = "básico (legacy)"; break;
                case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED: levelName = "limitado"; break;
                case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL: levelName = "completo"; break;
                case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3: levelName = "nivel 3"; break;
                default: levelName = "externo"; break;
            }
        }
        o.put("nivel", levelName);

        JSONArray capNames = new JSONArray();
        boolean logical = false;
        if (caps != null) {
            for (int cap : caps) {
                switch (cap) {
                    case 1: capNames.put("control manual"); break;
                    case 3: capNames.put("RAW"); break;
                    case 6: capNames.put("ráfaga"); break;
                    case 8: capNames.put("profundidad"); break;
                    case 9: capNames.put("cámara lenta"); break;
                    case 11: capNames.put("multicámara lógica"); logical = true; break;
                    case 12: capNames.put("monocromo"); break;
                    case 16: capNames.put("ultra alta resolución"); break;
                    default: break;
                }
            }
        }
        o.put("capacidades", capNames);
        if (logical && Build.VERSION.SDK_INT >= 28) {
            JSONArray p = new JSONArray();
            for (String pid : c.getPhysicalCameraIds()) p.put(pid);
            o.put("fisicas", p);
        }

        if (iso != null) o.put("iso", new JSONArray().put(iso.getLower()).put(iso.getUpper()));
        if (exposure != null) {
            o.put("exposicionSeg", new JSONArray()
                    .put(exposure.getLower() / 1e9)
                    .put(exposure.getUpper() / 1e9));
        }

        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map != null) {
            Size[] jpeg = map.getOutputSizes(ImageFormat.JPEG);
            if (jpeg != null && jpeg.length > 0) {
                Size best = jpeg[0];
                for (Size sz : jpeg) {
                    if ((long) sz.getWidth() * sz.getHeight() > (long) best.getWidth() * best.getHeight()) best = sz;
                }
                o.put("fotoMax", new JSONArray().put(best.getWidth()).put(best.getHeight()));
            }
        }

        seen.add(id);
        list.put(o);
    }

    private static JSONArray toArr(float[] v) throws JSONException {
        JSONArray a = new JSONArray();
        for (float f : v) a.put(r2(f));
        return a;
    }

    private static double r2(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0;
        return Math.round(v * 100.0) / 100.0;
    }
}
