package com.hans.android.voicebutton;

import android.content.Context;
import android.util.AtomicFile;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

final class PlayerCheckpointStore {
    private final AtomicFile file;

    PlayerCheckpointStore(Context context) {
        File directory = new File(context.getNoBackupFilesDir(), "player_state");
        if (!directory.isDirectory()) directory.mkdirs();
        file = new AtomicFile(new File(directory, "checkpoint.json"));
    }

    synchronized PlayerCheckpoint load() {
        if (!file.getBaseFile().isFile()) return PlayerCheckpoint.empty();
        try {
            byte[] bytes = file.readFully();
            return PlayerCheckpoint.fromJson(new JSONObject(
                    new String(bytes, StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            return PlayerCheckpoint.empty();
        }
    }

    synchronized void save(PlayerCheckpoint checkpoint) throws Exception {
        byte[] bytes = checkpoint.toJson().toString().getBytes(StandardCharsets.UTF_8);
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(bytes);
            output.flush();
            output.getFD().sync();
            file.finishWrite(output);
        } catch (Exception failure) {
            if (output != null) file.failWrite(output);
            throw failure;
        }
    }

    synchronized void clear() { file.delete(); }
}
