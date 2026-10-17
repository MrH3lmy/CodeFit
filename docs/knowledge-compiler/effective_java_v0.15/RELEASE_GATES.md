# v0.15 release gates — ALL BLOCKED

- [ ] Independent technical review of all 61 deep original drafts and the Java21/JMM nuances: failure context redaction, failure atomicity versus external payments, correct handling of interruption, volatile visibility versus compound atomicity, callback reentrancy/lock-order hazards.
- [ ] Independent instructional review of all 20 new mission drafts, with rubric thresholds, accepted responses and realistic wrong responses.
- [ ] Isolated, resource-constrained held-out grading experiments with appropriate sandboxing, hidden cases and adversarial submissions. Not implemented.
- [ ] Real concurrency stress/model-checking and race/deadlock analysis before any learner grading. Java fixture passing does not prove thread safety.
- [ ] Copyright and privacy review; no private PDFs or extracted publisher text/code or Drive identifiers in public handoff.
- [ ] Explicit authorization and independent review before production integration, XP, rewards, mastery, learner release or branch merge.

Author-level validation: 158 bounded-group Python tests passed, 73 Java21 behavioral assertions, 15 javac-negative cases, 5 source fingerprints. No independent content approval has occurred.
