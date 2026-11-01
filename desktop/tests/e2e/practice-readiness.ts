import { expect, type Locator } from "@playwright/test";
/** Native startup has a 15s supervised deadline. Keep ordinary assertions at their default. */
export async function expectPracticeReady(locator: Locator) {
  await expect(locator).toBeVisible({ timeout: 15_000 });
  await expect(locator).toBeEnabled();
}
