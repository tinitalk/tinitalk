import { t } from './i18n';
import type { Account, ContactPhoto, PushRecord } from './model';

const stores = ['accounts', 'inbox', 'contact_photos'] as const;
type Store = typeof stores[number];

// Small subset of tinimsg's IndexedDB pattern: resolve writes only after commit,
// propagate failure, and use the same durable storage from page and workers.
async function transaction<T>(name: Store, mode: IDBTransactionMode, operation: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  const db = await new Promise<IDBDatabase>((resolve, reject) => {
    const open = indexedDB.open('tinitalk-pwa', 2);
    open.onupgradeneeded = () => { for (const store of stores) if (!open.result.objectStoreNames.contains(store)) open.result.createObjectStore(store, { keyPath: 'id' }); };
    open.onerror = () => reject(open.error);
    open.onblocked = () => reject(new Error(t('web_close_other_tinitalk_windows_and_try_again_132')));
    open.onsuccess = () => { open.result.onversionchange = () => open.result.close(); resolve(open.result); };
  });
  return new Promise<T>((resolve, reject) => {
    const tx = db.transaction(name, mode);
    const req = operation(tx.objectStore(name));
    tx.oncomplete = () => { db.close(); resolve(req.result); };
    tx.onerror = tx.onabort = () => { db.close(); reject(tx.error ?? new Error(t('web_could_not_save_data_133'))); };
  });
}
export const accounts = () => transaction<Account[]>('accounts', 'readonly', s => s.getAll());
export const account = (id: string) => transaction<Account | undefined>('accounts', 'readonly', s => s.get(id));
export const saveAccount = (value: Account) => transaction('accounts', 'readwrite', s => s.put(value));
export const deleteAccount = (id: string) => transaction('accounts', 'readwrite', s => s.delete(id));
export const savePush = (value: PushRecord) => transaction('inbox', 'readwrite', s => s.put(value));
export const readPush = (id: string) => transaction<PushRecord | undefined>('inbox', 'readonly', s => s.get(id));
export const contactPhotos = () => transaction<ContactPhoto[]>('contact_photos', 'readonly', s => s.getAll());
export const contactPhoto = (id: string) => transaction<ContactPhoto | undefined>('contact_photos', 'readonly', s => s.get(id));
export const saveContactPhoto = (value: ContactPhoto) => transaction('contact_photos', 'readwrite', s => s.put(value));
export const deleteContactPhoto = (id: string) => transaction('contact_photos', 'readwrite', s => s.delete(id));
export async function prunePushes(removedAccount?: string): Promise<void> {
  const rows = await transaction<PushRecord[]>('inbox', 'readonly', s => s.getAll());
  for (const row of rows) if (row.accountId === removedAccount || row.receivedAt < Date.now() - 7 * 86400_000) await transaction('inbox', 'readwrite', s => s.delete(row.id));
}
