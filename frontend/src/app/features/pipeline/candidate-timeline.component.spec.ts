import { TestBed, ComponentFixture } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { provideRouter } from '@angular/router';
import { ActivatedRoute } from '@angular/router';
import { CandidateTimelineComponent } from './candidate-timeline.component';
import { PipelineService, TimelineResponse } from './pipeline.service';

function route(id: string | null): unknown {
  return { snapshot: { paramMap: { get: () => id } } };
}

function timeline(events: TimelineResponse['events'], pending = false): TimelineResponse {
  return { candidateId: 'c1', events, feedbackPending: pending };
}

describe('CandidateTimelineComponent', () => {
  let pipeline: { timeline: jasmine.Spy };

  function setup(svcReturn: unknown,
                 id: string | null = 'c1'): ComponentFixture<CandidateTimelineComponent> {
    TestBed.resetTestingModule();
    pipeline = { timeline: jasmine.createSpy('timeline').and.returnValue(svcReturn) };
    TestBed.configureTestingModule({
      imports: [CandidateTimelineComponent],
      providers: [
        { provide: PipelineService, useValue: pipeline },
        provideRouter([]),
        // After provideRouter so the mock ActivatedRoute wins (last provider for a token wins).
        { provide: ActivatedRoute, useValue: route(id) }
      ]
    });
    const fixture = TestBed.createComponent(CandidateTimelineComponent);
    fixture.detectChanges();
    return fixture;
  }

  it('renders events in order with feedback-pending', () => {
    const fixture = setup(of(timeline([
      { occurredAt: '2026-06-01T00:00:00Z', type: 'MESSAGE_SENT', label: 'Email sent' },
      { occurredAt: '2026-06-02T00:00:00Z', type: 'BOOKING_CHANGED', label: 'Interview booking changed' }
    ], true)));
    const text = fixture.nativeElement.textContent;
    expect(text).toContain('Email sent');
    expect(text).toContain('Interview booking changed');
    expect(text).toContain('pending');
  });

  it('renders the empty state when there are no events', () => {
    const fixture = setup(of(timeline([])));
    expect(fixture.nativeElement.textContent).toContain('No activity yet');
  });

  it('renders not-found on a 404', () => {
    const fixture = setup(throwError(() => ({ status: 404 })));
    expect(fixture.nativeElement.textContent).toContain('not available');
  });
});
