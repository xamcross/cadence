import { Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { CsvImportService, ImportJobStatus, ResolveDecision } from './csv-import.service';

/**
 * F42 standalone CSV import admin screen (US1/US2/US3). Upload a CSV, poll the async job status (counts +
 * per-row results), and resolve flagged duplicates with merge/skip. Internal Admin/Recruiter screen — no
 * candidate PII surface, no WCAG/Lighthouse gate (the F50/F51 internal-screen precedent). All strings $localize.
 */
@Component({
  selector: 'app-csv-import',
  standalone: true,
  imports: [CommonModule],
  template: `
    <section class="csv-import">
      <h1>{{ title }}</h1>

      <form class="upload" (submit)="$event.preventDefault()">
        <label for="csvFile">{{ chooseLabel }}</label>
        <input id="csvFile" type="file" accept=".csv,text/csv" (change)="onFile($event)" />
        <button type="button" class="btn btn--primary" (click)="upload()" [disabled]="busy() || !file()">{{ uploadLabel }}</button>
      </form>

      <p class="error alert alert--danger" role="alert" *ngIf="error()">{{ error() }}</p>

      <article class="status" *ngIf="job() as j">
        <p><strong>{{ statusLabel }}</strong> {{ j.status }}</p>
        <ul class="counts">
          <li>{{ importedLabel }} {{ j.importedCount }}</li>
          <li>{{ mergedLabel }} {{ j.mergedCount }}</li>
          <li>{{ skippedLabel }} {{ j.skippedCount }}</li>
          <li>{{ rejectedLabel }} {{ j.rejectedCount }}</li>
          <li>{{ duplicateLabel }} {{ j.duplicatePendingCount }}</li>
        </ul>
        <p *ngIf="j.rejectionReason" class="error alert alert--danger" role="alert">{{ rejectedFileLabel }} {{ j.rejectionReason }}</p>

        <table class="rows table" *ngIf="j.rowResults.length">
          <thead>
            <tr><th>{{ rowLabel }}</th><th>{{ outcomeLabel }}</th><th>{{ detailLabel }}</th></tr>
          </thead>
          <tbody>
            <tr *ngFor="let r of j.rowResults">
              <td class="num">{{ r.rowNumber }}</td>
              <td>{{ r.status }}</td>
              <td>
                <span *ngIf="r.reason">{{ r.failingField }}: {{ r.reason }}</span>
                <span *ngIf="r.status === 'DUPLICATE_PENDING'" class="dup-actions">
                  <button type="button" class="btn btn--outline btn--sm" (click)="resolveRow(r.rowNumber, 'MERGE')" [disabled]="busy()">{{ mergeLabel }}</button>
                  <button type="button" class="btn btn--ghost btn--sm" (click)="resolveRow(r.rowNumber, 'SKIP')" [disabled]="busy()">{{ skipLabel }}</button>
                </span>
              </td>
            </tr>
          </tbody>
        </table>

        <div class="bulk" *ngIf="j.duplicatePendingCount > 0">
          <button type="button" class="btn btn--outline" (click)="resolveAll('MERGE')" [disabled]="busy()">{{ mergeAllLabel }}</button>
          <button type="button" class="btn btn--ghost" (click)="resolveAll('SKIP')" [disabled]="busy()">{{ skipAllLabel }}</button>
        </div>
      </article>
    </section>
  `,
  styleUrls: ['./csv-import.component.scss']
})
export class CsvImportComponent {
  private readonly api = inject(CsvImportService);

  readonly title = $localize`:@@csv.title:Import candidates from CSV`;
  readonly chooseLabel = $localize`:@@csv.choose:Choose a CSV file`;
  readonly uploadLabel = $localize`:@@csv.upload:Upload`;
  readonly statusLabel = $localize`:@@csv.status:Status:`;
  readonly importedLabel = $localize`:@@csv.imported:Imported:`;
  readonly mergedLabel = $localize`:@@csv.merged:Merged:`;
  readonly skippedLabel = $localize`:@@csv.skipped:Skipped:`;
  readonly rejectedLabel = $localize`:@@csv.rejected:Rejected:`;
  readonly duplicateLabel = $localize`:@@csv.duplicate:Awaiting decision:`;
  readonly rejectedFileLabel = $localize`:@@csv.rejectedFile:File rejected:`;
  readonly rowLabel = $localize`:@@csv.row:Row`;
  readonly outcomeLabel = $localize`:@@csv.outcome:Outcome`;
  readonly detailLabel = $localize`:@@csv.detail:Detail`;
  readonly mergeLabel = $localize`:@@csv.merge:Merge`;
  readonly skipLabel = $localize`:@@csv.skip:Skip`;
  readonly mergeAllLabel = $localize`:@@csv.mergeAll:Merge all`;
  readonly skipAllLabel = $localize`:@@csv.skipAll:Skip all`;
  private readonly uploadFailed = $localize`:@@csv.uploadFailed:Upload failed — check the file and try again.`;

  readonly file = signal<File | null>(null);
  readonly job = signal<ImportJobStatus | null>(null);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);

  private pollTimer: ReturnType<typeof setTimeout> | null = null;

  onFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.file.set(input.files && input.files.length ? input.files[0] : null);
  }

  upload(): void {
    const f = this.file();
    if (!f) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.api.upload(f).subscribe({
      next: (a) => this.poll(a.jobId),
      error: () => {
        this.error.set(this.uploadFailed);
        this.busy.set(false);
      }
    });
  }

  private poll(jobId: string): void {
    this.api.status(jobId).subscribe({
      next: (s) => {
        this.job.set(s);
        if (s.status === 'ACCEPTED' || s.status === 'PROCESSING') {
          this.pollTimer = setTimeout(() => this.poll(jobId), 1000);
        } else {
          this.busy.set(false);
        }
      },
      error: () => {
        this.error.set(this.uploadFailed);
        this.busy.set(false);
      }
    });
  }

  resolveRow(rowNumber: number, action: 'MERGE' | 'SKIP'): void {
    this.sendResolve([{ rowNumber, action }], undefined);
  }

  resolveAll(action: 'MERGE' | 'SKIP'): void {
    this.sendResolve([], action);
  }

  private sendResolve(decisions: ResolveDecision[], defaultAction?: 'MERGE' | 'SKIP'): void {
    const j = this.job();
    if (!j) {
      return;
    }
    this.busy.set(true);
    this.api.resolve(j.jobId, decisions, defaultAction).subscribe({
      next: (s) => {
        this.job.set(s);
        this.busy.set(false);
      },
      error: () => this.busy.set(false)
    });
  }
}
