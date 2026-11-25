import { Inter_300Light, Inter_400Regular, Inter_500Medium, useFonts } from '@expo-google-fonts/inter';
import { StatusBar } from 'expo-status-bar';
import { useEffect, useMemo, useState } from 'react';
import { Pressable, useColorScheme, View } from 'react-native';
import { SafeAreaProvider, useSafeAreaInsets } from 'react-native-safe-area-context';
import Bridge from './modules/jarvis-bridge';
import { Icon } from './src/components/Icon';
import { Dot, IconButton, T } from './src/components/ui';
import { ArScreen } from './src/screens/ArScreen';
import { PairScreen } from './src/screens/PairScreen';
import { PhoneScreen } from './src/screens/PhoneScreen';
import { SettingsScreen } from './src/screens/SettingsScreen';
import { TalkScreen } from './src/screens/TalkScreen';
import { refresh, startStore, useStore } from './src/store';
import { dark, light, ThemeContext, useTheme } from './src/theme';

type Tab = 'talk' | 'ar' | 'phone' | 'settings';

function readMode(): 'system' | 'light' | 'dark' {
  try {
    const v = Bridge.getString('theme');
    return v === 'light' || v === 'dark' ? v : 'system';
  } catch {
    return 'system';
  }
}

export default function App() {
  const scheme = useColorScheme();
  const [mode, setModeState] = useState(readMode);
  const [fonts] = useFonts({ Inter_300Light, Inter_400Regular, Inter_500Medium });
  const t = mode === 'dark' || (mode === 'system' && scheme === 'dark') ? dark : light;
  const ctx = useMemo(
    () => ({ t, mode, setMode: (m: 'system' | 'light' | 'dark') => { setModeState(m); try { Bridge.setString('theme', m); } catch {} } }),
    [t, mode],
  );

  useEffect(() => {
    startStore();
    const s = Bridge.getState();
    if (s.pairing && s.settings.autoStart && !s.serviceRunning) Bridge.start();
    refresh();
  }, []);

  if (!fonts) return <View style={{ flex: 1, backgroundColor: t.bg }} />;
  return (
    <SafeAreaProvider>
      <ThemeContext.Provider value={ctx}>
        <StatusBar style={t.dark ? 'light' : 'dark'} />
        <Shell />
      </ThemeContext.Provider>
    </SafeAreaProvider>
  );
}

function Shell() {
  const { t, mode, setMode } = useTheme();
  const insets = useSafeAreaInsets();
  const snap = useStore((s) => s.snap);
  const [tab, setTab] = useState<Tab>('talk');
  const paired = !!snap.pairing && snap.status.state !== 'unpaired';
  const connected = snap.status.state === 'connected';
  const isDark = t.dark;

  return (
    <View style={{ flex: 1, backgroundColor: t.bg, paddingTop: insets.top + 6, paddingBottom: insets.bottom }}>
      <View style={{ flexDirection: 'row', alignItems: 'center', paddingHorizontal: 16, paddingBottom: 8 }}>
        <View style={{ width: 32, height: 32, borderRadius: 10, borderWidth: 1, borderColor: t.lineStrong, backgroundColor: t.card, alignItems: 'center', justifyContent: 'center' }}>
          <Dot on={connected} />
        </View>
        <T kind="medium" style={{ marginLeft: 10, fontSize: 15 }}>{snap.status.assistant || 'Jarvis'}</T>
        {paired ? <T style={{ marginLeft: 6, color: t.muted, fontSize: 13 }} numberOfLines={1}>· {snap.pairing?.pc}</T> : null}
        <View style={{ flex: 1 }} />
        <IconButton
          icon={isDark ? 'sun' : 'moon'}
          label="Toggle theme"
          onPress={() => setMode(mode === 'system' ? (isDark ? 'light' : 'dark') : mode === 'dark' ? 'light' : 'dark')}
        />
      </View>

      <View style={{ flex: 1 }}>
        {!paired ? <PairScreen reason={snap.status.reason} /> : tab === 'talk' ? <TalkScreen /> : tab === 'ar' ? <ArScreen /> : tab === 'phone' ? <PhoneScreen /> : <SettingsScreen />}
      </View>

      {paired ? (
        <View style={{ flexDirection: 'row', marginHorizontal: 12, marginBottom: 8, marginTop: 2, backgroundColor: t.surface, borderRadius: 20, borderWidth: 1, borderColor: t.line, padding: 4 }}>
          {([['talk', 'chat', 'Talk'], ['ar', 'ar', 'AR'], ['phone', 'phone', 'Phone'], ['settings', 'gear', 'Settings']] as const).map(([k, icon, label]) => {
            const on = tab === k;
            return (
              <Pressable key={k} onPress={() => setTab(k)} style={{ flex: 1, flexDirection: 'row', gap: 6, alignItems: 'center', justifyContent: 'center', paddingVertical: 10, borderRadius: 16, backgroundColor: on ? t.accent : 'transparent' }}>
                <Icon name={icon} size={16} color={on ? t.accentInk : t.muted} />
                <T kind={on ? 'medium' : 'regular'} style={{ fontSize: 13, color: on ? t.accentInk : t.muted }}>{label}</T>
              </Pressable>
            );
          })}
        </View>
      ) : null}
    </View>
  );
}
