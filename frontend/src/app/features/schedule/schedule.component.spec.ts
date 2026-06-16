import { TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { of, throwError } from 'rxjs';
import { ScheduleComponent } from './schedule.component';
import { CandidateSlotsResponse, ConfirmResponse, ScheduleService } from './schedule.service';

/**
 * F13 US2 (§II): the candidate slot-picker loads offered times (no login), confirms a pick, shows an
 * expired link distinctly, and reloads on a slot-taken conflict. Token rides the URL query string.
 */
describe('ScheduleComponent', () => {
  const open: CandidateSlotsResponse = {
    status: 'open', zoneHint: 'America/New_York', bookedStart: null,
    slots: [{ slotId: '0', start: '2026-06-20T14:00:00Z', end: '2026-06-20T15:00:00Z', zoneId: 'America/New_York' }]
  };
  const confirmed: ConfirmResponse = { status: 'booked', bookedStart: '2026-06-20T14:00:00Z', zoneId: 'America/New_York' };

  function setup(view: ScheduleService['view'], confirm: ScheduleService['confirm'], token = 'tok123') {
    const svc: Partial<ScheduleService> = { view, confirm };
    const route = { snapshot: { queryParamMap: { get: (_: string) => token } } };
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [ScheduleComponent],
      providers: [
        { provide: ScheduleService, useValue: svc },
        { provide: ActivatedRoute, useValue: route }
      ]
    });
    const fixture = TestBed.createComponent(ScheduleComponent);
    fixture.detectChanges(); // triggers ngOnInit
    return fixture.componentInstance;
  }

  it('loads offered slots into the open state', () => {
    const c = setup(() => of(open), () => of(confirmed));
    expect(c.state()).toBe('open');
    expect(c.slots().length).toBe(1);
    expect(c.zoneHint()).toBe('America/New_York');
  });

  it('confirms a slot into the booked state', () => {
    const c = setup(() => of(open), () => of(confirmed));
    c.confirm(open.slots[0]);
    expect(c.state()).toBe('booked');
    expect(c.bookedStart()).toBe('2026-06-20T14:00:00Z');
  });

  it('shows a distinct expired state for a 410', () => {
    const c = setup(() => throwError(() => ({ status: 410, error: { error: 'expired' } })), () => of(confirmed));
    expect(c.state()).toBe('expired');
  });

  it('reloads and warns on a slot-taken conflict', () => {
    let views = 0;
    const c = setup(() => { views++; return of(open); },
      () => throwError(() => ({ status: 409, error: { error: 'slot_taken' } })));
    c.confirm(open.slots[0]);
    expect(c.error()).toContain('just taken');
    expect(views).toBe(2); // initial load + reload after conflict
  });

  it('treats a missing token as invalid', () => {
    const c = setup(() => of(open), () => of(confirmed), '');
    expect(c.state()).toBe('invalid');
  });
});
