// AR tab data: things pinned in the room (notes, smart-home controls) and requests to desktop Jarvis.
import Bridge, { type ArTarget } from '../../modules/jarvis-bridge';

export type Spot = { image: string; widthM: number; offX: number; offZ: number };

export type Note = {
  id: string; kind: 'note'; text: string; place: string; created: number;
  remind: boolean; done: boolean; seen: number; spot?: Spot;
};
export type Control = { id: string; kind: 'control'; entity_id: string; name: string; domain: string; created: number; spot?: Spot };
export type Pinned = Note | Control;

export type HaState = {
  entity_id: string; name: string; domain: string; state: string; area: string;
  attrs: Record<string, any>; box?: number[] | null; confidence?: number;
};
export type FindHit = {
  object: string; where: string; scene: string; place: string; at: number; ago: string; sighting: string;
  box?: number[] | null; thumb?: string;
};

const KEY = 'ar_items';

export function loadPinned(): Pinned[] {
  try {
    const v = JSON.parse(Bridge.getString(KEY) || '[]');
    return Array.isArray(v) ? v : [];
  } catch {
    return [];
  }
}

export function savePinned(items: Pinned[]) {
  try { Bridge.setString(KEY, JSON.stringify(items)); } catch {}
  const notes = items.filter((i): i is Note => i.kind === 'note')
    .map(({ id, text, place, created, remind, done, seen }) => ({ id, text, place, created: created / 1000, remind, done, seen: seen / 1000 }));
  void req('notes_sync', { notes }, 15000);
}

export function targetsOf(items: Pinned[]): string {
  const t: ArTarget[] = items
    .filter((i) => i.spot && !(i.kind === 'note' && i.done))
    .map((i) => ({ id: i.id, image: i.spot!.image, widthM: i.spot!.widthM, offX: i.spot!.offX, offZ: i.spot!.offZ }));
  return JSON.stringify(t);
}

export function getPref(key: string, def: string): string {
  try { return Bridge.getString(key) ?? def; } catch { return def; }
}
export function setPref(key: string, v: string) {
  try { Bridge.setString(key, v); } catch {}
}

export type Reply<T> = { ok: true; data: T } | { ok: false; error: string };

/** A request to desktop Jarvis over the paired link. Never throws. */
export async function req<T = any>(op: string, args: Record<string, unknown>, timeoutMs = 60000): Promise<Reply<T>> {
  try {
    const r = JSON.parse(await Bridge.request(op, JSON.stringify(args), timeoutMs));
    return r.ok ? { ok: true, data: r.data as T } : { ok: false, error: String(r.error || 'Something went wrong') };
  } catch (e: any) {
    return { ok: false, error: e?.message || String(e) };
  }
}

export const uid = (p: string) => `${p}${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
export const slug = (s: string) => s.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 40);
