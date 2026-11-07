package com.hans.android.adaptivekeyboard;
import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;
public class GiphyResponseTest {
    @Test public void usesFastMobileGifNotEnormousOriginal() throws Exception {
        String response="{\"data\":[{\"title\":\"Facepalm\",\"url\":\"https://giphy.com/gifs/example\","+
            "\"user\":{\"username\":\"creator\"},\"images\":{\"fixed_width_small\":{\"url\":\"https://media1.giphy.com/low.gif\"},"+
            "\"fixed_width\":{\"url\":\"https://media2.giphy.com/medium.gif\"},"+
            "\"original\":{\"url\":\"https://media1.giphy.com/huge.gif\"}}}]}";
        List<GiphySearch.Gif> r=GiphySearch.parseGiphyResponse(response);
        assertEquals(1,r.size());
        assertEquals("Facepalm",r.get(0).title);
        assertEquals("https://media1.giphy.com/low.gif",r.get(0).thumbnail);
        assertEquals("https://media2.giphy.com/medium.gif",r.get(0).original);
        assertTrue(r.get(0).attribution.contains("creator"));
        assertEquals("https://giphy.com/gifs/example",r.get(0).link);
    }
    @Test public void invalidHostsAreNotOfferedAsMedia() throws Exception {
        String r="{\"data\":[{\"images\":{\"fixed_width_small\":{\"url\":\"https://bad.example/preview.gif\"},\"fixed_width\":{\"url\":\"https://bad.example/original.gif\"}}}]}";
        assertEquals(0,GiphySearch.parseGiphyResponse(r).size());
    }
    @Test public void missingMediaOrEmptyResponseCannotCrashKeyboard() throws Exception {
        assertTrue(GiphySearch.parseGiphyResponse("{\"data\":[]}").isEmpty());
        assertTrue(GiphySearch.parseGiphyResponse("{\"meta\":{\"status\":200}}").isEmpty());
    }
}
