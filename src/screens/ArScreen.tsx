import * as Haptics from 'expo-haptics';
import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { AppState, Image, Modal, Pressable, ScrollView, StyleSheet, Switch, TextInput, View } from 'react-native';
import Bridge, {
  ArView, JarvisAR, type ArAvailability, type ArFrameEvent, type ArItem, type ArViewRef,
} from '../../modules/jarvis-bridge';
import {
  getPref, loadPinned, req, savePinned, setPref, slug, targetsOf, uid,
  type Control, type FindHit, type HaState, type Note, type Pinned,
} from '../ar/arData';
import { Icon } from '../components/Icon';
import { Card, Pill, T } from '../components/ui';
import { useStore } from '../store';
import { useTheme } from '../theme';

type Mode = 'orb' | 'look' | 'home' | 'note' | 'find';
const MODES: readonly [Mode, string, string][] = [
  ['orb', 'orb', 'Orb'], ['look', 'eye', 'Look'], ['home', 'bulb', 'Home'], ['note', 'pin', 'Notes'], ['find', 'search', 'Find'],
];
const GLANCE_MS = 20000;
const glass = 'rgba(14,16,15,0.74)';
const ink = '#f3f5f1';
const dim = 'rgba(243,245,241,0.62)';
const lime = '#d6f34a';

export function ArScreen() {
  const { t } = useTheme();
  const [avail, setAvail] = useState<ArAvailability>(() => { try { return JarvisAR.availability(); } catch { return 'unknown'; } });
  const [camera, setCamera] = useState(() => !!Bridge.capabilities().camera);
  const [active, setActive] = useState(AppState.currentState === 'active');

  useEffect(() => {
    const sub = AppState.addEventListener('change', (s) => {
      setActive(s === 'active');
      if (s === 'active') {
        try { setAvail(JarvisAR.availability()); } catch {}
        setCamera(!!Bridge.capabilities().camera);
      }
    });
    return () => sub.remove();
  }, []);

  useEffect(() => {          // ARCore answers "checking" for a moment on first launch
    if (avail !== 'checking') return;
    const id = setTimeout(() => { try { setAvail(JarvisAR.availability()); } catch {} }, 400);
    return () => clearTimeout(id);
  }, [avail]);

  if (avail === 'unsupported') {
    return (
      <Setup title="AR isn't available on this phone" body="Jarvis AR needs a phone that supports Google ARCore. Everything else in the app works as usual." />
    );
  }
  if (avail === 'not_installed' || avail === 'too_old') {
    return (
      <Setup
        title={avail === 'too_old' ? 'Update Google Play Services for AR' : 'Get Google Play Services for AR'}
        body="It's Google's free AR engine. Jarvis uses it to place the orb, notes and controls in your room."
        action={avail === 'too_old' ? 'Update' : 'Install'}
        onAction={async () => { await JarvisAR.install(); try { setAvail(JarvisAR.availability()); } catch {} }}
      />
    );
  }
  if (!camera) {
    return (
      <Setup
        title="Let Jarvis see through the camera"
        body="AR shows the orb in your room, explains what you point at, finds your things and puts controls on your lights and AC. Frames go only to your own PC."
        action="Allow camera"
        onAction={async () => { const c = await Bridge.requestPermissions('camera'); setCamera(!!c.camera); }}
      />
    );
  }
  if (!active) return <View style={{ flex: 1, backgroundColor: t.bg }} />;
  return <ArLive />;
}

function Setup({ title, body, action, onAction }: { title: string; body: string; action?: string; onAction?: () => void }) {
  const { t } = useTheme();
  return (
    <View style={{ flex: 1, padding: 12, justifyContent: 'center' }}>
      <Card style={{ gap: 12 }}>
        <Icon name="orb" size={28} color={t.text} />
        <T kind="thin" style={{ fontSize: 26, lineHeight: 31 }}>{title}</T>
        <T style={{ color: t.muted, lineHeight: 20 }}>{body}</T>
        {action && onAction ? <View style={{ flexDirection: 'row' }}><Pill primary label={action} onPress={onAction} /></View> : null}
      </Card>
    </View>
  );
}

