// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;

/** Uses reviewed public model bytes only; all mutable fixtures are temporary. */
public final class CandidateRankingResourcesSmoke {
    private static void check(boolean value){if(!value)throw new AssertionError();}
    private interface Checked {void run()throws Exception;}
    private static void rejects(Checked work)throws Exception{boolean rejected=false;try{work.run();}catch(IOException expected){rejected=true;}check(rejected);}

    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("Supply the pinned candidate-model asset directory");
        File assets=new File(args[0]).getCanonicalFile();
        check(CandidateRankingResources.verify(assets)); // Missing assets must fail, never skip.
        Path temporary=Files.createTempDirectory("zapime-candidate-resources-");
        try{
            File parent=temporary.resolve("models").toFile();
            CandidateRankingResources.Source source=name->new FileInputStream(new File(assets,name));
            File installed=CandidateRankingResources.prepare(parent,source);
            check(CandidateRankingResources.verify(installed));
            AtomicInteger reopened=new AtomicInteger();
            check(CandidateRankingResources.prepare(parent,name->{reopened.incrementAndGet();throw new IOException("must_not_open");}).equals(installed));
            check(reopened.get()==0);

            // A corrupted fixed file is repaired only from the pinned APK bytes.
            Files.write(new File(installed,"model.json").toPath(),new byte[]{0});
            check(!CandidateRankingResources.verify(installed));
            CandidateRankingResources.prepare(parent,source);
            check(CandidateRankingResources.verify(installed));

            // Failed/stale staging is retried, but never accepted as a model.
            File fresh=temporary.resolve("fresh").toFile();
            rejects(()->CandidateRankingResources.prepare(fresh,name->new ByteArrayInputStream(new byte[]{1})));
            File recovered=CandidateRankingResources.prepare(fresh,source);
            check(CandidateRankingResources.verify(recovered));

            // No recursive deletion of unexpected user files, links or subdirs.
            Path unexpected=new File(installed,"unrelated.txt").toPath();
            Files.write(unexpected,new byte[]{4,5});
            rejects(()->CandidateRankingResources.prepare(parent,source));
            check(Files.size(unexpected)==2);Files.delete(unexpected);
            Path model=new File(installed,"lm_step.ort").toPath();
            Files.delete(model);
            Path outside=temporary.resolve("outside.txt");Files.write(outside,new byte[]{7,8,9});
            Files.createSymbolicLink(model,outside);
            rejects(()->CandidateRankingResources.prepare(parent,source));
            check(Files.size(outside)==3);Files.delete(model);
            CandidateRankingResources.prepare(parent,source);
            check(CandidateRankingResources.verify(installed));
            System.out.println("Candidate ranking pinned-resource smoke passed");
        }finally{
            try(java.util.stream.Stream<Path> entries=Files.walk(temporary)){
                Path[] paths=entries.sorted(Comparator.reverseOrder()).toArray(Path[]::new);
                for(Path path:paths)Files.delete(path);
            }
        }
    }
}
