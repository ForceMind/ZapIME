// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;
import android.content.Context;
import java.io.*;
final class ResourceInstaller {
    // This path is never called by the input worker. The independent model
    // installer has its own lock, so an 8 MB verification cannot stall typing.
    static File prepareCandidateRanking(Context context) throws IOException {
        File parent=new File(context.getFilesDir().getCanonicalFile(),"zapime/candidate-models");
        return CandidateRankingResources.prepare(parent,
            name->context.getAssets().open(CandidateModelCatalog.ASSET_DIRECTORY+"/"+name));
    }
    static synchronized void prepare(Context context) throws IOException {
        File root=new File(context.getFilesDir(),"zapime/resources");
        if(!root.isDirectory() && !root.mkdirs())throw new IOException("resource_directory");
        for(String name:context.getAssets().list("dictionary")) {
            File output=new File(root,name);if(output.isFile() && output.length()>0)continue;
            File staged=new File(root,name+".incoming");
            try(InputStream in=context.getAssets().open("dictionary/"+name);OutputStream out=new FileOutputStream(staged)) {byte[] buffer=new byte[65536];int count;while((count=in.read(buffer))!=-1)out.write(buffer,0,count);}
            if(!staged.renameTo(output))throw new IOException("resource_install");
        }
    }
}
