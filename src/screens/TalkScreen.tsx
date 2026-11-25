import * as Haptics from 'expo-haptics';
import { useEffect, useRef, useState } from 'react';
import { FlatList, Pressable, TextInput, View } from 'react-native';
import Bridge from '../../modules/jarvis-bridge';
import { Icon } from '../components/Icon';
import { Orb } from '../components/Orb';
import { Card, Dot, T, Tile } from '../components/ui';
import { answerConfirm, useStore, type Msg } from '../store';
import { useTheme } from '../theme';

const STATE_LABEL: Record<string, string> = {
  idle: 'At your service.', listening: 'Listening…', transcribing: 'Hearing you…', thinking: 'Thinking…',
  acting: 'Working on it…', speaking: 'Speaking…',
};

function greeting() {
  const h = new Date().getHours();
  return h < 5 ? 'Good evening' : h < 12 ? 'Good morning' : h < 17 ? 'Good afternoon' : 'Good evening';
}

export function TalkScreen() {
  const { t } = useTheme();
  const status = useStore((s) => s.snap.status);
  const msgs = useStore((s) => s.msgs);
  const agentState = useStore((s) => s.agentState);
  const level = useStore((s) => s.level);
  const phoneSpeaking = useStore((s) => s.phoneSpeaking);
  const [text, setText] = useState('');
  const [recording, setRecording] = useState(false);
  const list = useRef<FlatList<Msg>>(null);
  const connected = status.state === 'connected';
  const busy = agentState === 'thinking' || agentState === 'acting' || agentState === 'transcribing';

  useEffect(() => {
    if (msgs.length) setTimeout(() => list.current?.scrollToEnd({ animated: true }), 50);
  }, [msgs.length]);

  function send() {
    const v = text.trim();
    if (!v || !connected) return;
    Bridge.sendText(v);
    setText('');
  }

  async function micDown() {
    if (!connected) return;
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
    Bridge.stopRecording(true);
    Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Soft);
  }

  const name = status.user?.split(' ')[0];
  const line = !connected
    ? status.state === 'connecting' ? `Connecting to ${status.pc ?? 'your PC'}…` : status.reason || 'Offline. Retrying in the background.'
    : recording ? 'Listening… let go to send' : phoneSpeaking ? 'Speaking…' : STATE_LABEL[agentState] ?? agentState;

  return (
    <View style={{ flex: 1, padding: 12, paddingTop: 4, gap: 10 }}>
      <Card style={{ paddingBottom: 14 }}>
        <View style={{ flexDirection: 'row', alignItems: 'center' }}>
          <View style={{ flex: 1 }}>
            <T kind="thin" style={{ fontSize: 36, lineHeight: 40, letterSpacing: -0.7 }}>
              {greeting()},{'\n'}{name || 'friend'}
            </T>
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8, marginTop: 10 }}>
              <Dot on={connected} busy={status.state === 'connecting'} />
              <T style={{ color: t.muted, fontSize: 13, flexShrink: 1 }} numberOfLines={2}>{line}</T>
            </View>
          </View>
          <Orb size={118} state={recording ? 'listening' : agentState} level={recording ? level : phoneSpeaking ? 0.4 : 0} />
        </View>
      </Card>

      <Tile colors={t.g.convo} style={{ flex: 1, padding: 0 }}>
        <View style={{ flexDirection: 'row', justifyContent: 'space-between', paddingHorizontal: 16, paddingTop: 14 }}>
          <T style={{ color: t.tileInk, fontSize: 13 }}>Conversation</T>
          <View style={{ flexDirection: 'row', gap: 14, alignItems: 'center' }}>
            {busy || phoneSpeaking ? (
              <Pressable hitSlop={10} onPress={() => Bridge.stopAgent()} accessibilityLabel="Stop">
                <Icon name="stop" size={16} color={t.tileInk} />
              </Pressable>
            ) : null}
            <T style={{ color: 'rgba(255,255,255,0.75)', fontSize: 13 }}>{msgs.filter((m) => m.kind === 'user' || m.kind === 'assistant').length} messages</T>
          </View>
        </View>
        <FlatList
          ref={list}
          data={msgs}
          keyExtractor={(m) => m.id}
          contentContainerStyle={{ padding: 14, gap: 8, flexGrow: 1 }}
          ListEmptyComponent={
            <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', padding: 24 }}>
              <T style={{ color: 'rgba(255,255,255,0.9)', textAlign: 'center', fontSize: 13, lineHeight: 19 }}>
                Hold the mic to talk, or type below. Jarvis runs on your PC and can use this phone too — try “read my latest messages” or “open Spotify on my phone”.
              </T>
            </View>
          }
          renderItem={({ item }) => <Bubble m={item} />}
        />
      </Tile>

      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8, backgroundColor: t.card, borderRadius: 20, borderWidth: 1, borderColor: t.line, paddingLeft: 14, paddingRight: 6, paddingVertical: 6 }}>
        <T style={{ color: t.muted, fontSize: 12 }}>Talk to me</T>
        <View style={{ width: 1, height: 18, backgroundColor: t.line }} />
        <TextInput
          value={text}
          onChangeText={setText}
          onSubmitEditing={send}
          placeholder={connected ? 'Type, or hold the mic…' : 'Not connected'}
          placeholderTextColor={t.faint}
          editable={connected}
          returnKeyType="send"
          style={{ flex: 1, color: t.text, fontSize: 14, paddingVertical: 8 }}
        />
        {text.trim() ? (
          <Pressable onPress={send} hitSlop={8} accessibilityLabel="Send" style={{ padding: 6 }}>
            <Icon name="send" color={t.muted} />
          </Pressable>
        ) : null}
        <Pressable
          onPressIn={micDown}
          onPressOut={micUp}
          accessibilityLabel="Hold to talk"
          style={({ pressed }) => ({
            width: 46, height: 46, borderRadius: 14, alignItems: 'center', justifyContent: 'center',
            backgroundColor: connected ? t.accent : t.line,
            transform: [{ scale: recording ? 1.08 + level * 0.25 : pressed ? 0.95 : 1 }],
          })}
        >
          <Icon name="mic" color={t.accentInk} size={20} />
        </Pressable>
      </View>
    </View>
  );
}

