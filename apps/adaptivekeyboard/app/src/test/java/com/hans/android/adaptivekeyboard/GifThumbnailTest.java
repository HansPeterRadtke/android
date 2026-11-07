package com.hans.android.adaptivekeyboard;

import static org.junit.Assert.*;
import org.junit.Test;

public final class GifThumbnailTest {
    @Test public void knownWikimediaGifsGetDirectThumbnailUrlsWithoutProxy() {
        assertEquals(
            "https://upload.wikimedia.org/wikipedia/commons/thumb/f/fc/Scratch_Rainbow_nevit.gif/120px-Scratch_Rainbow_nevit.gif",
            GiphySearch.wikimediaThumbnailUrl(
                "https://upload.wikimedia.org/wikipedia/commons/f/fc/Scratch_Rainbow_nevit.gif",120));
        assertEquals(
            "https://upload.wikimedia.org/wikipedia/commons/thumb/7/78/Scratch-Lissajous-nevit-01.gif/120px-Scratch-Lissajous-nevit-01.gif",
            GiphySearch.wikimediaThumbnailUrl(
                "https://upload.wikimedia.org/wikipedia/commons/7/78/Scratch-Lissajous-nevit-01.gif?utm_source=commons",120));
    }
    @Test public void disallowUntrustedHostsOrBadWidths() {
        assertNull(GiphySearch.wikimediaThumbnailUrl("https://evil.test/wikipedia/commons/7/78/A.gif",120));
        assertNull(GiphySearch.wikimediaThumbnailUrl("http://upload.wikimedia.org/wikipedia/commons/7/78/A.gif",120));
        assertNull(GiphySearch.wikimediaThumbnailUrl("https://upload.wikimedia.org/wikipedia/commons/7/78/A.gif",9999));
        assertNull(GiphySearch.wikimediaThumbnailUrl("https://upload.wikimedia.org/wikipedia/commons/7/78/A.jpg",120));
    }
}