// =====================================================================================================================
function ArLive() {
  const ar = useRef<ArViewRef>(null);
  const connected = useStore((s) => s.snap.status.state === 'connected');
  const msgs = useStore((s) => s.msgs);
  const agentState = useStore((s) => s.agentState);
  const speakOnPhone = useStore((s) => s.snap.settings.speakOnPhone);

  const [mode, setMode] = useState<Mode>('orb');
  const [frame, setFrame] = useState<ArFrameEvent>({ tracking: false, reason: '', planes: 0, items: [] });
  const [error, setError] = useState('');
  const [orbOn, setOrbOn] = useState(() => getPref('ar_orb', '1') === '1');
  const [glance, setGlance] = useState(() => getPref('ar_glance', '1') === '1');
  const [place, setPlace] = useState(() => getPref('ar_place', ''));
  const [pinned, setPinned] = useState<Pinned[]>(loadPinned);
  const [controls, setControls] = useState<Record<string, HaState>>({});
  const [busy, setBusy] = useState('');
  const [toast, setToast] = useState('');
  const [caption, setCaption] = useState<{ text: string; key: string } | null>(null);
  const [recording, setRecording] = useState(false);
  const [question, setQuestion] = useState('');
  const [query, setQuery] = useState('');
  const [found, setFound] = useState<{ text: string; hit?: FindHit } | null>(null);
  const [notePending, setNotePending] = useState<{ id: string; spot?: Note['spot']; why?: string } | null>(null);
  const [editPlace, setEditPlace] = useState(false);
  const seen = useRef<string[]>([]);
  const glancing = useRef(false);
  const lastMsg = useRef(msgs.length ? msgs[msgs.length - 1].id : '');

  const items = useMemo(() => {
    const m: Record<string, ArItem> = {};
    for (const it of frame.items) m[it.id] = it;
    return m;
  }, [frame.items]);
  const targets = useMemo(() => targetsOf(pinned), [pinned]);

  const flash = useCallback((text: string, ms = 3500) => {
    setToast(text);
    setTimeout(() => setToast((cur) => (cur === text ? '' : cur)), ms);
  }, []);

  const updatePinned = useCallback((fn: (p: Pinned[]) => Pinned[]) => {
    setPinned((prev) => {
      const next = fn(prev);
      savePinned(next);
      return next;
    });
  }, []);

  // Jarvis's replies show as a caption next to the orb
  useEffect(() => {
    const last = msgs[msgs.length - 1];
    if (!last || last.id === lastMsg.current) return;
    lastMsg.current = last.id;
    if (last.kind === 'assistant') {
      setCaption({ text: last.text, key: last.id });
      const ms = Math.min(45000, 8000 + last.text.length * 55);
      setTimeout(() => setCaption((c) => (c?.key === last.id ? null : c)), ms);
    } else if (last.kind === 'error') {
      flash(last.text, 6000);
    }
  }, [msgs, flash]);

  // "Remember what I see": a glance every 20 s so "where did I leave my keys?" has an answer later
  useEffect(() => {
    if (!glance || !connected || !frame.tracking) return;
    const tick = async () => {
      if (glancing.current || !ar.current) return;
      glancing.current = true;
      try {
        const cap = await ar.current.capture(1024);
        const r = await req<{ objects: { name: string; box?: number[] | null }[] }>('sighting', { jpegRef: cap.id, place }, 90000);
        if (r.ok && cap.tracking) {
          for (const o of r.data.objects || []) {
            if (!o.box) continue;
            const id = `seen:${slug(o.name)}`;
            await ar.current?.anchorFromCapture(cap.id, (o.box[0] + o.box[2]) / 2, (o.box[1] + o.box[3]) / 2, id);
            seen.current = [...seen.current.filter((s) => s !== id), id];
            while (seen.current.length > 40) void ar.current?.removeAnchor(seen.current.shift()!);
          }
        }
      } catch {} finally {
        glancing.current = false;
      }
    };
    const first = setTimeout(tick, 3000);
    const id = setInterval(tick, GLANCE_MS);
    return () => { clearTimeout(first); clearInterval(id); };
  }, [glance, connected, frame.tracking, place]);

  // live state for the smart-home controls on screen
  const controlIds = useMemo(() => {
    const out: { anchor: string; eid: string }[] = [];
    for (const it of frame.items) {
      if (it.id.startsWith('ha:')) out.push({ anchor: it.id, eid: it.id.slice(3) });
      else if (it.id.startsWith('ctl:')) {
        const c = pinned.find((p) => p.id === it.id) as Control | undefined;
        if (c) out.push({ anchor: it.id, eid: c.entity_id });
      }
    }
    return out;
  }, [frame.items, pinned]);
  const eidKey = controlIds.map((c) => c.eid).sort().join(',');
  useEffect(() => {
    if (!eidKey || !connected) return;
    const poll = async () => {
      const r = await req<{ states: HaState[] }>('ha_states', { entity_ids: eidKey.split(',') }, 15000);
      if (r.ok) setControls((prev) => { const n = { ...prev }; for (const s of r.data.states) n[s.entity_id] = { ...n[s.entity_id], ...s }; return n; });
    };
    void poll();
    const id = setInterval(poll, 4000);
    return () => clearInterval(id);
  }, [eidKey, connected]);

  // ------------------------------------------------------------------ actions
  async function tapScene(x: number, y: number) {
    if (!ar.current) return;
    if (mode === 'orb') {
      if (!orbOn) toggleOrb(true);
      const r = await ar.current.placeOrb(x, y);
      if (!r.ok) flash(r.error || "Couldn't place it there");
      else Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
    } else if (mode === 'note') {
      const id = uid('note:');
      setBusy('Pinning…');
      const r = await ar.current.pinAt(x, y, id, true);
      setBusy('');
      if (!r.ok) return flash(r.error || "Couldn't pin there");
      Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
      setNotePending({
        id,
        spot: r.remember && r.image ? { image: r.image, widthM: r.widthM ?? 0.3, offX: r.offX ?? 0, offZ: r.offZ ?? 0 } : undefined,
        why: r.remember ? undefined : r.why,
      });
    }
  }

  function toggleOrb(v: boolean) {
    setOrbOn(v);
    setPref('ar_orb', v ? '1' : '0');
  }

  async function look(q?: string, voice?: boolean) {
    if (!ar.current) return;
    if (!connected) return flash('Not connected to your PC.');
    setBusy('Looking…');
    try {
      const cap = await ar.current.capture(1280);
      const r = await req('look', { jpegRef: cap.id, question: q || '', wavRef: !!voice, place, speak: speakOnPhone }, 30000);
      if (!r.ok) flash(r.error, 6000);
      else Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
    } catch (e: any) {
      flash(e?.message || 'Camera not ready');
    } finally {
      setBusy('');
      setQuestion('');
    }
  }

  async function micDown() {
    if (!connected) return flash('Not connected to your PC.');
    let caps = Bridge.capabilities();
    if (!caps.microphone) caps = await Bridge.requestPermissions('microphone');
    if (!caps.microphone) return;
    try {
      Bridge.startRecording();
      setRecording(true);
      Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
    } catch {}
  }

  function micUp() {
    if (!recording) return;
    setRecording(false);
    Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Soft);
    if (mode === 'look') {
      if (Bridge.stopRecordingKeep()) void look(undefined, true);
    } else {
      Bridge.stopRecording(true);
    }
  }

  async function scanHome() {
    if (!ar.current) return;
    if (!connected) return flash('Not connected to your PC.');
    setBusy('Looking for your devices…');
    try {
      const cap = await ar.current.capture(1024);
      const r = await req<{ devices: HaState[]; text: string }>('scan_devices', { jpegRef: cap.id, place }, 90000);
      if (!r.ok) return flash(r.error, 6000);
      let placed = 0;
      for (const d of r.data.devices) {
        if (!d.box) continue;
        const a = await ar.current.anchorFromCapture(cap.id, (d.box[0] + d.box[2]) / 2, (d.box[1] + d.box[3]) / 2, `ha:${d.entity_id}`);
        if (a.ok) placed++;
      }
      setControls((prev) => { const n = { ...prev }; for (const d of r.data.devices) n[d.entity_id] = d; return n; });
      flash(placed ? `Found ${placed} device${placed === 1 ? '' : 's'}. Tap a control to use it.` : "I don't see any of your devices here. Try from another angle.");
    } catch (e: any) {
      flash(e?.message || 'Camera not ready');
    } finally {
      setBusy('');
    }
  }

  async function haCall(eid: string, action: string, extra: Record<string, unknown> = {}) {
    Haptics.selectionAsync();
    const r = await req<{ state: HaState; text: string }>('ha_call', { entity_id: eid, action, ...extra }, 20000);
    if (!r.ok) return flash(r.error, 6000);
    if (r.data.state) setControls((prev) => ({ ...prev, [eid]: { ...prev[eid], ...r.data.state } }));
  }

  async function keepControl(anchor: string, eid: string) {
    const it = items[anchor];
    const st = controls[eid];
    if (!it || !it.on || !ar.current) return;
    const id = `ctl:${slug(eid)}`;
    const r = await ar.current.pinAt(it.x, it.y + 8, id, true);
    if (!r.ok) return flash(r.error || "Couldn't keep it there");
    if (anchor.startsWith('ha:')) void ar.current.removeAnchor(anchor);
    const ctl: Control = {
      id, kind: 'control', entity_id: eid, name: st?.name || eid, domain: st?.domain || eid.split('.')[0], created: Date.now(),
      spot: r.remember && r.image ? { image: r.image, widthM: r.widthM ?? 0.3, offX: r.offX ?? 0, offZ: r.offZ ?? 0 } : undefined,
    };
    updatePinned((p) => [...p.filter((x) => x.id !== id), ctl]);
    flash(r.remember ? `${ctl.name} will be here next time.` : `Kept for now. ${r.why ?? ''}`, 5000);
  }

  function forgetControl(anchor: string) {
    void ar.current?.removeAnchor(anchor);
    if (anchor.startsWith('ctl:')) updatePinned((p) => p.filter((x) => x.id !== anchor));
  }

  function saveNote(text: string, remind: boolean) {
    const p = notePending;
    setNotePending(null);
    if (!p) return;
    if (!text.trim()) { void ar.current?.removeAnchor(p.id); return; }
    const note: Note = { id: p.id, kind: 'note', text: text.trim(), place, created: Date.now(), remind, done: false, seen: 0, spot: p.spot };
    updatePinned((x) => [...x, note]);
    flash(p.spot ? 'Pinned. It will be here when you come back.' : `Pinned for now. ${p.why ?? ''}`, 5000);
  }

  function noteDone(id: string) {
    void ar.current?.removeAnchor(id);
    updatePinned((p) => p.map((x) => (x.id === id && x.kind === 'note' ? { ...x, done: true } : x)));
  }

  function noteDelete(id: string) {
    void ar.current?.removeAnchor(id);
    updatePinned((p) => p.filter((x) => x.id !== id));
  }

  async function onTargetFound(id: string) {
    const item = pinned.find((p) => p.id === id);
    if (!item) return;
    if (item.kind === 'note') {
      Haptics.notificationAsync(Haptics.NotificationFeedbackType.Warning);
      if (!item.done) flash(`📌 ${item.text}`, 7000);
      updatePinned((p) => p.map((x) => (x.id === id && x.kind === 'note' ? { ...x, seen: Date.now() } : x)));
      if (item.remind) void req('note_seen', { id }, 10000);
    } else {
      const r = await req<{ states: HaState[] }>('ha_states', { entity_ids: [item.entity_id] }, 15000);
      if (r.ok && r.data.states[0]) setControls((prev) => ({ ...prev, [item.entity_id]: r.data.states[0] }));
    }
  }

  async function find() {
    const q = query.trim();
    if (!q) return;
    if (!connected) return flash('Not connected to your PC.');
    setBusy('Searching what I saw…');
    const r = await req<{ hits: FindHit[]; text: string }>('find', { query: q }, 30000);
    setBusy('');
    if (!r.ok) return flash(r.error, 6000);
    setFound({ text: r.data.text, hit: r.data.hits[0] });
  }

  // ------------------------------------------------------------------ render
  const orb = items.orb;
  const foundId = found?.hit ? `seen:${slug(found.hit.object)}` : '';
  const hint = !frame.tracking
    ? frame.reason || error || 'Starting the camera…'
    : frame.planes === 0 ? 'Move your phone slowly so I can map the room.' : '';
  const thinking = agentState === 'thinking' || agentState === 'acting' || agentState === 'transcribing';

  return (
    <View style={{ flex: 1, overflow: 'hidden', borderRadius: 22, marginHorizontal: 8, backgroundColor: '#000' }}>
      <ArView
        ref={ar}
        style={StyleSheet.absoluteFill}
        orbVisible={orbOn}
        showPoints={frame.planes < 2}
        targets={targets}
        onFrame={(e) => setFrame(e.nativeEvent)}
        onArStatus={(e) => setError(e.nativeEvent.state === 'error' ? e.nativeEvent.message : '')}
        onTargetFound={(e) => void onTargetFound(e.nativeEvent.id)}
      />
      <Pressable style={StyleSheet.absoluteFill} onPress={(e) => void tapScene(e.nativeEvent.locationX, e.nativeEvent.locationY)} />

      {/* things in the room */}
      {frame.items.map((it) => {
        if (!it.on) return null;
        if (it.id.startsWith('note:')) {
          const n = pinned.find((p) => p.id === it.id) as Note | undefined;
          return n && !n.done ? <NoteCard key={it.id} it={it} note={n} onDone={() => noteDone(n.id)} onDelete={() => noteDelete(n.id)} /> : null;
        }
        const ctl = controlIds.find((c) => c.anchor === it.id);
        if (ctl) {
          return (
            <ControlCard
              key={it.id} it={it} st={controls[ctl.eid]} kept={it.id.startsWith('ctl:')}
              onCall={(a, x) => haCall(ctl.eid, a, x)} onKeep={() => keepControl(it.id, ctl.eid)} onForget={() => forgetControl(it.id)}
            />
          );
        }
        if (it.id === foundId && found?.hit) return <FoundMarker key={it.id} it={it} label={found.hit.object} />;
        return null;
      })}

      {/* Jarvis speaking */}
      {caption || thinking ? (
        <View
          pointerEvents="none"
          style={[styles.caption, orb?.on && orb.y > 140
            ? { top: Math.max(8, orb.y - 150), left: 16, right: 16 }
            : { bottom: 150, left: 16, right: 16 }]}
        >
          <T style={{ color: lime, fontSize: 11, marginBottom: 3 }}>{thinking ? 'Jarvis is thinking…' : 'Jarvis'}</T>
          {caption ? <T style={{ color: ink, fontSize: 14, lineHeight: 20 }} numberOfLines={7}>{caption.text}</T> : null}
        </View>
      ) : null}

      {/* top bar */}
      <View style={{ position: 'absolute', top: 10, left: 10, right: 10, flexDirection: 'row', gap: 8, alignItems: 'center' }}>
        <Pressable onPress={() => setEditPlace(true)} style={styles.chip}>
          <Icon name="pin" size={13} color={ink} />
          <T style={{ color: ink, fontSize: 12 }} numberOfLines={1}>{place || 'Which room?'}</T>
        </Pressable>
        <View style={{ flex: 1 }} />
        <Pressable
          onPress={() => { const v = !glance; setGlance(v); setPref('ar_glance', v ? '1' : '0'); flash(v ? 'I\'ll remember what I see (a glance every 20 s).' : 'Stopped remembering what I see.'); }}
          style={[styles.chip, glance && { borderColor: lime }]}
          accessibilityLabel="Remember what I see"
        >
          <Icon name="eye" size={13} color={glance ? lime : dim} />
          <T style={{ color: glance ? lime : dim, fontSize: 12 }}>{glance ? 'Remembering' : 'Not remembering'}</T>
        </Pressable>
      </View>
      {hint || !connected ? (
        <View pointerEvents="none" style={[styles.chip, { position: 'absolute', top: 50, alignSelf: 'center' }]}>
          <T style={{ color: ink, fontSize: 12 }}>{!connected ? 'Not connected to your PC' : hint}</T>
        </View>
      ) : null}
      {toast || busy ? (
        <View pointerEvents="none" style={[styles.chip, { position: 'absolute', top: 86, alignSelf: 'center', maxWidth: '90%' }]}>
          <T style={{ color: ink, fontSize: 13 }} numberOfLines={3}>{busy || toast}</T>
        </View>
      ) : null}

      {/* bottom: mode panel + mode switcher */}
      <View style={{ position: 'absolute', left: 10, right: 10, bottom: 10, gap: 8 }}>
        {mode === 'find' && found ? (
          <View style={styles.panel}>
            <View style={{ flexDirection: 'row', gap: 10 }}>
              {found.hit?.thumb ? <Image source={{ uri: `data:image/jpeg;base64,${found.hit.thumb}` }} style={{ width: 84, height: 84, borderRadius: 12 }} /> : null}
              <View style={{ flex: 1 }}>
                <T style={{ color: ink, fontSize: 14, lineHeight: 20 }}>{found.text}</T>
                {found.hit && items[foundId]?.on ? <T style={{ color: lime, fontSize: 12, marginTop: 4 }}>It's marked in view.</T> : null}
              </View>
              <Pressable hitSlop={10} onPress={() => setFound(null)}><Icon name="x" size={16} color={dim} /></Pressable>
            </View>
          </View>
        ) : null}

        <View style={[styles.panel, { flexDirection: 'row', alignItems: 'center', gap: 8 }]}>
          {mode === 'look' ? (
            <>
              <TextInput
                value={question} onChangeText={setQuestion} onSubmitEditing={() => look(question)} returnKeyType="send"
                placeholder="Ask about what you see…" placeholderTextColor={dim} style={styles.input}
              />
              <Pressable onPress={() => look(question)} style={styles.action} accessibilityLabel="What am I looking at?">
                <Icon name="eye" size={18} color="#1b2400" />
              </Pressable>
            </>
          ) : mode === 'find' ? (
            <>
              <TextInput
                value={query} onChangeText={setQuery} onSubmitEditing={find} returnKeyType="search"
                placeholder="Where did I leave my…" placeholderTextColor={dim} style={styles.input}
              />
              <Pressable onPress={find} style={styles.action} accessibilityLabel="Find">
                <Icon name="search" size={18} color="#1b2400" />
              </Pressable>
            </>
          ) : mode === 'home' ? (
            <>
              <T style={{ color: dim, fontSize: 13, flex: 1 }}>Point at a light, AC or TV, then scan.</T>
              <Pressable onPress={scanHome} style={[styles.action, { width: undefined, paddingHorizontal: 14, flexDirection: 'row', gap: 6 }]}>
                <Icon name="scan" size={16} color="#1b2400" />
                <T kind="medium" style={{ color: '#1b2400', fontSize: 13 }}>Scan</T>
              </Pressable>
            </>
          ) : mode === 'note' ? (
            <T style={{ color: dim, fontSize: 13, flex: 1 }}>
              Tap a spot (your desk, the fridge) to pin a note. {pinned.filter((p) => p.kind === 'note' && !p.done).length || 'No'} notes pinned.
            </T>
          ) : (
            <>
              <T style={{ color: dim, fontSize: 13, flex: 1 }}>{orbOn ? 'Tap a surface to move me. Hold the mic to talk.' : 'The orb is hidden.'}</T>
              <Switch value={orbOn} onValueChange={toggleOrb} trackColor={{ false: '#555', true: lime }} thumbColor={orbOn ? '#1b2400' : '#ddd'} />
            </>
          )}
          <Pressable
            onPressIn={micDown} onPressOut={micUp}
            accessibilityLabel={mode === 'look' ? 'Hold to ask about what you see' : 'Hold to talk'}
            style={[styles.action, { backgroundColor: recording ? '#fff' : lime, transform: [{ scale: recording ? 1.1 : 1 }] }]}
          >
            <Icon name="mic" size={18} color="#1b2400" />
          </Pressable>
        </View>

        <View style={[styles.panel, { flexDirection: 'row', padding: 4 }]}>
          {MODES.map(([k, icon, label]) => {
            const on = mode === k;
            return (
              <Pressable key={k} onPress={() => setMode(k)} style={{ flex: 1, alignItems: 'center', gap: 2, paddingVertical: 7, borderRadius: 14, backgroundColor: on ? lime : 'transparent' }}>
                <Icon name={icon} size={16} color={on ? '#1b2400' : dim} />
                <T kind={on ? 'medium' : 'regular'} style={{ fontSize: 11, color: on ? '#1b2400' : dim }}>{label}</T>
              </Pressable>
            );
          })}
        </View>
      </View>

      <TextPrompt
        visible={!!notePending}
        title="Pin a note here"
        placeholder="e.g. Water the plant on Sunday"
        withRemind
        hint={notePending?.why}
        onDone={(text, remind) => saveNote(text, remind)}
      />
      <TextPrompt
        visible={editPlace}
        title="Which room are you in?"
        placeholder="Office, kitchen, bedroom…"
        initial={place}
        onDone={(text) => { setEditPlace(false); setPlace(text.trim()); setPref('ar_place', text.trim()); }}
      />
    </View>
  );
}

