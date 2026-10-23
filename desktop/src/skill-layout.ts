/** Presentation geometry only. No learner state, eligibility or mastery decisions. */
export interface Point {
  id: string;
  x: number;
  y: number;
}
export function spatialNeighbor<T extends Point>(
  nodes: readonly T[],
  id: string,
  key: string,
): T | undefined {
  const here = nodes.find((n) => n.id === id);
  if (!here) return;
  if (key === "Home") return nodes[0];
  if (key === "End") return nodes.at(-1);
  const direction = (
    {
      ArrowRight: [1, 0],
      ArrowLeft: [-1, 0],
      ArrowDown: [0, 1],
      ArrowUp: [0, -1],
    } as Record<string, number[]>
  )[key];
  if (!direction) return;
  return nodes
    .filter(
      (n) => (n.x - here.x) * direction[0] + (n.y - here.y) * direction[1] > 0,
    )
    .map((n) => ({
      node: n,
      score:
        Math.hypot(n.x - here.x, n.y - here.y) +
        2 *
          Math.abs(
            (n.x - here.x) * direction[1] - (n.y - here.y) * direction[0],
          ),
    }))
    .sort((a, b) => a.score - b.score || a.node.id.localeCompare(b.node.id))[0]
    ?.node;
}
export function focusSkill(id: string) {
  const node = document.getElementById(id);
  node?.focus({ preventScroll: true });
  node?.scrollIntoView({
    block: "nearest",
    inline: "nearest",
    behavior: preferredScroll(),
  });
}
export function preferredScroll(): ScrollBehavior {
  return window.matchMedia("(prefers-reduced-motion: reduce)").matches
    ? "instant"
    : "smooth";
}
export function layoutGraph(
  nodes: readonly { ref: { id: string } }[],
  edges: readonly { prerequisite: { id: string }; dependent: { id: string } }[],
) {
  const levels = new Map<string, number>();
  const remaining = new Set(nodes.map((n) => n.ref.id));
  while (remaining.size) {
    const ready = [...remaining]
      .filter((id) =>
        edges
          .filter((e) => e.dependent.id === id)
          .every((e) => levels.has(e.prerequisite.id)),
      )
      .sort();
    if (!ready.length) throw new Error("Invalid graph topology");
    for (const id of ready) {
      levels.set(
        id,
        Math.max(
          -1,
          ...edges
            .filter((e) => e.dependent.id === id)
            .map((e) => levels.get(e.prerequisite.id)!),
        ) + 1,
      );
      remaining.delete(id);
    }
  }
  const max = Math.max(0, ...levels.values());
  const rows = Array.from({ length: max + 1 }, (_, i) =>
    [...levels].filter(([, l]) => l === i).map(([id]) => id),
  );
  const width = Math.max(900, ...rows.map((r) => r.length * 240 + 120));
  const height = Math.max(640, (max + 1) * 150 + 110);
  const points = nodes.map((n) => {
    const level = levels.get(n.ref.id)!,
      row = rows[level];
    return {
      id: n.ref.id,
      x: (width * (row.indexOf(n.ref.id) + 1)) / (row.length + 1),
      y: height - 95 - (level * (height - 190)) / Math.max(1, max),
    };
  });
  return { width, height, points };
}
