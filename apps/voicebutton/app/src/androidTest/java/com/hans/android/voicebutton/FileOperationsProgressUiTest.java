package com.hans.android.voicebutton;

import static org.junit.Assert.*;

import android.content.Context;
import android.net.Uri;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;

@RunWith(AndroidJUnit4.class)
public class FileOperationsProgressUiTest {
    @Test public void exportCopiesRealBytesAndReportsCurrentFileProgress()
            throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        File source = File.createTempFile("voicebutton_source_qa_", ".mp3",
                context.getCacheDir());
        File target = File.createTempFile("voicebutton_target_qa_", ".mp3",
                context.getCacheDir());
        try {
            byte[] body = new byte[220_000];
            for (int i = 0; i < body.length; i++) body[i] = (byte)(i * 17 + 3);
            try (FileOutputStream output = new FileOutputStream(source)) {
                output.write(body);
                output.flush();
                output.getFD().sync();
            }
            ArrayList<long[]> progress = new ArrayList<>();
            FileOperations.copy(context, Uri.fromFile(source), Uri.fromFile(target),
                    new FileOperations.TransferListener() {
                        @Override public void onCopy(long done, long total) {
                            progress.add(new long[]{done, total});
                        }
                        @Override public void onVerifying() {
                            fail("A copy-only export does not delete or move the source");
                        }
                    }, source.length());
            assertArrayEquals(Files.readAllBytes(source.toPath()),
                    Files.readAllBytes(target.toPath()));
            assertTrue("Must report progress during real export", !progress.isEmpty());
            long previous = 0L;
            for (long[] sample : progress) {
                assertEquals(source.length(), sample[1]);
                assertTrue(sample[0] >= previous);
                assertTrue(sample[0] <= sample[1]);
                previous = sample[0];
            }
            assertEquals(source.length(), previous);
            FileOperations.verifyIdentical(context, Uri.fromFile(source),
                    Uri.fromFile(target));
        } finally {
            source.delete();
            target.delete();
        }
    }

    @Test public void invalidNamesRejectedBeforeProviderMutation() {
        for (String invalid : new String[]{"", " ", "../child", "bad/name", "bad\\name"}) {
            try {
                FileOperations.cleanName(invalid);
                fail("Accepted unsafe name: " + invalid);
            } catch (Exception expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }
}
