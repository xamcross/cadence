import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../../../core/auth/auth.service';

/** Request a password-reset link. Always shows the same confirmation (enumeration-safe, FR-032). */
@Component({
  selector: 'app-reset-request',
  standalone: true,
  imports: [FormsModule],
  template: `
    <main class="auth-page">
      <h1 i18n="@@reset.request.title">Reset your password</h1>
      @if (done()) {
        <p role="status" i18n="@@reset.request.sent">
          If an account exists for that email, a reset link has been sent.
        </p>
      } @else {
        <form (ngSubmit)="submit()" novalidate>
          <label for="workspaceId" i18n="@@reset.request.workspace">Workspace ID</label>
          <input id="workspaceId" name="workspaceId" [(ngModel)]="workspaceId" required />
          <label for="email" i18n="@@reset.request.email">Email</label>
          <input id="email" name="email" type="email" [(ngModel)]="email" required autocomplete="username" />
          <button type="submit" [disabled]="submitting()" i18n="@@reset.request.submit">Send reset link</button>
        </form>
      }
    </main>
  `,
  styles: [`
    .auth-page { max-width: 24rem; margin: 2rem auto; padding: 1rem; display: flex; flex-direction: column; gap: 0.75rem; }
    input { width: 100%; padding: 0.5rem; min-height: 44px; box-sizing: border-box; }
    button { min-height: 44px; }
  `]
})
export class ResetRequestComponent {
  private readonly auth = inject(AuthService);
  workspaceId = '';
  email = '';
  readonly submitting = signal(false);
  readonly done = signal(false);

  submit(): void {
    this.submitting.set(true);
    this.auth.requestReset(this.workspaceId, this.email).subscribe({
      // Same outcome whether or not the account exists.
      next: () => this.done.set(true),
      error: () => this.done.set(true)
    });
  }
}
