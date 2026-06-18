import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AuthService } from '../../../core/auth/auth.service';
import { DashboardService, DashboardSnapshot, DashboardWindow } from './dashboard.service';

/**
 * F50 Core Dashboard — internal staff screen (Admin/Recruiter/Read-only). NOT a candidate page, so the §IX
 * Lighthouse/WCAG CI gates do not apply (the F50/F51 precedent). Three panels (time-to-schedule, no-show rate,
 * silence list) for a selectable window (7/30/90d, default 30, persisted across navigation via the service). The
 * export control is shown only for Admin/Recruiter (the backend is the authoritative gate). All strings $localize.
 */
@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './dashboard.component.html',
  styleUrls: ['./dashboard.component.scss']
})
export class DashboardComponent implements OnInit {
  private readonly dashboard = inject(DashboardService);
  private readonly auth = inject(AuthService);

  readonly windows: DashboardWindow[] = ['LAST_7_DAYS', 'LAST_30_DAYS', 'LAST_90_DAYS'];
  readonly window = signal<DashboardWindow>('LAST_30_DAYS');
  readonly snapshot = signal<DashboardSnapshot | null>(null);
  readonly loading = signal(false);
  readonly error = signal(false);
  readonly canExport = signal(false);

  readonly title = $localize`:@@dashboard.title:Core dashboard`;
  readonly windowLabels: Record<DashboardWindow, string> = {
    LAST_7_DAYS: $localize`:@@dashboard.window.7:Last 7 days`,
    LAST_30_DAYS: $localize`:@@dashboard.window.30:Last 30 days`,
    LAST_90_DAYS: $localize`:@@dashboard.window.90:Last 90 days`
  };
  readonly ttsTitle = $localize`:@@dashboard.tts.title:Median time to schedule`;
  readonly noShowTitle = $localize`:@@dashboard.noshow.title:No-show rate`;
  readonly silenceTitle = $localize`:@@dashboard.silence.title:Going silent`;
  readonly noData = $localize`:@@dashboard.nodata:No data for this window`;
  readonly notApplicable = $localize`:@@dashboard.na:Not applicable - no interviews yet`;
  readonly silenceEmpty = $localize`:@@dashboard.silence.empty:No candidates are currently silent`;
  readonly errorMsg = $localize`:@@dashboard.error:Could not load the dashboard. Try again.`;
  readonly exportLabel = $localize`:@@dashboard.export:Export CSV`;
  readonly hoursUnit = $localize`:@@dashboard.hours:h`;
  readonly daysSilentSuffix = $localize`:@@dashboard.daysSilent:days silent`;

  ngOnInit(): void {
    this.window.set(this.dashboard.selectedWindow());
    this.auth.me().subscribe({
      next: (m) => this.canExport.set(m.role === 'ADMIN' || m.role === 'RECRUITER'),
      error: () => this.canExport.set(false)
    });
    this.load();
  }

  selectWindow(w: DashboardWindow): void {
    if (w === this.window()) {
      return;
    }
    this.window.set(w);
    this.dashboard.selectedWindow.set(w);
    this.load();
  }

  onExport(): void {
    this.dashboard.download(this.window());
  }

  private load(): void {
    this.loading.set(true);
    this.error.set(false);
    this.dashboard.snapshot(this.window()).subscribe({
      next: (s) => {
        this.snapshot.set(s);
        this.loading.set(false);
      },
      error: () => {
        this.error.set(true);
        this.loading.set(false);
      }
    });
  }
}
