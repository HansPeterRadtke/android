package com.hans.android.audio;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Map;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class AudioInputCatalog {
    private AudioInputCatalog() {}

    public static List<AudioInputOption> list(Context context) {
        AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        List<AudioInputOption> result = new ArrayList<>();
        if (manager == null) return result;

        boolean classicBluetoothConnected = isBluetoothProfileConnected(context, BluetoothProfile.HEADSET);
        boolean bleBluetoothConnected = Build.VERSION.SDK_INT >= 33
                && isBluetoothProfileConnected(context, BluetoothProfile.LE_AUDIO);
        Set<Integer> seen = new HashSet<>();
        AudioDeviceInfo[] devices;
        try {
            devices = manager.getDevices(AudioManager.GET_DEVICES_INPUTS);
        } catch (SecurityException denied) {
            return result;
        }
        for (AudioDeviceInfo device : devices) {
            if (device == null || !device.isSource() || !seen.add(device.getId())) continue;
            int type = device.getType();
            if (!isSelectablePhysicalType(type)) continue;
            if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && !classicBluetoothConnected) continue;
            if (Build.VERSION.SDK_INT >= 31
                    && type == AudioDeviceInfo.TYPE_BLE_HEADSET
                    && !bleBluetoothConnected) continue;

            AudioInputOption.Category category = categoryFor(type);
            String base = baseLabel(category);
            String product = safeProductName(device);
            String label = product.isEmpty() || product.equalsIgnoreCase(base)
                    ? base
                    : base + " — " + product;
            result.add(new AudioInputOption(device.getId(), type, label, category));
        }
        result.sort(Comparator.comparingInt(AudioInputCatalog::priority)
                .thenComparing(AudioInputOption::getLabel, String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(AudioInputOption::getDeviceId));
        return numberDuplicatePhysicalInputs(collapseBuiltInInputs(result));
    }

    static List<AudioInputOption> collapseBuiltInInputs(List<AudioInputOption> inputs) {
        List<AudioInputOption> result = new ArrayList<>();
        AudioInputOption selectedBuiltIn = null;
        for (AudioInputOption input : inputs) {
            if (input.getCategory() != AudioInputOption.Category.BUILT_IN) {
                result.add(input);
                continue;
            }
            if (selectedBuiltIn == null || builtInPreference(input) < builtInPreference(selectedBuiltIn)) {
                selectedBuiltIn = input;
            }
        }
        if (selectedBuiltIn != null) {
            result.add(new AudioInputOption(selectedBuiltIn.getDeviceId(),
                    selectedBuiltIn.getDeviceType(), "Built-in microphone",
                    AudioInputOption.Category.BUILT_IN));
        }
        result.sort(Comparator.comparingInt(AudioInputCatalog::priority)
                .thenComparing(AudioInputOption::getLabel, String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(AudioInputOption::getDeviceId));
        return result;
    }

    private static int builtInPreference(AudioInputOption input) {
        String label = input.getLabel().toLowerCase(java.util.Locale.US);
        if (label.contains("noise") || label.contains("secondary")
                || label.contains("rear") || label.contains("back")) {
            return 1_000_000 + Math.max(0, input.getDeviceId());
        }
        return Math.max(0, input.getDeviceId());
    }

    public static JSONObject diagnosticSnapshot(Context context) {
        JSONObject result = new JSONObject();
        JSONArray raw = new JSONArray();
        JSONArray selectable = new JSONArray();
        AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        boolean classicConnected = isBluetoothProfileConnected(context, BluetoothProfile.HEADSET);
        boolean bleConnected = Build.VERSION.SDK_INT >= 33
                && isBluetoothProfileConnected(context, BluetoothProfile.LE_AUDIO);
        try {
            result.put("classic_bluetooth_headset_connected", classicConnected);
            result.put("ble_audio_connected", bleConnected);
            result.put("bluetooth_enabled", isBluetoothEnabled(context));
            result.put("bluetooth_connect_permission", Build.VERSION.SDK_INT < 31
                    || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED);
            if (manager != null) {
                if (Build.VERSION.SDK_INT >= 31) {
                    JSONArray communication = new JSONArray();
                    for (AudioDeviceInfo device : manager.getAvailableCommunicationDevices()) {
                        JSONObject item = new JSONObject();
                        item.put("device_id", device.getId());
                        item.put("device_type", device.getType());
                        item.put("is_source", device.isSource());
                        item.put("is_sink", device.isSink());
                        item.put("product_name", safeProductName(device));
                        item.put("address", safeAddress(device));
                        item.put("sample_rates", new JSONArray(device.getSampleRates()));
                        communication.put(item);
                    }
                    result.put("available_communication_devices", communication);
                    AudioDeviceInfo active = manager.getCommunicationDevice();
                    if (active != null) {
                        JSONObject item = new JSONObject();
                        item.put("device_id", active.getId());
                        item.put("device_type", active.getType());
                        item.put("is_source", active.isSource());
                        item.put("is_sink", active.isSink());
                        item.put("product_name", safeProductName(active));
                        item.put("address", safeAddress(active));
                        result.put("active_communication_device", item);
                    }
                }
                for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                    JSONObject item = new JSONObject();
                    int type = device == null ? -1 : device.getType();
                    String product = device == null ? "" : safeProductName(device);
                    boolean physical = device != null && isSelectablePhysicalType(type);
                    boolean accepted = physical && device.isSource();
                    String reason = "accepted";
                    if (device == null) {
                        accepted = false;
                        reason = "null_device";
                    } else if (!device.isSource()) {
                        accepted = false;
                        reason = "not_source";
                    } else if (!physical) {
                        accepted = false;
                        reason = "unsupported_or_virtual_type";
                    } else if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && !classicConnected) {
                        accepted = false;
                        reason = "classic_bluetooth_profile_not_connected";
                    } else if (Build.VERSION.SDK_INT >= 31
                            && type == AudioDeviceInfo.TYPE_BLE_HEADSET && !bleConnected) {
                        accepted = false;
                        reason = "ble_audio_profile_not_connected";
                    }
                    item.put("device_id", device == null ? -1 : device.getId());
                    item.put("device_type", type);
                    item.put("is_source", device != null && device.isSource());
                    item.put("product_name", product);
                    item.put("address", device == null ? "" : safeAddress(device));
                    item.put("sample_rates", device == null
                            ? new JSONArray() : new JSONArray(device.getSampleRates()));
                    item.put("physical_type", physical);
                    item.put("accepted_before_logical_collapse", accepted);
                    item.put("filter_reason", reason);
                    item.put("category", physical ? categoryFor(type).name() : "FILTERED");
                    raw.put(item);
                }
            }
            for (AudioInputOption option : list(context)) {
                JSONObject item = new JSONObject();
                item.put("device_id", option.getDeviceId());
                item.put("device_type", option.getDeviceType());
                item.put("label", option.getLabel());
                item.put("category", option.getCategory().name());
                selectable.put(item);
            }
            result.put("raw_android_inputs", raw);
            result.put("selectable_logical_inputs", selectable);
        } catch (Exception failure) {
            try {
                result.put("diagnostic_exception_class", failure.getClass().getName());
                result.put("diagnostic_exception_message",
                        failure.getMessage() == null ? "" : failure.getMessage());
            } catch (Exception ignored) {}
        }
        return result;
    }

    static List<AudioInputOption> numberDuplicatePhysicalInputs(
            List<AudioInputOption> inputs) {
        Map<AudioInputOption.Category, Integer> totals = new EnumMap<>(AudioInputOption.Category.class);
        for (AudioInputOption input : inputs) {
            totals.put(input.getCategory(), totals.getOrDefault(input.getCategory(), 0) + 1);
        }
        Map<AudioInputOption.Category, Integer> ordinals = new EnumMap<>(AudioInputOption.Category.class);
        List<AudioInputOption> result = new ArrayList<>();
        for (AudioInputOption input : inputs) {
            int total = totals.getOrDefault(input.getCategory(), 0);
            if (total <= 1) {
                result.add(input);
                continue;
            }
            int ordinal = ordinals.getOrDefault(input.getCategory(), 0) + 1;
            ordinals.put(input.getCategory(), ordinal);
            String base = baseLabel(input.getCategory());
            String label = input.getLabel();
            String suffix = label.startsWith(base) ? label.substring(base.length()) : " — " + label;
            result.add(new AudioInputOption(input.getDeviceId(), input.getDeviceType(),
                    base + " " + ordinal + suffix, input.getCategory()));
        }
        return result;
    }

    public static AudioDeviceInfo resolve(Context context, int deviceId) {
        if (deviceId == AudioInputOption.DEFAULT_DEVICE_ID) return null;
        AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (manager == null) return null;
        try {
            for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                if (device != null
                        && device.isSource()
                        && device.getId() == deviceId
                        && isSelectablePhysicalType(device.getType())) {
                    return device;
                }
            }
        } catch (SecurityException ignored) {
            return null;
        }
        return null;
    }

    public static String describe(AudioDeviceInfo device) {
        if (device == null) return "No microphone route";
        AudioInputOption.Category category = categoryFor(device.getType());
        String base = baseLabel(category);
        String product = safeProductName(device);
        return product.isEmpty() || product.equalsIgnoreCase(base) ? base : base + " — " + product;
    }

    static boolean isSelectablePhysicalType(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC:
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_AUX_LINE:
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_ACCESSORY:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
            case AudioDeviceInfo.TYPE_LINE_ANALOG:
            case AudioDeviceInfo.TYPE_LINE_DIGITAL:
                return true;
            default:
                return Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET;
        }
    }

    private static boolean isBluetoothProfileConnected(Context context, int profile) {
        if (Build.VERSION.SDK_INT >= 31
                && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) return false;
        try {
            return adapter.getProfileConnectionState(profile) == BluetoothProfile.STATE_CONNECTED;
        } catch (SecurityException denied) {
            return false;
        } catch (IllegalArgumentException unsupportedProfile) {
            return false;
        }
    }

    private static int priority(AudioInputOption option) {
        switch (option.getCategory()) {
            case BUILT_IN: return 0;
            case WIRED_OR_AUX: return 1;
            case USB: return 2;
            case BLUETOOTH: return 3;
            case LINE_INPUT: return 4;
            default: return 5;
        }
    }

    private static String safeProductName(AudioDeviceInfo device) {
        try {
            CharSequence name = device.getProductName();
            return name == null ? "" : name.toString().trim();
        } catch (SecurityException denied) {
            return "";
        }
    }

    private static String safeAddress(AudioDeviceInfo device) {
        try {
            String value = device.getAddress();
            return value == null ? "" : value;
        } catch (SecurityException denied) {
            return "";
        }
    }

    private static boolean isBluetoothEnabled(Context context) {
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) return false;
        try { return adapter.isEnabled(); }
        catch (SecurityException denied) { return false; }
    }

    private static String baseLabel(AudioInputOption.Category category) {
        switch (category) {
            case BUILT_IN: return "Built-in microphone";
            case WIRED_OR_AUX: return "Wired / AUX microphone";
            case USB: return "USB microphone";
            case BLUETOOTH: return "Bluetooth headset microphone";
            case LINE_INPUT: return "Line input";
            default: return "Microphone";
        }
    }

    private static AudioInputOption.Category categoryFor(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC:
                return AudioInputOption.Category.BUILT_IN;
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_AUX_LINE:
                return AudioInputOption.Category.WIRED_OR_AUX;
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_ACCESSORY:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                return AudioInputOption.Category.USB;
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                return AudioInputOption.Category.BLUETOOTH;
            case AudioDeviceInfo.TYPE_LINE_ANALOG:
            case AudioDeviceInfo.TYPE_LINE_DIGITAL:
                return AudioInputOption.Category.LINE_INPUT;
            default:
                if (Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                    return AudioInputOption.Category.BLUETOOTH;
                }
                return AudioInputOption.Category.OTHER;
        }
    }
}
