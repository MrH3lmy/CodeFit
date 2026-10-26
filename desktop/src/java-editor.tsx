import { useRef } from "react";

const tokens =
  /\/\*[\s\S]*?(?:\*\/|$)|\/\/[^\n]*|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|\b(?:class|interface|record|public|private|protected|static|final|void|int|long|boolean|double|float|char|byte|short|return|if|else|for|while|new|throw|throws|try|catch|finally|import|package|extends|implements|synchronized|volatile|true|false|null|this|super|var)\b|\b\d+(?:\.\d+)?\b/g;
/** Lexical colors only, never parses, compiles or executes learner content. React escapes all text. */
export function highlightJava(source: string) {
  const parts: React.ReactNode[] = [];
  let end = 0;
  for (const m of source.matchAll(tokens)) {
    if (m.index > end) parts.push(source.slice(end, m.index));
    const token = m[0],
      kind = token.startsWith("/")
        ? "comment"
        : /^["']/.test(token)
          ? "string"
          : /^\d/.test(token)
            ? "number"
            : "keyword";
    parts.push(
      <span key={m.index} className={`java-${kind}`}>
        {token}
      </span>,
    );
    end = m.index + token.length;
  }
  parts.push(source.slice(end) + "\n");
  return parts;
}
export function JavaEditor({
  value,
  onChange,
  onSave,
}: {
  value: string;
  onChange: (s: string) => void;
  onSave: () => void;
}) {
  const painted = useRef<HTMLPreElement>(null),
    numbers = useRef<HTMLDivElement>(null);
  return (
    <div className="java-editor">
      <div className="java-lines" aria-hidden="true" ref={numbers}>
        {Array.from({ length: value.split("\n").length }, (_, i) => (
          <div key={i}>{i + 1}</div>
        ))}
      </div>
      <div className="java-code">
        <pre ref={painted} className="java-paint" aria-hidden="true">
          {highlightJava(value)}
        </pre>
        <textarea
          aria-label="Java source editor"
          aria-describedby="java-editor-help"
          spellCheck={false}
          autoCapitalize="off"
          autoCorrect="off"
          wrap="off"
          maxLength={32768}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          onScroll={(e) => {
            if (painted.current) {
              painted.current.scrollTop = e.currentTarget.scrollTop;
              painted.current.scrollLeft = e.currentTarget.scrollLeft;
            }
            if (numbers.current)
              numbers.current.scrollTop = e.currentTarget.scrollTop;
          }}
          onKeyDown={(e) => {
            if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "s") {
              e.preventDefault();
              onSave();
              return;
            }
            // Alt+Tab remains an escape path; ordinary Tab indents using Chromium's native undo stack.
            if (
              e.key === "Tab" &&
              !e.shiftKey &&
              !e.altKey &&
              !e.ctrlKey &&
              !e.metaKey
            ) {
              e.preventDefault();
              document.execCommand("insertText", false, "    ");
            }
            if (e.key === "Enter") {
              const t = e.currentTarget,
                before = t.value.slice(0, t.selectionStart),
                line = before.slice(before.lastIndexOf("\n") + 1);
              const indent =
                (line.match(/^\s*/)?.[0] ?? "") +
                (line.trimEnd().endsWith("{") ? "    " : "");
              if (indent) {
                e.preventDefault();
                document.execCommand("insertText", false, "\n" + indent);
              }
            }
          }}
        />
      </div>
    </div>
  );
}
