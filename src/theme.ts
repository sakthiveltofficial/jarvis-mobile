// Mirrors the desktop UI tokens (ui/style.css in sakthiveltofficial/jarvis): light, airy, gradient tiles, lime accent.
import { createContext, useContext } from 'react';

export type Gradient = readonly [string, string, ...string[]];

export type Theme = {
  dark: boolean;
  bg: string;
  surface: string;
  card: string;
  line: string;
  lineStrong: string;
  text: string;
  muted: string;
  faint: string;
  accent: string;
  accentInk: string;
  good: string;
  bad: string;
  warn: string;
  glass: string;
  glassStrong: string;
  tileInk: string;
  g: {
    convo: Gradient;
    voice: Gradient;
    memory: Gradient;
    s1: Gradient;
    s2: Gradient;
    s3: Gradient;
    agenda: Gradient;
  };
};

export const light: Theme = {
  dark: false,
  bg: '#eceae6',
  surface: '#f7f6f3',
  card: '#ffffff',
  line: '#e2dfd9',
  lineStrong: '#cfcac2',
  text: '#17191a',
  muted: '#8b8f8c',
  faint: '#b8bab6',
  accent: '#d6f34a',
  accentInk: '#1b2400',
  good: '#3f9b5a',
  bad: '#d4553f',
  warn: '#e0912f',
  glass: 'rgba(255,255,255,0.62)',
  glassStrong: 'rgba(255,255,255,0.82)',
  tileInk: '#ffffff',
  g: {
    convo: ['#3f6f3e', '#7fa96a', '#c9dcb2', '#e7eddc'],
    voice: ['#c9cfa7', '#e9dc6a', '#f2c64b'],
    memory: ['#e3c7a6', '#ec9a6c', '#9aa6b0', '#7f8ea0'],
    s1: ['#f1c15a', '#e9925a', '#d8c9b9'],
    s2: ['#9fd36e', '#c9b27a', '#e4cdb5'],
    s3: ['#d9d6cf', '#b9b3a8', '#8e877b'],
    agenda: ['#f0c768', '#e7a06a', '#b9b7a6'],
  },
};

export const dark: Theme = {
  ...light,
  dark: true,
  bg: '#0e0f0f',
  surface: '#151716',
  card: '#1c1e1d',
  line: '#262928',
  lineStrong: '#353937',
  text: '#ecefec',
  muted: '#8a908c',
  faint: '#4a4f4c',
  glass: 'rgba(18,20,19,0.55)',
  glassStrong: 'rgba(18,20,19,0.78)',
  g: {
    convo: ['#1f3a20', '#355a33', '#4c6b45'],
    voice: ['#4a4a2a', '#7d6d22', '#8e6a1c'],
    memory: ['#5a4535', '#7b4a33', '#3c4652'],
    s1: ['#8b6427', '#6e3e27'],
    s2: ['#4c6a2e', '#5b4b30'],
    s3: ['#3a3833', '#2a2824'],
    agenda: ['#6e5426', '#6b3f2a', '#3a3a33'],
  },
};

export const font = {
  thin: 'Inter_300Light',
  regular: 'Inter_400Regular',
  medium: 'Inter_500Medium',
};

export const ThemeContext = createContext<{ t: Theme; mode: 'system' | 'light' | 'dark'; setMode: (m: 'system' | 'light' | 'dark') => void }>({
  t: light,
  mode: 'system',
  setMode: () => {},
});

export const useTheme = () => useContext(ThemeContext);
