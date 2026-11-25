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
  /** Stop push-to-talk and keep the audio for an AR request ({ wavRef: true }). */
  stopRecordingKeep(): boolean;
  /** Phone -> PC request (AR features). Resolves with the JSON reply as a string. */
  request(op: string, argsJson: string, timeoutMs: number): Promise<string>;
  requestPermissions(group: PermissionGroup): Promise<Capabilities>;
  openSettings(page: SettingsPage): void;
  requestShizuku(): 'not_running' | 'granted' | 'asked' | 'error';
  requestScreenCapture(): Promise<boolean>;
  stopScreenCapture(): void;
}

export default requireNativeModule<JarvisBridgeModule>('JarvisBridge');
