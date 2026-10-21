// Author-authored design fixtures. Never imported from or persisted to a learner profile.
export type MissionStatus =
  | "Available"
  | "In progress"
  | "Completed"
  | "Blocked";
export interface Mission {
  id: string;
  title: string;
  subtitle: string;
  category: string;
  difficulty: string;
  duration: string;
  status: MissionStatus;
  objectives: string[];
  prerequisite: string;
  skill: string;
}
export const missions: Mission[] = [
  {
    id: "CF-024",
    title: "Trace the invisible",
    subtitle: "Find the boundary. Explain the failure. Make it hold.",
    category: "Java foundations",
    difficulty: "Focused",
    duration: "20–30 min",
    status: "In progress",
    objectives: [
      "Trace a value across method boundaries",
      "Locate the first broken assumption",
      "Explain a minimal, testable correction",
    ],
    prerequisite: "Control flow · example prerequisite",
    skill: "Debugging",
  },
  {
    id: "CF-025",
    title: "A contract worth keeping",
    subtitle: "Design a collection API that does exactly what it promises.",
    category: "Software design",
    difficulty: "Stretch",
    duration: "30–45 min",
    status: "Available",
    objectives: [
      "Describe a clear collection contract",
      "Identify a boundary case",
      "Write a focused verification",
    ],
    prerequisite: "Collections · example prerequisite",
    skill: "API design",
  },
  {
    id: "CF-026",
    title: "The edge of a loop",
    subtitle: "Turn a boundary case into a reliable mental model.",
    category: "Java foundations",
    difficulty: "Foundation",
    duration: "15–20 min",
    status: "Completed",
    objectives: [
      "Trace iteration by hand",
      "Identify an off-by-one condition",
      "Explain the loop invariant",
    ],
    prerequisite: "None in this example",
    skill: "Control flow",
  },
  {
    id: "CF-027",
    title: "When threads collide",
    subtitle: "Understand what happens between two perfectly valid operations.",
    category: "Concurrency",
    difficulty: "Advanced",
    duration: "35–50 min",
    status: "Blocked",
    objectives: [
      "Describe a possible interleaving",
      "Identify shared mutable state",
      "Reason about a synchronization boundary",
    ],
    prerequisite: "Exceptions + Collections · sample path",
    skill: "Concurrency",
  },
];
export type NodeState = "Completed" | "In progress" | "Available" | "Locked";
export interface SkillNode {
  id: string;
  name: string;
  subtitle: string;
  state: NodeState;
  x: number;
  y: number;
  icon: "code" | "layers" | "bolt" | "shield" | "target";
  parents: string[];
  description: string;
}
export const skills: SkillNode[] = [
  {
    id: "core",
    name: "Java foundations",
    subtitle: "THE ORIGIN",
    state: "Completed",
    x: 450,
    y: 555,
    icon: "code",
    parents: [],
    description:
      "Build a precise mental model of values, expressions and program flow.",
  },
  {
    id: "flow",
    name: "Control flow",
    subtitle: "REASONING",
    state: "Completed",
    x: 220,
    y: 410,
    icon: "target",
    parents: ["core"],
    description:
      "Trace decisions and loops. Explain why a program follows a particular path.",
  },
  {
    id: "objects",
    name: "Object thinking",
    subtitle: "STRUCTURE",
    state: "Available",
    x: 675,
    y: 410,
    icon: "layers",
    parents: ["core"],
    description:
      "Give responsibilities a home. Model behavior through small, explicit contracts.",
  },
  {
    id: "debug",
    name: "Debugging",
    subtitle: "CURRENT FOCUS",
    state: "In progress",
    x: 185,
    y: 235,
    icon: "bolt",
    parents: ["flow"],
    description:
      "Move from a symptom to a testable explanation. Isolate the first broken assumption.",
  },
  {
    id: "collections",
    name: "Collections",
    subtitle: "DATA & CONTRACTS",
    state: "Available",
    x: 455,
    y: 305,
    icon: "layers",
    parents: ["flow", "objects"],
    description:
      "Choose data structures by their behavior and constraints, then test the edges.",
  },
  {
    id: "exceptions",
    name: "Exceptions",
    subtitle: "RESILIENCE",
    state: "Locked",
    x: 725,
    y: 235,
    icon: "shield",
    parents: ["objects"],
    description:
      "Make failures legible. Preserve useful context across boundaries.",
  },
  {
    id: "concurrency",
    name: "Concurrency",
    subtitle: "THE NEXT HORIZON",
    state: "Locked",
    x: 455,
    y: 90,
    icon: "bolt",
    parents: ["collections", "exceptions"],
    description:
      "Reason about interleavings, ownership and shared state before adding threads.",
  },
];
export type PreviewState =
  | "ready"
  | "unknown"
  | "empty"
  | "offline"
  | "loading"
  | "error";
export const stateLabels: Record<PreviewState, string> = {
  ready: "Populated example",
  unknown: "New learner / unknown",
  empty: "Empty",
  offline: "Offline",
  loading: "Loading",
  error: "Service error",
};
