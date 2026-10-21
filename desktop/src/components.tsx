import { useEffect, useRef, type ReactNode } from "react";
import { Icon, type IconName } from "./icons";
export function Badge({
  children,
  tone = "neutral",
}: {
  children: ReactNode;
  tone?: string;
}) {
  return <span className={`badge ${tone}`}>{children}</span>;
}
export function Button({
  children,
  onClick,
  icon,
  secondary = false,
  disabled = false,
}: {
  children: ReactNode;
  onClick?: () => void;
  icon?: IconName;
  secondary?: boolean;
  disabled?: boolean;
}) {
  return (
    <button
      className={secondary ? "button secondary" : "button primary"}
      onClick={onClick}
      disabled={disabled}
    >
      {children}
      {icon && <Icon name={icon} size={16} />}
    </button>
  );
}
export function SectionTitle({
  label,
  title,
  action,
}: {
  label?: string;
  title: string;
  action?: ReactNode;
}) {
  return (
    <div className="section-title">
      <div>
        {label && <p className="eyebrow">{label}</p>}
        <h2>{title}</h2>
      </div>
      {action}
    </div>
  );
}
export function Dialog({
  title,
  eyebrow = "DESIGN PREVIEW",
  children,
  onClose,
  wide = false,
}: {
  title: string;
  eyebrow?: string;
  children: ReactNode;
  onClose: () => void;
  wide?: boolean;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const close = useRef(onClose);
  close.current = onClose;
  useEffect(() => {
    const el = ref.current!;
    const previous = document.activeElement as HTMLElement | null;
    el.showModal();
    return () => {
      el.close();
      previous?.focus();
    };
  }, []);
  return (
    <dialog
      ref={ref}
      className={wide ? "dialog wide" : "dialog"}
      aria-labelledby="dialog-title"
      onCancel={(e) => {
        e.preventDefault();
        close.current();
      }}
    >
      <div className="dialog-header">
        <div>
          <p className="eyebrow">{eyebrow}</p>
          <h2 id="dialog-title">{title}</h2>
        </div>
        <button
          className="icon-button"
          aria-label="Close dialog"
          onClick={onClose}
        >
          <Icon name="close" />
        </button>
      </div>
      <div className="dialog-content">{children}</div>
    </dialog>
  );
}
export function StateView({
  kind,
  onReset,
}: {
  kind: "empty" | "loading" | "error";
  onReset: () => void;
}) {
  return (
    <section
      className={`state-view state-${kind}`}
      aria-busy={kind === "loading"}
    >
      <div className="state-symbol">
        <Icon
          name={
            kind === "error" ? "alert" : kind === "empty" ? "target" : "layers"
          }
          size={35}
        />
      </div>
      <p className="eyebrow">SIMULATED {kind.toUpperCase()} STATE</p>
      <h2>
        {kind === "empty"
          ? "Your next chapter is unwritten."
          : kind === "loading"
            ? "Assembling your next horizon…"
            : "The signal was interrupted."}
      </h2>
      <p>
        {kind === "empty"
          ? "No missions match this preview. Clear your filters to explore the demonstration collection."
          : kind === "loading"
            ? "This held loading state lets you inspect the experience. No learner request is running."
            : "The example service could not respond. Your work would stay in place while you reconnect."}
      </p>
      {kind === "loading" && (
        <div className="skeletons" aria-hidden="true">
          <i />
          <i />
          <i />
        </div>
      )}
      <Button
        secondary
        onClick={onReset}
        icon={kind === "error" ? "refresh" : "arrow"}
      >
        {kind === "error"
          ? "Retry preview"
          : kind === "loading"
            ? "Finish loading preview"
            : "Explore sample missions"}
      </Button>
    </section>
  );
}

export function tabKeys(e: React.KeyboardEvent<HTMLDivElement>) {
  if (!["ArrowRight", "ArrowLeft", "Home", "End"].includes(e.key)) return;
  const tabs = Array.from(
    e.currentTarget.querySelectorAll<HTMLButtonElement>(
      "[role=tab]:not(:disabled)",
    ),
  );
  const current = tabs.indexOf(document.activeElement as HTMLButtonElement);
  if (current < 0) return;
  e.preventDefault();
  const index =
    e.key === "Home"
      ? 0
      : e.key === "End"
        ? tabs.length - 1
        : (current + (e.key === "ArrowRight" ? 1 : -1) + tabs.length) %
          tabs.length;
  tabs[index].focus();
  tabs[index].click();
}
