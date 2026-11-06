package com.hans.android.adaptivekeyboard;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;

/** Read-only file grants for user-selected GIFs, never the private learning DB. */
public final class GifContentProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) {return "image/gif";}
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode) || uri.getPathSegments().size()!=2 ||
            !"gifs".equals(uri.getPathSegments().get(0))) throw new FileNotFoundException();
        String name=uri.getLastPathSegment();
        if (name==null || !name.matches("share-[A-Za-z0-9_.-]+\\.gif")) throw new FileNotFoundException();
        File file = new File(new File(getContext().getCacheDir(),"gifs"),name);
        if (!file.isFile()) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort) {return null;}
    @Override public Uri insert(Uri uri,ContentValues values) {throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args) {return 0;}
    @Override public int delete(Uri uri,String selection,String[] args) {return 0;}
}
