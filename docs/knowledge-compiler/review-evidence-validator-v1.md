# Independent-review evidence gate v1 (test-only)

## Boundary and existing contract

This contract validates evidence; it does not perform review, enroll reviewers, adjudicate content, publish a graph, or authorize a grader. The #253 contract uses the existing `review_attestation_v23.py` verifier with Ed25519 decisions, an externally pinned reviewer roster, exact subject fingerprints, replay controls, and a distinct owner adjudication. The main CodeFit branch does not contain that verifier or an integration point for it. The Java test fixture below models the policy with ephemeral synthetic keys; its payload format is deliberately not wire-compatible with #253 and must never be treated as a replacement verifier.

The test-only model lives under `src/test`, has no production call site, and uses synthetic original text only. It demonstrates fail-closed policy expectations and does not change `KnowledgePreview`, Java Training Pack publication/selection, learner evidence, or renderer/React capabilities. `KnowledgePreview` remains a pinned author-only preview. Even the fully populated synthetic path always returns `graderAvailable=false` and `productionActivation=false`.

## Versioned verdict contract

Schema `codefit-synthetic-review-evidence/1` pins:

- exact packet/revision identity and SHA-256 of exact manifest bytes and original content bytes;
- evaluator version and SHA-256, so content or evaluator changes stale every prior decision;
- separately signed technical and pedagogical ACCEPT decisions, with distinct enrolled identities, role-scoped keys, conflict disclosures, and unique nonces;
- an independently signed owner adjudication bound to both review decision digests;
- separate owner-signed distribution authorization and release authorization, each bound to the exact revision; release authorization binds both the adjudication and licensing evidence;
- a separately supplied trust-roster SHA-256 pin. A signature proves only possession of the registered key; it does not prove identity, independence, correctness, teaching quality, ownership, or distribution rights.

The synthetic manifest also explicitly binds the content and evaluator digests. Distribution evidence signs a license reference and `PUBLIC` scope; the later release authorization separately references the adjudication and distribution-evidence digests.

Claims embedded in author metadata (`status`, approval counts, publication or grader flags) are not evidence. The test gate rejects any author-draft record that claims approval or release. A valid verdict is an evidence classification only; this contract has no operation to import, activate, grade, grant XP, or change mastery.

## Deny-by-default status matrix

| State | Required evidence | Grading / publication |
|---|---|---|
| `AUTHOR_DRAFT` | Exact draft bytes only; no review evidence | Denied |
| `TECHNICAL_REVIEW_PENDING` | Pedagogical decision present; technical decision absent | Denied |
| `PEDAGOGY_REVIEW_PENDING` | Technical decision present; pedagogical decision absent | Denied |
| `OWNER_ADJUDICATION_PENDING` | Both distinct, valid, current ACCEPT decisions | Denied |
| `REVIEWED_UNRELEASED` | Owner adjudication accepted; distribution and/or separate release authorization absent | Denied |
| `RELEASE_AUTHORIZED` | Exact current review, adjudication, distribution authorization, and release authorization evidence validates | Still denied in this test-only implementation |
| `DENIED` | Any missing, forged, revoked, conflicting, stale, replayed, malformed, or mismatched evidence | Denied |

Any REJECT/NEEDS_REVISION decision, reviewer/author/owner identity collision, declared conflict, roster-pin mismatch, missing signature, duplicate/replayed nonce, changed content/evaluator digest, or forged approval metadata yields `DENIED`. Human acceptance remains an external prerequisite and is never inferred from tests, hashes, or a valid signature alone.

## Threat model and non-goals

The validator model exercises untrusted author metadata and evidence records against pinned exact bytes and synthetic trust credentials. Negative tests cover unsigned/tampered signatures, revoked reviewers, stale revision/evaluator, author-as-reviewer, conflicts, reviewer reuse, incomplete review, replay, roster-pin tampering, unsigned owner claims, unapproved release, and forged status flags.

It does not protect a compromised host, establish a real person’s identity, verify the truth of qualifications or rights, preserve a real signed-evidence vault, perform human source/pedagogy review, validate the #253 wire format, or provide a production signature-verification service. Production integration must call the existing #253 attestation verifier and retain its externally controlled roster pin; do not copy this synthetic verifier into application code. #253 remains open until actual independent review and owner adjudication are completed. #269 remains a separate blocked assessment/release gate.

## Manual human acceptance checklist

- [ ] Independently enroll technical and pedagogical reviewers; verify identity, scope, credentials, and conflicts outside this repository.
- [ ] Pin the exact review manifest and trusted roster through an owner-controlled out-of-band channel.
- [ ] Have reviewers assess the exact revision with authorized source access; collect distinct signed ACCEPT/REJECT/NEEDS_REVISION decisions through #253’s verifier.
- [ ] Independently reproduce the signed evidence, confirm revocation/nonce state, and manually adjudicate findings, disagreements, reviewer independence, and conflicts.
- [ ] Obtain explicit owner authorization for licensing/distribution and a separate exact-revision release decision.
- [ ] Complete #269’s separate evaluator/security gates before any future grading or learner-facing activation change.
- [ ] Keep author drafts ungraded and unpublished; retain #253 open while evidence or human decisions remain outstanding.

No actual reviewer identity, credential, signature, private key, unpublished manuscript, answer key, or learner data is included here.
