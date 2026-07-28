package com.hans.android.audio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.os.Build;
import android.os.SystemClock;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class AudioRouteController {
    private static final long COMMUNICATION_ROUTE_TIMEOUT_MS = 5000L;
    private static final long RECORDER_ROUTE_TIMEOUT_MS = 3000L;

    private final AudioManager manager;
    private boolean legacyScoStarted;
    private AudioDeviceInfo selectedCommunicationDevice;
    private String preparationDetail = "";

    public AudioRouteController(Context context) {
        manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    public AudioDeviceInfo prepare(Context context, AudioInputOption option) {
        if (manager == null) throw new IllegalStateException("Android AudioManager is unavailable");
        boolean bluetooth = option != null && option.isBluetooth();
        manager.setMode(bluetooth ? AudioManager.MODE_IN_COMMUNICATION : AudioManager.MODE_NORMAL);
        AudioDeviceInfo requestedInput = option == null || option.isSystemDefault()
                ? null
                : AudioInputCatalog.resolve(context, option.getDeviceId());

        if (bluetooth && requestedInput == null) {
            throw new IllegalStateException("The selected Bluetooth microphone is no longer connected");
        }

        try {
            if (Build.VERSION.SDK_INT >= 31) {
                if (bluetooth) {
                    List<AudioDeviceInfo> candidates = manager.getAvailableCommunicationDevices();
                    AudioDeviceInfo communicationSink = chooseCommunicationDevice(requestedInput, candidates);
                    if (communicationSink == null) {
                        throw new IllegalStateException("Android exposes the Bluetooth microphone but no matching telephone-mode communication device");
                    }
                    long started = SystemClock.elapsedRealtime();
                    if (!manager.setCommunicationDevice(communicationSink)) {
                        throw new IllegalStateException("Android rejected the Bluetooth telephone-mode communication route");
                    }
                    selectedCommunicationDevice = waitForCommunicationDevice(
                            communicationSink, COMMUNICATION_ROUTE_TIMEOUT_MS);
                    preparationDetail = "requested_input=" + describeDevice(requestedInput)
                            + ", communication_sink=" + describeDevice(selectedCommunicationDevice)
                            + ", communication_wait_ms="
                            + Math.max(0L, SystemClock.elapsedRealtime() - started)
                            + ", candidates=" + describeDevices(candidates);
                } else {
                    manager.clearCommunicationDevice();
                    selectedCommunicationDevice = null;
                    preparationDetail = "communication_route=platform_default";
                }
            } else if (bluetooth) {
                long started = SystemClock.elapsedRealtime();
                startLegacyScoAndWait(context, COMMUNICATION_ROUTE_TIMEOUT_MS);
                preparationDetail = "requested_input=" + describeDevice(requestedInput)
                        + ", legacy_sco_connected=true, communication_wait_ms="
                        + Math.max(0L, SystemClock.elapsedRealtime() - started);
            } else {
                manager.setBluetoothScoOn(false);
                manager.stopBluetoothSco();
                preparationDetail = "communication_route=platform_default";
            }
        } catch (SecurityException denied) {
            throw new IllegalStateException("Android denied permission to select the microphone route", denied);
        }
        return requestedInput;
    }

    public boolean applyPreferredDevice(AudioRecord recorder, AudioDeviceInfo requested,
                                        AudioInputOption option) {
        if (recorder == null || requested == null) return true;
        if (option != null && option.isBluetooth()) {
            // setCommunicationDevice selects a communication sink and Android automatically
            // chooses its matching source. Passing the input port to setCommunicationDevice
            // is invalid, and forcing the source here can fight that platform selection.
            return true;
        }
        try {
            return recorder.setPreferredDevice(requested);
        } catch (SecurityException denied) {
            return false;
        }
    }

    public AudioDeviceInfo waitForRoutedInput(AudioRecord recorder, AudioDeviceInfo requested,
                                              AudioInputOption option) {
        if (recorder == null) return null;
        long deadline = SystemClock.elapsedRealtime() + RECORDER_ROUTE_TIMEOUT_MS;
        AudioDeviceInfo current = recorder.getRoutedDevice();
        while (!matchesInputRoute(requested, option, current)
                && SystemClock.elapsedRealtime() < deadline) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Bluetooth route wait was cancelled because Voice Button was closed");
            }
            SystemClock.sleep(50L);
            current = recorder.getRoutedDevice();
        }
        if (!matchesInputRoute(requested, option, current)) {
            throw new IllegalStateException("Android did not route AudioRecord to the selected microphone within three seconds; requested="
                    + describeDevice(requested) + ", actual=" + describeDevice(current)
                    + ", communication=" + describeDevice(selectedCommunicationDevice));
        }
        return current;
    }

    public String getPreparationDetail() {
        return preparationDetail;
    }

    public void release() {
        if (manager == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                manager.clearCommunicationDevice();
            } else if (legacyScoStarted) {
                manager.setBluetoothScoOn(false);
                manager.stopBluetoothSco();
            }
        } catch (SecurityException ignored) {
            // Best-effort teardown. The process is also non-sticky and exits on user close.
        } finally {
            selectedCommunicationDevice = null;
            legacyScoStarted = false;
            try { manager.setMode(AudioManager.MODE_NORMAL); }
            catch (RuntimeException ignored) {}
        }
    }

    static int communicationDeviceScore(int requestedType, String requestedAddress,
                                        String requestedProduct, int candidateType,
                                        String candidateAddress, String candidateProduct,
                                        boolean candidateIsSink) {
        if (!candidateIsSink || !matchingBluetoothType(requestedType, candidateType)) return -1;
        int score = 100;
        String inputAddress = normalize(requestedAddress);
        String outputAddress = normalize(candidateAddress);
        if (!inputAddress.isEmpty() && inputAddress.equals(outputAddress)) score += 1000;
        String inputProduct = normalize(requestedProduct);
        String outputProduct = normalize(candidateProduct);
        if (!inputProduct.isEmpty() && inputProduct.equals(outputProduct)) score += 100;
        return score;
    }

    private static AudioDeviceInfo chooseCommunicationDevice(AudioDeviceInfo requestedInput,
                                                              List<AudioDeviceInfo> candidates) {
        if (requestedInput == null || candidates == null) return null;
        AudioDeviceInfo best = null;
        int bestScore = -1;
        for (AudioDeviceInfo candidate : candidates) {
            if (candidate == null) continue;
            int score = communicationDeviceScore(
                    requestedInput.getType(), safeAddress(requestedInput), safeProduct(requestedInput),
                    candidate.getType(), safeAddress(candidate), safeProduct(candidate), candidate.isSink());
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    private AudioDeviceInfo waitForCommunicationDevice(AudioDeviceInfo requested, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        AudioDeviceInfo current = manager.getCommunicationDevice();
        while (!sameDevice(requested, current) && SystemClock.elapsedRealtime() < deadline) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Bluetooth telephone-mode route wait was cancelled because Voice Button was closed");
            }
            SystemClock.sleep(50L);
            current = manager.getCommunicationDevice();
        }
        if (!sameDevice(requested, current)) {
            throw new IllegalStateException("Android accepted the Bluetooth communication request but did not activate it within five seconds; requested="
                    + describeDevice(requested) + ", actual=" + describeDevice(current));
        }
        return current;
    }

    private void startLegacyScoAndWait(Context context, long timeoutMs) {
        CountDownLatch connected = new CountDownLatch(1);
        AtomicInteger finalState = new AtomicInteger(AudioManager.SCO_AUDIO_STATE_DISCONNECTED);
        AtomicBoolean requestStarted = new AtomicBoolean(false);
        AtomicBoolean sawConnecting = new AtomicBoolean(false);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                int state = intent == null ? AudioManager.SCO_AUDIO_STATE_ERROR
                        : intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE,
                                AudioManager.SCO_AUDIO_STATE_ERROR);
                finalState.set(state);
                if (state == AudioManager.SCO_AUDIO_STATE_CONNECTING) {
                    sawConnecting.set(true);
                } else if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                    connected.countDown();
                } else if (requestStarted.get() && sawConnecting.get()
                        && (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED
                        || state == AudioManager.SCO_AUDIO_STATE_ERROR)) {
                    connected.countDown();
                }
            }
        };
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED);
        Intent sticky;
        if (Build.VERSION.SDK_INT >= 33) {
            sticky = context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            sticky = context.registerReceiver(receiver, filter);
        }
        try {
            int initial = sticky == null ? AudioManager.SCO_AUDIO_STATE_DISCONNECTED
                    : sticky.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE,
                            AudioManager.SCO_AUDIO_STATE_DISCONNECTED);
            finalState.set(initial);
            if (initial == AudioManager.SCO_AUDIO_STATE_CONNECTED) connected.countDown();
            requestStarted.set(true);
            manager.startBluetoothSco();
            manager.setBluetoothScoOn(true);
            legacyScoStarted = true;
            boolean signaled;
            try {
                signaled = connected.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Bluetooth SCO wait was interrupted", interrupted);
            }
            if (!signaled || finalState.get() != AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                manager.setBluetoothScoOn(false);
                manager.stopBluetoothSco();
                legacyScoStarted = false;
                throw new IllegalStateException("Bluetooth telephone-mode audio did not connect within five seconds; final_state="
                        + finalState.get());
            }
        } finally {
            try { context.unregisterReceiver(receiver); } catch (Exception ignored) {}
        }
    }

    private static boolean matchesInputRoute(AudioDeviceInfo requested,
                                             AudioInputOption option,
                                             AudioDeviceInfo actual) {
        if (actual == null) return false;
        if (option == null || option.isSystemDefault()) return true;
        if (option.getCategory() == AudioInputOption.Category.BUILT_IN) {
            return actual.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC;
        }
        if (option.isBluetooth()) {
            // The exact headset was already selected through its communication sink.
            // Android chooses the matching source automatically, and some vendors omit
            // or rewrite the source address even though the route is correct.
            return matchingBluetoothType(option.getDeviceType(), actual.getType());
        }
        return requested != null && (requested.getId() == actual.getId()
                || (requested.getType() == actual.getType()
                && !safeAddress(requested).isEmpty()
                && safeAddress(requested).equalsIgnoreCase(safeAddress(actual))));
    }

    private static boolean matchingBluetoothType(int requestedType, int candidateType) {
        if (requestedType == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            return candidateType == AudioDeviceInfo.TYPE_BLUETOOTH_SCO;
        }
        return Build.VERSION.SDK_INT >= 31
                && requestedType == AudioDeviceInfo.TYPE_BLE_HEADSET
                && candidateType == AudioDeviceInfo.TYPE_BLE_HEADSET;
    }

    private static boolean sameDevice(AudioDeviceInfo first, AudioDeviceInfo second) {
        if (first == null || second == null) return false;
        if (first.getId() == second.getId()) return true;
        String firstAddress = safeAddress(first);
        return first.getType() == second.getType()
                && !firstAddress.isEmpty()
                && firstAddress.equalsIgnoreCase(safeAddress(second));
    }

    private static String describeDevices(List<AudioDeviceInfo> devices) {
        if (devices == null || devices.isEmpty()) return "[]";
        StringBuilder out = new StringBuilder("[");
        for (AudioDeviceInfo device : devices) {
            if (out.length() > 1) out.append(';');
            out.append(describeDevice(device));
        }
        return out.append(']').toString();
    }

    private static String describeDevice(AudioDeviceInfo device) {
        if (device == null) return "null";
        return "id=" + device.getId() + ",type=" + device.getType()
                + ",source=" + device.isSource() + ",sink=" + device.isSink()
                + ",address=" + safeAddress(device) + ",product=" + safeProduct(device);
    }

    private static String safeAddress(AudioDeviceInfo device) {
        try { return device == null || device.getAddress() == null ? "" : device.getAddress(); }
        catch (SecurityException denied) { return ""; }
    }

    private static String safeProduct(AudioDeviceInfo device) {
        try {
            CharSequence name = device == null ? null : device.getProductName();
            return name == null ? "" : name.toString();
        } catch (SecurityException denied) {
            return "";
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.US);
    }
}
