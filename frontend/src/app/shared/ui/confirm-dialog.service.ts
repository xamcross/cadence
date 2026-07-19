import { Injectable, signal } from '@angular/core';

export interface ConfirmOptions {
  readonly title: string;
  readonly body?: string;
  readonly confirmLabel?: string;
  readonly cancelLabel?: string;
  /** Style the confirm button as destructive (btn--danger). */
  readonly danger?: boolean;
}

/** Brokers a single confirm modal. `confirm()` returns a Promise resolved by the user's choice
 *  (or false if a newer confirm supersedes it, or the dialog is dismissed). Rendered by ConfirmDialogComponent. */
@Injectable({ providedIn: 'root' })
export class ConfirmDialogService {
  readonly request = signal<ConfirmOptions | null>(null);
  private resolver: ((v: boolean) => void) | null = null;

  confirm(options: ConfirmOptions): Promise<boolean> {
    this.resolve(false); // supersede any open dialog
    this.request.set(options);
    return new Promise<boolean>((res) => { this.resolver = res; });
  }

  respond(confirmed: boolean): void { this.resolve(confirmed); }

  private resolve(value: boolean): void {
    const r = this.resolver;
    this.resolver = null;
    this.request.set(null);
    if (r) r(value);
  }
}
