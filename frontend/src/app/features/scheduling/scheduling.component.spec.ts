import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { SchedulingComponent } from './scheduling.component';
import { InitiateResponse, SchedulingService, StatusResponse } from './scheduling.service';

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

  function setup(initiate: SchedulingService['initiate']) {
    const stub: Partial<SchedulingService> = { initiate, status: () => of(sentStatus) };
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [SchedulingComponent],
      providers: [{ provide: SchedulingService, useValue: stub }]
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
});
