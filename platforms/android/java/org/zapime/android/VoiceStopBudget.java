// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** One stop-to-final budget, shared by every buffered frame and final decode. */
final class VoiceStopBudget {
    static final int LIMIT_MS=30000;
    private final LongSupplier clock;
    private final AtomicLong stoppedAt=new AtomicLong(-1);
    VoiceStopBudget(LongSupplier clock){this.clock=java.util.Objects.requireNonNull(clock);}
    boolean stop(){return stoppedAt.compareAndSet(-1,clock.getAsLong());}
    int remainingMillis(){long start=stoppedAt.get();if(start<0)return LIMIT_MS;long elapsed=Math.max(0,clock.getAsLong()-start);return (int)Math.max(0,LIMIT_MS-Math.min(LIMIT_MS,elapsed));}
    boolean expired(){return stoppedAt.get()>=0&&remainingMillis()==0;}
}
