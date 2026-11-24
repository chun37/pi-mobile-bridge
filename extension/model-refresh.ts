import type { ExtensionContext } from "@earendil-works/pi-coding-agent";

type Registry = ExtensionContext["modelRegistry"];
type Active = { controller: AbortController; promise: Promise<boolean> };
const active = new WeakMap<Registry, Active>();

/** Reload local credentials/configuration, not extensions or provider network catalogs.
 * Concurrent readers share one bounded operation. Never expose provider error text.
 * Returns true when some provider catalogs could not be refreshed.
 */
export function refreshModels(registry: Registry, timeoutMs = 8000): Promise<boolean> {
  const existing = active.get(registry);
  if (existing && !existing.controller.signal.aborted) return existing.promise;
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort("timeout"), timeoutMs);
  let onAbort: () => void;
  const cancelled = new Promise<never>((_, reject) => {
    onAbort = () => reject(new Error("cancelled"));
    controller.signal.addEventListener("abort", onAbort, { once: true });
  });
  const operation = Promise.resolve().then(() => registry.refresh({ allowNetwork: false, signal: controller.signal }));
  const entry: Active = { controller, promise: undefined! };
  entry.promise = Promise.race([operation, cancelled]).then(result => {
    if (controller.signal.aborted || result.aborted) throw new Error("aborted");
    if (registry.getError?.()) throw new Error("configuration");
    return result.errors.size > 0;
  }).catch(() => {
    throw new Error(controller.signal.reason === "timeout"
      ? "モデル候補の更新がタイムアウトしました。もう一度取得してください"
      : "モデル候補を更新できません。設定を確認して再取得してください");
  }).finally(() => {
    clearTimeout(timeout);
    controller.signal.removeEventListener("abort", onAbort);
    if (active.get(registry) === entry) active.delete(registry);
  });
  active.set(registry, entry);
  return entry.promise;
}

export function cancelModelRefresh(registry: Registry) {
  active.get(registry)?.controller.abort("shutdown");
}
