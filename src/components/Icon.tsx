import Svg, { Circle, Path, Rect } from 'react-native-svg';

const paths: Record<string, (c: string) => React.ReactNode> = {
  mic: (c) => (
    <>
      <Rect x={9} y={3} width={6} height={11} rx={3} stroke={c} />
      <Path d="M5 11a7 7 0 0 0 14 0M12 18v3" stroke={c} />
    </>
  ),
  send: (c) => <Path d="M5 12h14M13 6l6 6-6 6" stroke={c} />,
  moon: (c) => <Path d="M20 14.5A8 8 0 0 1 9.5 4 8 8 0 1 0 20 14.5z" stroke={c} />,
  sun: (c) => (
    <>
      <Circle cx={12} cy={12} r={4} stroke={c} />
      <Path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" stroke={c} />
    </>
  ),
  gear: (c) => (
    <>
      <Circle cx={12} cy={12} r={3} stroke={c} />
      <Path
        d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"
        stroke={c}
      />
    </>
  ),
  chat: (c) => <Path d="M21 12a8 8 0 0 1-11.6 7.1L4 20l1-4.6A8 8 0 1 1 21 12z" stroke={c} />,
  phone: (c) => (
    <>
      <Rect x={6} y={2.5} width={12} height={19} rx={2.5} stroke={c} />
      <Path d="M11 18.5h2" stroke={c} />
    </>
  ),
  stop: (c) => <Rect x={7} y={7} width={10} height={10} rx={2} stroke={c} />,
  check: (c) => <Path d="M5 12.5l4.5 4.5L19 7.5" stroke={c} />,
  x: (c) => <Path d="M6 6l12 12M18 6L6 18" stroke={c} />,
  refresh: (c) => <Path d="M20 11a8 8 0 0 0-14.9-3M4 13a8 8 0 0 0 14.9 3M4 4v4h4M20 20v-4h-4" stroke={c} />,
  scan: (c) => <Path d="M4 8V5a1 1 0 0 1 1-1h3M16 4h3a1 1 0 0 1 1 1v3M20 16v3a1 1 0 0 1-1 1h-3M8 20H5a1 1 0 0 1-1-1v-3M4 12h16" stroke={c} />,
  link: (c) => <Path d="M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1" stroke={c} />,
  arrow: (c) => <Path d="M7 17L17 7M9 7h8v8" stroke={c} />,
  speaker: (c) => <Path d="M4 9v6h4l5 4V5L8 9H4zM16.5 8.5a5 5 0 0 1 0 7M19 6a8.5 8.5 0 0 1 0 12" stroke={c} />,
  power: (c) => <Path d="M12 3v8M6.3 6.3a8 8 0 1 0 11.4 0" stroke={c} />,
};

export function Icon({ name, size = 18, color = '#17191a', width = 1.5 }: { name: keyof typeof paths | string; size?: number; color?: string; width?: number }) {
  const p = paths[name];
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none" strokeWidth={width} strokeLinecap="round" strokeLinejoin="round">
      {p ? p(color) : null}
    </Svg>
  );
}
