package com.hans.android.adaptivekeyboard;

import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** Reads the site's public RSS search feed only after an explicit GIF search. */
final class ReactionGifFeed {
    private static final String SITE="https://www.reactiongifs.com";
    private static final Pattern IMAGE=Pattern.compile(
        "<img\\b[^>]*?\\s+src\\s*=\\s*([\"'])(.*?)\\1",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    static boolean isHost(String host) {
        return "www.reactiongifs.com".equalsIgnoreCase(host) || "reactiongifs.com".equalsIgnoreCase(host);
    }

    static List<GiphySearch.Gif> search(String query) throws Exception {
        String term=query==null?"":query.trim();
        String url=term.isEmpty()?SITE+"/feed/":
            SITE+"/?feed=rss2&s="+URLEncoder.encode(term,"UTF-8");
        return parse(new String(GiphySearch.request(url,512_000),StandardCharsets.UTF_8));
    }

    private static String sameSite(String raw) {
        try {
            URI uri=URI.create(raw.trim().replace("&amp;","&"));
            if(!isHost(uri.getHost()) || uri.getUserInfo()!=null || uri.getPort()!=-1 ||
                !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())))
                return "";
            return SITE+uri.getRawPath();
        } catch(IllegalArgumentException invalid) {return "";}
    }

    static List<GiphySearch.Gif> parse(String xml) throws Exception {
        // The published feed is UTF-8 RSS with CDATA. Never load an external DTD.
        if(xml.contains("<!DOCTYPE"))throw new java.io.IOException("Unexpected RSS doctype");
        DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder=factory.newDocumentBuilder();
        builder.setEntityResolver((publicId,systemId)->new InputSource(new StringReader("")));
        Element rss=builder.parse(new InputSource(new StringReader(xml))).getDocumentElement();
        if(!"rss".equals(rss.getTagName()))throw new java.io.IOException("Invalid ReactionGIFs feed");
        NodeList items=rss.getElementsByTagName("item");
        List<GiphySearch.Gif> results=new ArrayList<>();
        Set<String> seen=new HashSet<>();
        for(int i=0;i<items.getLength() && results.size()<15;i++) {
            Element item=(Element)items.item(i);
            NodeList content=item.getElementsByTagNameNS("http://purl.org/rss/1.0/modules/content/","encoded");
            if(content.getLength()==0)content=item.getElementsByTagName("description");
            if(content.getLength()==0)continue;
            Matcher images=IMAGE.matcher(content.item(0).getTextContent());
            while(images.find()) {
                String url=sameSite(images.group(2));
                if(!url.toLowerCase(Locale.ROOT).endsWith(".gif") || !seen.add(url))continue;
                String title=field(item,"title");
                results.add(new GiphySearch.Gif(title.isEmpty()?"Reaction GIF":title,url,url,
                    sameSite(field(item,"link")),"ReactionGIFs.com"));
                break;
            }
        }
        return results;
    }

    private static String field(Element item,String name) {
        NodeList nodes=item.getElementsByTagName(name);
        return nodes.getLength()==0?"":nodes.item(0).getTextContent().trim();
    }
}
