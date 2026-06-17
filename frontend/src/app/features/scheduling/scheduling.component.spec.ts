import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { SchedulingComponent } from './scheduling.component';
import {
  InitiateResponse,
  RecruiterStatusResponse,
  RotateLinkResponse,
  SchedulingService,
  StatusResponse
} from './scheduling.service';
import { ActionResult, CandidateSla, DraftPreview, SlaNudgeService } from './sla-nudge.service';

/**
 * F13 US1 (§II): the recruiter surface sends a link (happy path), surfaces a 409 not-contactable, and
 * a 422 no-slots, and maps the per-candidate status to a label. The role guard redirect is covered by
 * role.guard.spec; the server is the security boundary.
 */
describe('SchedulingComponent', () => {
  const initiated: InitiateResponse = {
    schedulingRequestId: 'r1', status: 'PENDING_SELECTION', offeredSlotCount: 5,
    sentAt: '2026-06-16T10:00:00Z', expiresAt: '2026-06-19T10:00:00Z'
  };
  const sentStatus: StatusResponse = {
    status: 'PENDING_SELECTION', sentAt: '2026-06-16T10:00:00Z', expiresAt: '2026-06-19T10:00:00Z', chosenStart: null
  };

  function setup(initiate: SchedulingService['initiate'], overrides: Partial<SchedulingService> = {},
                 slaOverrides: Partial<SlaNudgeService> = {}) {
    const stub: Partial<SchedulingService> = { initiate, status: () => of(sentStatus), ...overrides };
    const slaStub: Partial<SlaNudgeService> = {
      getSla: () => of({ candidateId: 'cand1', slaState: 'GREEN', lastActivityAt: null, openDraftId: null }),
      previewDraft: () => of({ messageType: 'SLA_HOLDING', subject: 's', body: 'b', missingFields: [] }),
      approve: () => of({ draftId: 'd1', result: 'SENT_ENQUEUED' }),
      dismiss: () => of({ draftId: 'd1', result: 'DISMISSED' }),
      ...slaOverrides
    };
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [SchedulingComponent],
      providers: [
        { provide: SchedulingService, useValue: stub },
        { provide: SlaNudgeService, useValue: slaStub }
      ]
    });
    const fixture = TestBed.createComponent(SchedulingComponent);
    return fixture.componentInstance;
  }

  it('sends a link and shows the offered-slot count', () => {
    const c = setup(() => of(initiated));
    c.candidateId = 'cand1';
    c.templateId = 'tmpl1';
    c.send();
    expect(c.result()?.offeredSlotCount).toBe(5);
    expect(c.error()).toBeNull();
    expect(c.statusView()?.status).toBe('PENDING_SELECTION');
  });

  it('surfaces a not-contactable refusal', () => {
    const c = setup(() => throwError(() => ({ status: 409, error: { error: 'not_contactable' } })));
    c.candidateId = 'cand1';
    c.templateId = 'tmpl1';
    c.send();
    expect(c.result()).toBeNull();
    expect(c.error()).toContain('contacted');
  });

  it('surfaces a no-slots refusal', () => {
    const c = setup(() => throwError(() => ({ status: 422, error: { error: 'no_slots' } })));
    c.candidateId = 'cand1';
    c.templateId = 'tmpl1';
    c.send();
    expect(c.error()).toContain('No available slots');
  });

  it('maps scheduling status to a label', () => {
    const c = setup(() => of(initiated));
    expect(c.statusLabel({ ...sentStatus, status: 'BOOKED' })).toBe('Scheduled');
    expect(c.statusLabel({ ...sentStatus, status: 'EXPIRED' })).toBe('Link expired');
  });

  // ---- F30 candidate Status panel (T032) ----

  describe('F30 candidate status panel', () => {
    const recruiterStatus: RecruiterStatusResponse = {
      displayState: 'PUBLISHED', outcome: 'IN_PROGRESS', stage: 'Onsite', nextStep: 'Collecting feedback',
      expectedDate: '2030-01-20', statusLink: 'https://app.example/status?token=abc', publishedAt: null
    };

    it('client validation mirrors the server rules (in-progress requires stage + next + date)', () => {
      const c = setup(() => of(initiated));
      c.candidateId = 'cand1';
      c.statusOutcome = 'IN_PROGRESS';
      c.statusStage = '';
      c.statusNextStep = '';
      c.statusExpectedDate = '';
      expect(c.statusValid()).toBe(false);
      c.statusStage = 'Onsite';
      c.statusNextStep = 'Collecting feedback';
      expect(c.statusValid()).toBe(false); // still missing the date
      c.statusExpectedDate = '2030-01-20';
      expect(c.statusValid()).toBe(true);
    });

    it('a terminal outcome requires only a non-blank next-step message', () => {
      const c = setup(() => of(initiated));
      c.statusOutcome = 'COMPLETE_REJECTED';
      c.statusStage = '';
      c.statusExpectedDate = '';
      c.statusNextStep = '   ';
      expect(c.statusValid()).toBe(false);
      c.statusNextStep = 'Thank you for your time.';
      expect(c.statusValid()).toBe(true);
    });

    it('does NOT publish when invalid (guards against the dateless holding message)', () => {
      const publishSpy = jasmine.createSpy('publishStatus');
      const c = setup(() => of(initiated), {
        publishStatus: publishSpy as unknown as SchedulingService['publishStatus']
      });
      c.candidateId = 'cand1';
      c.statusOutcome = 'IN_PROGRESS';
      c.statusNextStep = 'x'; // no stage, no date
      c.publishStatus();
      expect(publishSpy).not.toHaveBeenCalled();
      expect(c.statusTouched()).toBe(true);
    });

    it('publishes a valid status and surfaces the returned link', () => {
      const publishSpy = jasmine.createSpy('publishStatus').and.returnValue(of(recruiterStatus));
      const c = setup(() => of(initiated), {
        publishStatus: publishSpy as unknown as SchedulingService['publishStatus']
      });
      c.candidateId = 'cand1';
      c.statusOutcome = 'IN_PROGRESS';
      c.statusStage = 'Onsite';
      c.statusNextStep = 'Collecting feedback';
      c.statusExpectedDate = '2030-01-20';
      c.publishStatus();
      expect(publishSpy).toHaveBeenCalledTimes(1);
      expect(c.statusLink()).toBe('https://app.example/status?token=abc');
      expect(c.statusMsg()).toContain('published');
    });

    it('surfaces a 400 invalid_status from the server', () => {
      const publishSpy = jasmine.createSpy('publishStatus')
        .and.returnValue(throwError(() => ({ status: 400, error: { error: 'invalid_status' } })));
      const c = setup(() => of(initiated), {
        publishStatus: publishSpy as unknown as SchedulingService['publishStatus']
      });
      c.candidateId = 'cand1';
      c.statusOutcome = 'COMPLETE_OFFER';
      c.statusNextStep = 'Congratulations';
      c.publishStatus();
      expect(c.statusMsg()).toContain('incomplete');
    });

    it('rotate-link replaces the displayed link and notes the old one no longer works', () => {
      const rotated: RotateLinkResponse = { statusLink: 'https://app.example/status?token=NEW' };
      const rotateSpy = jasmine.createSpy('rotateStatusLink').and.returnValue(of(rotated));
      const c = setup(() => of(initiated), {
        rotateStatusLink: rotateSpy as unknown as SchedulingService['rotateStatusLink']
      });
      c.candidateId = 'cand1';
      c.statusLink.set('https://app.example/status?token=OLD');
      c.rotateStatusLink();
      expect(rotateSpy).toHaveBeenCalledWith('cand1');
      expect(c.statusLink()).toBe('https://app.example/status?token=NEW');
      expect(c.statusMsg()).toContain('rotated');
    });

    it('copy-link writes the current link to the clipboard', async () => {
      const writeText = jasmine.createSpy('writeText').and.returnValue(Promise.resolve());
      const original = navigator.clipboard;
      Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
      try {
        const c = setup(() => of(initiated));
        c.statusLink.set('https://app.example/status?token=abc');
        c.copyStatusLink();
        expect(writeText).toHaveBeenCalledWith('https://app.example/status?token=abc');
      } finally {
        Object.defineProperty(navigator, 'clipboard', { value: original, configurable: true });
      }
    });

    it('loadStatus reads the persisted status + link', () => {
      const readSpy = jasmine.createSpy('readStatus').and.returnValue(of(recruiterStatus));
      const c = setup(() => of(initiated), {
        readStatus: readSpy as unknown as SchedulingService['readStatus']
      });
      c.candidateId = 'cand1';
      c.loadStatus();
      expect(readSpy).toHaveBeenCalledWith('cand1');
      expect(c.statusLink()).toBe('https://app.example/status?token=abc');
      expect(c.statusStage).toBe('Onsite');
    });
  });

  // ---- F31 SLA nudge panel (T040) ----

  describe('F31 SLA nudge panel', () => {
    const redWithDraft: CandidateSla = {
      candidateId: 'cand1', slaState: 'RED', lastActivityAt: '2026-06-01T10:00:00Z', openDraftId: 'd1'
    };

    it('loadSla renders the candidate green/amber/red state', () => {
      const c = setup(() => of(initiated), {}, { getSla: () => of(redWithDraft) });
      c.candidateId = 'cand1';
      c.loadSla();
      expect(c.sla()?.slaState).toBe('RED');
      expect(c.sla()?.openDraftId).toBe('d1');
      expect(c.slaLabel('RED')).toContain('silence');
    });

    it('approve sends the holding message and clears the preview', () => {
      const approveSpy = jasmine.createSpy('approve').and.returnValue(of({ draftId: 'd1', result: 'SENT_ENQUEUED' }));
      const c = setup(() => of(initiated), {}, { getSla: () => of(redWithDraft), approve: approveSpy as unknown as SlaNudgeService['approve'] });
      c.candidateId = 'cand1';
      c.approveDraft('d1');
      expect(approveSpy).toHaveBeenCalledWith('d1');
      expect(c.slaMsg()).toContain('sent');
      expect(c.draftPreview()).toBeNull();
    });

    it('dismiss sends nothing and notes it', () => {
      const dismissSpy = jasmine.createSpy('dismiss').and.returnValue(of({ draftId: 'd1', result: 'DISMISSED' }));
      const c = setup(() => of(initiated), {}, { getSla: () => of(redWithDraft), dismiss: dismissSpy as unknown as SlaNudgeService['dismiss'] });
      c.candidateId = 'cand1';
      c.dismissDraft('d1');
      expect(dismissSpy).toHaveBeenCalledWith('d1');
      expect(c.slaMsg()).toContain('dismissed');
    });

    it('preview surfaces a missing-field warning', () => {
      const preview: DraftPreview = { messageType: 'SLA_HOLDING', subject: 's', body: 'b', missingFields: ['expected_date'] };
      const c = setup(() => of(initiated), {}, { getSla: () => of(redWithDraft), previewDraft: () => of(preview) });
      c.candidateId = 'cand1';
      c.previewDraft();
      expect(c.draftPreview()?.missingFields).toContain('expected_date');
    });
  });
});
