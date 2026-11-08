// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Actual official-package import, when independently obtained. No inference or weight publication. */
public final class VoiceKeyModelImportProbe {
    public static void main(String[] args)throws Exception{
        if(args.length!=3)throw new IllegalArgumentException("package report output-parent required");
        File source=new File(args[0]);if(Files.isSymbolicLink(source.toPath())||!source.isFile())throw new IOException("official_package_missing");
        Path parent=Paths.get(args[2]);Files.createDirectories(parent);Path isolated=Files.createTempDirectory(parent,"verified-import-");
        VoiceKeyModelStore.Catalog catalog=VoiceKeyModelCatalog.require();VoiceKeyModelStore store=new VoiceKeyModelStore(isolated.toFile(),catalog);
        boolean completed=false;
        try{
            try(InputStream in=new FileInputStream(source)){store.importPackage(in,()->false);}
            File verified=store.verified(()->false);if(!store.installed()||catalog.outputs.size()!=2||!verified.isDirectory())throw new IOException("official_import_failed");
            String report="{\"scope\":\"Actual source-pinned official package through production Java storage/import; no inference, Android/JNI, phone, or model redistribution acceptance.\",\"passed\":true,\"model_id\":\""+catalog.id+"\",\"package_sha256\":\""+catalog.packageSha+"\",\"package_bytes\":"+catalog.packageSize+",\"imported_members\":"+catalog.outputs.size()+",\"model_directory\":\""+verified.getCanonicalPath().replace("\\","\\\\").replace("\"","\\\"")+"\"}\n";
            Path output=Paths.get(args[1]);Files.createDirectories(output.getParent());Files.write(output,report.getBytes(StandardCharsets.UTF_8));completed=true;
            System.out.println("PASS source-pinned official VoiceKey model import; weights remain in ignored output directory");
        }finally{if(!completed)VoiceKeyModelStore.remove(isolated.toFile());}
    }
}