function Bubble({ m }: { m: Msg }) {
  const { t } = useTheme();
  const glass = t.dark ? 'rgba(18,20,19,0.55)' : 'rgba(255,255,255,0.82)';
  switch (m.kind) {
    case 'user':
      return (
        <View style={{ alignSelf: 'flex-end', maxWidth: '86%', backgroundColor: 'rgba(30,60,30,0.72)', borderRadius: 14, paddingHorizontal: 13, paddingVertical: 9 }}>
          <T selectable style={{ color: '#fff', fontSize: 14, lineHeight: 20 }}>{m.text}</T>
        </View>
      );
    case 'assistant':
      return (
        <View style={{ alignSelf: 'flex-start', maxWidth: '90%', backgroundColor: glass, borderRadius: 14, paddingHorizontal: 13, paddingVertical: 9 }}>
          {m.proactive ? <T style={{ color: t.muted, fontSize: 11, marginBottom: 2 }}>{m.proactive === 'offer' ? 'noticed something' : m.proactive}</T> : null}
          <T selectable style={{ fontSize: 14, lineHeight: 20 }}>{m.text}</T>
        </View>
      );
    case 'tool':
      return (
        <View style={{ alignSelf: 'flex-start', flexDirection: 'row', alignItems: 'center', gap: 6, borderWidth: 1, borderColor: 'rgba(214,243,74,0.65)', backgroundColor: 'rgba(214,243,74,0.16)', borderRadius: 10, paddingHorizontal: 10, paddingVertical: 6, maxWidth: '92%' }}>
          <Icon name={m.done ? (m.ok ? 'check' : 'x') : 'refresh'} size={13} color="#fff" />
          <T numberOfLines={1} style={{ color: '#fff', fontSize: 12, flexShrink: 1 }}>
            <T kind="medium" style={{ color: '#fff', fontSize: 12 }}>{m.tool.replace(/_/g, ' ')}</T> {m.summary}
          </T>
        </View>
      );
    case 'memory':
      return (
        <View style={{ alignSelf: 'flex-start', borderWidth: 1, borderColor: 'rgba(214,243,74,0.65)', backgroundColor: 'rgba(214,243,74,0.16)', borderRadius: 10, paddingHorizontal: 10, paddingVertical: 6 }}>
          <T style={{ color: '#fff', fontSize: 12 }}>✓ <T kind="medium" style={{ color: '#fff', fontSize: 12 }}>memory</T> recalled {m.count} related memories</T>
        </View>
      );
    case 'confirm':
      return (
        <View style={{ alignSelf: 'stretch', backgroundColor: glass, borderRadius: 14, padding: 12, gap: 8 }}>
          <T kind="medium">{m.reason}</T>
          <T style={{ color: t.muted, fontSize: 12 }} numberOfLines={4}>{m.tool.replace(/_/g, ' ')}: {m.summary}</T>
          {m.done ? (
            <T style={{ color: t.muted, fontSize: 12 }}>{m.answer === undefined ? 'Answered' : m.answer ? 'Allowed' : 'Declined'}</T>
          ) : (
            <View style={{ flexDirection: 'row', gap: 8 }}>
              <Pressable onPress={() => answerConfirm(m.id, true)} style={{ backgroundColor: t.accent, borderRadius: 999, paddingHorizontal: 16, paddingVertical: 8 }}>
                <T kind="medium" style={{ color: t.accentInk, fontSize: 13 }}>Allow</T>
              </Pressable>
              <Pressable onPress={() => answerConfirm(m.id, false)} style={{ borderWidth: 1, borderColor: t.line, borderRadius: 999, paddingHorizontal: 16, paddingVertical: 8 }}>
                <T style={{ fontSize: 13 }}>Deny</T>
              </Pressable>
            </View>
          )}
        </View>
      );
    default:
      return (
        <T style={{ color: m.kind === 'error' ? '#ffd9cf' : 'rgba(255,255,255,0.85)', fontSize: 12, textAlign: 'center', paddingHorizontal: 12 }}>
          {m.text}
        </T>
      );
  }
}
