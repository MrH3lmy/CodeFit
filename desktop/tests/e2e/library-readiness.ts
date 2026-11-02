import { expect, type Page } from "@playwright/test";
/** Only call when this flow starts/replaces the native Java session.
 * JavaSession supervises native startup for 15s; ordinary warm operations keep their default checks.
 */
export async function expectNativeLibrary(
  page: Page,
  expected = "8 deck descriptions",
) {
  await expect(page.locator(".library > .status")).toContainText(expected, {
    timeout: 15_000,
  });
}
