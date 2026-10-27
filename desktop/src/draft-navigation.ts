/** No source is stored here. A mounted editor owns the leave decision. */
let guard: (() => boolean) | undefined;
export function registerDraftGuard(next: () => boolean): () => void {
  guard = next;
  return () => {
    if (guard === next) guard = undefined;
  };
}
export function mayLeaveDraft(): boolean {
  return guard?.() ?? true;
}
