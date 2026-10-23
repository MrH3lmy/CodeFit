# Hunter author-only curriculum preview — v0.41

**Unreviewed and non-gradable.** No XP, rewards, verified mastery or production import. All stage prompts are original paraphrased author work; publisher source examples and solutions are deliberately absent.

| Item | Proposed skill | Predict | Debug | Build | Defend |
|---|---|---|---|---|---|
| 57 | Scope local state | Predict loop-local variable visibility | Diagnose stale iterator in two independent batches | Implement scoped batch summary and stable bound | Explain exceptions for checked errors and local `var` |
| 58 | Enhanced iteration | Predict Cartesian pairing count | Diagnose incorrect nested iterator advance and unsafe removal | Implement route pairing, filtering and lockstep | Explain where indices, replacement or iterators are necessary |
| 59 | Library selection | Predict negative absolute-value and modulo edge cases | Diagnose biased coupon buckets and noncryptographic secret generation | Use bounded deterministic RNG and separate SecureRandom token generation | Defend Java21 RNG selection and reject speed folklore |
| 60 | Exact decimals | Predict binary decimal artifacts and BigDecimal scale equality | Diagnose KWD scale, rounding and double conversion defects | Allocate exact installments with explicit rounding and checked minor units | Defend decimal versus scaled-integer contracts |

## Technical nuances for independent reviewers

- Item57: checked exceptions can justify declaration outside a try block; cached bounds require stability; Java10+ local `var` does not change scope.
- Item58: `Map` is not `Iterable`; removal/replacement/lockstep have separate APIs; fail-fast iterators are not a correctness guarantee.
- Item59: Java17 introduced `RandomGenerator`; `SecureRandom` is required for secrets; historical performance claims are not Java21 benchmarks.
- Item60: `BigDecimal(String)` avoids decimal-literal binary approximation; `compareTo` and `equals` differ on scale; KWD has three decimal minor units; integer overflow must be checked.

The complete private author notes, predecessor mapping, SHA-bound source locators and Java21 fixtures are stored in the separately verified v0.41 checkpoint. Genuine independent technical and blind pedagogy review remains required (#253).
