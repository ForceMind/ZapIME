// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One producer; completion is published only after its final PCM offer. */
final class VoicePcmQueue {
    private final BlockingQueue<short[]> chunks;
    private final AtomicBoolean completed=new AtomicBoolean();
    VoicePcmQueue(){this(new ArrayBlockingQueue<>(602));}
    VoicePcmQueue(BlockingQueue<short[]> chunks){this.chunks=java.util.Objects.requireNonNull(chunks);}
    boolean offer(short[] pcm){return chunks.offer(pcm);}
    void finish(){completed.set(true);}
    short[] next(AtomicBoolean cancelled)throws InterruptedException {
        while(!cancelled.get()){
            short[] pcm=chunks.poll(100,TimeUnit.MILLISECONDS);
            if(pcm!=null)return pcm;
            // poll can time out immediately before the final offer + finish.
            // Observing completion must be followed by a fresh emptiness check.
            if(completed.get()&&chunks.isEmpty())return null;
        }
        return null;
    }
    void clear(){short[] pcm;while((pcm=chunks.poll())!=null)Arrays.fill(pcm,(short)0);}
}
