export type BridgeState = 'unpaired' | 'connecting' | 'connected' | 'disconnected' | 'stopped';

export type BridgeStatus = {
  state: BridgeState;
  pc?: string;
  host?: string;
  reason?: string;
  retryInMs?: number;
  user?: string;
  assistant?: string;
  at?: number;
};

export type Capabilities = {
  accessibility: boolean;
  screenCapture: boolean;
  notifications: boolean;
  postNotifications: boolean;
  microphone: boolean;
  camera: boolean;
  sms: boolean;
  calls: boolean;
  contacts: boolean;
  location: boolean;
  files: boolean;
  overlay: boolean;
  battery: boolean;
  shizuku: boolean;
  shizukuRunning: boolean;
  apiLevel: number;
};

export type BridgeSettings = { speakOnPhone: boolean; forwardNotifications: boolean; autoStart: boolean };

export type Pairing = { pc: string; hosts: string[]; port: number; deviceId: string };

export type BridgeSnapshot = {
  status: BridgeStatus;
  serviceRunning: boolean;
  pairing: Pairing | null;
  settings: BridgeSettings;
  capabilities: Capabilities;
};

/** An event from desktop Jarvis (same shapes as the desktop UI's WebSocket events). */
export type DesktopEvent = { type: string; [k: string]: any };

export type PhoneAction = { action: string; summary: string; ok: boolean; at: number };

export type JarvisBridgeModuleEvents = {
  onStatus: (s: BridgeStatus) => void;
  onEvent: (e: DesktopEvent) => void;
  onAction: (a: PhoneAction) => void;
  onLevel: (l: { value: number }) => void;
};

export type PermissionGroup = 'microphone' | 'camera' | 'sms' | 'calls' | 'contacts' | 'location' | 'postNotifications' | 'files';
export type SettingsPage = 'accessibility' | 'notifications' | 'files' | 'overlay' | 'battery' | 'shizuku' | 'app';
