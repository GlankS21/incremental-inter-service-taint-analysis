"use client";

import { useTranslations } from "next-intl";
import { useEffect, useId, useRef, useState, type ReactNode, type RefObject } from "react";
import { createPortal } from "react-dom";
import { FOCUSABLE_SELECTOR, wrapFocus } from "@/lib/focus-trap";

interface Props {
  open: boolean;
  onClose: () => void;
  title: string;
  children: ReactNode;
}

/**
 * Modal dialog: rendered over the page, closed with Esc, the close button or a click on the backdrop.
 * The keyboard focus stays inside while it is open and returns to where it was once closed.
 */
export function Modal({ open, onClose, title, children }: Props) {
  const [mounted, setMounted] = useState(false);
  const dialogRef = useRef<HTMLDivElement>(null);
  const onCloseRef = useRef(onClose);

  useEffect(() => {
    onCloseRef.current = onClose;
  }, [onClose]);

  useEffect(() => {
    // The portal target only exists in the browser
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setMounted(true);
  }, []);

  useEffect(() => {
    if (!open || !mounted) {
      return;
    }
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const dialog = dialogRef.current;
    const focusables = () => Array.from(dialog?.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR) ?? []);
    // The first field rather than the close button, when there is one
    const first = focusables().find((element) => element.dataset.modalClose === undefined) ?? dialog;
    first?.focus();

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        onCloseRef.current();
        return;
      }
      if (event.key !== "Tab") {
        return;
      }
      const elements = focusables();
      const target = wrapFocus(elements.length, elements.indexOf(document.activeElement as HTMLElement), event.shiftKey);
      if (target !== null) {
        event.preventDefault();
        elements[target].focus();
      }
    };
    const overflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      document.body.style.overflow = overflow;
      previous?.focus();
    };
  }, [open, mounted]);

  if (!open || !mounted) {
    return null;
  }
  return createPortal(
    <ModalDialog title={title} onClose={onClose} dialogRef={dialogRef}>
      {children}
    </ModalDialog>,
    document.body,
  );
}

/** Markup of {@link Modal}, without the portal and the keyboard handling. */
export function ModalDialog({
  title,
  onClose,
  children,
  dialogRef,
}: {
  title: string;
  onClose: () => void;
  children: ReactNode;
  dialogRef?: RefObject<HTMLDivElement | null>;
}) {
  const tCommon = useTranslations("common");
  const titleId = useId();
  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center bg-slate-900/50 p-0 sm:items-center sm:p-4" onMouseDown={(event) => {
      if (event.target === event.currentTarget) {
        onClose();
      }
    }}>
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        className="max-h-[92vh] w-full overflow-y-auto rounded-t-2xl bg-white p-5 shadow-xl outline-none sm:max-w-lg sm:rounded-2xl sm:p-6"
      >
        <div className="mb-4 flex items-start justify-between gap-3">
          <h2 id={titleId} className="text-lg font-semibold break-words text-slate-900">
            {title}
          </h2>
          <button
            type="button"
            data-modal-close=""
            onClick={onClose}
            aria-label={tCommon("close")}
            className="-m-1 rounded-lg p-1 text-slate-500 hover:bg-slate-100 hover:text-slate-700"
          >
            <svg className="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
              <path d="M6 6l12 12M18 6L6 18" strokeLinecap="round" />
            </svg>
          </button>
        </div>
        {children}
      </div>
    </div>
  );
}
