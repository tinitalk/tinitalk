export class Favorites {
  keys: string[] = [];
  constructor(private readonly storage: Pick<Storage, 'getItem' | 'setItem'>) {
    try {
      const saved: unknown = JSON.parse(storage.getItem('tinitalk-favorites') ?? '[]');
      if (Array.isArray(saved)) this.keys = [...new Set(saved.filter((key): key is string => typeof key === 'string'))];
    } catch { /* An empty book is safe when browser storage is unavailable. */ }
  }
  set(key: string, favorite: boolean, position = this.keys.length): void {
    if (favorite && this.keys.includes(key)) return;
    const next = this.keys.filter(value => value !== key);
    if (favorite) next.splice(Math.max(0, position), 0, key);
    this.save(next);
  }
  reorder(visible: string[]): void {
    const ordered = [...new Set(visible)].filter(key => this.keys.includes(key));
    const moving = new Set(ordered);
    this.save(this.keys.map(key => moving.has(key) ? ordered.shift()! : key));
  }
  removeAccount(id: string): void { this.discard(this.keys.filter(key => !key.startsWith(`${id}:`))); }
  removeContact(key: string): void { this.discard(this.keys.filter(value => value !== key)); }
  private discard(next: string[]): void {
    // The entity is already deleted. Preference persistence must not interrupt
    // the remaining cleanup, and the current view must forget it regardless.
    try { this.save(next); } catch { this.keys = next; }
  }
  private save(next: string[]): void {
    if (next.length === this.keys.length && next.every((key, index) => key === this.keys[index])) return;
    this.storage.setItem('tinitalk-favorites', JSON.stringify(next));
    this.keys = next;
  }
}
