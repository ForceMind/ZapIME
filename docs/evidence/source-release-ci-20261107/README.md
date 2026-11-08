# Source release preparation failure and repair

The first PR preparation run42440405990 passed. The repaired draft-lookup revision ae37747acef97094b3927a23886eb9d26f30068f was then checked in [run42440662967](https://github.com/ForceMind/ZapIME/actions/runs/42440662967), actual PR checkout6eaddb90bdd25fdb0524392a256bb44e561b4531.

The 17 release tests, pinned upstream verification, 134 shared workspace tests, fmt and strict Clippy passed. Source preparation then refused its output because the Rust cache restored the previous run's `target/source-release`. The original job128609001659 log is preserved in `failed-42440662967.log.gz`. This was an actual failed workflow; it was never published or relabeled successful.

The workflow now keeps release assets outside the Cargo cache in each job's `runner.temp`, using a directory scoped to run ID and attempt. Preparation remains create-only; artifact upload, download and publication all use that location. New CI must validate the repair; this record does not claim publication or device acceptance.

A subsequent configuration run [42440837908](https://github.com/ForceMind/ZapIME/actions/runs/42440837908), head555a47a627cfe8025cd666954020f61aa79989aa, failed before jobs started: the temporary-directory expression had been placed in job-level env. It is now placed in the prepare/publish step env, while upload/download step inputs use the same runner-temp expression. This correction preserves run/attempt isolation and the original failed run.

The corrected workflow at head1c26273bc7a42a05f5cf6eed0e5fc49d6cbcabad passed [PR preparation run42441063163](https://github.com/ForceMind/ZapIME/actions/runs/42441063163): 17 real-Git/release tests, shared-core checks, source-only preparation and upload all succeeded with the run-scoped output. Publication was correctly skipped for the pull-request event. Official actionlint1.7.11 also accepted the final step-context configuration. Public release still requires the separate main-push publishing job and its readback.
