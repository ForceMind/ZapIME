# Source-release draft discovery hardening

The preserved [original publication evidence](../source-alpha-publication-20261107/README.md)
shows first publisher job128611997032 failing after creation when its immediate
draft lookup returned `None`. The same-source retry published successfully. This
slice fixes unsafe response handling; the original GitHub visibility cause is
still unproven and the old failed log remains unchanged.

## Reproduction and change

`baseline-reproduction.json` uses the unmodified script and fake GitHub state
machine from main58a3a546c758378d1887aeeaa99b4bd4d3a25c77 in a temporary synthetic
Git repository. Keeping the newly created matching draft absent from tag/list
queries reproduces `TypeError: 'NoneType' object is not subscriptable` after only
the tag and draft creation, with no uploaded assets. No remote request was made.

The corrected script creates a draft through the REST endpoint, retains and
checks its returned release ID, and confirms via that ID before touching assets.
404/null reads are bounded to four attempts at0/1/2/4 seconds. A lost creation
response permits only read-only tag/list/ID reconciliation; no second creation
POST occurs in that invocation. Bad IDs, wrong source/notes, malformed field
types, ambiguous drafts and authentication failures reject safely. Exhaustion
returns an explicit stage-specific `ReleaseError` and preserves the remote
state. Upload and final publication each require a valid ID readback; no `None`
value reaches release field indexing.

Existing same-source drafts still download and compare every existing asset
before filling only missing ones. Published releases remain read-only. No tag,
Release, public attachment, plan version, model, product or workflow was changed
by these local tests. The workflow's concurrency group protects its own same-tag
jobs; manual/external modifications can still race tag-addressed mutation
commands. This is not an atomic remote publication claim.

## Local validation

`local-tests.log` contains all30 passing tests (the original17 plus13 additional
failure/recovery cases). They exercise hidden list visibility, delayed/missing
ID reads, bounded failure then next-invocation resume, null/invalid/lost creation
responses, creation/confirmation401/403, invalid ID/type/source/body, foreign
draft refusal, missing post-upload/publication reads, partial upload recovery,
published immutability and original real-Git archive constraints. Test clocks
record delays without sleeping; fake mutations are never GitHub writes.

Python compilation and `git diff --check` passed. An independent read-only review
ran the initial26-test suite without finding a blocking defect; four additional
tests directly cover its suggested identity/authentication/foreign-draft cases
and actual partial-upload failure. Source hashes in `manifest.json` identify the
tested script and tests. This evidence does not claim CI, new publication,
Android runtime, ARM/S25 or formal signing acceptance.
