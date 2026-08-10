package com.codefit.service;

import com.codefit.model.InterviewMaterialType;
import com.codefit.model.InterviewRequirement;

/**
 * Resolves one {@code AVAILABLE} {@link InterviewRequirement} into an
 * {@link InterviewRequirementReadiness} from existing CodeFit progress data, for exactly one
 * {@link InterviewMaterialType}. Kept as a small internal strategy - not a public interface, DI
 * framework, or factory registry - so adding a future material type means writing one more
 * implementation and listing it in {@link InterviewReadinessService}, without touching the
 * weighting/critical-gate aggregation logic at all.
 */
interface InterviewRequirementReadinessResolver {
    boolean supports(InterviewMaterialType type);

    /** Only ever called with a requirement whose {@code getReference().type()} this resolver {@link #supports}. */
    InterviewRequirementReadiness resolve(InterviewRequirement requirement);
}
