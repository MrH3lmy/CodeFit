/** Bounded callers use this to reject duplicate JSON object keys, including escaped aliases. */
export function strictJson(text: string): unknown {
  const value: unknown = JSON.parse(text);
  const stack: (Set<string> | null)[] = [];
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (ch === "{") stack.push(new Set());
    else if (ch === "[") stack.push(null);
    else if (ch === "}" || ch === "]") stack.pop();
    else if (ch === '"') {
      const start = i++;
      while (text[i] !== '"') {
        if (text[i] === "\\") i++;
        i++;
      }
      let next = i + 1;
      while (/\s/.test(text[next] ?? "x")) next++;
      if (text[next] === ":") {
        const key = JSON.parse(text.slice(start, i + 1)) as string;
        const keys = stack.at(-1);
        if (!keys || keys.has(key)) throw new Error("DUPLICATE_KEY");
        keys.add(key);
      }
    }
  }
  return value;
}
