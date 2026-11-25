import { requireNativeModule, requireNativeView } from 'expo';
import type { ComponentType, Ref } from 'react';
import type { ViewProps } from 'react-native';

export type ArAvailability = 'supported' | 'not_installed' | 'too_old' | 'unsupported' | 'checking' | 'unknown';

/** Where an anchored item is on screen right now (dp). x/y are -1 when it's behind you. */
export type ArItem = { id: string; x: number; y: number; dist: number; on: boolean };
export type ArFrameEvent = { tracking: boolean; reason: string; planes: number; items: ArItem[] };
export type ArStatusEvent = { state: 'starting' | 'tracking' | 'error'; message: string };

/** A pinned spot ARCore should look for: a photo of the spot (Augmented Image) and where the item sits on it. */
export type ArTarget = { id: string; image: string; widthM: number; offX: number; offZ: number };

export type ArCapture = { id: string; width: number; height: number; tracking: boolean; thumb: string };
export type PinResult = {
  ok: boolean; error?: string;
  remember?: boolean; why?: string; image?: string; widthM?: number; offX?: number; offZ?: number;
};

export type ArViewRef = {
  /** Grab the camera view (JPEG kept natively; use its id as jpegRef in Bridge.request). */
  capture(maxSide: number): Promise<ArCapture>;
  /** Anchor a point (fractions of a captured frame) in the room. */
  anchorFromCapture(captureId: string, u: number, v: number, id: string): Promise<{ ok: boolean; method?: string; error?: string }>;
  /** Move the orb to a tapped surface (dp), or in front of you with -1, -1. */
  placeOrb(x: number, y: number): Promise<{ ok: boolean; error?: string }>;
  /** Pin an item at a tapped spot (dp); remember=true saves a photo of the spot to find it again later. */
  pinAt(x: number, y: number, id: string, remember: boolean): Promise<PinResult>;
  removeAnchor(id: string): Promise<void>;
  clearAnchors(prefix: string): Promise<void>;
};

export type ArViewProps = ViewProps & {
  ref?: Ref<ArViewRef>;
  orbVisible?: boolean;
  showPoints?: boolean;
  /** JSON of ArTarget[] */
  targets?: string;
  onArStatus?: (e: { nativeEvent: ArStatusEvent }) => void;
  onFrame?: (e: { nativeEvent: ArFrameEvent }) => void;
  onTargetFound?: (e: { nativeEvent: { id: string } }) => void;
};

declare class JarvisARModule {
  availability(): ArAvailability;
  install(): Promise<'installed' | 'requested' | 'declined' | 'error'>;
}

export const JarvisAR = requireNativeModule<JarvisARModule>('JarvisAR');
export const ArView = requireNativeView('JarvisAR') as ComponentType<ArViewProps>;
