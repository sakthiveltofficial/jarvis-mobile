import { NativeModule, requireNativeModule } from 'expo';

import type {
  BridgeSnapshot, Capabilities, DesktopEvent, JarvisBridgeModuleEvents, PermissionGroup, PhoneAction, SettingsPage,
} from './JarvisBridge.types';

declare class JarvisBridgeModule extends NativeModule<JarvisBridgeModuleEvents> {
  getState(): BridgeSnapshot;
  capabilities(): Capabilities;
  history(): DesktopEvent[];
  actions(): PhoneAction[];
  start(): void;
  stop(): void;
  reconnect(): void;
  pair(uri: string): Promise<{ ok: boolean; message: string }>;
  unpair(): void;
  setHosts(hosts: string[]): void;
  setSetting(key: 'speakOnPhone' | 'forwardNotifications' | 'autoStart', value: boolean): void;
  getString(key: string): string | null;
  setString(key: string, value: string): void;
  sendText(text: string): boolean;
  confirm(id: string, approve: boolean): boolean;
  stopAgent(): boolean;
  stopSpeaking(): boolean;
  startRecording(): boolean;
  stopRecording(send: boolean): boolean;
  requestPermissions(group: PermissionGroup): Promise<Capabilities>;
  openSettings(page: SettingsPage): void;
  requestShizuku(): 'not_running' | 'granted' | 'asked' | 'error';
  requestScreenCapture(): Promise<boolean>;
  stopScreenCapture(): void;
}

export default requireNativeModule<JarvisBridgeModule>('JarvisBridge');
