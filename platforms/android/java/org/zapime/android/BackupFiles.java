// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;
import java.io.*;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

/** The provider sees only encrypted bytes. No file deletion or atomicity promise. */
final class BackupFiles {
    static final int MAX=6*1024*1024;
    interface Document {OutputStream write()throws IOException;InputStream read()throws IOException;}
    static void check(BooleanSupplier cancelled)throws IOException{if(cancelled.getAsBoolean())throw new IOException("backup_cancelled");}
    static byte[] read(InputStream input,BooleanSupplier cancelled)throws IOException {
        if(input==null)throw new IOException("backup_document_unavailable");
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];
        try{while(true){check(cancelled);int n=input.read(buffer);if(n<0)break;if(n==0){int value=input.read();if(value<0)break;buffer[0]=(byte)value;n=1;}if(out.size()+n>MAX)throw new IOException("backup_file_too_large");out.write(buffer,0,n);}}
        finally{Arrays.fill(buffer,(byte)0);}
        check(cancelled);if(out.size()==0)throw new IOException("backup_file_empty");return out.toByteArray();
    }
    static void writeVerified(Document document,byte[] encrypted,BooleanSupplier cancelled)throws IOException {
        if(encrypted==null||encrypted.length==0||encrypted.length>MAX)throw new IOException("backup_file_too_large");
        check(cancelled);
        try(OutputStream out=document.write()){
            if(out==null)throw new IOException("backup_document_unavailable");
            for(int offset=0;offset<encrypted.length;offset+=8192){check(cancelled);out.write(encrypted,offset,Math.min(8192,encrypted.length-offset));}out.flush();
        }
        byte[] actual;
        try(InputStream in=document.read()){actual=read(in,cancelled);}
        try{check(cancelled);if(!MessageDigest.isEqual(encrypted,actual))throw new IOException("backup_readback_failed");}
        finally{Arrays.fill(actual,(byte)0);}
    }
    private BackupFiles(){}
}