// ---------------------------------------------------------------------------------------------------------------------
function anchorStyle(it: ArItem, w: number) {
  return { position: 'absolute' as const, left: Math.max(6, Math.min(it.x - w / 2, 9999)), top: Math.max(6, it.y - 18), width: w };
}

function NoteCard({ it, note, onDone, onDelete }: { it: ArItem; note: Note; onDone: () => void; onDelete: () => void }) {
  const scale = Math.max(0.75, Math.min(1.1, 1.4 / Math.max(0.6, it.dist)));
  return (
    <View style={[anchorStyle(it, 190), { transform: [{ translateY: -60 }, { scale }] }]}>
      <View style={[styles.card, { borderColor: 'rgba(158,217,255,0.7)' }]}>
        <T style={{ color: ink, fontSize: 14, lineHeight: 19 }} numberOfLines={5}>{note.text}</T>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10, marginTop: 6 }}>
          <T style={{ color: dim, fontSize: 11, flex: 1 }} numberOfLines={1}>
            {note.remind ? '⏰ ' : ''}{note.place || new Date(note.created).toLocaleDateString()}
          </T>
          <Pressable hitSlop={8} onPress={onDone}><Icon name="check" size={15} color={lime} /></Pressable>
          <Pressable hitSlop={8} onPress={onDelete}><Icon name="x" size={15} color={dim} /></Pressable>
        </View>
      </View>
      <View style={styles.stem} />
    </View>
  );
}

