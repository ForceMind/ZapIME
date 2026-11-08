# Cache fill capacity reproduction and local verification

Baseline main`be5b45630bfdaaf1a9286446a24dbb40646c5fca` still contained the
unresolved [PR #22P1](https://github.com/ForceMind/ZapIME/pull/22#discussion_r4355312082)
and the conditions described by the unmerged [PR #25research](https://github.com/ForceMind/ZapIME/pull/25).
Both were freshly read through the GitHub connector. No existing draft was merged.

`baseline-failure.log` is an actual race-enabled run of the new
`TestObjectCacheAbandonedFillsRemainGloballyBounded` against unchanged baseline
production code. Thirty-two unique synthetic backend reads were each observed
starting, then their callers were cancelled and observed returning. The blocked
backend reads remained alive. A33rd key started another physical read, triggering
the expected failure. The backend's release barrier cleans up all synthetic work.
There were no real object requests, devices, user data or credentials in the log.

`baseline-test.go.txt` preserves a standalone replay fixture: copy it to
`services/sync/internal/syncapi/object_cache_capacity_test.go` in an isolated
checkout of the baseline and run the single named test. `baseline-recheck.log`
records the same capacity failure again. The first extraction omitted the fake
backend's Put/Probe methods and failed to compile; that harness-only failure is
retained as `baseline-replay-first-harness-failure.log`, then corrected without
changing the assertion or baseline product. It is not an additional product bug.

The fix replaces unbounded per-key `DoChan` admission with a maximum32-entry map,
one shared done signal per admitted key, and zero waiting-key queue. A fill owns
capacity through actual backend completion, validation and result publication.
It uses its own Background timeout, preserves remaining waiters, permits bounded
warm completion after all callers cancel, and rejects late bytes after deadline.
An uncooperative backend holds capacity even after its callers time out.

`cache-race.log` records9 cache groups repeated10 times, using start/release/done
barriers rather than arbitrary sleeps. Additional tests cover full-capacity
same-key join after the first caller cancels, warm hits, admission recovery,
deadline with a deliberately uncooperative backend, corrupt/error retries and
pre-cancelled requests. Existing copy, durability, TTL/LRU and sharing tests stay.
The20ms synthetic deadline is deliberately a timer behavior test, not a speed
or latency claim. An independent review ran the same race subset10 times and
found no blocking defect.

`local-validation.json` records fixedGo1.27.3, official toolchainSHA and actual
commands. `go-vet.log` and `go-build.log` are empty on successful commands.
`manifest.json` hashes the tested source/tests and retained evidence.

The added PostgreSQL/HTTP test uses real transactions and synthetic object data,
checks cancelled handlers release HTTP slots while fills retain capacity,
requires overload to return503 without changes, then retries the same cursor
after real backend completion. It was compiled locally but NOT RUN because this
environment has no PostgreSQL. Mandatory serviceCI must run it with an actual DB;
no database skip is reported as a pass. RealS3/R2, Docker/installer/recovery and
deployment were NOT RUN by this local evidence. No STATUS/plan or user system was
changed by this slice.
