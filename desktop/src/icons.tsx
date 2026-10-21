import type { CSSProperties } from "react";
export type IconName =
  | "command"
  | "tree"
  | "mission"
  | "library"
  | "arrow"
  | "chevron"
  | "check"
  | "lock"
  | "sun"
  | "moon"
  | "settings"
  | "close"
  | "plus"
  | "minus"
  | "target"
  | "code"
  | "bolt"
  | "shield"
  | "clock"
  | "search"
  | "refresh"
  | "wifi"
  | "alert"
  | "expand"
  | "play"
  | "bookmark"
  | "layers"
  | "info";
const paths: Record<IconName, string> = {
  command: "M12 3 21 8v8l-9 5-9-5V8l9-5Zm0 0v18M3 8l9 5 9-5M3 16l9-5 9 5",
  tree: "M12 3v6m-7 5v-3h14v3M12 9v8M3 15h4v4H3v-4Zm7 3h4v4h-4v-4Zm7-3h4v4h-4v-4ZM10 1h4v4h-4V1Z",
  mission: "M5 4h14v17H5V4Zm4-2h6v4H9V2Zm0 8h6m-6 4h6m-6 4h3",
  library: "M3 4h5v16H3V4Zm7 0h4v16h-4V4Zm6 1 4-1 3 15-4 1-3-15Z",
  arrow: "M4 12h16m-6-6 6 6-6 6",
  chevron: "m9 5 7 7-7 7",
  check: "m5 12 4 4 10-10",
  lock: "M6 10h12v11H6V10Zm3 0V6a3 3 0 0 1 6 0v4m-3 5v2",
  sun: "M12 1v3m0 16v3M1 12h3m16 0h3M4 4l2 2m12 12 2 2M4 20l2-2M18 6l2-2M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0Z",
  moon: "M20 15A9 9 0 0 1 9 4a9 9 0 1 0 11 11Z",
  settings: "M4 6h16M4 12h16M4 18h16M8 3v6m8 0v6M10 15v6",
  close: "m6 6 12 12M6 18 18 6",
  plus: "M5 12h14M12 5v14",
  minus: "M5 12h14",
  target: "M21 12a9 9 0 1 1-9-9m5 9a5 5 0 1 1-5-5m0 5 9-9m-5 0h5v5",
  code: "m8 6-6 6 6 6m8-12 6 6-6 6M14 3l-4 18",
  bolt: "m14 2-9 12h7l-2 8 9-13h-7l2-7Z",
  shield: "m12 2 8 3v7c0 5-8 10-8 10S4 17 4 12V5l8-3Z",
  clock: "M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Zm-9-5v5l3 2",
  search: "M16 10a6 6 0 1 1-12 0 6 6 0 0 1 12 0Zm-1 5 6 6",
  refresh: "M20 8a9 9 0 0 0-16 0m0 0V3m0 5h5m-5 8a9 9 0 0 0 16 0m0 0v5m0-5h-5",
  wifi: "M3 8a15 15 0 0 1 18 0M6 12a10 10 0 0 1 12 0m-9 4a5 5 0 0 1 6 0m-3 4h.01",
  alert: "m12 3 10 18H2L12 3Zm0 6v5m0 3v1",
  expand: "M3 9V3h6m6 0h6v6M3 15v6h6m6 0h6v-6",
  play: "m7 3 14 9-14 9V3Z",
  bookmark: "M6 3h12v19l-6-4-6 4V3Z",
  layers: "m12 2 10 6-10 6L2 8l10-6ZM2 12l10 6 10-6M2 16l10 6 10-6",
  info: "M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Zm-9-1v6m0-10v1",
};
export function Icon({
  name,
  size = 20,
  style,
}: {
  name: IconName;
  size?: number;
  style?: CSSProperties;
}) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.5"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      style={style}
    >
      <path d={paths[name]} />
    </svg>
  );
}
export function Mark() {
  return (
    <svg viewBox="0 0 40 46" className="brand-mark" aria-hidden="true">
      <path d="M20 2 37 12v21L20 43 3 33V12Z" />
      <path d="m26 14-6-4-10 6v14l10 6 6-4M20 19l10 6-10 6V19Z" />
    </svg>
  );
}
