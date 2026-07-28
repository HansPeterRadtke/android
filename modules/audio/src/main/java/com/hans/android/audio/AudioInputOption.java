package com.hans.android.audio;

import java.util.Objects;

public final class AudioInputOption {
    public enum Category {
        SYSTEM_DEFAULT,
        BUILT_IN,
        WIRED_OR_AUX,
        USB,
        BLUETOOTH,
        LINE_INPUT,
        OTHER
    }

    public static final int DEFAULT_DEVICE_ID = -1;

    private final int deviceId;
    private final int deviceType;
    private final String label;
    private final Category category;

    public AudioInputOption(int deviceId, int deviceType, String label, Category category) {
        this.deviceId = deviceId;
        this.deviceType = deviceType;
        this.label = Objects.requireNonNull(label, "label");
        this.category = Objects.requireNonNull(category, "category");
    }

    public static AudioInputOption systemDefault() {
        return new AudioInputOption(DEFAULT_DEVICE_ID, 0, "System default microphone", Category.SYSTEM_DEFAULT);
    }

    public int getDeviceId() { return deviceId; }
    public int getDeviceType() { return deviceType; }
    public String getLabel() { return label; }
    public Category getCategory() { return category; }
    public boolean isSystemDefault() { return deviceId == DEFAULT_DEVICE_ID; }
    public boolean isBluetooth() { return category == Category.BLUETOOTH; }

    @Override public String toString() { return label; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof AudioInputOption)) return false;
        AudioInputOption that = (AudioInputOption) other;
        return deviceId == that.deviceId;
    }

    @Override public int hashCode() { return Integer.hashCode(deviceId); }
}
