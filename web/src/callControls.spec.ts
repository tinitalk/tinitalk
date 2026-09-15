import { expect, it } from 'vitest';
import { microphoneControlIcon } from './callControls';

it('uses the same microphone icon as native Android in both mute states', () => {
  expect(microphoneControlIcon(false)).toBe('micOff');
  expect(microphoneControlIcon(true)).toBe('micOff');
});
