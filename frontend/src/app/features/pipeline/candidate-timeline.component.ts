import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { PipelineService, TimelineEvent } from './pipeline.service';

/**
 * F51 candidate timeline — a chronological, PII-free activity stream for one candidate (internal staff screen).
 * Scoping (HM -> own requisitions, erased -> not-found) is enforced server-side; an out-of-scope/unknown candidate
 * returns 404 and renders the not-found state. All strings $localize.
 */
@Component({
  selector: 'app-candidate-timeline',
  standalone: true,
  imports: [CommonModule, RouterLink],
  template: `
    <a routerLink="/pipeline" i18n="@@timeline.back">Back to pipeline</a>
    <h1 i18n="@@timeline.title">Candidate timeline</h1>
    @if (notFound()) {
      <p class="error" role="alert" i18n="@@timeline.notFound">This candidate is not available.</p>
    } @else if (loaded()) {
      @if (feedbackPending()) {
        <p class="pending" i18n="@@timeline.feedbackPending">Interviewer feedback is still pending.</p>
      }
      @if (events().length === 0) {
        <p class="empty" i18n="@@timeline.empty">No activity yet.</p>
      } @else {
        <ol class="timeline">
          @for (e of events(); track e.occurredAt + e.type) {
            <li><time>{{ e.occurredAt | date: 'medium' }}</time> <span>{{ e.label }}</span></li>
          }
        </ol>
      }
    }
  `,
  styles: [`
    .timeline { list-style: none; padding: 0; }
    .timeline li { padding: 0.5rem 0; border-bottom: 1px solid #eee; display: flex; gap: 1rem; }
    time { color: #555; min-width: 12rem; }
    .error { color: #8a1c13; }
    a { min-height: 44px; display: inline-block; }
  `]
})
export class CandidateTimelineComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly pipeline = inject(PipelineService);

  readonly events = signal<TimelineEvent[]>([]);
  readonly feedbackPending = signal(false);
  readonly loaded = signal(false);
  readonly notFound = signal(false);

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('candidateId');
    if (!id) { this.notFound.set(true); return; }
    this.pipeline.timeline(id).subscribe({
      next: (t) => { this.events.set(t.events); this.feedbackPending.set(t.feedbackPending); this.loaded.set(true); },
      error: () => this.notFound.set(true)
    });
  }
}
