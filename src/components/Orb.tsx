import { useEffect, useRef } from 'react';
import { Animated, Easing, StyleSheet, View } from 'react-native';
import Svg, { Circle, Defs, Ellipse, RadialGradient, Stop } from 'react-native-svg';
import { useTheme } from '../theme';

/**
 * A small take on the desktop's wireframe orb: soft lime/green/yellow glow, thin rings,
 * breathing with Jarvis's state and the mic level.
 */
export function Orb({ size = 150, state, level }: { size?: number; state: string; level: number }) {
  const { t } = useTheme();
  const breath = useRef(new Animated.Value(0)).current;
  const spin = useRef(new Animated.Value(0)).current;
  const lvl = useRef(new Animated.Value(0)).current;
  const busy = state === 'thinking' || state === 'acting' || state === 'transcribing';

  useEffect(() => {
    const a = Animated.loop(
      Animated.sequence([
        Animated.timing(breath, { toValue: 1, duration: busy ? 700 : 2600, easing: Easing.inOut(Easing.sin), useNativeDriver: true }),
        Animated.timing(breath, { toValue: 0, duration: busy ? 700 : 2600, easing: Easing.inOut(Easing.sin), useNativeDriver: true }),
      ]),
    );
    a.start();
    return () => a.stop();
  }, [busy, breath]);

  useEffect(() => {
    const a = Animated.loop(Animated.timing(spin, { toValue: 1, duration: busy ? 2400 : 14000, easing: Easing.linear, useNativeDriver: true }));
    a.start();
    return () => a.stop();
  }, [busy, spin]);

  useEffect(() => {
    Animated.spring(lvl, { toValue: level, useNativeDriver: true, speed: 30, bounciness: 2 }).start();
  }, [level, lvl]);

  const glow = Animated.add(breath.interpolate({ inputRange: [0, 1], outputRange: [0.92, 1.06] }), lvl.interpolate({ inputRange: [0, 1], outputRange: [0, 0.35] }));
  const rotate = spin.interpolate({ inputRange: [0, 1], outputRange: ['0deg', '360deg'] });
  const ring = t.dark ? 'rgba(236,239,236,0.28)' : 'rgba(23,25,26,0.22)';
  const r = size / 2;

  return (
    <View style={{ width: size, height: size }}>
      <Animated.View style={[StyleSheet.absoluteFill, { transform: [{ scale: glow }] }]}>
        <Svg width={size} height={size}>
          <Defs>
            <RadialGradient id="g1" cx="38%" cy="62%" r="55%">
              <Stop offset="0" stopColor="#f2d65a" stopOpacity={0.85} />
              <Stop offset="1" stopColor="#f2d65a" stopOpacity={0} />
            </RadialGradient>
            <RadialGradient id="g2" cx="64%" cy="40%" r="55%">
              <Stop offset="0" stopColor="#7fbf5a" stopOpacity={0.8} />
              <Stop offset="1" stopColor="#7fbf5a" stopOpacity={0} />
            </RadialGradient>
          </Defs>
          <Circle cx={r} cy={r} r={r} fill="url(#g1)" />
          <Circle cx={r} cy={r} r={r} fill="url(#g2)" />
        </Svg>
      </Animated.View>
      <Animated.View style={[StyleSheet.absoluteFill, { transform: [{ rotate }] }]}>
        <Svg width={size} height={size}>
          {[0.18, 0.42, 0.66, 0.9].map((f) => (
            <Ellipse key={`h${f}`} cx={r} cy={r} rx={r * 0.62} ry={r * 0.62 * f} stroke={ring} strokeWidth={0.8} fill="none" />
          ))}
          {[0.2, 0.55, 0.85].map((f) => (
            <Ellipse key={`v${f}`} cx={r} cy={r} rx={r * 0.62 * f} ry={r * 0.62} stroke={ring} strokeWidth={0.8} fill="none" />
          ))}
          <Circle cx={r} cy={r} r={r * 0.62} stroke={ring} strokeWidth={1} fill="none" />
        </Svg>
      </Animated.View>
      <View style={[StyleSheet.absoluteFill, { alignItems: 'center', justifyContent: 'center' }]}>
        <View style={{ width: 10, height: 10, borderRadius: 5, backgroundColor: t.accent, shadowColor: t.accent, shadowOpacity: 0.9, shadowRadius: 8, elevation: 4 }} />
      </View>
    </View>
  );
}
