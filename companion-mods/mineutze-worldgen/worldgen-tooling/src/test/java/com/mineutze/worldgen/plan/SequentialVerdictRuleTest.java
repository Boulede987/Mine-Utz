package com.mineutze.worldgen.plan;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SequentialVerdictRuleTest {

    private static final FeasibilityBudget BUDGET = new FeasibilityBudget(Duration.ofMinutes(2), 4, 0.01, 0.99);
    private static final SequentialVerdictRule PRODUCTION_RULE = new SequentialVerdictRule(new ProductionFailureModel(200, 5), BUDGET);
    private static final SequentialVerdictRule NO_REFINEMENT_RULE = new SequentialVerdictRule(new ProductionFailureModel(200, 0), BUDGET);

    private static StageEvidence evidence(int successes, int trials) {
        return new StageEvidence(trials, successes, successes == trials ? OptionalInt.empty() : OptionalInt.of(1));
    }

    private static Evidence evidence(StageEvidence coarse, StageEvidence refinement, StageEvidence realization) {
        return new Evidence(coarse, refinement, realization);
    }

    @Test
    void passesWhenRetriedStagesAreCertifiedAndEveryRenderWorked() {
        Evidence strong = evidence(evidence(50, 50), evidence(50, 50), evidence(3, 3));

        assertEquals(Optional.of(FeasibilityVerdict.Outcome.PASS), PRODUCTION_RULE.decide(strong, SequentialVerdictRule.Deadline.NOT_REACHED));
    }

    /** 0 of 400 coarse: per-attempt rate is at most ~1.1% at 99%, so 200 tries miss at least ~10% of worlds. */
    @Test
    void failsWhenTheCoarseStageIsCertainlyTooRare() {
        Evidence hopeless = evidence(evidence(0, 400), StageEvidence.EMPTY, StageEvidence.EMPTY);

        assertEquals(Optional.of(FeasibilityVerdict.Outcome.FAIL), PRODUCTION_RULE.decide(hopeless, SequentialVerdictRule.Deadline.NOT_REACHED));
    }

    @Test
    void keepsGoingWhileTheEvidenceIsInconclusive() {
        Evidence thin = evidence(evidence(0, 5), StageEvidence.EMPTY, StageEvidence.EMPTY);

        assertEquals(Optional.empty(), PRODUCTION_RULE.decide(thin, SequentialVerdictRule.Deadline.NOT_REACHED));
    }

    @Test
    void isBorderlineWhenTheBudgetRunsOutUndecided() {
        Evidence thin = evidence(evidence(0, 5), StageEvidence.EMPTY, StageEvidence.EMPTY);

        assertEquals(Optional.of(FeasibilityVerdict.Outcome.BORDERLINE), PRODUCTION_RULE.decide(thin, SequentialVerdictRule.Deadline.REACHED));
    }

    @Test
    void aSingleFailedRenderBlocksPass() {
        Evidence oneBadRender = evidence(evidence(50, 50), evidence(50, 50), evidence(2, 3));

        assertEquals(Optional.empty(), PRODUCTION_RULE.decide(oneBadRender, SequentialVerdictRule.Deadline.NOT_REACHED));
    }

    @Test
    void noRenderYetBlocksPass() {
        Evidence notRendered = evidence(evidence(50, 50), evidence(50, 50), StageEvidence.EMPTY);

        assertEquals(Optional.empty(), PRODUCTION_RULE.decide(notRendered, SequentialVerdictRule.Deadline.NOT_REACHED));
    }

    @Test
    void rendersThatKeepFailingFail() {
        Evidence badRenders = evidence(evidence(50, 50), evidence(50, 50), evidence(0, 5));

        assertEquals(Optional.of(FeasibilityVerdict.Outcome.FAIL), PRODUCTION_RULE.decide(badRenders, SequentialVerdictRule.Deadline.NOT_REACHED));
    }

    @Test
    void tooFewRefinementSuccessesCannotCertifyItsFiveRetries() {
        Evidence fewRefinements = evidence(evidence(50, 50), evidence(3, 3), evidence(3, 3));

        assertEquals(Optional.empty(), PRODUCTION_RULE.decide(fewRefinements, SequentialVerdictRule.Deadline.NOT_REACHED));
    }

    @Test
    void withoutARefinementStagePassNeedsNoRefinementTrials() {
        Evidence directSolve = evidence(evidence(50, 50), StageEvidence.EMPTY, evidence(1, 1));

        assertEquals(Optional.of(FeasibilityVerdict.Outcome.PASS), NO_REFINEMENT_RULE.decide(directSolve, SequentialVerdictRule.Deadline.NOT_REACHED));
    }

    @Test
    void certifiedUpperBoundIsNotBelowTheLowerBoundWhenEveryRenderWorked() {
        Evidence mixed = evidence(evidence(3, 40), evidence(4, 6), evidence(1, 1));

        assertTrue(PRODUCTION_RULE.certifiedWorldFailureUpperBound(mixed) >= PRODUCTION_RULE.worldFailureLowerBound(mixed));
    }
}
