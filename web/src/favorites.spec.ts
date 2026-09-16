import { expect, it } from 'vitest';
import { Favorites } from './favorites';

it('persists favorites independently for accounts and restores removal position', () => {
  let value: string | null = null;
  const storage = { getItem: () => value, setItem: (_: string, next: string) => { value = next; } };
  const favorites = new Favorites(storage);
  favorites.set('a:anna', true);
  favorites.set('b:anna', true);
  favorites.set('a:anna', true);
  expect(new Favorites(storage).keys).toEqual(['a:anna', 'b:anna']);
  favorites.set('a:anna', false);
  favorites.set('a:anna', true, 0);
  favorites.removeAccount('a');
  expect(new Favorites(storage).keys).toEqual(['b:anna']);
});

it('reorders visible favorites without deleting unavailable peers', () => {
  const favorites = new Favorites({ getItem: () => '["a:one","b:offline","a:two"]', setItem: () => {} });
  favorites.reorder(['a:two', 'a:one']);
  expect(favorites.keys).toEqual(['a:two', 'b:offline', 'a:one']);
});

it('does not write unchanged preferences when storage is blocked', () => {
  const favorites = new Favorites({getItem: () => null, setItem: () => { throw new Error('blocked'); }});
  expect(() => favorites.removeAccount('missing')).not.toThrow();
});

it.each(['account', 'contact'])('cleans up a deleted %s even when storage rejects writes', kind => {
  const favorites = new Favorites({
    getItem: () => '["a:anna","a:ira","b:anna"]',
    setItem: () => { throw new Error('QuotaExceededError'); },
  });
  expect(() => kind === 'account' ? favorites.removeAccount('a') : favorites.removeContact('a:anna')).not.toThrow();
  expect(favorites.keys).toEqual(kind === 'account' ? ['b:anna'] : ['a:ira', 'b:anna']);
});

it('still reports failed explicit favorite changes without changing the visible order', () => {
  const favorites = new Favorites({getItem: () => '["a:anna"]', setItem: () => { throw new Error('blocked'); }});
  expect(() => favorites.set('a:anna', false)).toThrow('blocked');
  expect(favorites.keys).toEqual(['a:anna']);
});
