package com.codefit.evolution.evidence;

import java.time.Instant;

/** recordedAt is ingestion time, never a replacement for the observation's source timestamp. */
public record StoredEvidence(
        long sequence, String id, Instant recordedAt, EvidenceObservation observation) {}
