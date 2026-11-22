"use client";

import { useTranslations } from "next-intl";
import { useRouter } from "next/navigation";
import { useState, type FormEvent, type ReactNode } from "react";
import { useToast } from "@/components/toast";
import { Alert, Button, Field } from "@/components/ui";
import { api } from "@/lib/client/api";
import { useErrorMessage } from "@/lib/client/use-error-message";
import { ApiError } from "@/lib/errors";
import { formatDateTime } from "@/lib/format";
import { parseSynonymTerms } from "@/lib/search";
import type { SynonymGroup } from "@/lib/types";

const BASE_PATH = "/search/admin/synonyms";

/** A failed save: the terms that were sent (to name the one a violation "terms[2]" is about) and the error. */
interface Failure {
  terms: string[];
  error: unknown;
}

interface Props {
  groups: SynonymGroup[];
  /** Prefilled first term of the create form. */
  initialTerm?: string;
  /** Shown between the create form and the list (the list filter). */
  children?: ReactNode;
}

/** Creates, edits in place and deletes synonym groups of the product search. */
export function SynonymManager({ groups, initialTerm, children }: Props) {
  const t = useTranslations("adminSynonyms");
  const tCommon = useTranslations("common");
  const errorMessage = useErrorMessage();
  const toast = useToast();
  const router = useRouter();
  const [busy, setBusy] = useState<"create" | number | null>(null);
  const [createFailure, setCreateFailure] = useState<Failure | null>(null);
  // Remounts the create form (clearing its input) after a success
  const [createKey, setCreateKey] = useState(0);
  const [editing, setEditing] = useState<number | null>(null);
  const [editFailure, setEditFailure] = useState<Failure | null>(null);
  const [listError, setListError] = useState<unknown>(null);

  const termsError = (failure: Failure | null): string | undefined => {
    if (!failure) {
      return undefined;
    }
    if (!(failure.error instanceof ApiError)) {
      return typeof failure.error === "string" ? failure.error : undefined;
    }
    const direct = failure.error.fieldError("terms");
    if (direct) {
      return direct;
    }
    const violation = failure.error.violations.find((item) => /^terms\[\d+]$/.test(item.field));
    if (!violation) {
      return undefined;
    }
    const term = failure.terms[Number(violation.field.slice("terms[".length, -1))];
    return term ? t("termError", { term, message: violation.message }) : violation.message;
  };

  /** Message for the alert under the form: everything but the field errors. */
  const formMessage = (failure: Failure | null): string | null => {
    if (!failure || typeof failure.error === "string") {
      return null;
    }
    const { error } = failure;
    return error instanceof ApiError && error.status === 400 && error.violations.length === 0 ? error.message : errorMessage(error);
  };

  /** Sends the terms of the form; returns true when saved. */
  const save = async (text: string, group: SynonymGroup | null, fail: (failure: Failure) => void): Promise<boolean> => {
    const terms = parseSynonymTerms(text);
    if (terms.length < 2) {
      fail({ terms, error: t("tooFewTerms") });
      return false;
    }
    setBusy(group ? group.id : "create");
    try {
      const answer = group
        ? await api.send<SynonymGroup>("PUT", `${BASE_PATH}/${group.id}`, { terms })
        : await api.send<SynonymGroup>("POST", BASE_PATH, { terms });
      toast.success(answer?.message ?? (group ? t("saved") : t("created")));
      return true;
    } catch (caught) {
      fail({ terms, error: caught });
      return false;
    } finally {
      setBusy(null);
    }
  };

  const create = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setCreateFailure(null);
    const text = String(new FormData(event.currentTarget).get("terms") ?? "");
    if (await save(text, null, setCreateFailure)) {
      setCreateKey((key) => key + 1);
      if (initialTerm) {
        // Drops the ?term= prefilled from the statistics page (the new URL loads the updated list)
        router.replace("/admin/search/synonyms");
      } else {
        router.refresh();
      }
    }
  };

  const update = async (event: FormEvent<HTMLFormElement>, group: SynonymGroup) => {
    event.preventDefault();
    setEditFailure(null);
    const text = String(new FormData(event.currentTarget).get("terms") ?? "");
    if (await save(text, group, setEditFailure)) {
      setEditing(null);
      router.refresh();
    }
  };

  const remove = async (group: SynonymGroup) => {
    if (!window.confirm(t("deleteConfirm", { terms: group.terms.join(", ") }))) {
      return;
    }
    setBusy(group.id);
    setListError(null);
    try {
      const answer = await api.send<void>("DELETE", `${BASE_PATH}/${group.id}`);
      toast.success(answer?.message ?? t("deleted"));
      router.refresh();
    } catch (caught) {
      setListError(caught);
    } finally {
      setBusy(null);
    }
  };

  const createError = termsError(createFailure);
  const createMessage = formMessage(createFailure);
  const editError = termsError(editFailure);
  const editMessage = formMessage(editFailure);

  return (
    <div className="flex flex-col gap-6">
      <form key={createKey} onSubmit={(event) => void create(event)} className="card flex flex-col gap-4 p-6">
        <h2 className="font-semibold text-slate-900">{t("newTitle")}</h2>
        <Field label={t("terms")} htmlFor="synonym-terms" hint={t("termsHint")} error={createError} required>
          <input
            id="synonym-terms"
            name="terms"
            required
            maxLength={1300}
            defaultValue={initialTerm ? `${initialTerm}, ` : ""}
            placeholder={t("termsPlaceholder")}
            autoComplete="off"
            autoFocus={Boolean(initialTerm)}
            aria-invalid={createError ? true : undefined}
            className={`input ${createError ? "input-invalid" : ""}`}
          />
        </Field>
        {createMessage && <Alert tone="danger">{createMessage}</Alert>}
        <div>
          <Button type="submit" loading={busy === "create"} disabled={busy !== null}>
            {t("create")}
          </Button>
        </div>
      </form>

      {children}
      {listError !== null && <Alert tone="danger">{errorMessage(listError)}</Alert>}

      {groups.length > 0 && (
        <div className="card overflow-x-auto">
          <table className="table">
            <thead>
              <tr>
                <th>{t("terms")}</th>
                <th>{t("updatedAt")}</th>
                <th className="text-right">{t("actions")}</th>
              </tr>
            </thead>
            <tbody>
              {groups.map((group) =>
                editing === group.id ? (
                  <tr key={group.id}>
                    <td colSpan={3}>
                      <form onSubmit={(event) => void update(event, group)} className="flex flex-col gap-2 py-1">
                        <input
                          name="terms"
                          required
                          maxLength={1300}
                          defaultValue={group.terms.join(", ")}
                          aria-label={t("editLabel", { terms: group.terms.join(", ") })}
                          aria-invalid={editError ? true : undefined}
                          autoComplete="off"
                          // The field replaces the row whose "Edit" button was just pressed
                          autoFocus
                          className={`input ${editError ? "input-invalid" : ""}`}
                        />
                        {editError && <p className="text-xs text-rose-600">{editError}</p>}
                        {editMessage && <Alert tone="danger">{editMessage}</Alert>}
                        <div className="flex gap-2">
                          <Button type="submit" size="sm" loading={busy === group.id} disabled={busy !== null}>
                            {tCommon("save")}
                          </Button>
                          <Button type="button" size="sm" variant="ghost" disabled={busy !== null} onClick={() => setEditing(null)}>
                            {tCommon("cancel")}
                          </Button>
                        </div>
                      </form>
                    </td>
                  </tr>
                ) : (
                  <tr key={group.id}>
                    <td>
                      <ul className="flex flex-wrap gap-1.5">
                        {group.terms.map((term) => (
                          <li key={term} className="rounded-full bg-slate-100 px-2.5 py-0.5 text-sm text-slate-700">
                            {term}
                          </li>
                        ))}
                      </ul>
                    </td>
                    <td className="whitespace-nowrap text-slate-600">{formatDateTime(group.updatedAt)}</td>
                    <td className="text-right whitespace-nowrap">
                      <div className="flex justify-end gap-3 text-sm">
                        <button
                          type="button"
                          className="link"
                          disabled={busy !== null}
                          onClick={() => {
                            setEditFailure(null);
                            setEditing(group.id);
                          }}
                        >
                          {tCommon("edit")}
                        </button>
                        <button
                          type="button"
                          className="text-rose-600 hover:underline disabled:text-slate-400"
                          disabled={busy !== null}
                          onClick={() => void remove(group)}
                        >
                          {tCommon("delete")}
                        </button>
                      </div>
                    </td>
                  </tr>
                ),
              )}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
