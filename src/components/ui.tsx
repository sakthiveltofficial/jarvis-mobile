import { LinearGradient } from 'expo-linear-gradient';
import type { ReactNode } from 'react';
import { Pressable, StyleSheet, Switch, Text, View, type StyleProp, type TextStyle, type ViewStyle } from 'react-native';
import { font, useTheme, type Gradient } from '../theme';
import { Icon } from './Icon';

export function T({ style, kind = 'regular', children, numberOfLines, selectable }: {
  style?: StyleProp<TextStyle>; kind?: 'thin' | 'regular' | 'medium'; children: ReactNode; numberOfLines?: number; selectable?: boolean;
}) {
  const { t } = useTheme();
  return (
    <Text selectable={selectable} numberOfLines={numberOfLines} style={[{ color: t.text, fontFamily: font[kind], fontSize: 14 }, style]}>
      {children}
    </Text>
  );
}

export function IconButton({ icon, onPress, on, size = 36, label }: { icon: string; onPress: () => void; on?: boolean; size?: number; label: string }) {
  const { t } = useTheme();
  return (
    <Pressable
      accessibilityLabel={label}
      onPress={onPress}
      style={({ pressed }) => ({
        width: size, height: size, borderRadius: 11, alignItems: 'center', justifyContent: 'center',
        borderWidth: 1, borderColor: on ? t.accent : t.line, backgroundColor: on ? t.accent : t.card,
        transform: [{ scale: pressed ? 0.95 : 1 }],
      })}
    >
      <Icon name={icon} size={16} color={on ? t.accentInk : t.text} />
    </Pressable>
  );
}

export function Pill({ label, onPress, primary, danger, icon, disabled }: {
  label: string; onPress: () => void; primary?: boolean; danger?: boolean; icon?: string; disabled?: boolean;
}) {
  const { t } = useTheme();
  const bg = primary ? t.text : t.card;
  const fg = primary ? t.surface : danger ? t.bad : t.text;
  return (
    <Pressable
      disabled={disabled}
      onPress={onPress}
      style={({ pressed }) => ({
        flexDirection: 'row', alignItems: 'center', gap: 6, paddingVertical: 9, paddingHorizontal: 15, borderRadius: 999,
        borderWidth: 1, borderColor: primary ? t.text : t.line, backgroundColor: bg, opacity: disabled ? 0.5 : pressed ? 0.85 : 1,
      })}
    >
      {icon ? <Icon name={icon} size={15} color={fg} /> : null}
      <T style={{ color: fg, fontSize: 13 }} kind="medium">{label}</T>
    </Pressable>
  );
}

export function Card({ children, style }: { children: ReactNode; style?: StyleProp<ViewStyle> }) {
  const { t } = useTheme();
  return (
    <View style={[{ backgroundColor: t.surface, borderRadius: 22, padding: 18, borderWidth: t.dark ? 1 : 0, borderColor: t.line }, styles.shadow, style]}>
      {children}
    </View>
  );
}

export function Tile({ colors, children, style, onPress }: { colors: Gradient; children: ReactNode; style?: StyleProp<ViewStyle>; onPress?: () => void }) {
  const body = (
    <LinearGradient colors={colors} start={{ x: 0.1, y: 0 }} end={{ x: 0.9, y: 1 }} style={[{ borderRadius: 22, padding: 16, overflow: 'hidden' }, style]}>
      {children}
    </LinearGradient>
  );
  if (!onPress) return body;
  return (
    <Pressable onPress={onPress} style={({ pressed }) => ({ transform: [{ scale: pressed ? 0.98 : 1 }], flex: (StyleSheet.flatten(style) as ViewStyle)?.flex })}>
      {body}
    </Pressable>
  );
}

export function ToggleRow({ label, hint, value, onChange }: { label: string; hint?: string; value: boolean; onChange: (v: boolean) => void }) {
  const { t } = useTheme();
  return (
    <View style={{ flexDirection: 'row', alignItems: 'center', paddingVertical: 10, gap: 12 }}>
      <View style={{ flex: 1 }}>
        <T>{label}</T>
        {hint ? <T style={{ color: t.muted, fontSize: 12, marginTop: 2 }}>{hint}</T> : null}
      </View>
      <Switch
        value={value}
        onValueChange={onChange}
        trackColor={{ false: t.lineStrong, true: t.accent }}
        thumbColor={value ? t.text : t.card}
      />
    </View>
  );
}

export function Dot({ on, busy }: { on: boolean; busy?: boolean }) {
  const { t } = useTheme();
  return (
    <View
      style={{
        width: 8, height: 8, borderRadius: 4, backgroundColor: on ? t.accent : busy ? t.warn : t.faint,
        borderWidth: on ? 3 : 0, borderColor: 'rgba(214,243,74,0.3)',
      }}
    />
  );
}

export const styles = StyleSheet.create({
  shadow: { shadowColor: '#141414', shadowOpacity: 0.06, shadowRadius: 20, shadowOffset: { width: 0, height: 8 }, elevation: 2 },
});
