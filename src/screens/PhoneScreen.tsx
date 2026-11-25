import { Alert, ScrollView, View } from 'react-native';
import Bridge, { type Capabilities } from '../../modules/jarvis-bridge';
import { Card, Pill, T, Tile } from '../components/ui';
import { refresh, useRefreshOnFocus, useStore } from '../store';
import { useTheme, type Gradient } from '../theme';

type Cap = {
  key: keyof Capabilities;
  title: string;
  what: string;
  grad: keyof ReturnType<typeof useTheme>['t']['g'];
  enable: () => void | Promise<unknown>;
  optional?: boolean;
};

const after = (p: Promise<unknown> | void) => Promise.resolve(p).then(refresh);

export function PhoneScreen() {
  const { t } = useTheme();
  useRefreshOnFocus();
  const caps = useStore((s) => s.snap.capabilities);
  const actions = useStore((s) => s.actions);

  const list: Cap[] = [
    { key: 'accessibility', title: 'Screen control', what: 'Read the screen, tap, swipe and type', grad: 'voice', enable: () => Bridge.openSettings('accessibility') },
    {
      key: 'screenCapture', title: 'Screenshots', what: caps.apiLevel >= 30 ? 'Comes with screen control' : 'Lets Jarvis see the screen',
      grad: 's2', enable: () => (caps.apiLevel >= 30 ? Bridge.openSettings('accessibility') : after(Bridge.requestScreenCapture())),
    },
    { key: 'notifications', title: 'Notifications', what: 'Read, open, reply and dismiss', grad: 'memory', enable: () => Bridge.openSettings('notifications') },
    { key: 'sms', title: 'Messages', what: 'Read and send SMS', grad: 's1', enable: () => after(Bridge.requestPermissions('sms')) },
    { key: 'calls', title: 'Calls', what: 'Call, answer, hang up, call log', grad: 'agenda', enable: () => after(Bridge.requestPermissions('calls')) },
    { key: 'contacts', title: 'Contacts', what: 'Find people by name', grad: 's3', enable: () => after(Bridge.requestPermissions('contacts')) },
    { key: 'files', title: 'Files', what: 'Browse, read, save, send to PC', grad: 'convo', enable: () => (caps.apiLevel >= 30 ? Bridge.openSettings('files') : after(Bridge.requestPermissions('files'))) },
    { key: 'location', title: 'Location', what: '“Where’s my phone?”', grad: 's2', enable: () => after(Bridge.requestPermissions('location')) },
    { key: 'microphone', title: 'Microphone', what: 'Talk to Jarvis from here', grad: 'voice', enable: () => after(Bridge.requestPermissions('microphone')) },
    { key: 'battery', title: 'Always on', what: 'Stay connected with the screen off', grad: 'memory', enable: () => Bridge.openSettings('battery') },
    { key: 'overlay', title: 'Open apps', what: 'Lets Jarvis bring apps to the front', grad: 's1', enable: () => Bridge.openSettings('overlay'), optional: true },
    {
      key: 'shizuku', title: 'Power mode', what: caps.shizukuRunning ? 'Shell commands and keys via Shizuku' : 'Install and start Shizuku first',
      grad: 's3', optional: true,
      enable: () => {
        const r = Bridge.requestShizuku();
        if (r === 'not_running') {
          Alert.alert('Power mode uses Shizuku', 'Install Shizuku, start it with Wireless debugging (its app walks you through it), then come back and tap Power mode again.', [
            { text: 'Later' },
            { text: 'Open Shizuku', onPress: () => Bridge.openSettings('shizuku') },
          ]);
        }
        setTimeout(refresh, 1500);
      },
    },
  ];

  const on = list.filter((c) => caps[c.key]).length;

  return (
    <ScrollView contentContainerStyle={{ padding: 12, paddingTop: 4, gap: 10, paddingBottom: 30 }}>
      <Card>
        <T kind="thin" style={{ fontSize: 34, lineHeight: 38, letterSpacing: -0.6 }}>Phone control</T>
        <T style={{ color: t.muted, marginTop: 6, lineHeight: 20 }}>
          Choose what Jarvis may do on this phone. Risky things like sending a message or deleting a file still ask you first.
        </T>
        <View style={{ flexDirection: 'row', alignItems: 'baseline', gap: 6, marginTop: 12 }}>
          <T kind="thin" style={{ fontSize: 40, lineHeight: 44 }}>{on}</T>
          <T style={{ color: t.muted }}>of {list.length} turned on</T>
        </View>
      </Card>

      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 10 }}>
        {list.map((c) => (
          <View key={c.key} style={{ width: '48.4%' }}>
            <Tile colors={t.g[c.grad] as Gradient} onPress={() => c.enable()} style={{ minHeight: 132 }}>
              <T style={{ color: t.tileInk, fontSize: 12, opacity: 0.9 }}>{c.title}{c.optional ? ' · optional' : ''}</T>
              <T kind="thin" style={{ color: t.tileInk, fontSize: 30, lineHeight: 36, marginTop: 6 }}>{caps[c.key] ? 'On' : 'Off'}</T>
              <T style={{ color: t.tileInk, fontSize: 12, opacity: 0.9, marginTop: 'auto' }}>{caps[c.key] ? c.what : `Tap to allow · ${c.what}`}</T>
            </Tile>
          </View>
        ))}
      </View>

      {!caps.accessibility || !caps.notifications ? (
        <Card>
          <T kind="medium">Greyed out in Settings?</T>
          <T style={{ color: t.muted, marginTop: 6, lineHeight: 20 }}>
            Android 13 and newer block Accessibility and Notification access for apps installed from an APK. Open App info, tap ⋮ (top right) → “Allow restricted settings”, then try again.
          </T>
          <View style={{ flexDirection: 'row', marginTop: 10 }}>
            <Pill label="Open App info" onPress={() => Bridge.openSettings('app')} />
          </View>
        </Card>
      ) : null}

      <Card>
        <T kind="medium">What Jarvis did on this phone</T>
        {actions.length === 0 ? (
          <T style={{ color: t.muted, marginTop: 8 }}>Nothing yet.</T>
        ) : (
          actions.slice(0, 25).map((a, i) => (
            <View key={`${a.at}-${i}`} style={{ flexDirection: 'row', gap: 10, paddingVertical: 7, borderTopWidth: i ? 1 : 0, borderTopColor: t.line, marginTop: i ? 0 : 8 }}>
              <T style={{ color: t.muted, fontSize: 12, width: 44 }}>{new Date(a.at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</T>
              <T style={{ flex: 1, fontSize: 13 }} numberOfLines={2}>{a.summary}</T>
              <T style={{ color: a.ok ? t.good : t.bad, fontSize: 12 }}>{a.ok ? 'done' : 'failed'}</T>
            </View>
          ))
        )}
      </Card>
    </ScrollView>
  );
}
