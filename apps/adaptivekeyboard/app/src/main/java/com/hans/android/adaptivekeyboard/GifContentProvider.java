package com.hans.android.adaptivekeyboard;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** Exposes only selected, cached GIFs via temporary read grants to recipient apps. */
public final class GifContentProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    private File fileFor(Uri uri) throws FileNotFoundException {
        if(uri==null || !"content".equals(uri.getScheme()) ||
            !getContext().getPackageName().concat(".gifs").equals(uri.getAuthority()) ||
            uri.getPathSegments().size()!=2 || !"gifs".equals(uri.getPathSegments().get(0)))
            throw new FileNotFoundException("Not a shared GIF URI");
        String name=uri.getLastPathSegment();
        if(name==null || !name.matches("share-[A-Za-z0-9_.-]+\\.gif"))
            throw new FileNotFoundException("Invalid GIF filename");
        File file=new File(new File(getContext().getCacheDir(),"gifs"),name);
        if(!file.isFile())throw new FileNotFoundException("GIF not cached");
        return file;
    }

    @Override public String getType(Uri uri) {
        try {fileFor(uri);return "image/gif";}
        catch(FileNotFoundException ignored) {return null;}
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if(!"r".equals(mode))throw new FileNotFoundException("GIF is read-only");
        return ParcelFileDescriptor.open(fileFor(uri),ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort) {
        try {
            File file=fileFor(uri);
            String[] columns=projection==null?
                new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;
            MatrixCursor cursor=new MatrixCursor(columns,1);
            Object[] values=new Object[columns.length];
            for(int i=0;i<columns.length;i++) {
                if(OpenableColumns.DISPLAY_NAME.equals(columns[i]))values[i]=file.getName();
                else if(OpenableColumns.SIZE.equals(columns[i]))values[i]=file.length();
            }
            cursor.addRow(values);
            return cursor;
        } catch(FileNotFoundException invalid) {return null;}
    }
    @Override public Uri insert(Uri uri,ContentValues values) {throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args) {return 0;}
    @Override public int delete(Uri uri,String selection,String[] args) {return 0;}
}