function FoundMarker({ it, label }: { it: ArItem; label: string }) {
  return (
    <View pointerEvents="none" style={[anchorStyle(it, 150), { alignItems: 'center', transform: [{ translateY: -30 }] }]}>
      <View style={[styles.card, { borderColor: '#ffb347', paddingVertical: 6 }]}>
        <T kind="medium" style={{ color: '#ffd28a', fontSize: 13 }}>{label} · last seen here</T>
      </View>
      <View style={{ width: 22, height: 22, borderRadius: 11, borderWidth: 3, borderColor: '#ffb347', marginTop: 6 }} />
    </View>
  );
}

function ControlCard({ it, st, kept, onCall, onKeep, onForget }: {
  it: ArItem; st?: HaState; kept: boolean;
  onCall: (action: string, extra?: Record<string, unknown>) => void; onKeep: () => void; onForget: () => void;
}) {
  const [open, setOpen] = useState(false);
  if (!st) return null;
  const on = !['off', 'unavailable', 'closed', 'idle', 'standby'].includes(st.state);
  const a = st.attrs || {};
  const scale = Math.max(0.8, Math.min(1.1, 1.6 / Math.max(0.6, it.dist)));
  let detail = st.state;
  if (st.domain === 'light' && on && a.brightness_pct != null) detail = `${a.brightness_pct}%`;
  if (st.domain === 'climate') detail = `${st.state}${a.temperature != null ? ` · ${a.temperature}°` : ''}${a.current_temperature != null ? ` (room ${a.current_temperature}°)` : ''}`;
  if (st.domain === 'media_player' && on && a.volume_level != null) detail = `${st.state} · vol ${Math.round(a.volume_level * 100)}`;
  const step = (key: string, by: number, lo: number, hi: number, cur: number) => onCall('set', { [key]: Math.max(lo, Math.min(hi, Math.round((cur + by) * 10) / 10)) });

  return (
    <View style={[anchorStyle(it, 210), { transform: [{ translateY: -70 }, { scale }] }]}>
      <View style={[styles.card, { borderColor: on ? lime : 'rgba(255,255,255,0.25)' }]}>
        <Pressable onPress={() => setOpen(!open)} style={{ flexDirection: 'row', alignItems: 'center', gap: 10 }}>
          <View style={{ flex: 1 }}>
            <T kind="medium" style={{ color: ink, fontSize: 14 }} numberOfLines={1}>{st.name}</T>
            <T style={{ color: on ? lime : dim, fontSize: 12 }} numberOfLines={1}>{detail}</T>
          </View>
          <Pressable
            onPress={() => onCall(st.domain === 'media_player' || st.domain === 'climate' ? (on ? 'turn_off' : 'turn_on') : 'toggle')}
            hitSlop={6}
            style={{ width: 40, height: 40, borderRadius: 20, alignItems: 'center', justifyContent: 'center', backgroundColor: on ? lime : 'rgba(255,255,255,0.14)' }}
            accessibilityLabel={on ? `Turn off ${st.name}` : `Turn on ${st.name}`}
          >
            <Icon name="power" size={18} color={on ? '#1b2400' : ink} />
          </Pressable>
        </Pressable>
        {open ? (
          <View style={{ marginTop: 10, gap: 8 }}>
            {st.domain === 'light' ? (
              <Row label="Brightness">
                <Step icon="minus" onPress={() => step('brightness', -15, 1, 100, a.brightness_pct ?? 50)} />
                <Step icon="plus" onPress={() => step('brightness', 15, 1, 100, a.brightness_pct ?? 50)} />
                <Chip label="Warm" onPress={() => onCall('set', { color: 'warm' })} />
                <Chip label="Cool" onPress={() => onCall('set', { color: 'cool' })} />
              </Row>
            ) : null}
            {st.domain === 'climate' ? (
              <>
                <Row label="Temperature">
                  <Step icon="minus" onPress={() => step('temperature', -(a.target_temp_step || 1), a.min_temp ?? 16, a.max_temp ?? 30, a.temperature ?? 24)} />
                  <Step icon="plus" onPress={() => step('temperature', a.target_temp_step || 1, a.min_temp ?? 16, a.max_temp ?? 30, a.temperature ?? 24)} />
                </Row>
                <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: 6 }}>
                  {(a.hvac_modes || ['cool', 'heat', 'dry', 'fan_only', 'auto']).filter((m: string) => m !== 'off').map((m: string) => (
                    <Chip key={m} label={m.replace('_', ' ')} on={st.state === m} onPress={() => onCall('set', { hvac_mode: m })} />
                  ))}
                </ScrollView>
              </>
            ) : null}
            {st.domain === 'media_player' ? (
              <Row label="Volume">
                <Step icon="minus" onPress={() => onCall('volume_down')} />
                <Step icon="plus" onPress={() => onCall('volume_up')} />
                <Chip label={a.is_volume_muted ? 'Unmute' : 'Mute'} onPress={() => onCall(a.is_volume_muted ? 'unmute' : 'mute')} />
                <Chip label="⏯" onPress={() => onCall('play_pause')} />
              </Row>
            ) : null}
            {st.domain === 'fan' ? (
              <Row label="Speed">
                <Step icon="minus" onPress={() => onCall('set', { speed: Math.max(0, (a.percentage ?? 50) - 25) })} />
                <Step icon="plus" onPress={() => onCall('set', { speed: Math.min(100, (a.percentage ?? 50) + 25) })} />
              </Row>
            ) : null}
            {st.domain === 'cover' ? (
              <Row label="Blind">
                <Chip label="Open" onPress={() => onCall('open')} />
                <Chip label="Close" onPress={() => onCall('close')} />
              </Row>
            ) : null}
            <View style={{ flexDirection: 'row', gap: 8 }}>
              {!kept ? <Chip label="📌 Keep here" onPress={onKeep} /> : null}
              <Chip label={kept ? 'Remove' : 'Hide'} onPress={onForget} />
            </View>
          </View>
        ) : null}
      </View>
      <View style={styles.stem} />
    </View>
  );
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
      <T style={{ color: dim, fontSize: 12, width: 78 }}>{label}</T>
      {children}
    </View>
  );
}

