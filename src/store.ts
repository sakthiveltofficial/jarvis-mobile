// Conversation + connection state, fed by the native bridge's events.
import { useEffect, useSyncExternalStore } from 'react';
import Bridge, {
  type BridgeSnapshot, type BridgeStatus, type Capabilities, type DesktopEvent, type PhoneAction,
} from '../modules/jarvis-bridge';

export type Msg =
  | { kind: 'user' | 'assistant'; id: string; text: string; proactive?: string }
  | { kind: 'info' | 'error'; id: string; text: string }
  | { kind: 'tool'; id: string; tool: string; summary: string; done: boolean; ok?: boolean; text?: string }
  | { kind: 'confirm'; id: string; tool: string; summary: string; reason: string; done: boolean; answer?: boolean }
  | { kind: 'memory'; id: string; count: number };

type State = {
  snap: BridgeSnapshot;
  msgs: Msg[];
  agentState: string;
  level: number;
  phoneSpeaking: boolean;
  actions: PhoneAction[];
};

let n = 0;
const uid = () => `m${Date.now().toString(36)}${(n++).toString(36)}`;

function safeSnap(): BridgeSnapshot {
  try {
    return Bridge.getState();
  } catch {
    return {
      status: { state: 'unpaired' }, serviceRunning: false, pairing: null,
      settings: { speakOnPhone: true, forwardNotifications: false, autoStart: true },
      capabilities: {} as Capabilities,
    };
  }
}

let state: State = { snap: safeSnap(), msgs: [], agentState: 'idle', level: 0, phoneSpeaking: false, actions: [] };
const subs = new Set<() => void>();
const set = (patch: Partial<State>) => {
  state = { ...state, ...patch };
  subs.forEach((f) => f());
};

function apply(msgs: Msg[], ev: DesktopEvent): Msg[] {
  switch (ev.type) {
    case 'user':
      return [...msgs, { kind: 'user', id: uid(), text: ev.text }];
    case 'assistant':
      return [...msgs, { kind: 'assistant', id: uid(), text: ev.text, proactive: ev.proactive }];
    case 'info':
    case 'error':
      return [...msgs, { kind: ev.type, id: uid(), text: ev.text }];
    case 'tool_start':
      if (ev.agent) return msgs;
      return [...msgs, { kind: 'tool', id: ev.id, tool: ev.tool, summary: ev.summary || '', done: false }];
    case 'tool_end':
      return msgs.map((m) => (m.kind === 'tool' && m.id === ev.id ? { ...m, done: true, ok: ev.ok, text: ev.text } : m));
    case 'confirm':
      return [...msgs, { kind: 'confirm', id: ev.id, tool: ev.tool, summary: ev.summary || '', reason: ev.reason || '', done: false }];
    case 'confirm_done':
      return msgs.map((m) => (m.kind === 'confirm' && m.id === ev.id ? { ...m, done: true } : m));
    case 'memory_search':
      return ev.nodes?.length ? [...msgs, { kind: 'memory', id: uid(), count: ev.nodes.length }] : msgs;
    default:
      return msgs;
  }
}

let started = false;
export function startStore() {
  if (started) return;
  started = true;
  try {
    let msgs: Msg[] = [];
    for (const ev of Bridge.history()) msgs = apply(msgs, ev);
    set({ msgs: msgs.slice(-200), actions: Bridge.actions().slice().reverse() });
  } catch {}
  Bridge.addListener('onStatus', (s: BridgeStatus) => set({ snap: { ...safeSnap(), status: s } }));
  Bridge.addListener('onEvent', (ev: DesktopEvent) => {
    if (ev.type === 'state') return set({ agentState: ev.state });
    if (ev.type === 'phone_audio') return set({ phoneSpeaking: !!ev.playing });
    const msgs = apply(state.msgs, ev);
    if (msgs !== state.msgs) set({ msgs: msgs.slice(-200) });
  });
  Bridge.addListener('onAction', (a: PhoneAction) => set({ actions: [a, ...state.actions].slice(0, 100) }));
  Bridge.addListener('onLevel', (l: { value: number }) => set({ level: l.value }));
}

export function refresh() {
  set({ snap: safeSnap() });
}

export function answerConfirm(id: string, approve: boolean) {
  Bridge.confirm(id, approve);
  set({ msgs: state.msgs.map((m) => (m.kind === 'confirm' && m.id === id ? { ...m, done: true, answer: approve } : m)) });
}

export function clearChat() {
  set({ msgs: [] });
}

export function useStore<T>(sel: (s: State) => T): T {
  return useSyncExternalStore(
    (f) => {
      subs.add(f);
      return () => subs.delete(f);
    },
    () => sel(state),
  );
}

/** Re-reads capabilities when the app returns to the foreground (after a trip to Android settings). */
export function useRefreshOnFocus() {
  useEffect(() => {
    const { AppState } = require('react-native');
    const sub = AppState.addEventListener('change', (s: string) => s === 'active' && refresh());
    return () => sub.remove();
  }, []);
}
