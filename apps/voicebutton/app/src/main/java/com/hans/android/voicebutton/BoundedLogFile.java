package com.hans.android.voicebutton;
import java.io.*;

/** Complete-line retention; called only by the diagnostic worker. */
final class BoundedLogFile {
    private BoundedLogFile() {}
    static synchronized void append(File file,byte[] bytes,long limit,boolean durable) throws IOException {
        if(bytes.length>limit)throw new IOException("Diagnostic entry exceeds retention budget");
        File parent=file.getParentFile();
        if(parent!=null&&!parent.isDirectory()&&!parent.mkdirs()&&!parent.isDirectory())throw new IOException("Cannot create log storage");
        if(file.length()>limit-bytes.length)retainTail(file,Math.max(0L,limit/2-bytes.length));
        try(FileOutputStream out=new FileOutputStream(file,true)) {
            out.write(bytes);out.flush();if(durable)out.getFD().sync();
        }
    }
    private static void retainTail(File file,long keep) throws IOException {
        File temp=new File(file.getPath()+".rotate");
        try(RandomAccessFile in=new RandomAccessFile(file,"r");FileOutputStream out=new FileOutputStream(temp)) {
            long start=Math.max(0,in.length()-keep);in.seek(start);
            if(start>0){int c;while((c=in.read())!=-1&&c!='\n'){} }
            byte[] buffer=new byte[65536];int n;
            while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
            out.flush();out.getFD().sync();
        }
        if(!temp.renameTo(file))throw new IOException("Cannot rotate log; original retained");
    }
}
