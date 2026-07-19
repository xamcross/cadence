import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { PipelineService, TimelineEvent } from './pipeline.service';
import { PageHeaderComponent } from '../../shared/ui/page-header.component';
import { EmptyStateComponent } from '../../shared/ui/empty-state.component';
import { SkeletonComponent } from '../../shared/ui/skeleton.component';

/**
 * F51 candidate timeline — a chronological, PII-free activity stream for one candidate (internal staff screen).
 * Scoping (HM -> own requisitions, erased -> not-found) is enforced server-side; an out-of-scope/unknown candidate
 * returns 404 and renders the not-found state. All strings $localize.
 */
@Component({
  selector: 'app-candidate-timeline',
  standalone: true,
  imports: [CommonModule, RouterLink, PageHeaderComponent, EmptyStateComponent, SkeletonComponent],
  template: `
    <app-page-header
      eyebrow="Your work" i18n-eyebrow="@@timeline.eyebrow"
      heading="Candidate timeline" i18n-heading="@@timeline.title"
      subtitle="Chronological activity for this candidate." i18n-subtitle="@@timeline.subtitle"
      backLink="/pipeline" backLabel="Back to pipeline" i18n-backLabel="@@timeline.back">
    </app-page-header>
    @if (notFound()) {
      <p class="error alert alert--danger" role="alert" i18n="@@timeline.notFound">This candidate is not available.</p>
    } @else if (!loaded()) {
      <app-skeleton variant="lines" />
    } @else {
      @if (feedbackPending()) {
        <p class="pending" i18n="@@timeline.feedbackPending">Interviewer feedback is still pending.</p>
      }
      @if (events().length === 0) {
        <app-empty-state
          heading="No activity yet" i18n-heading="@@timeline.empty.heading"
          body="Nothing has happened for this candidate yet." i18n-body="@@timeline.empty.body">
        </app-empty-state>
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
    .timeline li { padding: var(--space-2) 0; border-bottom: 1px solid var(--line); display: flex; gap: var(--space-4); }
    time { color: var(--ink-muted); min-width: 12rem; }
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
