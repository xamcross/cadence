import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { InterestRequestsComponent } from './interest-requests.component';
import {
  InterestActionResponse,
  InterestListResponse,
  InterestRequestRow,
  InterestRequestsService
} from './interest-requests.service';
import { attachToBody, axeViolations, detachFromBody } from '../../../../testing/axe';

/**
 * F70 interest-request admin queue (US2). Verifies the list renders, the status filter defaults to `open` and
 * round-trips to the service, the per-row actions dispatch with the right id/role, email/org carry the
 * "unverified" label, and (SC-012) a `<script>`/`=cmd` field value renders inert via Angular interpolation
 * auto-escape (no innerHTML bypass). RBAC is enforced server-side (the route roleGuard is covered separately).
 */
describe('InterestRequestsComponent (F70)', () => {
  const rows: InterestRequestRow[] = [
    {
      id: 'r1', name: 'Dana Lee', email: 'dana@example.com', emailUnverified: true,
      organization: 'Acme Talent', organizationUnverified: true, message: 'We hire ~20 eng/quarter.',
      status: 'NEW', submittedAt: '2026-06-23T09:12:00Z'
    }
  ];

  let listSpy: jasmine.Spy;
  let reviewSpy: jasmine.Spy;
  let dismissSpy: jasmine.Spy;
  let inviteSpy: jasmine.Spy;
  let eraseSpy: jasmine.Spy;
  let exportSpy: jasmine.Spy;
  let attachedEls: HTMLElement[] = [];

  function setup(listValue: InterestListResponse = { requests: rows }): ComponentFixture<InterestRequestsComponent> {
    const ok: InterestActionResponse = { status: 'REVIEWED' };
    listSpy = jasmine.createSpy('list').and.returnValue(of(listValue));
    reviewSpy = jasmine.createSpy('review').and.returnValue(of(ok));
    dismissSpy = jasmine.createSpy('dismiss').and.returnValue(of({ status: 'DISMISSED' } as InterestActionResponse));
    inviteSpy = jasmine.createSpy('invite').and.returnValue(of({ status: 'INVITED', invitationId: 'inv1' } as InterestActionResponse));
    eraseSpy = jasmine.createSpy('erase').and.returnValue(of({ status: 'erased' } as InterestActionResponse));
    exportSpy = jasmine.createSpy('exportCsv');
    const stub: Partial<InterestRequestsService> = {
      list: listSpy as InterestRequestsService['list'],
      review: reviewSpy as InterestRequestsService['review'],
      dismiss: dismissSpy as InterestRequestsService['dismiss'],
      invite: inviteSpy as InterestRequestsService['invite'],
      erase: eraseSpy as InterestRequestsService['erase'],
      exportCsv: exportSpy as InterestRequestsService['exportCsv']
    };
    TestBed.configureTestingModule({
      imports: [InterestRequestsComponent],
      providers: [{ provide: InterestRequestsService, useValue: stub }]
    });
    const fixture = TestBed.createComponent(InterestRequestsComponent);
    const el = fixture.nativeElement as HTMLElement;
    attachedEls.push(el);
    attachToBody(el);
    fixture.detectChanges();
    return fixture;
  }

  afterEach(() => {
    attachedEls.forEach(detachFromBody);
    attachedEls = [];
  });

  it('lists requests and shows the unverified labels for email + organization', () => {
    const fixture = setup();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelectorAll('.request-row').length).toBe(1);
    expect(el.textContent).toContain('Dana Lee');
    expect(el.textContent).toContain('dana@example.com');
    const email = el.querySelector('.cell-email') as HTMLElement;
    const org = el.querySelector('.cell-org') as HTMLElement;
    expect(email.querySelector('.unverified')).toBeTruthy();
    expect(org.querySelector('.unverified')).toBeTruthy();
  });

  it('defaults the status filter to open (which excludes REVIEWED) and reloads on change', () => {
    const fixture = setup();
    expect(fixture.componentInstance.filter).toBe('open');
    expect(listSpy).toHaveBeenCalledWith('open');

    fixture.componentInstance.filter = 'reviewed';
    fixture.componentInstance.load();
    expect(listSpy).toHaveBeenCalledWith('reviewed');

    fixture.componentInstance.filter = 'all';
    fixture.componentInstance.load();
    expect(listSpy).toHaveBeenCalledWith('all');
  });

  it('dispatches review / dismiss / erase with the row id', () => {
    const fixture = setup();
    fixture.componentInstance.review(rows[0]);
    expect(reviewSpy).toHaveBeenCalledWith('r1');
    fixture.componentInstance.dismiss(rows[0]);
    expect(dismissSpy).toHaveBeenCalledWith('r1');
    fixture.componentInstance.erase(rows[0]);
    expect(eraseSpy).toHaveBeenCalledWith('r1');
  });

  it('invites with the selected role', () => {
    const fixture = setup();
    fixture.componentInstance.roleFor['r1'] = 'HIRING_MANAGER';
    fixture.componentInstance.invite(rows[0]);
    expect(inviteSpy).toHaveBeenCalledWith('r1', 'HIRING_MANAGER');
  });

  it('exports CSV via the service with the current status filter', () => {
    const fixture = setup();
    // Default filter is `open`.
    fixture.componentInstance.exportCsv();
    expect(exportSpy).toHaveBeenCalledWith('open');

    // Reflects the active filter when changed.
    fixture.componentInstance.filter = 'all';
    fixture.componentInstance.exportCsv();
    expect(exportSpy).toHaveBeenCalledWith('all');
  });

  it('wires the Export CSV button to the export action', () => {
    const fixture = setup();
    const btn = (fixture.nativeElement as HTMLElement).querySelector('.act-export') as HTMLButtonElement;
    expect(btn).toBeTruthy();
    btn.click();
    expect(exportSpy).toHaveBeenCalledWith('open');
  });

  it('surfaces the alreadyMember outcome distinctly', () => {
    inviteSpy = jasmine.createSpy('invite').and.returnValue(of({ status: 'INVITED', alreadyMember: true } as InterestActionResponse));
    const fixture = setup();
    (fixture.componentInstance as unknown as { api: InterestRequestsService }).api.invite = inviteSpy as InterestRequestsService['invite'];
    fixture.componentInstance.invite(rows[0]);
    expect(fixture.componentInstance.noteFor['r1']).toBeTruthy();
  });

  it('renders a malicious field value inert (SC-012 — no script execution, no markup injection)', () => {
    const malicious: InterestRequestRow = {
      ...rows[0],
      id: 'r2',
      name: '<script>window.__xss = true;</script>',
      organization: '=cmd|/c calc',
      message: '<img src=x onerror="window.__xss2=true">'
    };
    const win = window as unknown as { __xss?: boolean; __xss2?: boolean };
    delete win.__xss;
    delete win.__xss2;
    const fixture = setup({ requests: [malicious] });
    const el: HTMLElement = fixture.nativeElement;
    // The script/markup is shown as literal text (interpolation auto-escape), never parsed into the DOM.
    expect(el.querySelector('.cell-name script')).toBeNull();
    expect(el.querySelector('.cell-message img')).toBeNull();
    expect(el.querySelector('.cell-name')?.textContent).toContain('<script>');
    expect(el.querySelector('.cell-org')?.textContent).toContain('=cmd|/c calc');
    // No injected handler ran.
    expect(win.__xss).toBeUndefined();
    expect(win.__xss2).toBeUndefined();
  });

  it('renders the shared page-header masthead', () => {
    const fixture = setup();
    expect(fixture.nativeElement.querySelector('app-page-header .page__head h1')).not.toBeNull();
  });

  it('wraps the table in the shared table-scroll region', () => {
    const fixture = setup();
    expect(fixture.nativeElement.querySelector('app-table-scroll table.rows.table')).not.toBeNull();
  });

  it('shows the guided empty-state with a Show all requests CTA when the filtered view is empty', () => {
    const fixture = setup({ requests: [] });
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('app-empty-state')).not.toBeNull();
    expect(el.querySelector('table.rows')).toBeNull();
    expect(el.querySelector('.act-show-all')).not.toBeNull();
  });

  it('the empty-state CTA switches the filter to all and reloads', () => {
    const fixture = setup({ requests: [] });
    const el: HTMLElement = fixture.nativeElement;
    const cta = el.querySelector('.act-show-all') as HTMLButtonElement;
    cta.click();
    expect(fixture.componentInstance.filter).toBe('all');
    expect(listSpy).toHaveBeenCalledWith('all');
  });

  it('has zero axe WCAG 2.2 AA violations', async () => {
    const fixture = setup();
    const violations = await axeViolations(fixture.nativeElement);
    expect(violations).withContext(violations.map((v) => v.id).join(', ')).toEqual([]);
  });
});
