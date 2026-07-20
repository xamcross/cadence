import { Injectable, signal } from '@angular/core';

export type ToastKind = 'success' | 'error' | 'info';
export interface Toast { readonly id: number; readonly kind: ToastKind; readonly message: string; }

/** Transient action feedback. Signal-driven; rendered by ToastHostComponent (mounted once in AppComponent). */
@Injectable({ providedIn: 'root' })
export class ToastService {
  private seq = 0;
  readonly toasts = signal<readonly Toast[]>([]);

  private delayFor(kind: ToastKind): number { return kind === 'error' ? 8000 : 4000; }

  show(kind: ToastKind, message: string): number {
    const id = ++this.seq;
    this.toasts.update((list) => [...list, { id, kind, message }]);
    setTimeout(() => this.dismiss(id), this.delayFor(kind));
    return id;
  }
  success(message: string): number { return this.show('success', message); }
  error(message: string): number { return this.show('error', message); }
  info(message: string): number { return this.show('info', message); }
  dismiss(id: number): void { this.toasts.update((list) => list.filter((t) => t.id !== id)); }
}
