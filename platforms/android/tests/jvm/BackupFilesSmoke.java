// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;
import java.io.*;
import java.nio.file.*;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** Actual file/stream faults; no Android provider or encryption acceptance claim. */
public final class BackupFilesSmoke {
    interface Task {void run()throws Exception;}
    static void check(boolean ok){if(!ok)throw new AssertionError("backup document boundary");}
    static void reject(Task task)throws Exception{boolean failed=false;try{task.run();}catch(IOException expected){failed=true;}check(failed);}
    public static void main(String[] args)throws Exception {
        Path directory=Files.createTempDirectory("zapime-backup-io-");int checks=0;
        try {
            Path file=directory.resolve("fixture.bin");byte[] encrypted=new byte[20003];Arrays.fill(encrypted,(byte)87);
            BackupFiles.Document document=new BackupFiles.Document(){public OutputStream write()throws IOException{return Files.newOutputStream(file);}public InputStream read()throws IOException{return Files.newInputStream(file);}};
            BackupFiles.writeVerified(document,encrypted,()->false);check(Arrays.equals(Files.readAllBytes(file),encrypted));checks++;
            BackupFiles.Document corrupt=new BackupFiles.Document(){public OutputStream write()throws IOException{return Files.newOutputStream(file);}public InputStream read(){return new ByteArrayInputStream(new byte[]{1,2});}};
            reject(()->BackupFiles.writeVerified(corrupt,encrypted,()->false));checks++;
            AtomicBoolean readCalled=new AtomicBoolean();BackupFiles.Document closeFailure=new BackupFiles.Document(){public OutputStream write(){return new ByteArrayOutputStream(){public void close()throws IOException{throw new IOException("synthetic close failure");}};}public InputStream read(){readCalled.set(true);return new ByteArrayInputStream(encrypted);}};
            reject(()->BackupFiles.writeVerified(closeFailure,encrypted,()->false));check(!readCalled.get());checks++;
            AtomicBoolean opened=new AtomicBoolean();BackupFiles.Document untouched=new BackupFiles.Document(){public OutputStream write(){opened.set(true);return new ByteArrayOutputStream();}public InputStream read(){throw new AssertionError();}};
            reject(()->BackupFiles.writeVerified(untouched,encrypted,()->true));check(!opened.get());checks++;
            AtomicBoolean stop=new AtomicBoolean();ByteArrayOutputStream partial=new ByteArrayOutputStream();BackupFiles.Document interrupted=new BackupFiles.Document(){public OutputStream write(){return new FilterOutputStream(partial){public void write(byte[] b,int off,int len)throws IOException{out.write(b,off,len);stop.set(true);}};}public InputStream read(){throw new AssertionError();}};
            reject(()->BackupFiles.writeVerified(interrupted,encrypted,stop::get));check(partial.size()>0&&partial.size()<encrypted.length);checks++;
            InputStream oversized=new InputStream(){public int read(){return 1;}public int read(byte[] b,int off,int len){Arrays.fill(b,off,off+len,(byte)1);return len;}};
            reject(()->BackupFiles.read(oversized,()->false));reject(()->BackupFiles.read(new ByteArrayInputStream(new byte[0]),()->false));reject(()->BackupFiles.read(null,()->false));checks++;
            // The sync-key page opts into a smaller budget without weakening
            // the existing local-backup default or opening oversized outputs.
            final int keyLimit=16*1024;byte[] keyPackage=new byte[keyLimit];Arrays.fill(keyPackage,(byte)53);
            check(Arrays.equals(keyPackage,BackupFiles.read(new ByteArrayInputStream(keyPackage),()->false,keyLimit)));
            reject(()->BackupFiles.read(new ByteArrayInputStream(new byte[keyLimit+1]),()->false,keyLimit));checks++;
            opened.set(false);reject(()->BackupFiles.writeVerified(untouched,encrypted,()->false,keyLimit));check(!opened.get());
            BackupFiles.writeVerified(document,keyPackage,()->false,keyLimit);check(Arrays.equals(Files.readAllBytes(file),keyPackage));
            BackupFiles.Document extraReadback=new BackupFiles.Document(){public OutputStream write()throws IOException{return Files.newOutputStream(file);}public InputStream read(){return new ByteArrayInputStream(new byte[keyLimit+1]);}};
            reject(()->BackupFiles.writeVerified(extraReadback,keyPackage,()->false,keyLimit));checks++;
            reject(()->BackupFiles.read(new ByteArrayInputStream(keyPackage),()->false,0));
            reject(()->BackupFiles.read(new ByteArrayInputStream(keyPackage),()->false,BackupFiles.MAX+1));
            opened.set(false);reject(()->BackupFiles.writeVerified(untouched,keyPackage,()->false,0));check(!opened.get());checks++;
            Path report=Paths.get(args[0]);Files.createDirectories(report.getParent());Files.write(report,("{\"passed\":true,\"checks\":"+checks+",\"scope\":\"Actual host JVM file/readback/stream faults only; not Android SAF or encryption\"}\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            System.out.println("PASS backup file/readback/cancellation boundaries: "+checks);
        }finally{try(java.util.stream.Stream<Path> paths=Files.walk(directory)){for(Path p:(Iterable<Path>)paths.sorted(java.util.Comparator.reverseOrder())::iterator)Files.delete(p);}}
    }
}
