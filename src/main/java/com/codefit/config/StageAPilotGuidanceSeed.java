package com.codefit.config;

import java.util.List;

/**
 * The documented Stage A pilot set (#171), a follow-up content task split from #162 while PR #170
 * completed the reusable hint-ladder mechanism. #162's own "Initial content scope" section deferred
 * authoring guidance to this task, and the mechanism's docs (see
 * {@code docs/problem-solving-hint-ladder.md}, "Known limitations") explicitly recorded that no
 * pilot content had been seeded yet.
 *
 * <h2>Selection</h2>
 *
 * <p>These are the first ten real problems on the "A" sheet of the one approved real workbook
 * fixture ({@code data/import-fixtures/Ahmed-Junior-Training-Sheet-V7.0.xlsx} — see
 * {@code RealJuniorTrainingSheetImportTest} and {@code docs/junior-training-sheet-fixture.md}), in
 * the workbook's own row order: spreadsheet rows 11, 12, 16-23. Rows 3-10 are the workbook's own
 * "Sample Name/Link" placeholder rows and blank instructional rows, both already dropped by
 * {@link com.codefit.service.TrainingSheetWorkbookReader} before they would ever reach an importer —
 * these ten are the earliest rows a real import actually accepts as problems, which is what "the
 * very beginning of Stage A" means concretely. Their {@code (platform, externalCode)} identity
 * (e.g. {@code ("Codeforces", "CF677-D2-A")}) is the same stable identity
 * {@code RealJuniorTrainingSheetImportTest} already asserts against the real workbook, and each
 * entry's {@link Entry#sequenceOrder()} matches exactly what a real import assigns for that row
 * (row order, since the "A" sheet carries no explicit Order column) — so importing the real
 * workbook later merges into these same {@code Problem}/{@code RoadmapEntry} rows (see
 * {@code ProblemService#upsertProblem}/{@code #upsertRoadmapMembership}) rather than creating
 * duplicates, and this guidance stays attached either way.
 *
 * <h2>Content</h2>
 *
 * <p>Every field below is original, CodeFit-authored explanation of each problem's standard
 * technique, written from scratch for this pilot rather than sourced, paraphrased, or adapted from
 * any Codeforces editorial, third-party blog, or video walkthrough. {@link Entry#referenceLinks()}
 * points only at each problem's own official Codeforces statement page — never at a third-party
 * editorial — so nothing here bundles or represents copied third-party content as CodeFit's own (see
 * {@link com.codefit.model.GuidanceSource#CODEFIT}). Every entry provides all four hint-ladder
 * levels and all four required Explanation parts (idea/reasoning, pseudocode, complexity, common
 * mistakes), so {@link com.codefit.model.ProblemGuidance#hasCompleteExplanation()} holds for each
 * one once seeded (see {@code DatabaseConfig#seedStageAPilotGuidance}).
 */
final class StageAPilotGuidanceSeed {

    record Entry(int sequenceOrder, String platform, String externalCode, String title, String url, String topic,
                 String clarifyText, String observationText, String approachText, String explanationText,
                 String pseudocodeText, String complexityNotes, String commonMistakesText,
                 List<String> prerequisites, List<String> referenceLinks) {
    }

    private StageAPilotGuidanceSeed() {
    }

