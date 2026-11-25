import Constants from 'expo-constants';
import { useState } from 'react';
import { Alert, Linking, Pressable, ScrollView, TextInput, View } from 'react-native';
import Bridge from '../../modules/jarvis-bridge';
import { Card, Dot, Pill, T, ToggleRow } from '../components/ui';
import { clearChat, refresh, useStore } from '../store';
import { useTheme } from '../theme';

export function SettingsScreen() {
  const { t, mode, setMode } = useTheme();
  const snap = useStore((s) => s.snap);
  const [hosts, setHosts] = useState((snap.pairing?.hosts ?? []).join(', '));
  const set = (k: 'speakOnPhone' | 'forwardNotifications' | 'autoStart', v: boolean) => {
    Bridge.setSetting(k, v);
    refresh();
  };
  const connected = snap.status.state === 'connected';

  return (
    <ScrollView contentContainerStyle={{ padding: 12, paddingTop: 4, gap: 10, paddingBottom: 30 }} keyboardShouldPersistTaps="handled">
      <Card>
        <T style={{ color: t.muted, fontSize: 12 }}>Paired computer</T>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10, marginTop: 6 }}>
          <Dot on={connected} busy={snap.status.state === 'connecting'} />
          <T kind="thin" style={{ fontSize: 28, lineHeight: 34, flex: 1 }} numberOfLines={1}>{snap.pairing?.pc ?? '—'}</T>
        </View>
        <T style={{ color: t.muted, fontSize: 12, marginTop: 4 }}>
          {connected ? `Connected via ${snap.status.host}` : snap.status.reason ?? snap.status.state}
        </T>
        <T style={{ color: t.muted, fontSize: 12, marginTop: 14 }}>Addresses (LAN, Tailscale…), tried in order</T>
        <TextInput
          value={hosts}
          onChangeText={setHosts}
          autoCapitalize="none"
          autoCorrect={false}
          placeholder="192.168.1.20, 100.101.102.103"
          placeholderTextColor={t.faint}
          style={{ marginTop: 6, borderRadius: 12, borderWidth: 1, borderColor: t.line, backgroundColor: t.card, color: t.text, padding: 10, fontSize: 13 }}
        />
        <View style={{ flexDirection: 'row', gap: 8, marginTop: 12, flexWrap: 'wrap' }}>
          <Pill label="Save & reconnect" icon="refresh" onPress={() => { Bridge.setHosts(hosts.split(',')); refresh(); }} />
          <Pill
            label="Unpair"
            danger
            onPress={() =>
              Alert.alert('Unpair this phone?', `${snap.pairing?.pc ?? 'The PC'} will forget this phone. You can pair again any time.`, [
                { text: 'Cancel', style: 'cancel' },
                { text: 'Unpair', style: 'destructive', onPress: () => { Bridge.unpair(); clearChat(); setTimeout(refresh, 600); } },
              ])
            }
          />
        </View>
      </Card>

      <Card>
        <ToggleRow label="Speak replies on the phone" hint="When you talk from here, Jarvis answers through this phone instead of the PC speakers." value={snap.settings.speakOnPhone} onChange={(v) => set('speakOnPhone', v)} />
        <ToggleRow label="Tell Jarvis about new notifications" hint="Lets Jarvis mention important messages. Needs notification access." value={snap.settings.forwardNotifications} onChange={(v) => set('forwardNotifications', v)} />
        <ToggleRow label="Stay connected" hint="Reconnect in the background and after a restart." value={snap.settings.autoStart} onChange={(v) => { set('autoStart', v); v ? Bridge.start() : Bridge.stop(); }} />
      </Card>

      <Card>
        <T style={{ color: t.muted, fontSize: 12, marginBottom: 8 }}>Appearance</T>
        <View style={{ flexDirection: 'row', backgroundColor: t.card, borderRadius: 999, borderWidth: 1, borderColor: t.line, padding: 3 }}>
          {(['system', 'light', 'dark'] as const).map((m) => (
            <Pressable key={m} onPress={() => setMode(m)} style={{ flex: 1, paddingVertical: 8, borderRadius: 999, alignItems: 'center', backgroundColor: mode === m ? t.text : 'transparent' }}>
              <T kind="medium" style={{ fontSize: 13, color: mode === m ? t.surface : t.text }}>{m[0].toUpperCase() + m.slice(1)}</T>
            </Pressable>
          ))}
        </View>
      </Card>

      <Card>
        <T style={{ color: t.muted, fontSize: 12 }}>About</T>
        <T style={{ marginTop: 6 }}>Jarvis for Android {Constants.expoConfig?.version ?? ''}</T>
        <T style={{ color: t.muted, fontSize: 12, marginTop: 4, lineHeight: 18 }}>
          A companion for Jarvis on your computer. The connection is end-to-end encrypted with a key made when you paired; nothing goes through a cloud server.
        </T>
        <View style={{ flexDirection: 'row', gap: 8, marginTop: 12, flexWrap: 'wrap' }}>
          <Pill label="Updates" icon="arrow" onPress={() => Linking.openURL('https://github.com/sakthiveltofficial/jarvis-mobile/releases')} />
          <Pill label="App info" onPress={() => Bridge.openSettings('app')} />
        </View>
      </Card>
    </ScrollView>
  );
}
