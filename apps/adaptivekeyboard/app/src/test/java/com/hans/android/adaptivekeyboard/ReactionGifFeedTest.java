package com.hans.android.adaptivekeyboard;

import org.junit.Test;
import static org.junit.Assert.*;

public class ReactionGifFeedTest {
    @Test public void extractsOnlyRealProviderMediaAndDeduplicates() throws Exception {
        String item="<item><title>Calling Bullshit</title><link>https://www.reactiongifs.com/calling/</link>"+
            "<content:encoded><![CDATA[<img src='https://evil.example/a.gif'><img alt='BS' "+
            "src='https://reactiongifs.com/r/bs.gif'>]]></content:encoded></item>";
        String rss="<rss xmlns:content='http://purl.org/rss/1.0/modules/content/'><channel>"+
            item+item+"</channel></rss>";
        var gifs=ReactionGifFeed.parse(rss);
        assertEquals(1,gifs.size());
        assertEquals("Calling Bullshit",gifs.get(0).title);
        assertEquals("https://www.reactiongifs.com/r/bs.gif",gifs.get(0).original);
    }
    @Test public void emptySearchFeedIsValid() throws Exception {
        assertTrue(ReactionGifFeed.parse("<rss><channel/></rss>").isEmpty());
    }
    @Test(expected=java.io.IOException.class) public void rejectsExternalEntities() throws Exception {
        ReactionGifFeed.parse("<!DOCTYPE rss SYSTEM 'file:///does-not-exist'><rss/>");
    }
}