    static final List<Entry> PILOT_SET = List.of(
            new Entry(1, "Codeforces", "CF677-D2-A", "Vanya and Fence",
                    "https://codeforces.com/problemset/problem/677/A", "Simulation",
                    "You need the minimum width of road so that n friends can walk in a single row without "
                            + "being noticed over a fence of height h. Each friend normally takes width 1, but must "
                            + "bend down (taking width 2) if their own height exceeds h. What decides whether a "
                            + "specific friend needs width 1 or width 2?",
                    "The width one friend contributes depends only on that friend's own height compared to h - "
                            + "it never depends on any other friend's height or on the order they walk in.",
                    "Loop over the n heights once; for each one add 1 to a running total if the height is at "
                            + "most h, otherwise add 2. Print the total once the loop finishes. No sorting, arrays, "
                            + "or extra data structures are required.",
                    "Because each friend's contribution is decided by an independent per-element rule, the "
                            + "minimum total width is exactly the sum of every friend's individual contribution. "
                            + "There is no way to group or reorder friends to reduce this sum, since the rule never "
                            + "compares friends to each other - it only compares one height to the fixed fence "
                            + "height h. A single accumulating pass is therefore both correct and sufficient.",
                    "read n, h\ntotal = 0\nfor i in 1..n:\n    read a_i\n    if a_i > h:\n        total += 2\n"
                            + "    else:\n        total += 1\nprint(total)",
                    "O(n) time - one pass over the n heights. O(1) extra space beyond the running total; the "
                            + "heights don't need to be stored in an array at all.",
                    "Reading all heights into an array first and then mishandling the loop bound when summing "
                            + "them, instead of accumulating while reading. Getting the boundary condition backwards "
                            + "(using >= h instead of > h, or vice versa) for who must bend. Re-declaring or "
                            + "resetting the running total inside the loop instead of before it.",
                    List.of("Basic input parsing", "Conditional statements (if/else)"),
                    List.of("https://codeforces.com/problemset/problem/677/A")),

            new Entry(2, "Codeforces", "CF734-D2-A", "Anton and Danik",
                    "https://codeforces.com/problemset/problem/734/A", "Strings",
                    "You're given a string of n characters, each either 'A' (Anton won that game) or 'D' "
                            + "(Danik won that game). Determine who won more games overall, or whether it's a tie.",
                    "The final answer only depends on the total count of 'A' characters versus the total count "
                            + "of 'D' characters in the string - the order the games were played in never matters.",
                    "Walk through the string once, keeping two counters (or a single counter incremented for "
                            + "one letter and decremented for the other). After the pass, compare the two counts and "
                            + "print the result that matches whichever is larger, or the tie result if they're equal.",
                    "Since 'won more games' is purely a tally comparison and never depends on sequence or "
                            + "position, a single linear pass that accumulates two counts captures everything the "
                            + "problem asks about. Comparing the final counts after the pass (rather than trying to "
                            + "track a 'leader so far' mid-scan) keeps the logic simple and avoids any partial-state "
                            + "edge cases.",
                    "read n\nread s\ncountA = 0\ncountD = 0\nfor c in s:\n    if c == 'A': countA += 1\n"
                            + "    else: countD += 1\nif countA > countD: print(result for Anton)\n"
                            + "else if countD > countA: print(result for Danik)\nelse: print(tie result)",
                    "O(n) time to scan the string once, O(1) extra space (two counters) beyond storing the "
                            + "input string itself.",
                    "Forgetting the exact tie-break output text required when the counts are equal - check the "
                            + "problem statement's precise wording rather than guessing. Using a single "
                            + "increment/decrement counter but getting the sign backwards for one of the two "
                            + "letters. Off-by-one mistakes reading n versus the actual string length.",
                    List.of("Basic input parsing", "String iteration/character counting"),
                    List.of("https://codeforces.com/problemset/problem/734/A")),

            new Entry(3, "Codeforces", "CF791-D2-A", "Bear and Big Brother",
                    "https://codeforces.com/problemset/problem/791/A", "Simulation",
                    "Limak weighs a, Bob weighs b, with a <= b. Every full year, Limak's weight triples and "
                            + "Bob's weight doubles. Find how many full years must pass until Limak becomes "
                            + "strictly heavier than Bob.",
                    "Because Limak starts no heavier than Bob but grows faster every year (x3 versus x2), the "
                            + "gap is guaranteed to flip within very few years given how small the starting weights "
                            + "are - there's no need for a closed-form formula, replaying the years directly is fine.",
                    "Use a loop: while Limak's current weight is still less than or equal to Bob's, multiply "
                            + "Limak's weight by 3 and Bob's by 2, and increment a year counter. Stop as soon as "
                            + "Limak's weight exceeds Bob's, then print the year counter.",
                    "The problem describes a literal year-by-year process with fixed multipliers, so simulating "
                            + "it exactly as stated - rather than deriving a formula for when 3-fold growth "
                            + "overtakes 2-fold growth - is both the most direct translation of the statement and "
                            + "safe here, since the tiny input bounds guarantee the loop finishes almost "
                            + "immediately.",
                    "read a, b\nyears = 0\nwhile a <= b:\n    a = a * 3\n    b = b * 2\n    years += 1\n"
                            + "print(years)",
                    "O(log(b/a)) iterations in the worst case, which is tiny given the problem's small input "
                            + "bounds; O(1) space.",
                    "Using the wrong loop condition (continuing only while a < b, which stops one year too "
                            + "early when a equals b). Incrementing the year counter before applying that year's "
                            + "growth instead of after. Assuming a mathematical shortcut is required when direct "
                            + "simulation is simpler and less error-prone at this scale.",
                    List.of("Loops (while)", "Basic arithmetic"),
                    List.of("https://codeforces.com/problemset/problem/791/A")),

            new Entry(4, "Codeforces", "CF231-D2-A", "Team",
                    "https://codeforces.com/problemset/problem/231/A", "Ad Hoc",
                    "For each of n problems, you're given three 0/1 values showing whether each of three "
                            + "teammates is sure about the solution. Count how many problems have at least two "
                            + "teammates sure.",
                    "Each problem is independent of every other problem, and 'at least two of the three are "
                            + "sure' is exactly the same condition as 'the sum of the three 0/1 values is 2 or "
                            + "more' - which teammate specifically agrees never matters, only the count.",
                    "Loop over the n problems; for each, read the three values, add them together, and "
                            + "increment a result counter whenever that sum is at least 2. Print the counter after "
                            + "the loop.",
                    "Turning 'at least two out of three agree' into a numeric sum threshold removes the need to "
                            + "reason about which specific pair of teammates might agree - every combination that "
                            + "satisfies the rule produces a sum of 2 or 3, and every combination that doesn't "
                            + "produces a sum of 0 or 1, so a plain sum-and-compare check is a complete and exact "
                            + "match for the stated rule.",
                    "read n\nsolved = 0\nfor i in 1..n:\n    read x1, x2, x3\n    if x1 + x2 + x3 >= 2:\n"
                            + "        solved += 1\nprint(solved)",
                    "O(n) time - one pass over the n problems, each doing O(1) work. O(1) extra space beyond "
                            + "the running counter.",
                    "Parsing the three per-problem values incorrectly (e.g. reading them as one merged token "
                            + "instead of three separate integers). Using a strict '> 2' check instead of '>= 2', "
                            + "which would wrongly exclude the common exactly-two-agree case. Reusing a per-row sum "
                            + "variable across iterations without resetting it when the loop body is structured "
                            + "that way.",
                    List.of("Basic input parsing", "Conditional statements (if/else)"),
                    List.of("https://codeforces.com/problemset/problem/231/A")),

            new Entry(5, "Codeforces", "CF263-D2-A", "Beautiful Matrix",
                    "https://codeforces.com/problemset/problem/263/A", "Implementation",
                    "You have a 5x5 grid containing a single 1 and twenty-four 0s. You may repeatedly swap two "
                            + "adjacent rows or two adjacent columns. Find the minimum number of such swaps needed "
                            + "to move the 1 into the exact center cell.",
                    "An adjacent row swap only ever changes the 1's row position by one step, completely "
                            + "independently of its column position - and symmetrically, an adjacent column swap "
                            + "only ever changes its column position. The row and column dimensions never interact "
                            + "with each other through these moves.",
                    "Scan the grid to find the (row, column) position of the 1. Compute the distance from that "
                            + "row to the center row, and separately the distance from that column to the center "
                            + "column, and add the two distances together as the answer.",
                    "Since every allowed move changes exactly one of the two coordinates by exactly one step, "
                            + "the minimum number of row-swaps needed is precisely the row distance to the center, "
                            + "and the minimum number of column-swaps needed is precisely the column distance to "
                            + "the center - and because the two kinds of moves never help each other, these two "
                            + "minimums simply add. There is no combined or diagonal move available that could ever "
                            + "make the total any smaller.",
                    "read 5x5 grid\nfor r in 1..5:\n    for c in 1..5:\n        if grid[r][c] == 1:\n"
                            + "            foundRow = r\n            foundCol = c\nmoves = abs(foundRow - 3) + "
                            + "abs(foundCol - 3)\nprint(moves)",
                    "O(1) - the grid is a fixed 5x5 size regardless of anything else, so scanning it is "
                            + "constant work.",
                    "Mixing 1-indexed and 0-indexed coordinate conventions when computing the distance to the "
                            + "center (the center is index 3 when rows/columns are numbered 1 through 5, or index 2 "
                            + "if numbered 0 through 4) - pick one convention and use it consistently. Forgetting "
                            + "the absolute value, which can produce a negative distance for cells above or left of "
                            + "center. Assuming a single diagonal move could substitute for one row-swap plus one "
                            + "column-swap - only whole-row or whole-column swaps exist.",
                    List.of("2D array traversal", "Absolute value / distance calculations"),
                    List.of("https://codeforces.com/problemset/problem/263/A")),

            new Entry(6, "Codeforces", "CF405-D2-A", "Gravity Flip",
                    "https://codeforces.com/problemset/problem/405/A", "Sorting",
                    "You have n columns of stacked cubes with heights a_1..a_n. Gravity switches to pull "
                            + "everything to the right side of the box. Output the resulting height of each column, "
                            + "from left to right, once the cubes have resettled.",
                    "Flipping gravity horizontally rearranges which column each existing stack of cubes ends up "
                            + "in, but never creates or destroys any cubes. Once everything has fallen and packed "
                            + "against the right side with no gaps, the tallest original stacks must end up "
                            + "occupying the rightmost columns and the shortest must end up on the left.",
                    "Take the given heights, sort that list into ascending order, and print the sorted list - "
                            + "that directly gives each column's height from left (shortest) to right (tallest) "
                            + "after the flip.",
                    "Because the columns pack together with no empty gaps once gravity settles them, and "
                            + "gravity only changes which column each value ends up in (never the multiset of "
                            + "values itself), the smallest stacks must occupy the leftmost columns after a "
                            + "right-pulling flip and the largest the rightmost - which is exactly what sorting the "
                            + "original heights in ascending order reproduces, with no need to simulate individual "
                            + "cubes falling one at a time.",
                    "read n\nread a[1..n]\nsort a ascending\nprint a[1..n] separated by spaces",
                    "O(n log n) time for the sort, O(n) space to hold the array.",
                    "Sorting in descending order instead of ascending - double check which side of the box "
                            + "gravity is pulling toward and which end of the sorted list corresponds to it. "
                            + "Assuming the output should preserve the original input order instead of the sorted "
                            + "order. Spacing or formatting mistakes when printing the resulting sequence.",
                    List.of("Sorting"),
                    List.of("https://codeforces.com/problemset/problem/405/A")),

            new Entry(7, "Codeforces", "CF112-D2-A", "Petya and Strings",
                    "https://codeforces.com/problemset/problem/112/A", "Strings",
                    "You're given two strings of equal length. Compare them as if letter case didn't matter, "
                            + "and report whether the first is smaller, equal to, or greater than the second.",
                    "Case-insensitive comparison is the same as first converting both strings to one uniform "
                            + "case (e.g. all lowercase) and then doing an ordinary lexicographic comparison - no "
                            + "custom case-aware character comparator needs to be written by hand.",
                    "Convert both strings to the same case (lowercase is the simplest choice), then use the "
                            + "language's built-in string comparison on the converted strings, and map the result "
                            + "to the three required output values.",
                    "Because the requirement is explicitly 'as if case doesn't matter,' removing the case "
                            + "difference up front by normalizing both strings to one case turns the problem into "
                            + "plain lexicographic comparison, which every standard library already implements "
                            + "correctly - this avoids subtle bugs from comparing raw character codes where "
                            + "uppercase and lowercase letters sit in different ranges.",
                    "read s1, s2\ns1 = lowercase(s1)\ns2 = lowercase(s2)\nif s1 < s2: print(-1)\n"
                            + "else if s1 > s2: print(1)\nelse: print(0)",
                    "O(L) time and O(L) extra space, where L is the (equal) length of the two strings.",
                    "Comparing the original mixed-case strings directly, which gives wrong answers because "
                            + "uppercase and lowercase letters occupy different ranges in most character encodings. "
                            + "Lowercasing only one of the two strings instead of both. Swapping which comparison "
                            + "direction maps to -1 versus 1.",
                    List.of("String case conversion", "Lexicographic comparison"),
                    List.of("https://codeforces.com/problemset/problem/112/A")),

            new Entry(8, "Codeforces", "CF236-D2-A", "Boy or Girl",
                    "https://codeforces.com/problemset/problem/236/A", "Strings",
                    "You're given a username made of lowercase letters. Decide which of two fixed messages to "
                            + "print, based on whether the number of distinct letters used in the username is odd "
                            + "or even.",
                    "Only the count of distinct characters matters here, not how many times each letter "
                            + "repeats and not their order - a letter that appears five times still counts once "
                            + "toward the distinct total.",
                    "Collect the username's characters into a set so duplicates are automatically removed, "
                            + "take the size of that set, and check whether the size is odd or even to decide which "
                            + "exact required message to print (check the problem statement itself for the precise "
                            + "wording of each message).",
                    "Since the rule depends only on how many unique letters appear, deduplicating the "
                            + "characters first with a set-like structure directly produces the distinct count the "
                            + "parity check needs - there's no need to manually track which letters have already "
                            + "been seen with extra bookkeeping.",
                    "read s\ndistinctChars = set of unique characters in s\nif size(distinctChars) is odd:\n"
                            + "    print(required message for odd case)\nelse:\n"
                            + "    print(required message for even case)",
                    "O(L) time and O(min(L, alphabet size)) space, where L is the username length - the "
                            + "alphabet is small (lowercase Latin letters), so the set never grows beyond that "
                            + "bound.",
                    "Counting total character occurrences instead of distinct characters. Printing the required "
                            + "message with the wrong exact casing or punctuation - copy the required output text "
                            + "exactly as the problem statement specifies rather than approximating it. Confusing "
                            + "'odd count of distinct letters' with 'odd count of total letters.'",
                    List.of("Sets / deduplication", "Parity (odd/even) checks"),
                    List.of("https://codeforces.com/problemset/problem/236/A")),

            new Entry(9, "Codeforces", "CF59-D2-A", "Word",
                    "https://codeforces.com/problemset/problem/59/A", "Strings",
                    "You're given a word mixing uppercase and lowercase letters. Output the word rewritten "
                            + "entirely in one case - uppercase or lowercase - choosing whichever case appears "
                            + "strictly more often in the original word, and lowercase if the two counts are equal.",
                    "Only the counts of uppercase versus lowercase letters in the original word decide which "
                            + "single case to output - the letters themselves and their positions in the word stay "
                            + "exactly the same, only their case is rewritten uniformly across the whole word.",
                    "Scan the word once, counting how many characters are uppercase and how many are "
                            + "lowercase. Compare the two counts to pick the target case, then output the entire "
                            + "word converted to that one chosen case.",
                    "Because the output case is decided purely by a majority count with a defined tie-break, a "
                            + "single counting pass followed by one whole-string case conversion implements the "
                            + "rule exactly - there is no need to decide each letter's case individually, since "
                            + "every letter in the output ends up in the same chosen case regardless of what case "
                            + "it originally had.",
                    "read s\nupperCount = 0\nlowerCount = 0\nfor c in s:\n    if isUppercase(c): upperCount += 1\n"
                            + "    else: lowerCount += 1\nif upperCount > lowerCount:\n    print(uppercase(s))\n"
                            + "else:\n    print(lowercase(s))",
                    "O(L) time to scan and convert, O(L) space for the converted output string, where L is the "
                            + "word length.",
                    "Getting the tie-break backwards - an equal count of uppercase and lowercase letters must "
                            + "resolve to lowercase output, not uppercase. Converting each character based on its "
                            + "own original case instead of converting the whole word uniformly to the one chosen "
                            + "case. Using a strict comparison where a non-strict one (or vice versa) was needed "
                            + "when comparing the two counts.",
                    List.of("Character classification (uppercase/lowercase)", "String case conversion"),
                    List.of("https://codeforces.com/problemset/problem/59/A")),

            new Entry(10, "Codeforces", "CF344-D2-A", "Magnets",
                    "https://codeforces.com/problemset/problem/344/A", "Simulation",
                    "n magnets are placed left to right one at a time, each in one of two orientations ('01' "
                            + "or '10'). A magnet joins the current group if its orientation matches the previous "
                            + "magnet's (they attract); otherwise it starts a new, separate group. Count the total "
                            + "number of groups formed.",
                    "Whether a new group starts depends only on comparing each magnet's orientation to the "
                            + "orientation of the immediately preceding magnet - nothing further back in the "
                            + "sequence affects that decision.",
                    "Read the orientations in order. Start a group counter at 1, since the first magnet always "
                            + "begins a group. For every following magnet, compare it to the previous one and "
                            + "increment the counter whenever the orientation differs. Print the final counter.",
                    "Because grouping is defined purely by comparing each element to its immediate predecessor "
                            + "and that comparison propagates naturally along the sequence, a single left-to-right "
                            + "pass that only ever remembers the previous magnet's orientation is enough to "
                            + "reconstruct the entire grouping - there's no need to store or look back through the "
                            + "full history of earlier magnets.",
                    "read n\nread orientation[1..n]\ngroups = 1\nfor i in 2..n:\n"
                            + "    if orientation[i] != orientation[i-1]:\n        groups += 1\nprint(groups)",
                    "O(n) time for one pass over the n magnets. O(1) extra space if orientations are compared "
                            + "as they're read rather than all stored first (O(n) if they are stored).",
                    "Starting the group counter at 0 instead of 1, undercounting every case by one. Comparing "
                            + "each magnet to the very first magnet instead of to the one immediately before it. "
                            + "Misreading the two-character orientation values - comparing only one character "
                            + "instead of the full two-character orientation can silently treat different "
                            + "orientations as equal.",
                    List.of("Sequential/array traversal", "String equality comparison"),
                    List.of("https://codeforces.com/problemset/problem/344/A"))
    );
}
