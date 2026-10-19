package com.hans.android.voicebutton;

import static org.junit.Assert.*;
import com.hans.android.audio.reliable.ReliableSessionManifest;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=31, application=android.app.Application.class)
public class RecordingManifestIndexTest {
    private static ReliableSessionManifest manifest(String id,long date,
                                                       String state,boolean done) {
        ReliableSessionManifest m = new ReliableSessionManifest();
        m.sessionId=id;m.createdAt=date;m.state=state;m.recordingFinished=done;
        return m;
    }

    @Test public void onePassSelectsNewestUnfinishedAndInterrupted() {
        ReliableSessionManifest a=manifest("old",100,"PAUSED",false);
        ReliableSessionManifest b=manifest("new",200,"INTERRUPTED",false);
        ReliableSessionManifest c=manifest("complete",300,"COMPLETE",true);
        ReliableSessionManifest d=manifest("unreadable",400,"UNREADABLE_METADATA",false);
        RecordingManifestIndex index=RecordingManifestIndex.from(Arrays.asList(a,b,c,d));
        assertEquals("new",index.unfinished.sessionId);
        assertEquals("new",index.interrupted.sessionId);
        assertNotSame(b,index.unfinished);
    }

    @Test public void noIncompleteSessionMeansStartCanProceed() {
        RecordingManifestIndex index=RecordingManifestIndex.from(
                Collections.singletonList(manifest("done",1,"COMPLETE",true)));
        assertNull(index.unfinished);
        assertNull(index.interrupted);
        assertNull(RecordingManifestIndex.from(null).unfinished);
    }
}
