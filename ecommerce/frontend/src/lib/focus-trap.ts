/** Elements that take the keyboard focus inside a dialog. */
export const FOCUSABLE_SELECTOR = [
  "a[href]",
  "button:not([disabled])",
  "input:not([disabled]):not([type='hidden'])",
  "select:not([disabled])",
  "textarea:not([disabled])",
  "[tabindex]:not([tabindex='-1'])",
].join(",");

/**
 * Index of the element to focus when Tab (or Shift+Tab) would leave the dialog, or null to let the
 * browser move the focus normally. The focus wraps from the last element to the first and back; focus
 * outside the dialog (index -1) is brought back in.
 *
 * @param current index of the focused element among the focusable ones, -1 when it is not one of them
 */
export function wrapFocus(count: number, current: number, backwards: boolean): number | null {
  if (count === 0) {
    return null;
  }
  if (current === -1) {
    return backwards ? count - 1 : 0;
  }
  if (backwards && current === 0) {
    return count - 1;
  }
  if (!backwards && current === count - 1) {
    return 0;
  }
  return null;
}
