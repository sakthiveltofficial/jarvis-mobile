import { CameraView, useCameraPermissions } from 'expo-camera';
import * as Haptics from 'expo-haptics';
import { useRef, useState } from 'react';
import { ActivityIndicator, KeyboardAvoidingView, ScrollView, StyleSheet, TextInput, View } from 'react-native';
import Bridge from '../../modules/jarvis-bridge';
import { Card, Pill, T, Tile } from '../components/ui';
import { refresh } from '../store';
import { useTheme } from '../theme';

export function PairScreen({ reason }: { reason?: string }) {
  const { t } = useTheme();
  const [perm, askPerm] = useCameraPermissions();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(reason ?? null);
  const [manual, setManual] = useState(false);
  const [code, setCode] = useState('');
  const handled = useRef(false);

  async function pair(uri: string) {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      await Bridge.requestPermissions('postNotifications');
      const r = await Bridge.pair(uri.trim());
      if (r.ok) {
        Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
        refresh();
      } else {
        setError(r.message);
        handled.current = false;
      }
    } catch (e: any) {
      setError(String(e?.message ?? e));
      handled.current = false;
    } finally {
      setBusy(false);
    }
  }

  return (
    <KeyboardAvoidingView behavior="height" style={{ flex: 1 }}>
      <ScrollView contentContainerStyle={{ padding: 16, gap: 12 }} keyboardShouldPersistTaps="handled">
        <Card>
          <T kind="thin" style={{ fontSize: 44, lineHeight: 48, letterSpacing: -0.8, marginTop: 8 }}>Hello.</T>
          <T kind="thin" style={{ fontSize: 26, lineHeight: 32, letterSpacing: -0.4, color: t.muted }}>
            Let's link this phone to Jarvis on your computer.
          </T>
          <View style={{ marginTop: 18, gap: 6 }}>
            <T style={{ color: t.muted }}>1. On your PC, open Jarvis → ⚙ → Connections.</T>
            <T style={{ color: t.muted }}>2. Under “My phone”, press Pair a phone.</T>
            <T style={{ color: t.muted }}>3. Scan the code it shows.</T>
          </View>
        </Card>

        {!manual ? (
          <Tile colors={t.g.convo} style={{ padding: 12 }}>
            <View style={{ aspectRatio: 1, borderRadius: 16, overflow: 'hidden', backgroundColor: 'rgba(0,0,0,0.25)' }}>
              {perm?.granted ? (
                <CameraView
                  style={StyleSheet.absoluteFill}
                  facing="back"
                  barcodeScannerSettings={{ barcodeTypes: ['qr'] }}
                  onBarcodeScanned={({ data }) => {
                    if (handled.current || !data?.startsWith('jarvis://')) return;
                    handled.current = true;
                    pair(data);
                  }}
                />
              ) : (
                <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', gap: 12, padding: 24 }}>
                  <T style={{ color: '#fff', textAlign: 'center' }}>Jarvis needs the camera to scan the pairing code.</T>
                  <Pill label="Allow camera" onPress={askPerm} icon="scan" />
                </View>
              )}
              <View pointerEvents="none" style={[StyleSheet.absoluteFill, { margin: 36 }]}>
                {[
                  { top: 0, left: 0, borderTopWidth: 2, borderLeftWidth: 2 },
                  { top: 0, right: 0, borderTopWidth: 2, borderRightWidth: 2 },
                  { bottom: 0, left: 0, borderBottomWidth: 2, borderLeftWidth: 2 },
                  { bottom: 0, right: 0, borderBottomWidth: 2, borderRightWidth: 2 },
                ].map((s, i) => (
                  <View key={i} style={[{ position: 'absolute', width: 34, height: 34, borderColor: t.accent, borderRadius: 6 }, s]} />
                ))}
              </View>
              {busy ? (
                <View style={[StyleSheet.absoluteFill, { alignItems: 'center', justifyContent: 'center', backgroundColor: 'rgba(0,0,0,0.35)' }]}>
                  <ActivityIndicator color={t.accent} size="large" />
                  <T style={{ color: '#fff', marginTop: 10 }}>Pairing…</T>
                </View>
              ) : null}
            </View>
            <T style={{ color: '#fff', marginTop: 10, textAlign: 'center', opacity: 0.9 }}>Point at the QR code on your PC</T>
          </Tile>
        ) : (
          <Card>
            <T kind="medium">Paste the pairing link</T>
            <T style={{ color: t.muted, fontSize: 12, marginTop: 4 }}>Under the QR code on your PC there's a “Copy link” button. It starts with jarvis://pair</T>
            <TextInput
              value={code}
              onChangeText={setCode}
              placeholder="jarvis://pair?…"
              placeholderTextColor={t.faint}
              autoCapitalize="none"
              autoCorrect={false}
              multiline
              style={{ marginTop: 12, minHeight: 80, borderRadius: 14, borderWidth: 1, borderColor: t.line, backgroundColor: t.card, color: t.text, padding: 12, fontSize: 13 }}
            />
            <View style={{ flexDirection: 'row', marginTop: 12 }}>
              <Pill label={busy ? 'Pairing…' : 'Pair'} primary onPress={() => pair(code)} disabled={busy || !code.trim()} />
            </View>
          </Card>
        )}

        {error ? (
          <Card style={{ borderWidth: 1, borderColor: t.bad }}>
            <T style={{ color: t.bad }}>{error}</T>
          </Card>
        ) : null}

        <View style={{ flexDirection: 'row', justifyContent: 'center' }}>
          <Pill label={manual ? 'Scan a QR code instead' : 'Paste a link instead'} icon={manual ? 'scan' : 'link'} onPress={() => setManual(!manual)} />
        </View>
        <T style={{ color: t.muted, fontSize: 12, textAlign: 'center', paddingHorizontal: 20 }}>
          Your phone and PC need to be on the same Wi-Fi, or both on Tailscale. Everything between them is end-to-end encrypted.
        </T>
      </ScrollView>
    </KeyboardAvoidingView>
  );
}
