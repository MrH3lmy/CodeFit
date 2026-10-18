# A worker-pool capacity leak (unpublished assessment candidate)

A local batch scheduler has twelve capacity units. claim(job, units) must atomically reserve a
positive amount for a job with no active claim, only if enough units remain. A rejected claim must
leave every field unchanged. release(job) returns the active claim's units once, or zero when no
claim exists; after release that job ID may be claimed again. free() and allocated() report the two
sides of the budget. On return from every operation both are nonnegative and sum to twelve.
Inputs use nonnull job IDs; the service does not promise that two separately called getters form
one atomic snapshot while other workers are running.

The faulty version can accept duplicate reservations, leak units on repeated release and corrupt
the budget when workers submit concurrently. Reproduce a failure, repair the implementation and
explain the full operation boundary. Preserve the API and all accepted work; do not print a result
from Exercise or replace Main. Finite workloads cannot prove safety for every schedule.

This is original CodeFit author/reviewer content, not an approved or assigned pilot task. It is
not installed in the learner catalog. No learner is to be presented this form before independent
review and the protocol's separate assignment/embargo/freeze gates.