function Step({ icon, onPress }: { icon: string; onPress: () => void }) {
  return (
    <Pressable onPress={onPress} hitSlop={4} style={{ width: 32, height: 32, borderRadius: 16, alignItems: 'center', justifyContent: 'center', backgroundColor: 'rgba(255,255,255,0.14)' }}>
      <Icon name={icon} size={15} color={ink} />
    </Pressable>
  );
}

function Chip({ label, onPress, on }: { label: string; onPress: () => void; on?: boolean }) {
  return (
    <Pressable onPress={onPress} style={{ paddingHorizontal: 10, paddingVertical: 6, borderRadius: 999, backgroundColor: on ? lime : 'rgba(255,255,255,0.14)' }}>
      <T style={{ color: on ? '#1b2400' : ink, fontSize: 12 }}>{label}</T>
    </Pressable>
  );
}

function TextPrompt({ visible, title, placeholder, initial = '', withRemind, hint, onDone }: {
  visible: boolean; title: string; placeholder: string; initial?: string; withRemind?: boolean; hint?: string;
  onDone: (text: string, remind: boolean) => void;
}) {
  const { t } = useTheme();
  const [text, setText] = useState(initial);
  const [remind, setRemind] = useState(true);
  useEffect(() => { if (visible) { setText(initial); setRemind(true); } }, [visible, initial]);
  return (
    <Modal visible={visible} transparent animationType="fade" onRequestClose={() => onDone('', false)}>
      <View style={{ flex: 1, backgroundColor: 'rgba(0,0,0,0.45)', justifyContent: 'center', padding: 20 }}>
        <Card style={{ gap: 12 }}>
          <T kind="medium" style={{ fontSize: 16 }}>{title}</T>
          {hint ? <T style={{ color: t.warn, fontSize: 12 }}>{hint}</T> : null}
          <TextInput
            autoFocus value={text} onChangeText={setText} placeholder={placeholder} placeholderTextColor={t.faint} multiline={withRemind}
            style={{ borderWidth: 1, borderColor: t.line, borderRadius: 12, padding: 12, color: t.text, fontSize: 15, minHeight: withRemind ? 70 : undefined, backgroundColor: t.card }}
          />
          {withRemind ? (
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10 }}>
              <T style={{ flex: 1, color: t.muted, fontSize: 13 }}>Remind me when I come back to this spot</T>
              <Switch value={remind} onValueChange={setRemind} trackColor={{ false: t.lineStrong, true: t.accent }} thumbColor={remind ? t.text : t.card} />
            </View>
          ) : null}
          <View style={{ flexDirection: 'row', gap: 8, justifyContent: 'flex-end' }}>
            <Pill label="Cancel" onPress={() => onDone(withRemind ? '' : initial, false)} />
            <Pill primary label="Save" onPress={() => onDone(text, remind)} />
          </View>
        </Card>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  chip: {
    flexDirection: 'row', alignItems: 'center', gap: 6, backgroundColor: glass, borderRadius: 999, paddingHorizontal: 12,
    paddingVertical: 7, borderWidth: 1, borderColor: 'rgba(255,255,255,0.12)', maxWidth: 220,
  },
  panel: { backgroundColor: glass, borderRadius: 20, padding: 10, borderWidth: 1, borderColor: 'rgba(255,255,255,0.1)' },
  input: { flex: 1, color: ink, fontSize: 14, paddingVertical: 8, paddingHorizontal: 6 },
  action: { width: 44, height: 44, borderRadius: 14, alignItems: 'center', justifyContent: 'center', backgroundColor: lime },
  caption: {
    position: 'absolute', backgroundColor: glass, borderRadius: 16, padding: 12, borderWidth: 1, borderColor: 'rgba(214,243,74,0.35)',
  },
  card: { backgroundColor: glass, borderRadius: 16, padding: 10, borderWidth: 1 },
  stem: { alignSelf: 'center', width: 2, height: 22, backgroundColor: 'rgba(255,255,255,0.5)' },
});
